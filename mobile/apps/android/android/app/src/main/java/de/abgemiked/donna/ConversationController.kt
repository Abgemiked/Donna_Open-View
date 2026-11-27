package com.yourcompany.donna

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.yourcompany.donna.overlay.TurnRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.yourcompany.donna.device.DeviceActionKind
import com.yourcompany.donna.device.OfflineFallback
import com.yourcompany.donna.device.OfflineGate
import com.yourcompany.donna.device.OfflineRoutineStore
import com.yourcompany.donna.device.DeviceActionReport
import com.yourcompany.donna.device.TurnBudget
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ConversationController (A2, Increment 3c+3d) — die vollständige In-Service-Gesprächsschleife der Live-Bubble.
 *
 * Komponiert die bereits REINEN Bausteine, OHNE die 1200-Zeilen-[VoiceInputActivity] auszuweiden (die bleibt
 * Lockscreen-Fallback): [OverlayStateMachine] orchestriert LISTENING→THINKING→SPEAKING→Relisten; der
 * `SpeechRecognizer` liefert STT; `/chat` wird per SSE gestreamt ([SseLineReader]/[SseLineParser]); die Antwort
 * läuft satzweise ([SentenceChunker]) in eine [SentenceSpeechPipeline], deren Synthese/Wiedergabe der
 * [SpeechDriver] kapselt (Edge/Piper/System + Audio-Fokus); [BargeInDetector] lauscht WÄHREND Donna spricht.
 *
 * Alle View-Updates laufen über [Host] zurück an das Panel im [LiveBubbleService]. Alle öffentlichen Methoden
 * und Callbacks auf dem Main-Thread.
 *
 * Bewusste Vereinfachungen dieses Increments (Follow-ups): kein Standort im /chat-Body (lat/lon=null); Aktions-/
 * Wetter-/Map-Karten werden (noch) nicht gerendert — nur Text-Antwort; keine Texteingabe (STT ist der Input).
 */
class ConversationController(
    private val context: Context,
    private val host: Host,
) {
    interface Host {
        /** Zustandswechsel (für die Panel-Statuszeile: hört zu / denkt / spricht / Fehler). */
        fun onStatus(state: OverlayState)
        /** Partielles Erkenntnis-Transkript (Mikes laufende Äußerung). */
        fun onPartialTranscript(text: String)
        /** Endgültige Äußerung von Mike (Frage über der Antwort anzeigen). */
        fun onUserUtterance(text: String)
        /** Gerendertes Antwort-HTML setzen (TurnRenderer). */
        fun setAnswerHtml(html: CharSequence)
        /** Antwortfläche sichtbar machen. */
        fun showAnswer()
        /** Antwortfläche ans Ende scrollen. */
        fun autoscroll()
        /** Nutzertauglicher Fehler-/Hinweistext. */
        fun onError(message: String)
        /** Aktions-Karte (Ja/Nein) anzeigen — Ausführung erst nach explizitem Ja-Tap (= Bestätigung). */
        fun onActionCard(action: JSONObject)
        /**
         * #147 Geräte-Aktion (Taschenlampe, Lautstärke, App/Einstellungen öffnen, Timer/Wecker): läuft OHNE Karte
         * und Tipp, aber nur über die Prüfung des Geräte-Moduls (Positivliste, Aktionsart eingeschaltet, [budget]).
         */
        fun onDeviceAction(action: JSONObject, budget: TurnBudget)
        /** Wetter-Info-Karte anzeigen. */
        fun onWeatherCard(data: JSONObject)
        /** Maps-Karte anzeigen (Host prüft die URL-Richtlinie; verbotene Schemata werden ausgelassen). */
        fun onMapCard(url: String)
        /** Alle Karten des vorigen Turns entfernen (ein neuer Turn beginnt). */
        fun clearCards()
        /** Bestätigen-Tasten scharf schalten (kurz NACH Streaming-Ende — Fehltipp-Schutz). */
        fun armCards()
        /** Gibt es noch unbestätigte Aktions-Karten? (dann nach der Antwort NICHT auto-relisten). */
        fun hasPendingActionCards(): Boolean
        /**
         * #52: Wartet genau EINE einfache Ja/Nein-Aktions-Karte (Typ per [VoiceConfirm.isVoiceConfirmable]) und
         * sonst keine Auswahl-/Fehlerkarte? Nur dann darf eine gesprochene Antwort sie entscheiden.
         */
        fun hasVoiceConfirmableCard(): Boolean
        /**
         * #52: Die EINE bestätigbare Karte wie ein Ja-Tap ausführen (inkl. Fehltipp-Schutz: nur wenn scharf).
         * @return true, wenn die Aktion ausgeführt wurde.
         */
        fun confirmCardByVoice(): Boolean
        /** #52: Wartende Aktions-Karten wie ein Nein-Tap verwerfen. */
        fun dismissCardsByVoice()
        /** #122: Das Gespräch ist zu Ende (Stille, Ende-Wort, Ende-Taste, Limit, Fehler) → Blase schließen. */
        fun onConversationEnded()
        /** #122 Paket C: Der Nutzer tippt gerade (Tippmodus) → nach der Antwort nicht automatisch zuhoeren. */
        fun isTypingActive(): Boolean = false
    }

    private val ui = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val prefs by lazy { context.getSharedPreferences(TtsEngines.PREFS, Context.MODE_PRIVATE) }
    private val speech = SpeechDriver(context)
    private val machine = OverlayStateMachine(
        conversationMode = true, // Default; aus Prefs überschrieben in init
    )
    private val bargeInDetector by lazy { BargeInDetector(context) { onVoiceBargeIn() } }
    private val turnRenderer = TurnRenderer(object : TurnRenderer.Host {
        override fun setResponseHtml(html: CharSequence) { turnGuard.onAnswer(html); host.setAnswerHtml(html) }
        override fun showResponse() = host.showAnswer()
        override fun autoscroll() = host.autoscroll()
    })

    @Volatile private var released = false
    @Volatile private var started = false

    // ── STT-Zustand ─────────────────────────────────────────────────────────────
    private var speechRecognizer: SpeechRecognizer? = null
    private var listenGeneration = 0
    private var isListening = false
    private var heardSpeech = false
    private var listenRetries = 0
    // #146: fuer den aktuellen Zug liegt schon eine Antwort vor -> ein Erkennerfehler danach erzeugt nie eine Meldung
    private val turnGuard = TurnGuard()

    // ── Gesprächs-/Stream-Zustand ─────────────────────────────────────────────
    private var currentSessionId: String? = null
    private var requestActive = false
    private var requestJob: Job? = null
    @Volatile private var activeConn: HttpURLConnection? = null
    private var turn: Turn? = null
    // Aktive TTS-Pipeline UNABHÄNGIG von `turn` (turn wird bei CancelRequest früh genullt) — damit Abbruch/
    // Barge-in/Fehler die vorab synthetisierten Sätze wirklich stoppen (Echo-Schutz).
    private var activePipeline: SentenceSpeechPipeline? = null
    private var lastRenderAt = 0L

    // ── Echo-/Barge-in-Zustand ─────────────────────────────────────────────────
    private var lastSpokenText = ""
    private var echoFilterArmed = false
    private var bargeInSession = false
    // #52: nur DIREKT nach Donnas Frage mit wartender Karte darf ein gesprochenes Ja sie bestätigen
    private val voiceReplyWindow = VoiceReplyWindow()

    // Weitersprechen nach Barge-in-Fehlalarm: Rest des unterbrochenen Turns (vor dem Barge-in gemerkt) + wie oft
    // in diesem Turn schon fortgesetzt wurde (max. BargeInResume.MAX_RESUMES_PER_TURN → keine Endlosschleife).
    private class ResumeSnapshot(val rest: List<String>, val raw: String)
    private var pendingResume: ResumeSnapshot? = null
    private var resumesThisTurn = 0

    // #220: Unterbrechungsstelle (Satz + Wortposition) fuer den Sprachbefehl „weiter“. Bleibt ueber Mikro-Taps
    // erhalten (anders als pendingResume), bis ein neuer Nutzer-Turn startet, sie verbraucht wird oder abgelaufen ist.
    private var interrupted: ResumeSnapshot? = null
    private var interruptedAt = 0L

    // Offline-STT-Fallback (Finding 2/C): bei Netz-STT-Ausfall EINMAL On-Device neu versuchen; pro Berührung.
    private var preferOfflineStt = false
    private var triedOfflineStt = false

    // Karten-Arm-Timer (Fehltipp-Schutz) als FELD → abbrechbar, damit ein alter Timer nicht die Karten eines
    // schnellen Folge-Turns (Barge-in) zu früh scharf schaltet.
    private val armRunnable = Runnable { if (!released) host.armCards() }

    // #122: Beginn des Weiterhörens (uptimeMillis) — der Erkenner meldet Stille oft früher als das Hörfenster lang ist
    private var followUpStartedAt = 0L

    // ── Zeitgeber ───────────────────────────────────────────────────────────────
    private val endingRunnable = Runnable { if (!released) fire(OverlayEvent.EndingElapsed) }
    private val noSpeechRunnable = Runnable { onNoSpeechTimeout() }
    private val maxListenRunnable = Runnable {
        if (!machine.state.isListening) return@Runnable
        // #226: Mike hat (lange) gesprochen → das Gesagte senden statt verwerfen; sonst wie bisher.
        if ((heardSpeech || collector.hasPending) && !bargeInSession) fire(OverlayEvent.SpeechEnded(userInitiated = true))
        else if (!tryResumeAfterFalseAlarm()) fire(OverlayEvent.CancelRequested)
    }
    // #226: Nachhören nach dem Erkenner-Ende (Logik: SpeechContinuation/SpeechCollector, JVM-getestet)
    private val collector = SpeechCollector()
    private var lastPartial = ""
    private val graceRunnable = Runnable { onGraceElapsed() }
    /** Nachlauf-Fenster, das ab „Erkenner bereit“ (onReadyForSpeech) zählt; 0 = schon gesetzt. */
    private var pendingGraceMs = 0L
    /** Sprachende aufgeschoben, aber der Erkenner liefert kein Ergebnis → wie „Fertig“ (Ergebnis abholen). */
    private val resultGuardRunnable = Runnable {
        if (!released && machine.state.isListening) fire(OverlayEvent.SpeechEnded(userInitiated = true))
    }
    private val recognizerWatchdog = Runnable {
        if (machine.state == OverlayState.THINKING && !requestActive) {
            fail(OverlayErrors.fromRecognizer(OverlayErrors.ERROR_NO_MATCH, heardSpeech = true))
        }
    }
    private val autoListenRunnable = Runnable {
        if (machine.state.isListening && !released) {
            echoFilterArmed = true // Auto-Relisten direkt nach Donnas Stimme → Echo filtern
            followUpStartedAt = SystemClock.uptimeMillis() // Hörfenster (6/10/15 s) beginnt jetzt
            // Jeder Auto-Relisten ist ein NEUER Turn → wieder mit Online-STT + frischem Retry-Budget starten.
            // Sonst bliebe die freihändige Konversation nach EINEM transienten ERROR_SERVER bis zum nächsten Tap
            // im Offline-Prefer (Review-Hinweis zum STT-Fix). Der interne Offline-Retry läuft über fire(MicRequested,
            // keepSttFlags=true), NICHT über diesen Runnable — er bleibt also unberührt.
            preferOfflineStt = false
            triedOfflineStt = false
            listenRetries = 0 // #146: frisches Budget fuer stille Neustarts je Weiterhoeren
            startListening()
        }
    }

    init {
        machine.conversationMode = prefs.getBoolean(PREF_CONVERSATION_MODE, true)
        speech.onAudioStarted = { onTtsAudioStarted() }
    }

    // ── Öffentliche API ───────────────────────────────────────────────────────
    /** Blase ist erschienen → sofort zuhören (Funktionstasten-Flow). */
    fun start() { resumeIfIdle() }

    /**
     * FIX 2 Tap-to-Stop: Tap auf das Panel-Stop-Element stoppt Eva SOFORT — unabhängig vom Sprach-Barge-in
     * (der Antwort-Text eilt der TTS 1–2 s voraus; Mike liest schneller). Nur während Donna spricht wirksam;
     * wie ein manuelles Barge-in → stoppt die Ausgabe und geht ins Zuhören, damit Mike sofort antworten kann.
     */
    fun stopSpeakingByTap() {
        if (released || machine.state != OverlayState.SPEAKING) return
        rememberInterruption(captureResumeSnapshot()) // #220: Stelle merken, BEVOR die Ausgabe abgebrochen wird
        // fire(MicRequested) löst CancelRequest aus und bricht den oft noch laufenden SSE-Stream ab. Vorher den
        // bisher gestreamten Text FINAL rendern (sonst bliebe der Rest weg + ein pending renderRunnable verfiele);
        // danach den Arm-Timer neu setzen, damit eine bereits gezeigte Ja/Nein-Karte bedienbar bleibt (armCards
        // käme sonst nie, da finishStream/failStream nach dem Abbruch nicht mehr laufen).
        settleStreamedAnswer()
        fire(OverlayEvent.MicRequested)
        rearmCards()
    }

    /** Bisher gestreamten Antworttext FINAL rendern (sonst bliebe der Rest weg + ein pending renderRunnable verfiele). */
    private fun settleStreamedAnswer() {
        turn?.let { t -> ui.removeCallbacks(t.renderRunnable); t.renderScheduled = false; renderResponse(t) }
    }

    /** Arm-Timer der Karten neu setzen, damit eine bereits gezeigte Ja/Nein-Karte nach einem Abbruch bedienbar bleibt. */
    private fun rearmCards() {
        ui.removeCallbacks(armRunnable)
        ui.postDelayed(armRunnable, ARM_DELAY_MS)
    }

    /** #122 Paket B: Ende-Taste / Benachrichtigung „Beenden“ → Gespräch beenden (Endegrund `button`). */
    fun endConversation() {
        if (released) return
        fire(OverlayEvent.StopRequested)
    }

    /**
     * #122 Paket B: „Stumm“ (Benachrichtigung): Mikrofon aus bis zum nächsten Tipp auf die Blase. Kein neuer
     * Automaten-Zustand — derselbe Weg wie der alte Stopp (CancelRequested → IDLE, Tipp → resumeIfIdle).
     */
    fun muteMic() {
        if (released || !machine.state.isListening) return
        fire(OverlayEvent.CancelRequested, MUTED_STATUS)
    }

    /**
     * #122 Paket C: Tippmodus beginnt → Mikrofon bzw. Sprachausgabe anhalten (wie das Antippen des Eingabefelds im
     * Activity-Overlay). KEIN Gespraechsende: derselbe Weg wie „Stumm“ (CancelRequested → IDLE); ein laufendes Denken
     * bleibt unberuehrt.
     */
    fun pauseForTyping() {
        if (released || !PanelFocus.stopsAudioOnOpen(machine.state)) return
        val speaking = machine.state == OverlayState.SPEAKING
        if (speaking) settleStreamedAnswer() // wie Tipp-zum-Stoppen: Resttext final zeigen, Karte danach bedienbar halten
        fire(OverlayEvent.CancelRequested, TYPING_STATUS)
        if (speaking) rearmCards()
    }

    /**
     * #122 Paket C: getippte Nachricht senden — derselbe Weg wie im Activity-Overlay (TextSubmitted, kein Erkenner).
     * Ende-Woerter, Echo-Filter und Sprach-Bestaetigung gelten fuer getippten Text nicht.
     * @return true, wenn gesendet wurde (leerer Text → nichts)
     */
    fun submitText(raw: String?): Boolean {
        if (released) return false
        val text = TypedInput.prepare(raw) ?: return false
        host.onUserUtterance(text)
        sendToBackend(text, INPUT_MODE_TEXT)
        return true
    }

    /**
     * #122 Paket B: Restanteil des Hörfensters (1 → 0) für den Ring der Blase — dieselbe Rechnung
     * ([FollowUpWindow.remainingMs]) und dieselbe Startzeit wie der Automat/Erkenner, kein eigener Zeitgeber.
     */
    fun followUpFraction(): Float =
        FollowUpRing.fraction(FollowUpWindow.readMs(prefs), followUpStartedAt, SystemClock.uptimeMillis())

    /**
     * Erst-Start beim Blasen-Erscheinen ODER zurück ins Zuhören aus IDLE/ERROR (zweiter Funktionstasten-Druck
     * oder Blasen-Tap). Ohne das wäre die Schleife nach Stille/Fehler/Stop tot (`started` bleibt true).
     * @return true, wenn (neu) zu lauschen begonnen wurde.
     */
    fun resumeIfIdle(): Boolean {
        if (released) return false
        if (!started) { started = true; fire(OverlayEvent.MicRequested); return true }
        // ENDING („Bis gleich.“): Tipp fuehrt zurueck ins Zuhoeren, wie im Activity-Overlay (der Automat erlaubt es)
        if (machine.state == OverlayState.IDLE || machine.state == OverlayState.ERROR || machine.state == OverlayState.ENDING) {
            fire(OverlayEvent.MicRequested); return true
        }
        return false
    }

    /** Gesprächsschleife anhalten (Recognizer/Barge-in/Pipeline/Request), Instanz bleibt verwendbar. */
    fun stop() {
        fire(OverlayEvent.Aborted)
    }

    /** Endgültig freigeben (Service-Teardown). */
    fun release() {
        released = true
        voiceReplyWindow.close()
        ui.removeCallbacks(noSpeechRunnable)
        ui.removeCallbacks(maxListenRunnable)
        ui.removeCallbacks(recognizerWatchdog)
        ui.removeCallbacks(autoListenRunnable)
        ui.removeCallbacks(armRunnable)
        ui.removeCallbacks(endingRunnable)
        emitSummary(EndReason.BUTTON) // Teardown von außen (Blase weggewischt/Dienst beendet): Zusammenfassung genau einmal
        cancelListening()
        stopBargeInDetector()
        cancelRequest()
        try { speech.release() } catch (_: Exception) {}
        try { scope.cancel() } catch (_: Exception) {}
    }

    // ── Zustandsautomat ─────────────────────────────────────────────────────────
    private fun fire(event: OverlayEvent, statusOverride: String? = null, keepSttFlags: Boolean = false) {
        if (released) return
        ui.removeCallbacks(recognizerWatchdog)
        ui.removeCallbacks(autoListenRunnable)
        // #52: Berührung/Abbruch/Stille/neuer Turn/Barge-in beenden das „Antwort auf Donnas Frage"-Fenster
        // (der interne Offline-Retry mit keepSttFlags gehört noch zur selben Antwort-Runde).
        when (event) {
            is OverlayEvent.MicRequested -> if (!keepSttFlags) voiceReplyWindow.close()
            is OverlayEvent.StopRequested, is OverlayEvent.CancelRequested, is OverlayEvent.EndPhraseHeard,
            is OverlayEvent.Failed, is OverlayEvent.Aborted,
            is OverlayEvent.NoSpeechTimeout, is OverlayEvent.VoiceBargeIn, is OverlayEvent.TextSubmitted ->
                voiceReplyWindow.close()
            else -> {}
        }
        // #220: Gespraechsende/Abbruch/Bestaetigung → alte Unterbrechungsstelle verfaellt (kein spaetes „weiter“ auf Altem)
        if (event is OverlayEvent.StopRequested || event is OverlayEvent.EndPhraseHeard || event is OverlayEvent.Aborted) interrupted = null
        if (event is OverlayEvent.VoiceBargeIn) turnGuard.onUserListenStart() // Unterbrechen per Stimme = neuer Hoerstart
        if (event is OverlayEvent.MicRequested && !keepSttFlags) {
            turnGuard.onUserListenStart() // Funktionstaste/Mikrofon-Taste: neuer Zug, die Antwort-Sperre gilt nicht mehr
            // Normale Nutzer-Berührung: Echo-/Barge-in-Kontext UND STT-Flags zurücksetzen (Netz-STT zuerst).
            // keepSttFlags=true (interner Offline-Retry aus THINKING) behält den Kontext DIESER Berührung —
            // sonst verlöre der Retry den Echo-Filter und die Barge-in-Buchhaltung.
            echoFilterArmed = false
            lastSpokenText = ""
            bargeInSession = false
            pendingResume = null
            resumesThisTurn = 0
            preferOfflineStt = false
            triedOfflineStt = false
        }
        val t = machine.dispatch(event)
        // #122 Paket B: Fensterbeginn gilt nur innerhalb EINES Weiterhörens (sonst zeigte der Ring eine alte Restzeit)
        if (t.state != OverlayState.FOLLOW_UP) followUpStartedAt = 0L
        // Ausstehender Ende-Zeitgeber darf nach einem Tipp waehrend „Bis gleich.“ nicht mehr schliessen
        if (t.state != OverlayState.ENDING) ui.removeCallbacks(endingRunnable)
        // Barge-in-Lauschen nur während Donna spricht
        if (t.state != OverlayState.SPEAKING) stopBargeInDetector()
        host.onStatus(t.state)
        // Beim Gesprächsende zeigt die Statuszeile „Bis gleich.“ bzw. den Klartext — kein Fehlertext darüber
        if (t.state == OverlayState.ENDING) host.onError(ConversationTexts.endingStatus(machine.endReason))
        else if (statusOverride != null && t.state != OverlayState.ENDED) host.onError(statusOverride)
        if (t.state == OverlayState.THINKING && !requestActive) {
            ui.postDelayed(recognizerWatchdog, RECOGNIZER_WATCHDOG_MS)
        }
        t.effects.forEach { applyEffect(it) }
    }

    private fun fail(error: UserError) {
        // #53/#54: NUR „Barge-in feuerte, danach keine Sprache" ist ein Echo-Fehlauslöser (Strike). Netz-/Server-/
        // Rechte-/Erkenner-Ausfälle sind keine Echo-Evidenz — sie dürfen das Unterbrechen nicht in die Pause treiben.
        if (EchoStrikePolicy.countsAsStrike(error.kind)) {
            // Fehlalarm ohne echte Sprache: Donna spricht weiter statt Fehler (Resume bucht den Strike selbst).
            if (tryResumeAfterFalseAlarm()) return
            // #214: kein Resume mehr moeglich → ehrlich melden statt STT-Fehlertext („Ich kann dich nicht verstehen“)
            val resumable = interrupted != null && BargeInResume.isFresh(SystemClock.uptimeMillis(), interruptedAt)
            val selfText = BargeInResume.selfInterruptText(bargeInSession, error.kind, resumable)
            endBargeInSession(genuine = false)
            if (selfText != null) {
                fire(OverlayEvent.CancelRequested, selfText)
                return
            }
        } else {
            bargeInSession = false
        }
        fire(OverlayEvent.Failed(error), panelMessageFor(error))
    }

    /**
     * Echte Konnektivität: aktives Netz hat INTERNET-Capability UND ist VALIDATED (vom System real geprüft, nicht
     * nur „verbunden"). Dient dazu, einen transienten Erkenner-Fehler bei gutem Netz NICHT als „kein Netz" zu melden.
     */
    private fun isOnline(): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        // #146: Internet-Faehigkeit und (validiert ODER verbunden) — „validiert“ allein ist bei VPN/privatem DNS/Netzwechsel falsch negativ
        caps != null && SttErrorPolicy.isOnline(
            hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            connected = connectedNow(cm), // echter Verbindungszustand
        )
    } catch (_: Exception) {
        false
    }

    @Suppress("DEPRECATION")
    private fun connectedNow(cm: ConnectivityManager?): Boolean = cm?.activeNetworkInfo?.isConnected == true

    /**
     * Panel-taugliche Fehlermeldung: Das Panel hat KEIN Tippfeld (FLAG_NOT_FOCUSABLE, voice-first). Die
     * geteilten OverlayErrors-Texte (shared mit VoiceInputActivity, das Tippen kann) bieten teils „…oder
     * tippe/schreib" an — das zeigt im Panel ins Leere. Enthält eine Meldung einen Tipp-Bezug, wird sie
     * kind-gerecht durch eine typing-freie Variante ersetzt; spezifische Meldungen ohne Tipp-Bezug (z. B.
     * meine ehrliche Offline-Meldung, Server/Auth) bleiben unverändert. OverlayErrors/VIA bleiben unberührt.
     */
    private fun panelMessageFor(error: UserError): String {
        val m = error.message
        if (!m.contains("tipp", ignoreCase = true) && !m.contains("schreib", ignoreCase = true)) return m
        return when (error.kind) {
            UserError.Kind.NO_SPEECH -> "Ich habe nichts gehört. Ruf mich neu auf oder sprich lauter."
            UserError.Kind.NO_MATCH -> "Ich kann dich gerade nicht verstehen. Ruf mich neu auf und sprich noch einmal."
            UserError.Kind.MIC_UNAVAILABLE -> "Das Mikrofon ist gerade nicht frei. Versuch es gleich nochmal."
            UserError.Kind.MIC_PERMISSION -> "Ich darf das Mikrofon nicht nutzen. Bitte im Donna-Onboarding erlauben."
            UserError.Kind.RECOGNIZER_UNAVAILABLE -> "Spracherkennung ist auf diesem Gerät nicht verfügbar."
            UserError.Kind.NETWORK -> SttErrorPolicy.TEXT_HICCUP // nie pauschal „kein Netz“ (#146); echtes Offline entscheidet SttErrorPolicy
            else -> "Das hat nicht geklappt. Ruf mich neu auf und sprich noch einmal."
        }
    }

    /** Genau ein Messwert-Ereignis je Gespräch (nur Zahlen + Endegrund); Sendefehler bleiben folgenlos. */
    private fun emitSummary(fallback: EndReason) {
        val summary = machine.takeSummary(fallback) ?: return
        ConversationSummarySender.send(context.applicationContext, summary)
    }

    private fun applyEffect(effect: OverlayEffect) {
        when (effect) {
            OverlayEffect.StartListening -> { listenRetries = 0; turnGuard.onUserListenStart(); heardSpeech = false; requestMicAndListen() }
            OverlayEffect.StartListeningAfterEcho -> {
                heardSpeech = false
                ui.postDelayed(autoListenRunnable, ECHO_GUARD_MS)
            }
            OverlayEffect.FinishListening -> {
                ui.removeCallbacks(noSpeechRunnable)
                ui.removeCallbacks(maxListenRunnable)
                // #226: „Fertig“ im Nachhören → kein Nachlauf mehr; das Ergebnis (oder der Fehler) liefert den Text
                ui.removeCallbacks(graceRunnable)
                ui.removeCallbacks(resultGuardRunnable)
                try { speechRecognizer?.stopListening() } catch (_: Exception) {}
            }
            OverlayEffect.CancelListening -> cancelListening()
            OverlayEffect.StopSpeaking -> stopSpeaking()
            OverlayEffect.CancelRequest -> cancelRequest()
            OverlayEffect.CloseOverlayDelayed -> { /* Blase bleibt; nichts automatisch schließen (Service-Modell) */ }
            is OverlayEffect.ScheduleEnding -> {
                ui.removeCallbacks(endingRunnable)
                ui.postDelayed(endingRunnable, effect.delayMs)
            }
            OverlayEffect.CloseConversation -> {
                ui.removeCallbacks(endingRunnable)
                emitSummary(EndReason.BUTTON)
                host.onConversationEnded()
            }
        }
    }

    // ── Spracherkennung (STT) ───────────────────────────────────────────────────
    private fun requestMicAndListen() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        } else {
            // Ein Service darf NICHT prompten — sauber abbrechen (Recht wird im Onboarding erteilt).
            // statusOverride, damit der Hinweis NACH host.onStatus(IDLE) gesetzt wird (sonst sofort überschrieben).
            fire(OverlayEvent.CancelRequested, "Mikrofon ist nicht freigegeben. Bitte im Donna-Onboarding erlauben, dann Blase erneut aufrufen.")
        }
    }

    /**
     * @param continuation #226: Nachhör-Neustart innerhalb DERSELBEN Äußerung — gesammelter Text bleibt, es zählt
     * als „schon gesprochen“, und statt des Kein-Sprache-Timers läuft das Nachlauf-Fenster ([graceRunnable]).
     */
    private fun startListening(continuation: Boolean = false) {
        if (released) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            fail(OverlayErrors.recognizerUnavailable())
            return
        }
        if (continuation) heardSpeech = true else {
            heardSpeech = false
            collector.reset()
            ui.removeCallbacks(graceRunnable)
            pendingGraceMs = 0L
        }
        lastPartial = ""
        ui.removeCallbacks(resultGuardRunnable)
        val silence = SilenceProfile.read(prefs)
        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        listenGeneration++
        ui.removeCallbacks(noSpeechRunnable)
        ui.removeCallbacks(maxListenRunnable)
        // Erstes Zuhören 8 s; Weiterhören nach einer Antwort: eingestelltes Fenster (bei jedem Start frisch gelesen)
        if (!continuation) {
            val noSpeechMs = if (machine.state == OverlayState.FOLLOW_UP) {
                FollowUpWindow.remainingMs(FollowUpWindow.readMs(prefs), followUpStartedAt, SystemClock.uptimeMillis())
            } else OverlayTimings.NO_SPEECH_TIMEOUT_MS
            ui.postDelayed(noSpeechRunnable, noSpeechMs)
        }
        // #226: im Nachhören höchstens die Restzeit der ganzen Äußerung
        val maxMs = if (continuation) minOf(MAX_LISTEN_MS, collector.remainingMs(SystemClock.uptimeMillis())) else MAX_LISTEN_MS
        ui.postDelayed(maxListenRunnable, maxMs)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(buildRecognitionListener())
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silence.completeMs)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silence.possiblyCompleteMs)
                // Mindestdauer, bevor eine Stille als „fertig" zählt — gegen zu frühes Abschneiden (Finding B).
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, silence.minimumMs)
                // On-Device-Erkennung bevorzugen, wenn Online-STT zuvor am Netz scheiterte (Finding 2/C).
                if (preferOfflineStt) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            SttBias.applyTo(intent)
            try {
                startListening(intent)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "startListening fehlgeschlagen: ${e.message}")
                try { destroy() } catch (_: Exception) {}
                ui.removeCallbacks(noSpeechRunnable)
                ui.removeCallbacks(maxListenRunnable)
                fail(OverlayErrors.fromRecognizer(OverlayErrors.ERROR_CLIENT, heardSpeech = false))
            }
        }
    }

    private fun buildRecognitionListener(): RecognitionListener {
        val gen = listenGeneration
        fun stale() = gen != listenGeneration
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (stale()) return
                isListening = true
                armGraceOnReady()
            }
            override fun onBeginningOfSpeech() {
                if (stale()) return
                heardSpeech = true
                ui.removeCallbacks(noSpeechRunnable)
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                if (stale()) return
                isListening = false
                ui.removeCallbacks(noSpeechRunnable)
                ui.removeCallbacks(maxListenRunnable)
                // #226: Längerer/unvollständiger Satz → Sprachende noch nicht melden, gleich wird nachgehört
                // (onResults entscheidet). Kurze Befehle melden es sofort wie bisher.
                if (continuationAllowed() && collector.deferEndOfSpeech(lastPartial, SilenceProfile.read(prefs))) {
                    ui.removeCallbacks(resultGuardRunnable)
                    ui.postDelayed(resultGuardRunnable, SpeechContinuation.RESULT_GUARD_MS)
                    return
                }
                fire(OverlayEvent.SpeechEnded(userInitiated = false))
            }
            override fun onPartialResults(partialResults: Bundle?) {
                if (stale()) return
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: return
                if (partial.isNotBlank()) {
                    if (!heardSpeech) heardSpeech = true
                    lastPartial = partial
                    ui.removeCallbacks(noSpeechRunnable)
                    // #226: Mike spricht im Nachlauf-Fenster weiter → Fenster stoppen, Ergebnis dieses Abschnitts abwarten
                    ui.removeCallbacks(graceRunnable)
                    pendingGraceMs = 0L
                    host.onPartialTranscript(collector.display(partial))
                }
            }
            override fun onResults(results: Bundle?) {
                if (stale()) return
                listenGeneration++ // Ergebnis verbraucht; späte Callbacks dieses Erkenners verfallen
                isListening = false
                ui.removeCallbacks(noSpeechRunnable)
                ui.removeCallbacks(maxListenRunnable)
                ui.removeCallbacks(resultGuardRunnable)
                ui.removeCallbacks(graceRunnable)
                val raw = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                // #226: Abschnitt sammeln; ggf. nahtlos weiterhören statt sofort zu senden
                val decision = collector.offer(raw, SystemClock.uptimeMillis(), SilenceProfile.read(prefs), continuationAllowed() && !echoSegment(raw))
                if (decision is ContinuationDecision.Continue) {
                    continueListening(decision.graceMs)
                    return
                }
                val text = (decision as ContinuationDecision.Finish).text
                if (machine.state.isListening) fire(OverlayEvent.SpeechEnded(userInitiated = false))
                if (machine.state != OverlayState.THINKING) return
                deliverRecognized(text)
            }
            override fun onError(error: Int) {
                if (stale()) return
                isListening = false
                ui.removeCallbacks(noSpeechRunnable)
                ui.removeCallbacks(maxListenRunnable)
                ui.removeCallbacks(resultGuardRunnable)
                // #226: Fehler/Stille im Nachhören = Mike ist fertig → gesammelten Text senden (keine Fehlermeldung)
                if (collector.hasPending) {
                    ui.removeCallbacks(graceRunnable)
                    listenGeneration++
                    val text = collector.takeWith(lastPartial)
                    if (machine.state.isListening) fire(OverlayEvent.SpeechEnded(userInitiated = false))
                    if (machine.state == OverlayState.THINKING) deliverRecognized(text)
                    return
                }
                handleRecognizerError(error)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    /** #226: Nachhören nur im Zuhören und nicht in einer Barge-in-Sitzung (#223: Fehlalarm/Weitermachen bleibt unverändert). */
    private fun continuationAllowed(): Boolean = !released && machine.state.isListening && !bargeInSession

    /** #226: Erkenner für den nächsten Abschnitt neu starten; ohne neue Sprache nach [graceMs] senden. */
    private fun continueListening(graceMs: Long) {
        ui.removeCallbacks(graceRunnable)
        // Fenster zählt ab „Erkenner bereit“; Sicherung, falls er nie bereit wird
        pendingGraceMs = graceMs
        ui.postDelayed(graceRunnable, graceMs + SpeechContinuation.READY_ALLOWANCE_MS)
        ui.post {
            if (released || !collector.hasPending) return@post
            if (machine.state.isListening) startListening(continuation = true)
            else if (machine.state == OverlayState.THINKING) {
                // „Fertig“ kam zwischen Ergebnis und Neustart: kein Erkenner mehr → Gesammeltes direkt senden
                ui.removeCallbacks(graceRunnable)
                deliverRecognized(collector.take())
            }
        }
    }

    /** #226: Nachhör-Erkenner ist bereit → jetzt beginnt das eigentliche Nachlauf-Fenster. */
    private fun armGraceOnReady() {
        if (pendingGraceMs <= 0L || !collector.hasPending) return
        ui.removeCallbacks(graceRunnable)
        ui.postDelayed(graceRunnable, pendingGraceMs)
        pendingGraceMs = 0L
    }

    /** #226: Ein Abschnitt, der nur Donnas Echo ist, wird nicht gesammelt (geht wie bisher einzeln durch den Echo-Filter). */
    private fun echoSegment(raw: String): Boolean =
        echoFilterArmed && !collector.hasPending && raw.isNotBlank() && SpokenTextMatcher.isEcho(raw, lastSpokenText)

    /** #226: Nachlauf-Fenster ohne neue Sprache abgelaufen → Nachhör-Erkenner verwerfen, gesammelten Text senden. */
    private fun onGraceElapsed() {
        if (released || !collector.hasPending || !machine.state.isListening) return
        listenGeneration++
        isListening = false
        ui.removeCallbacks(noSpeechRunnable)
        ui.removeCallbacks(maxListenRunnable)
        ui.removeCallbacks(resultGuardRunnable)
        try { speechRecognizer?.cancel() } catch (_: Exception) {}
        val text = collector.take()
        fire(OverlayEvent.SpeechEnded(userInitiated = false))
        if (machine.state == OverlayState.THINKING) deliverRecognized(text)
    }

    /** Erkannter (ggf. aus mehreren Abschnitten zusammengesetzter) Text: Echo-Filter, Befehle, Bestätigung, Senden. */
    private fun deliverRecognized(text: String?) {
        if (text.isNullOrBlank()) {
            voiceReplyWindow.close()
            fail(OverlayErrors.fromRecognizer(OverlayErrors.ERROR_NO_MATCH, heardSpeech = true))
        } else if (echoFilterArmed && SpokenTextMatcher.isEcho(text, lastSpokenText, bargeIn = bargeInSession)) {
            echoFilterArmed = false
            voiceReplyWindow.close()
            // Echo-Treffer nach Barge-in = Fehlalarm → weitersprechen; sonst (Resume-Budget leer) wie bisher.
            if (!tryResumeAfterFalseAlarm()) {
                endBargeInSession(genuine = false)
                fire(OverlayEvent.CancelRequested, "Ich habe nur mich selbst gehört. Ruf mich neu auf, wenn du etwas sagen willst.")
            }
        } else if (tryResumeByCommand(text)) {
            // #220: „weiter“ — NACH dem Echo-Filter (Donnas eigenes „weiter“ ist kein Befehl, sonst Schleife)
            voiceReplyWindow.close()
        } else {
            echoFilterArmed = false
            endBargeInSession(genuine = true)
            // #122: Ganze Äußerung = Abschlussphrase im Weiterhören → Gespräch beenden, NICHT senden. Eine
            // wartende Karte bleibt dabei unbestätigt (nichts wird ausgeführt).
            // Bei offener Rueckfrage (Antwortfenster/Karte) beenden nur eindeutige Abschiede; „stopp“, „halt“,
            // „nein danke“ … gehen an die Bestaetigungs-Logik weiter.
            val confirmationPending = ConversationEnd.confirmationPending(voiceReplyWindow.isOpen, host.hasPendingActionCards())
            if (ConversationEnd.shouldEnd(machine.followUpListen, text, confirmationPending)) {
                fire(OverlayEvent.EndPhraseHeard)
                return
            }
            host.onUserUtterance(text)
            // #52: Direkt nach Donnas Frage mit wartender Karte ist ein kurzes „Ja"/„Nein" ihre Antwort
            // (nicht ein neuer Turn). Das Fenster wird dabei EINMAL verbraucht.
            if (voiceReplyWindow.consume() && handleVoiceConfirmation(text)) return
            sendToBackend(text, INPUT_MODE_VOICE, genuine = true)
        }
    }

    /** Erkennerfehler ohne gesammelten Text (bisheriges Verhalten, Entscheidung: [SttErrorPolicy]). */
    private fun handleRecognizerError(error: Int) {
        val state = machine.state
        if (!state.isListening && state != OverlayState.THINKING) return
        // Entscheidung (rein, JVM-getestet): SttErrorPolicy. Netz-Ausfall kommt oft ERST nach Sprechende (THINKING,
        // solange kein /chat laeuft) — darum auch dort behandelt. Hier nur die Umsetzung + Diagnose-Logzeile (ohne Inhalte).
        val online = isOnline()
        val action = SttErrorPolicy.decide(error, state, online, turnGuard.answered, triedOfflineStt, listenRetries, heardSpeech, requestActive)
        android.util.Log.i(TAG, "STT-Fehler code=$error state=$state online=$online answered=${turnGuard.answered} triedOffline=$triedOfflineStt retries=$listenRetries -> ${action.javaClass.simpleName}")
        when (action) {
            SttErrorAction.OfflineRetry -> {
                if (released) return
                triedOfflineStt = true
                preferOfflineStt = true
                ui.postDelayed({
                    if (released) return@postDelayed
                    when (machine.state) {
                        OverlayState.LISTENING, OverlayState.FOLLOW_UP -> startListening()
                        // aus THINKING zurück ins Zuhören; keepSttFlags behält preferOfflineStt für den Retry
                        OverlayState.THINKING -> fire(OverlayEvent.MicRequested, keepSttFlags = true)
                        else -> {}
                    }
                }, 150L)
            }
            SttErrorAction.RestartSilently -> {
                if (released) return
                listenRetries++
                val delay = if (error == OverlayErrors.ERROR_SERVER_DISCONNECTED) 0L else 300L
                ui.postDelayed({
                    if (!released && machine.state.isListening) startListening()
                }, delay)
            }
            SttErrorAction.Silence -> {
                // #122: Der Erkenner meldet Stille oft vor Ablauf des Hörfensters → still neu starten, bis es um ist
                val left = FollowUpWindow.remainingMs(FollowUpWindow.readMs(prefs), followUpStartedAt, SystemClock.uptimeMillis())
                if (state == OverlayState.FOLLOW_UP && FollowUpWindow.shouldRestart(left) && !released) {
                    ui.postDelayed({
                        if (!released && machine.state == OverlayState.FOLLOW_UP) startListening()
                    }, 200L)
                } else fireSilence()
            }
            SttErrorAction.QuietIdle -> {
                // Weiterhören/Antwort liegt vor: keine Meldung, still in Ruhe
                bargeInSession = false
                // CancelRequested: IDLE ohne Sprachausgabe/Abschiedsfloskel (NoSpeechTimeout im Weiterhoeren = „Bis gleich.“ + Ende)
                fire(OverlayEvent.CancelRequested)
            }
            is SttErrorAction.Show -> fail(action.error)
        }
    }

    /**
     * #52: Sprach-Bestätigung der wartenden Aktions-Karte. Läuft NUR (a) im [VoiceReplyWindow] direkt nach Donnas
     * Frage, (b) hinter dem Echo-Filter, (c) bei genau einer einfachen Ja/Nein-Karte. Ausführen löst nur ein
     * ausdrückliches STARKES „Ja" aus („ok/klar/passt/genau" nicht), und nur wenn es nicht der Ausklang von Donnas
     * eigener Frage sein kann ([VoiceConfirm.echoSuspect]). Eindeutiges Ja → wie Ja-Tap (Fehltipp-Schutz: nur bei
     * scharfer Karte), eindeutiges Nein → verwerfen, alles Mehrdeutige → NICHTS passiert, Karte bleibt tippbar.
     * Alles andere (lange Sätze, andere Wörter) = normaler neuer Turn. Backend-Aktions-Protokoll unberührt:
     * ausgeführt wird dieselbe Aktion über denselben Executor-Pfad.
     * @return true, wenn die Äußerung hier verbraucht wurde (kein /chat-Turn).
     */
    private fun handleVoiceConfirmation(text: String): Boolean {
        return when (VoiceConfirm.decide(host.hasVoiceConfirmableCard(), text, lastSpokenText)) {
            VoiceConfirm.Decision.CONFIRM -> {
                val done = host.confirmCardByVoice()
                fire(
                    OverlayEvent.Aborted,
                    if (done) "Okay, ich führe das aus." else "Zu früh — bitte auf „Ja“ tippen, um zu bestätigen.",
                )
                true
            }
            VoiceConfirm.Decision.DISMISS -> {
                host.dismissCardsByVoice()
                fire(OverlayEvent.Aborted, "Okay, abgebrochen.")
                true
            }
            VoiceConfirm.Decision.AMBIGUOUS ->
                nothingExecuted("Das war nicht eindeutig — bitte direkt auf die Karte tippen.")
            VoiceConfirm.Decision.NEEDS_STRONG_YES ->
                nothingExecuted("Zum Bestätigen bitte auf „Ja“ tippen — per Sprache zählt nur ein klares „Ja“.")
            VoiceConfirm.Decision.ECHO_SUSPECT ->
                nothingExecuted("Das klang wie mein eigenes Echo — bitte auf die Karte tippen.")
            VoiceConfirm.Decision.PASS_THROUGH -> false
        }
    }

    /** Verbraucht die Äußerung ohne Ausführung: Mikro aus, Karte bleibt tippbar, ehrlicher Hinweis. */
    private fun nothingExecuted(message: String): Boolean {
        fire(OverlayEvent.Aborted, message)
        return true
    }

    private fun fireSilence() {
        if (tryResumeAfterFalseAlarm()) return
        endBargeInSession(genuine = false)
        // #116: nur das Weiterhören nach einer Antwort lief ab -> kein Fehlertext (nur ohne Gespräch der Hinweis)
        fire(
            OverlayEvent.NoSpeechTimeout,
            OverlayErrors.silenceStatus(machine.hasConversation, "Ich habe nichts gehört. Ruf mich neu auf oder sprich lauter."),
        )
    }

    private fun onNoSpeechTimeout() {
        if (machine.state.isListening && !heardSpeech) fireSilence()
    }

    private fun cancelListening() {
        listenGeneration++
        isListening = false
        ui.removeCallbacks(noSpeechRunnable)
        ui.removeCallbacks(maxListenRunnable)
        // #226: abgebrochen → Gesammeltes verwerfen, Nachlauf aus
        ui.removeCallbacks(graceRunnable)
        ui.removeCallbacks(resultGuardRunnable)
        collector.reset()
        pendingGraceMs = 0L
        try { speechRecognizer?.cancel() } catch (_: Exception) {}
        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        speechRecognizer = null
    }

    // ── Barge-in ─────────────────────────────────────────────────────────────────
    private fun voiceBargeInEnabled(): Boolean =
        machine.conversationMode && PanelFocus.voiceBargeInAllowed(host.isTypingActive()) && VoiceBargeInSetting.read(prefs) &&
            !BargeInDetector.disabledForProcess && !BargeInDetector.echoStrikes.isPaused() && machine.canVoiceBargeIn

    private fun onTtsAudioStarted() {
        if (released) return
        // DIAGNOSE (Gerätetest Barge-in): zeigt im Logcat, welcher Guard ggf. früh zurückkehrt.
        android.util.Log.i(
            TAG,
            "bargeIn/onTtsAudioStarted state=${machine.state} conv=${machine.conversationMode} " +
                "pref=${VoiceBargeInSetting.read(prefs)} " +
                "disabled=${BargeInDetector.disabledForProcess} paused=${BargeInDetector.echoStrikes.isPaused()} " +
                "can=${machine.canVoiceBargeIn} running=${bargeInDetector.isRunning}",
        )
        if (machine.state != OverlayState.SPEAKING || !voiceBargeInEnabled() || bargeInDetector.isRunning) return
        if (!BargeInResume.detectorAllowed(resumesThisTurn)) return // #214: nach Fehlalarm-Resume keine Schleife
        android.util.Log.i(TAG, "bargeIn/start() -> ${bargeInDetector.start()}")
    }

    private fun stopBargeInDetector() {
        if (bargeInDetector.isRunning) bargeInDetector.stop()
    }

    private fun onVoiceBargeIn() {
        if (released || machine.state != OverlayState.SPEAKING) return
        // Rest des Turns VOR dem Abbruch merken (fire → CancelRequest/StopSpeaking verwirft Pipeline + Turn).
        // Nur ziehen, wenn die State-Machine das Barge-in annimmt (der Snapshot verbraucht den Chunker).
        val full = if (machine.canVoiceBargeIn) captureResumeSnapshot() else null
        rememberInterruption(full) // #220: fuer „weiter“ (unabhaengig vom Fehlalarm-Budget)
        val snapshot = full?.takeIf { BargeInResume.canResume(resumesThisTurn, it.rest) }
        fire(OverlayEvent.VoiceBargeIn)
        if (machine.state == OverlayState.LISTENING) {
            bargeInSession = true
            echoFilterArmed = true
            pendingResume = snapshot
            if (snapshot != null) {
                // Fehlalarm schnell erkennen: kommt nach dem Barge-in keine Sprache, hört Donna nicht 8 s zu.
                ui.removeCallbacks(noSpeechRunnable)
                ui.postDelayed(noSpeechRunnable, BARGE_IN_NO_SPEECH_MS)
            }
        }
        // Review R6: CancelRequest verhindert finishStream/failStream → armCards käme nie. Wie bei stopSpeakingByTap
        // den Arm-Timer neu setzen, damit eine bereits gezeigte Karte (Tap UND Sprach-Ja) bedienbar bleibt.
        ui.removeCallbacks(armRunnable)
        ui.postDelayed(armRunnable, ARM_DELAY_MS)
    }

    /** Noch nicht gesprochenen Rest des laufenden Turns festhalten (null = Resume nicht möglich/erlaubt). */
    private fun captureResumeSnapshot(): ResumeSnapshot? {
        val t = turn ?: return null
        val p = activePipeline ?: return null
        // Noch nicht gechunkter Text: ist der Stream zu Ende, auch der Rest ohne Satzzeichen; läuft er noch, nur
        // vollständige Sätze (der Stream wird beim Barge-in abgebrochen — ein halber Satz wäre unsauber).
        // Ein fortgesetzter Turn hat seinen Rest schon komplett in der Pipeline (raw ist nur Anzeige) → kein Leftover,
        // sonst wuerde der gesamte Text erneut vorgelesen.
        val leftover = if (t.resumed) emptyList() else
            t.chunker.update(SpeechText.displayText(t.raw.toString()), final = t.streamEnded)
                .map { SpeechText.cleanForSpeech(it) }
                .filter { it.isNotEmpty() }
        // #220: angebrochenen Satz ab der Stelle (MediaPlayer-Fortschritt), nicht von vorn
        val rest = BargeInResume.remainingAt(p.unspokenSentences(), leftover, speech.playbackFraction())
        return if (rest.any { it.isNotBlank() }) ResumeSnapshot(rest, t.raw.toString()) else null
    }

    private fun rememberInterruption(snap: ResumeSnapshot?) {
        // Neue Unterbrechung ueberschreibt IMMER (auch mit null): eine aeltere Stelle waere sonst veraltet
        interrupted = snap
        interruptedAt = SystemClock.uptimeMillis()
    }

    /**
     * #220: „weiter“ / „mach weiter“ nach einer Unterbrechung → exakt an der gemerkten Stelle fortfahren, kein
     * Backend-Turn, kein Neustart. Nur mit frischer Stelle ([BargeInResume.RESUME_WINDOW_MS]).
     * @return true, wenn die Aeusserung hier verbraucht wurde.
     */
    private fun tryResumeByCommand(text: String): Boolean {
        val snap = interrupted ?: return false
        if (released || !BargeInResume.isResumeCommand(text)) return false
        if (!BargeInResume.isFresh(SystemClock.uptimeMillis(), interruptedAt)) { interrupted = null; return false }
        val st = machine.state
        // BargeInFalseAlarm kennt nur LISTENING/THINKING (OverlayStateMachine) — sonst nicht verbrauchen
        if (st != OverlayState.LISTENING && st != OverlayState.THINKING) return false
        echoFilterArmed = false
        if (!startResumedSpeech(snap)) return false // Stelle bleibt erhalten, „weiter“ geht normal weiter
        interrupted = null
        pendingResume = null
        endBargeInSession(genuine = true)
        android.util.Log.i(TAG, "weiter -> setzt an der Unterbrechungsstelle fort (${snap.rest.size} Saetze)")
        return true
    }

    /**
     * Barge-in war ein Fehlalarm (keine echte Nutzer-Sprache): den unterbrochenen Turn fortsetzen.
     * Die Pipeline kann nicht pausieren → neue Pipeline mit dem gemerkten Rest (der unterbrochene Satz wird neu
     * gesprochen). Der Echo-Strike wird weiter gebucht (endBargeInSession(false)).
     * @return true, wenn fortgesetzt wird (Aufrufer darf dann keine Fehlermeldung/IDLE-Übergang mehr auslösen).
     */
    private fun tryResumeAfterFalseAlarm(): Boolean {
        val snap = pendingResume
        if (released || !bargeInSession || snap == null) return false
        val st = machine.state
        if (st != OverlayState.LISTENING && st != OverlayState.THINKING) return false
        if (!BargeInResume.canResume(resumesThisTurn, snap.rest)) return false
        pendingResume = null
        interrupted = null // die Stelle wird jetzt ohnehin fortgesetzt
        resumesThisTurn++
        echoFilterArmed = false
        endBargeInSession(genuine = false)
        android.util.Log.i(TAG, "bargeIn/Fehlalarm -> Donna spricht weiter (${snap.rest.size} Saetze)")
        startResumedSpeech(snap)
        return true
    }

    /** Neue Pipeline mit dem gemerkten Rest (an der Unterbrechungsstelle) → SPEAKING, Erkenner verwerfen. */
    private fun startResumedSpeech(snap: ResumeSnapshot): Boolean {
        fire(OverlayEvent.BargeInFalseAlarm) // → SPEAKING, Erkenner verwerfen
        if (machine.state != OverlayState.SPEAKING) return false
        val rt = Turn("", INPUT_MODE_VOICE)
        rt.resumed = true
        rt.raw.append(snap.raw)
        rt.started = true
        rt.streamEnded = true
        rt.sawDone = true
        turn = rt
        activePipeline = rt.pipeline
        requestActive = true
        speech.acquireFocus()
        lastSpokenText = snap.rest.joinToString(" ")
        snap.rest.forEach { rt.pipeline.enqueue(it) }
        rt.pipeline.finish()
        return true
    }

    private fun endBargeInSession(genuine: Boolean) {
        if (!bargeInSession) return
        bargeInSession = false
        pendingResume = null
        if (genuine) {
            BargeInDetector.echoStrikes.reset()
        } else {
            BargeInDetector.echoStrikes.record()
        }
    }

    // ── /chat senden + SSE-Stream ─────────────────────────────────────────────
    private inner class Turn(val message: String, val inputMode: String) {
        val raw = StringBuilder()
        var chunker = SentenceChunker()
        var sawDone = false
        var started = false
        var streamEnded = false
        var resumed = false // #220: Fortsetzung — der Rest steckt schon in der Pipeline
        var sawCardOrAction = false
        val deviceBudget = TurnBudget()
        var truncated = false
        var renderScheduled = false
        val renderRunnable = Runnable {
            renderScheduled = false
            if (turn === this) renderResponse(this)
        }
        fun newPipeline() = SentenceSpeechPipeline(
            scope = scope,
            synthesize = { speech.synthesizeSentence(it) },
            play = { speech.playSentence(it) },
            fallbackSpeak = { speech.speakSystemSentence(it) },
            onFinished = { spoke -> onSpeechFinished(this, spoke) },
        )
        var pipeline: SentenceSpeechPipeline = newPipeline()
    }

    private sealed class StreamMsg {
        class Event(val event: SseEvent) : StreamMsg()
        class Failure(val error: UserError) : StreamMsg()
        object End : StreamMsg()
    }

    private fun sendToBackend(message: String, inputMode: String, genuine: Boolean = false) {
        fire(OverlayEvent.TextSubmitted(fromRecognizer = inputMode == INPUT_MODE_VOICE, genuineSpeech = genuine))
        turnGuard.onRequestStarted() // neuer Zug: noch keine Antwort
        lastSpokenText = ""
        bargeInSession = false
        pendingResume = null
        interrupted = null // #220: neuer Nutzer-Turn → alte Unterbrechungsstelle verfaellt
        resumesThisTurn = 0 // neuer Nutzer-Turn → frisches Resume-Budget

        val myTurn = Turn(message, inputMode)
        OfflineRoutineStore.syncIfStale(context) // #147 D: Routinen-Stand fürs Handy ohne Server (gedrosselt, im Hintergrund)
        turn = myTurn
        activePipeline = myTurn.pipeline
        requestActive = true
        speech.acquireFocus() // Fremde Medien für Donnas Turn ducken; Freigabe am Turn-Ende
        if (inputMode == INPUT_MODE_VOICE) listenGeneration++
        ui.removeCallbacks(recognizerWatchdog)
        lastRenderAt = 0L
        ui.removeCallbacks(armRunnable) // alten Arm-Timer abbrechen — kein Früh-Scharfschalten neuer Karten
        host.clearCards() // Karten des vorigen Turns entfernen

        val token = TokenStore.getToken(context) ?: ""
        val sessionId = currentSessionId
        // X-Biometric-Auth nur bei entsperrtem Gerät (wie VIA: an Entsperrt/Biometrie gekoppelt), nicht
        // allein am Token — der Service läuft ohnehin nur entsperrt, aber so bleibt es auch korrekt, falls
        // das Backend den (derzeit reservierten) Header künftig auswertet.
        val authed = token.isNotBlank() && !isDeviceLocked()
        val messages = Channel<StreamMsg>(Channel.UNLIMITED)

        requestJob = scope.launch {
            launch(Dispatchers.IO) { readStream(messages, message, inputMode, token, sessionId, authed) }
            for (msg in messages) {
                if (turn !== myTurn) break
                handleStreamMsg(myTurn, msg)
            }
        }
    }

    /** #147 Paket D: Server nicht erreichbar -> Grundbefehle auf dem Gerät (Regeltabelle), sonst ehrliche Meldung. */
    private fun offlineReply(out: Channel<StreamMsg>, message: String) {
        OfflineFallback.events(context, prefs, message).forEach { out.trySend(StreamMsg.Event(it)) }
    }

    private suspend fun readStream(
        out: Channel<StreamMsg>,
        message: String,
        inputMode: String,
        token: String,
        sessionId: String?,
        authed: Boolean,
    ) {
        var conn: HttpURLConnection? = null
        var reached = false   // #147 D: Verbindungsaufbau zum Server gelungen
        var sawEvent = false  // #147 D: der Server hat schon ein Ereignis geliefert
        val timedOut = AtomicBoolean(false)
        try {
            // #147 Paket D: gar kein Netz -> sofort der Offline-Weg (Grundbefehle auf dem Gerät), ohne Wartezeit
            if (OfflineFallback.noInternet(context)) {
                offlineReply(out, message)
                return
            }
            val url = URL(BuildConfig.DONNA_API_URL + "/chat")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream")
                setRequestProperty("Accept-Encoding", "identity")
                sessionId?.let { setRequestProperty("X-Session-ID", it) }
                if (authed) setRequestProperty("X-Biometric-Auth", "true")
                doOutput = true
                connectTimeout = OfflineGate.CONNECT_TIMEOUT_MS // #147 D: kurze Zeitgrenze fürs Verbinden
                readTimeout = 120_000
            }
            activeConn = conn
            conn.connect()
            reached = true
            val body = JSONObject().apply {
                put("message", message)
                put("stream", true)
                put("client", "android")
                put("input_mode", inputMode)
                put("tts_engine", TtsEngines.normalize(prefs.getString(TtsEngines.PREF_ENGINE, TtsEngines.DEFAULT)))
                put("tts_voice", TtsEngines.normalizeEdgeVoice(prefs.getString(TtsEngines.PREF_EDGE_VOICE, null)))
                // #147: nur die in den Einstellungen eingeschalteten Geräte-Aktionsarten (Standard: keine)
                val deviceKinds = DeviceActionKind.enabledKeysNoCards(prefs)
                if (deviceKinds.isNotEmpty()) {
                    put("device_actions", JSONArray(deviceKinds))
                    put(DeviceActionReport.REQUEST_FLAG, true) // #183: diese App meldet Ergebnisse zurück
                }
            }.toString()
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

            val streamConn = conn
            val completed = coroutineScope {
                val limiter = launch(Dispatchers.IO) {
                    delay(STREAM_MAX_MS)
                    timedOut.set(true)
                    try { streamConn.disconnect() } catch (_: Exception) {}
                }
                try {
                    val code = streamConn.responseCode
                    if (code != 200) {
                        if (OfflineGate.shouldFallback(sawEvent, null, code, true)) {
                            offlineReply(out, message) // 502/503/504: Server dahinter nicht erreichbar
                            return@coroutineScope false
                        }
                        out.trySend(StreamMsg.Failure(OverlayErrors.fromHttp(code)))
                        return@coroutineScope false
                    }
                    streamConn.getHeaderField("X-Session-ID")?.let { currentSessionId = it }
                    streamConn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val line = SseLineReader.readLine(reader) ?: break
                            val event = SseLineParser.parse(line) ?: continue
                            sawEvent = true
                            out.trySend(StreamMsg.Event(event))
                            if (event is SseEvent.Done || event is SseEvent.Error) break
                        }
                    }
                    true
                } finally {
                    limiter.cancel()
                }
            }
            if (completed) out.trySend(StreamMsg.End)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (currentCoroutineContext().isActive && OfflineGate.shouldFallback(sawEvent, e, null, reached)) {
                offlineReply(out, message) // Server nicht erreichbar (Verbindungsaufbau gescheitert)
                return
            }
            if (currentCoroutineContext().isActive) {
                out.trySend(
                    StreamMsg.Failure(
                        if (timedOut.get()) UserError(UserError.Kind.NETWORK, "Donna braucht zu lange. Nochmal versuchen?")
                        else OverlayErrors.fromException(e),
                    ),
                )
            }
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
            if (activeConn === conn) activeConn = null
            out.close()
        }
    }

    private fun handleStreamMsg(t: Turn, msg: StreamMsg) {
        when (msg) {
            is StreamMsg.Event -> when (val e = msg.event) {
                is SseEvent.Delta -> onDelta(t, e.text)
                is SseEvent.WeatherCard -> { t.sawCardOrAction = true; host.onWeatherCard(e.data) }
                is SseEvent.MapCard -> { t.sawCardOrAction = true; host.onMapCard(e.url) }
                is SseEvent.Action -> {
                    t.sawCardOrAction = true
                    if (JsonSafe.str(e.action, "type", "") == DeviceActionKind.TYPE) host.onDeviceAction(e.action, t.deviceBudget)
                    else host.onActionCard(e.action)
                }
                is SseEvent.Error -> failStream(
                    t,
                    UserError(UserError.Kind.SERVER, e.message.ifBlank { "Donna konnte gerade nicht antworten. Nochmal versuchen?" }),
                )
                is SseEvent.Replace -> replaceText(t, e.text)
                SseEvent.Done -> { t.sawDone = true; finishStream(t) }
            }
            is StreamMsg.End ->
                if (!t.sawDone && !t.streamEnded) {
                    failStream(t, UserError(UserError.Kind.NETWORK, "Die Verbindung wurde unterbrochen, die Antwort ist unvollständig. Nochmal versuchen?"))
                } else {
                    finishStream(t)
                }
            is StreamMsg.Failure -> failStream(t, msg.error)
        }
    }

    private fun failStream(t: Turn, error: UserError) {
        ui.removeCallbacks(t.renderRunnable)
        if (t.raw.isNotEmpty()) renderResponse(t)
        requestActive = false
        t.pipeline.stop()      // Donna darf nicht weiterreden, während Fehler/Relisten läuft (Echo-Schutz)
        activePipeline = null
        turn = null
        speech.releaseFocus()
        // Bereits gezeigte Karten bleiben bedienbar (Ja/Nein), auch wenn der Stream mit Fehler endete.
        ui.removeCallbacks(armRunnable); ui.postDelayed(armRunnable, ARM_DELAY_MS)
        fail(error)
    }

    private fun replaceText(t: Turn, text: String) {
        t.pipeline.stop()
        speech.stopPlaybackKeepFocus()
        t.pipeline = t.newPipeline()
        activePipeline = t.pipeline  // Echo-Schutz: neue Pipeline MUSS hier übernommen werden (sonst stoppt
                                     // ein späterer Abbruch nur die alte, tote Pipeline → Donna spräche weiter)
        t.chunker = SentenceChunker()
        t.raw.setLength(0)
        t.raw.append(text.take(MAX_RESPONSE_CHARS))
        t.truncated = false
        lastSpokenText = ""
        if (!t.started && text.isNotBlank()) {
            t.started = true
            fire(OverlayEvent.ResponseStarted)
        }
        ui.removeCallbacks(t.renderRunnable)
        t.renderScheduled = false
        renderResponse(t)
        t.chunker.update(SpeechText.displayText(t.raw.toString()), final = false).forEach { speakChunk(t, it) }
    }

    private fun onDelta(t: Turn, text: String) {
        if (t.truncated) return
        val room = MAX_RESPONSE_CHARS - t.raw.length
        if (text.length > room) {
            t.raw.append(text, 0, room.coerceAtLeast(0))
            t.truncated = true
        } else {
            t.raw.append(text)
        }
        if (!t.started) {
            t.started = true
            fire(OverlayEvent.ResponseStarted)
        }
        scheduleRender(t)
        t.chunker.update(SpeechText.displayText(t.raw.toString()), final = false).forEach { speakChunk(t, it) }
    }

    private fun speakChunk(t: Turn, chunk: String) {
        // Antwort trifft waehrend des Tippens ein (Tippen in THINKING): nur als Text zeigen, nicht ueber das Tippen sprechen
        if (!PanelFocus.speaksAnswer(host.isTypingActive())) return
        val clean = SpeechText.cleanForSpeech(chunk)
        if (clean.isNotEmpty()) {
            lastSpokenText = if (lastSpokenText.isEmpty()) clean else "$lastSpokenText $clean"
            t.pipeline.enqueue(clean)
        }
    }

    private fun scheduleRender(t: Turn) {
        when (val d = TurnRenderer.throttle(
            now = SystemClock.uptimeMillis(),
            lastRenderAt = lastRenderAt,
            throttleMs = OverlayTimings.TEXT_THROTTLE_MS,
            alreadyScheduled = t.renderScheduled,
        )) {
            TurnRenderer.Throttle.RenderNow -> {
                ui.removeCallbacks(t.renderRunnable)
                t.renderScheduled = false
                renderResponse(t)
            }
            is TurnRenderer.Throttle.Schedule -> {
                t.renderScheduled = true
                ui.postDelayed(t.renderRunnable, d.delayMs)
            }
            TurnRenderer.Throttle.Skip -> {}
        }
    }

    private fun renderResponse(t: Turn) {
        lastRenderAt = SystemClock.uptimeMillis()
        turnRenderer.render(t.raw.toString(), t.streamEnded, userScrolled = false)
    }

    private fun finishStream(t: Turn) {
        if (t.streamEnded) return
        t.streamEnded = true
        ui.removeCallbacks(t.renderRunnable)
        t.renderScheduled = false
        renderResponse(t)
        ui.removeCallbacks(armRunnable); ui.postDelayed(armRunnable, ARM_DELAY_MS) // Fehltipp-Schutz: Bestätigen erst kurz nach Stream-Ende
        val finalText = SpeechText.displayText(t.raw.toString())
        t.chunker.update(finalText, final = true).forEach { speakChunk(t, it) }
        // Nur wenn WIRKLICH nichts kam (kein Text UND keine Karte/Aktion) ist es ein Fehler — eine
        // Antwort, die nur eine Karte/Aktion auslöst (z. B. Termin, Navigation), ist gültig.
        if (finalText.isBlank() && !t.sawCardOrAction) {
            failStream(t, UserError(UserError.Kind.SERVER, "Donna hat nichts geantwortet. Nochmal versuchen?"))
            return
        }
        t.pipeline.finish()
    }

    private fun onSpeechFinished(t: Turn, spokeAnything: Boolean) {
        if (turn !== t) return
        interrupted = null // #220: alles gesprochen → nichts mehr fortzusetzen
        requestActive = false
        activePipeline = null
        turn = null
        speech.releaseFocus()
        val confirmable = host.hasVoiceConfirmableCard()
        fire(OverlayEvent.ResponseFinished(
            spokeAnything,
            hasPendingCards = host.hasPendingActionCards(),
            typingActive = host.isTypingActive(),
            // #52: eine einfache Ja/Nein-Karte wartet → nach der Frage zuhören (Sprach-Antwort), begrenzt durch
            // die Auto-Relisten-Limits der Zustandsmaschine; Auswahl-/Fehlerkarten bleiben beim alten IDLE.
            voiceConfirmable = confirmable,
        ))
        // Das Antwort-Fenster öffnet NUR, wenn die Maschine wirklich automatisch zuhört (Echo-Guard-Relisten).
        if (confirmable && machine.state.isListening) voiceReplyWindow.open()
    }

    private fun stopSpeaking() {
        activePipeline?.stop()   // aktive Pipeline stoppen — `turn` ist bei CancelRequest schon null
        turn?.pipeline?.stop()   // Backup gegen eine verfehlte activePipeline-Invariante (Defense-in-Depth)
        activePipeline = null
        speech.stopPlaybackKeepFocus()
        speech.releaseFocus()
    }

    private fun cancelRequest() {
        requestActive = false
        requestJob?.cancel()
        requestJob = null
        activePipeline?.stop()   // vorab synthetisierte Sätze stoppen (Echo-Schutz bei Barge-in/Stop/Fehler)
        turn?.pipeline?.stop()   // Backup gegen eine verfehlte activePipeline-Invariante (Defense-in-Depth)
        activePipeline = null
        turn = null
        // Disconnect auf eigenem Thread (NICHT im scope): in release() wird der scope unmittelbar danach
        // gecancelt — ein scope.launch-Disconnect liefe nie, der blockierende Read hinge bis readTimeout (120s).
        activeConn?.let { c -> Thread { try { c.disconnect() } catch (_: Exception) {} }.start() }
        activeConn = null
    }

    private fun isDeviceLocked(): Boolean =
        (context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager)?.isKeyguardLocked == true

    companion object {
        private const val TAG = "ConversationController"
        private const val INPUT_MODE_VOICE = "voice"
        private const val MAX_RESPONSE_CHARS = 20_000
        private const val MAX_LISTEN_MS = 30_000L
        private const val RECOGNIZER_WATCHDOG_MS = 15_000L
        private const val ECHO_GUARD_MS = 350L
        private const val BARGE_IN_NO_SPEECH_MS = 4_000L // nach Barge-in: ohne Sprache früher als Fehlalarm werten
        private const val ARM_DELAY_MS = 400L // Karten-Buttons erst so lange nach Stream-Ende scharf (Fehltipp-Schutz)
        internal const val MUTED_STATUS = "Stumm"
        private const val STREAM_MAX_MS = 240_000L
        private const val PREF_CONVERSATION_MODE = "conversation_mode"
        private const val INPUT_MODE_TEXT = "text"
        private const val TYPING_STATUS = "Du schreibst — ich höre gerade nicht zu."
    }
}
