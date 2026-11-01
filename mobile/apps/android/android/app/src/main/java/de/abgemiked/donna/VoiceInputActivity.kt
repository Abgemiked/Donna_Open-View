package com.yourcompany.donna

/**
 * VoiceInputActivity — Gemini-artiges Bottom-Sheet-Overlay für Donna
 *
 * Erscheint als Bottom-Sheet über der aktuellen App, wenn Donna per
 * Android Assistant / Side-Button aufgerufen wird.
 *
 * Ablauf (UX-02/03/04): Zustandsautomat [OverlayStateMachine] (Leerlauf → Zuhören → Denken → Sprechen
 * → Fehler) mit animiertem [DonnaOrbView]; die Antwort wird per SSE inkrementell gelesen und gedrosselt
 * angezeigt; die Sprachausgabe läuft satzweise ([SentenceSpeechPipeline], erster Satz sofort);
 * Sprachende wird automatisch erkannt, im Gesprächsmodus hört Donna nach dem ECHTEN Ende der Sprachausgabe
 * automatisch wieder zu.
 *
 * Design: folgt dem System-Theme (UX-01): Dark/Light (values/values-night) und auf Android 12+
 * die Material-You-Systemfarben (values-v31 / values-night-v31). Fallback = Pulse-Palette
 * (dunkelblau #03090f, Cyan-Akzent #38bdf8) bzw. deren Light-Variante. Farben: colors.xml je Ressourcen-Ordner
 * (values, values-night, values-v31, values-night-v31); Orb-/Scrim-Zusatzfarben in donna_orb.xml.
 */

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.text.InputType
import android.content.ActivityNotFoundException
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.json.JSONArray
import org.json.JSONObject
import com.yourcompany.donna.overlay.ActionCardBuilder
import com.yourcompany.donna.overlay.ActionExecutor
import com.yourcompany.donna.overlay.TurnRenderer

class VoiceInputActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private var speechRecognizer: SpeechRecognizer? = null
    // Android-TTS (System/Samsung SMT): letzte Stufe, wenn weder Edge (Backend /tts) noch Piper-lokal sprechen koennen
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    // Wiedergabe pro Satz (MP3 von Edge oder WAV von Piper-lokal; A/B-Wahl in den Einstellungen)
    private var mediaPlayer: android.media.MediaPlayer? = null
    private val ui = Handler(Looper.getMainLooper())
    // Nicht abgefangene Fehler in Koroutinen (Netz, Audio, Player) dürfen das Overlay nie abstürzen lassen
    private val scope = CoroutineScope(
        Dispatchers.Main + SupervisorJob() + CoroutineExceptionHandler { _, e ->
            android.util.Log.w("VoiceInputActivity", "Koroutine fehlgeschlagen: ${e.message}")
            ui.post {
                if (!isDestroyed && !isFinishing && machine.state != OverlayState.IDLE) {
                    fail(UserError(UserError.Kind.UNKNOWN, "Etwas ist schiefgelaufen. Nochmal versuchen?"))
                }
            }
        }
    )

    // UI-Elemente
    private lateinit var statusText: TextView
    private lateinit var partialText: TextView
    private lateinit var cardContainer: LinearLayout   // Wetter- / Map- / Aktionskarten
    private lateinit var cardScroll: FrameLayout       // Wrapper (kein eigenes Scrollen): der Körper scrollt als Ganzes
    private lateinit var historyScroll: FrameLayout
    private lateinit var bodyScroll: ScrollView        // Verlauf · Karten · Frage · Antwort — nimmt den verbleibenden Platz
    private lateinit var bodyContent: LinearLayout
    private lateinit var adaptivePanel: AdaptivePanel
    private lateinit var questionText: TextView
    private lateinit var responseScroll: FrameLayout
    private lateinit var responseText: TextView
    private lateinit var inputField: EditText
    private lateinit var micButton: TextView   // TextView als Icon-Button
    private lateinit var sendButton: TextView
    private lateinit var orb: DonnaOrbView
    private lateinit var actionPill: TextView   // Fertig / Abbrechen / Stopp / Nochmal
    private lateinit var conversationChip: TextView
    private var micBg: GradientDrawable? = null
    private lateinit var historyPanel: LinearLayout  // Gesprächsverlauf-Panel
    private lateinit var contentPanel: LinearLayout  // Das eigentliche Overlay-Panel (für IME)

    // ── Zustandsautomat (UX-02) ──────────────────────────────────────────────
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val machine by lazy {
        OverlayStateMachine(conversationMode = prefs.getBoolean(PREF_CONVERSATION_MODE, true))
    }

    // ── Extrahierte Overlay-Bausteine (B1 Phase-C Refactor) ───────────────────
    // Reine Struktur-Extraktion: die Logik lebt jetzt in overlay/, die Activity delegiert nur.
    private val actionExecutor by lazy {
        ActionExecutor(object : ActionExecutor.Host {
            override fun startActivity(intent: Intent) = this@VoiceInputActivity.startActivity(intent)
            override fun getLaunchIntentForPackage(packageName: String): Intent? =
                this@VoiceInputActivity.packageManager.getLaunchIntentForPackage(packageName)
            override fun sendToTesla(target: String) = this@VoiceInputActivity.sendToTesla(target)
            override fun teslaTargetOf(action: JSONObject): String = this@VoiceInputActivity.teslaTargetOf(action)
            override fun openCheckedUrl(checked: UrlPolicy.Checked?) = this@VoiceInputActivity.openCheckedUrl(checked)
            override fun onActionFailed(e: Exception) { toast(startFailureMessage(e)) }
        })
    }
    private val actionCardBuilder by lazy {
        ActionCardBuilder(object : ActionCardBuilder.Host {
            override val ctx: android.content.Context get() = this@VoiceInputActivity
            override fun dp(n: Int): Int = this@VoiceInputActivity.dp(n)
            override fun setRole(v: View, className: String, checked: (() -> Boolean)?) =
                this@VoiceInputActivity.setRole(v, className, checked)
            override val armTag: String get() = ARM_TAG
            override val colorSurface: Int get() = this@VoiceInputActivity.colorSurface
            override val colorBorder: Int get() = this@VoiceInputActivity.colorBorder
            override val colorText: Int get() = this@VoiceInputActivity.colorText
            override val colorOnAccent: Int get() = this@VoiceInputActivity.colorOnAccent
            override val colorAccent: Int get() = this@VoiceInputActivity.colorAccent
            override val colorMuted: Int get() = this@VoiceInputActivity.colorMuted
            override val colorCard: Int get() = this@VoiceInputActivity.colorCard
            override fun executeAction(action: JSONObject) {
                actionExecutor.execute(action)
                maybeAutoSend(action)   // B2: nach Mikes „Ja" den Senden-/Anrufen-Button selbst tippen
            }
            override fun sendToTesla(target: String) = this@VoiceInputActivity.sendToTesla(target)
            override fun startMapsNavigation(dest: String, lat: Double?, lon: Double?) =
                this@VoiceInputActivity.startMapsNavigation(dest, lat, lon)
            override fun teslaTargetOf(action: JSONObject): String = this@VoiceInputActivity.teslaTargetOf(action)
        })
    }
    private val turnRenderer by lazy {
        TurnRenderer(object : TurnRenderer.Host {
            override fun setResponseHtml(html: CharSequence) { responseText.text = html }
            override fun showResponse() { responseScroll.visibility = View.VISIBLE }
            override fun autoscroll() { bodyScroll.post { bodyScroll.scrollTo(0, bodyContent.height) } }
        })
    }
    /**
     * B2 Accessibility Auto-Send (#3/#1): tippt nach Mikes Bestätigung („Ja"-Button bzw. verbale
     * Zustimmung → ActionCardBuilder ruft executeAction) den Senden-/Anrufen-Button selbst.
     * Nur whatsapp/call; `autoSend:false` schaltet es ab (abwärtskompatibles Opt-out). Kern-Invariante:
     * wird nur aus dem Confirm-Pfad gerufen — ohne Mikes OK passiert nichts. Kein Node → Service toastet.
     */
    private fun maybeAutoSend(action: JSONObject) {
        val type = action.safeString("type")
        if (type != "whatsapp" && type != "call") return
        val spec = SendNodeFinder.specFor(type) ?: return
        val manualHint = if (type == "call")
            "Bitte in der Telefon-App auf Anrufen tippen."
        else
            "Bitte in WhatsApp auf Senden tippen."
        // F4: autoSend:false → kein Auto-Tap, aber sichtbarer Hinweis, dass Mike selbst tippen muss.
        if (!action.optBoolean("autoSend", true)) { toast(manualHint); return }
        val service = DonnaAccessibilityService.instance
        if (service == null) {
            // F1: Service aus/nicht verbunden → nicht still, sondern sichtbarer Fallback.
            toast(manualHint)
            return
        }
        service.requestAutoTap(spec, spec.toHints(), 7_000L) { /* Service toastet selbst bei Fehlschlag */ }
    }

    private var isListening = false          // Erkenner läuft
    private var heardSpeech = false          // in diesem Zuhören wurde Sprache erkannt
    private var listenStartedAt = 0L         // elapsedRealtime, wann der Erkenner bereit war
    private var listenRetries = 0            // stille Neustarts des Erkenners (max. 1 pro Zuhören)
    private var biometricPending = false     // Biometrie-Prompt offen → erst danach zuhören
    private var listenGeneration = 0         // erhöht bei jedem Start/Abbruch: veraltete Erkenner-Callbacks verwerfen
    private var imeVisible = false           // Tastatur offen (Insets)
    private var userScrolledResponse = false // Nutzer hat die Antwort selbst gescrollt → kein Autoscroll
    private var lastError: UserError? = null
    // ── Voice-Barge-in (Donna per Stimme unterbrechen) ──
    private val bargeInDetector by lazy { BargeInDetector(this) { onVoiceBargeIn() } }
    private var bargeInSession = false       // das laufende Zuhören wurde durch Mikes Stimme ausgelöst
    private var lastSpokenText = ""          // was Donna in dieser Antwort gesprochen hat (Echo-Abgleich)
    // Echo-Filter nur bei Auto-Relisten und Barge-in: bei manuellem Mic-Tap spricht Mike garantiert selbst
    private var echoFilterArmed = false
    private var pendingRetry: Pair<String, String>? = null   // (Nachricht, Eingabemodus) der fehlgeschlagenen Anfrage

    // ── Session-Tracking (Gesprächsverlauf) ──────────────────────────────────
    @Volatile private var currentSessionId: String? = null
    private var isAuthenticated = false

    // ── Aktueller Antwort-Turn (Streaming) ───────────────────────────────────
    private var turn: Turn? = null
    private var requestJob: Job? = null
    /** Eine Anfrage/Antwort läuft (von sendToBackend bis Sprachende, Fehler oder Abbruch). */
    private var requestActive = false
    @Volatile private var activeConn: HttpURLConnection? = null
    private var lastRenderAt = 0L
    private var utteranceSeq = 0
    private val ttsWaiters = HashMap<String, CancellableContinuation<Boolean>>()

    private val noSpeechRunnable = Runnable { onNoSpeechTimeout() }
    private val maxListenRunnable = Runnable { onMaxListenReached() }
    private val recognizerWatchdog = Runnable { onRecognizerStuck() }
    private val closeRunnable = Runnable { onCloseTimer() }
    private val autoListenRunnable = Runnable { onAutoListenDue() }
    private val armCardsRunnable = Runnable { armActionButtons() }
    private val idleCloseRunnable = Runnable { onIdleCloseDue() }

    // ── Theme (UX-01) ────────────────────────────────────────────────────────
    // Farben kommen aus res/values*/colors.xml (Light/Dark/Dynamic Colors). `by lazy`, weil
    // Property-Initializer vor attachBaseContext laufen; die Activity wird bei Schema-Wechsel
    // neu erstellt (keine configChanges) und liest die Farben dann frisch.
    private fun themeColor(id: Int): Int = ContextCompat.getColor(this, id)
    private val colorCard      by lazy { themeColor(R.color.donna_card) }
    private val colorSurface   by lazy { themeColor(R.color.donna_surface) }
    private val colorAccent    by lazy { themeColor(R.color.donna_accent) }
    private val colorAccent2   by lazy { themeColor(R.color.donna_accent2) }
    private val colorText      by lazy { themeColor(R.color.donna_text) }
    private val colorMuted     by lazy { themeColor(R.color.donna_muted) }
    private val colorBorder    by lazy { themeColor(R.color.donna_border) }
    private val colorHandle    by lazy { themeColor(R.color.donna_handle) }
    private val colorMicActive by lazy { themeColor(R.color.donna_mic_active) }
    /** Text/Icon auf einer Akzent-Flaeche (Avatar, Senden, "Ja"). */
    private val colorOnAccent  by lazy { themeColor(R.color.donna_on_accent) }
    private val colorOk        by lazy { themeColor(R.color.donna_ok) }
    /** Fehler = Bernstein (donna_orb.xml), Abdunklung hinter dem Overlay. */
    private val colorError     by lazy { themeColor(R.color.donna_orb_error) }
    private val colorScrim     by lazy { themeColor(R.color.donna_scrim) }

    companion object {
        private const val MIC_PERMISSION_RC = 200
        private const val PREFS = "donna_overlay_prefs"
        private const val PREF_CONVERSATION_MODE = "conversation_mode"
        /**
         * Voice-Barge-in (Unterbrechen per Stimme, Mikrofon offen WÄHREND Donna spricht): Standard AN (Opt-out, Voice-Batch).
         * Ausschalten: lange auf den Gesprächs-Chip tippen. Gegen Selbst-Auslösung/Lärm schuetzen AEC-Pflicht,
         * Lernphase, Sprachband-VAD + Stimm-Ähnlichkeit + Umgebungsboden ([BargeInVad]) und die Echo-Strikes
         * (2 Fehlausloeser → fuer den Prozess aus); 90-s-Hard-Cap und Abbruch im Hintergrund/gesperrt bleiben.
         */
        private const val VOICE_BARGE_IN_DEFAULT = true
        /** Voice-Barge-in (Unterbrechen per Stimme) — Einstellung, siehe [VOICE_BARGE_IN_DEFAULT]. */
        private const val PREF_VOICE_BARGE_IN = "voice_barge_in"
        /** Einmaliger Erst-Hinweis auf das Unterbrechen per Stimme. */
        private const val PREF_BARGE_IN_HINT_SHOWN = "barge_in_hint_shown"
        /** Vorbereitet für den Einstellungen-Screen (späterer Batch): "short" | "normal" | "long". */
        private const val PREF_SILENCE_PROFILE = "silence_profile"
        /** Seitentaste nach dem Sprechen losgelassen = „Fertig“, aber nur nach so langem Zuhören (Tipp-Schutz). */
        private const val KEY_RELEASE_MIN_LISTEN_MS = 1_500L
        private const val ACTION_CARD_TAG = "donna_action_card"
        private const val ARM_TAG = "donna_arm_button"
        /** Max. Zuhördauer am Stück. */
        private const val MAX_LISTEN_MS = 30_000L
        /** Spracherkenner liefert nach „Sprachende“ kein Ergebnis → abbrechen. */
        private const val RECOGNIZER_WATCHDOG_MS = 15_000L
        /** Echo-Schutz vor dem automatischen Relisten. */
        private const val ECHO_GUARD_MS = 350L
        /** Edge-TTS pro Satz: schnell oder gar nicht (danach Piper-lokal). Worst Case ≈ 1,5 + 3 s. */
        private const val EDGE_CONNECT_TIMEOUT_MS = 1_500
        private const val EDGE_READ_TIMEOUT_MS = 3_000
        /** Karten-Buttons erst kurz nach Streaming-Ende scharf (Fehltipp-Schutz, Karten rutschen beim Streamen). */
        private const val ARM_DELAY_MS = 400L
        private const val CLOSE_AFTER_NOTHING_HEARD_MS = 2_500L
        /** Nach einem Gespräch im Leerlauf: Overlay nach so langer Ruhe schließen (Akku, aufgeräumter Bildschirm). */
        private const val IDLE_CLOSE_MS = 60_000L
        /** Gesamtzeitgrenze für EINE Antwort (Stream); darüber wird die Verbindung getrennt. */
        private const val STREAM_MAX_MS = 240_000L
        /** Obergrenze für den angezeigten/gesprochenen Antworttext (Schutz vor fehlerhaften Servern). */
        private const val MAX_RESPONSE_CHARS = 20_000
        private const val INPUT_MODE_VOICE = "voice"
        private const val INPUT_MODE_TEXT = "text"
    }

    /** dp → px */
    private fun dp(n: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, n.toFloat(), resources.displayMetrics
    ).toInt()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            applyTtsSettings()
        } else {
            // Fallback: Google TTS explizit
            tts?.shutdown()
            tts = TextToSpeech(this, { s2 ->
                if (s2 == TextToSpeech.SUCCESS) applyTtsSettings()
            }, "com.google.android.tts")
        }
    }

    private fun applyTtsSettings() {
        tts?.language = Locale.GERMAN
        val voices = tts?.voices
        // Google TTS: nfh = weiblich neural (beste verfügbare Stimme)
        // Fallback-Kette: nfh-network → nfh-local → deb-network → erste DE-Stimme
        val best = voices?.firstOrNull { v -> v.name == "de-de-x-nfh-network" }
            ?: voices?.firstOrNull { v -> v.name == "de-de-x-nfh-local" }
            ?: voices?.firstOrNull { v -> v.name == "de-de-x-deb-network" }
            ?: voices?.firstOrNull { v -> v.locale.language == "de" }
        best?.let { tts?.voice = it }
        tts?.setPitch(0.92f)   // wärmer, voller — weniger hell/hoch
        tts?.setSpeechRate(0.93f)
        tts?.setOnUtteranceProgressListener(utteranceListener)
        ttsReady = true
    }

    // ── Sprachausgabe (satzweise, UX-03) ─────────────────────────────────────

    /** Beendet-Meldungen des System-TTS an die wartende Pipeline weiterreichen (Binder-Thread → UI-Thread). */
    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) { ui.post { onTtsAudioStarted() } }
        override fun onDone(utteranceId: String?) = completeUtterance(utteranceId, true)
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = completeUtterance(utteranceId, false)
        override fun onError(utteranceId: String?, errorCode: Int) = completeUtterance(utteranceId, false)
        override fun onStop(utteranceId: String?, interrupted: Boolean) = completeUtterance(utteranceId, !interrupted)
    }

    private fun completeUtterance(id: String?, spoke: Boolean) {
        if (id == null) return
        ui.post {
            val cont = ttsWaiters.remove(id)
            if (cont != null && cont.isActive) cont.resume(spoke)
        }
    }

    /**
     * System-TTS (Android / Samsung SMT) — Offline-Fallback. Kehrt zurück, wenn der Satz ZU ENDE gesprochen
     * ist (UtteranceProgressListener). Ergebnis: true = es war Ton zu hören.
     */
    private suspend fun speakSystem(text: String): Boolean {
        val engine = tts
        if (!ttsReady || engine == null) return false
        val clean = SpeechText.cleanForSpeech(text)
        if (clean.isEmpty()) return false
        val id = "donna_${++utteranceSeq}"
        // Watchdog: bleibt onDone/onError aus (hängende Engine), geht es nach angemessener Zeit weiter
        val watchdogMs = 4_000L + clean.length * 90L
        val watchdog = Runnable {
            val cont = ttsWaiters.remove(id)
            if (cont != null && cont.isActive) {
                try { engine.stop() } catch (_: Exception) {}
                cont.resume(false)
            }
        }
        try {
            return suspendCancellableCoroutine<Boolean> { cont ->
                ttsWaiters[id] = cont
                cont.invokeOnCancellation {
                    ttsWaiters.remove(id)
                    try { engine.stop() } catch (_: Exception) {}
                }
                if (engine.speak(clean, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) {
                    ttsWaiters.remove(id)
                    if (cont.isActive) cont.resume(false)
                } else {
                    ui.postDelayed(watchdog, watchdogMs)
                }
            }
        } finally {
            ui.removeCallbacks(watchdog)
        }
    }

    /**
     * Edge-TTS (Backend) für EINEN Satz — POST /tts?voice=… → MP3-Bytes.
     *
     * Kurze Socket-Timeouts (Netz-Roundtrip pro Satz): lieber schnell zur Piper-lokal-Stimme wechseln als warten.
     * Bei HTTP 204 (nichts Sprechbares) → null = kein Audio. Bei Fehler / Timeout wirft die Funktion; die
     * [SentenceSynthesizer]-Kette probiert dann die andere Engine, zuletzt System-TTS.
     */
    private suspend fun synthesizeViaServer(text: String): ByteArray? = withContext(Dispatchers.IO) {
        val voice = TtsEngines.normalizeEdgeVoice(prefs.getString(TtsEngines.PREF_EDGE_VOICE, null))
        val url = URL(BuildConfig.DONNA_API_URL + "/tts?voice=" + voice)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer ${TokenStore.getToken(this@VoiceInputActivity) ?: ""}")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "audio/mpeg")
            connectTimeout = EDGE_CONNECT_TIMEOUT_MS
            readTimeout = EDGE_READ_TIMEOUT_MS   // blockierendes I/O ist nicht abbrechbar → hier hart begrenzen
            doOutput = true
        }
        try {
            val body = """{"text":${JSONObject.quote(text)},"was_voice_input":true}"""
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code == 204) return@withContext null   // nichts Sprechbares — kein Audio
            if (code != 200) throw IOException("HTTP $code")
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    /** Piper-lokal für EINEN Satz → WAV-Bytes. Wirft, wenn das Modell nicht bereit ist (→ andere Engine/System-TTS). */
    private suspend fun synthesizeViaPiper(text: String): ByteArray? = withContext(Dispatchers.Default) {
        if (!PiperTTSModule.isEngineReady()) PiperTTSModule.ensureLoadedFromDisk(applicationContext.filesDir)
        if (!PiperTTSModule.isEngineReady()) throw IOException("Piper-Modell nicht bereit")
        PiperTTSModule.synthesizeWav(text)   // null = nichts Sprechbares
    }

    /**
     * Engine-Kette pro Satz (A/B-Umschalter, jeweils die andere Engine als Fallback): Reihenfolge aus der
     * Einstellung [TtsEngines.PREF_ENGINE], gelesen bei JEDEM Satz (Umschalten wirkt sofort). Edge wird nach
     * zwei Fehlern in Folge kurz übersprungen ([TtsHealth.edge]) — Piper antwortet dann ohne Netz-Wartezeit.
     */
    private val sentenceSynth by lazy {
        // explizit getypte Map: nur so werden die Lambdas zu suspend-Funktionen
        val backends = HashMap<String, suspend (String) -> ByteArray?>()
        backends[TtsEngines.EDGE] = { s -> synthesizeViaServer(s) }
        backends[TtsEngines.PIPER] = { s -> synthesizeViaPiper(s) }
        SentenceSynthesizer(
            order = { TtsEngines.order(prefs.getString(TtsEngines.PREF_ENGINE, TtsEngines.DEFAULT)) },
            backends = backends,
            isUsable = { engine -> if (engine == TtsEngines.EDGE) !TtsHealth.edge.isDead() else true },
            onSuccess = { engine -> if (engine == TtsEngines.EDGE) TtsHealth.edge.markSuccess() },
            onFailure = { engine -> if (engine == TtsEngines.EDGE) TtsHealth.edge.markFailed() },
        )
    }

    /** Spielt einen Satz (MP3 von Edge oder WAV von Piper) ab und kehrt zurück, wenn er WIRKLICH zu Ende ist. */
    private suspend fun playAudio(bytes: ByteArray) {
        val tmpFile = File(cacheDir, "donna_overlay_tts_${System.nanoTime()}.${WavEncoder.extensionFor(bytes)}")
        var player: android.media.MediaPlayer? = null
        try {
            withContext(Dispatchers.IO) { FileOutputStream(tmpFile).use { it.write(bytes) } }
            val mp = android.media.MediaPlayer()
            player = mp
            mediaPlayer = mp  // sofort zuweisen, damit onDestroy() ihn findet
            suspendCancellableCoroutine<Unit> { cont ->
                // Race Prepared ↔ Stopp: nach Abbruch darf start() nicht mehr laufen
                var cancelled = false
                // Watchdog: bleiben onPrepared/onCompletion/onError aus (hängender Player), geht es weiter
                val watchdog = Runnable {
                    if (cont.isActive) cont.resumeWithException(IOException("Wiedergabe-Zeitüberschreitung"))
                }
                ui.postDelayed(watchdog, 8_000L)   // bis „prepared“
                mp.setDataSource(tmpFile.absolutePath)
                mp.setOnPreparedListener {
                    try {
                        if (!cancelled) {
                            ui.removeCallbacks(watchdog)
                            ui.postDelayed(watchdog, it.duration.coerceAtLeast(1_000) + 4_000L)   // Dauer + Puffer
                            it.start()
                            ui.post { onTtsAudioStarted() }
                        }
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }
                mp.setOnErrorListener { _, what, extra ->
                    if (cont.isActive) cont.resumeWithException(IOException("MediaPlayer-Fehler $what/$extra"))
                    true
                }
                cont.invokeOnCancellation {
                    cancelled = true
                    ui.removeCallbacks(watchdog)
                    try { mp.stop() } catch (_: Exception) {}
                }
                mp.setOnCompletionListener { ui.removeCallbacks(watchdog); if (cont.isActive) cont.resume(Unit) }
                mp.prepareAsync()
            }
        } finally {
            if (mediaPlayer === player) mediaPlayer = null
            try { player?.release() } catch (_: Exception) {}
            tmpFile.delete()   // auch bei Abbruch während des Schreibens
        }
    }

    /** JSON-null-sicher: org.json liefert für `null` sonst den Text „null“. */
    private fun JSONObject.safeString(key: String, default: String = ""): String = JsonSafe.str(this, key, default)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // WakeWordService hält AudioRecord dauerhaft — stop() unterbricht recorder.read() sofort.
        // Keine Wartezeit mehr (UX-04): der Wake-Word-Dienst ist aus (No-Op); sollte das Mikrofon doch noch
        // belegt sein, startet der Erkenner einmal still neu (siehe onError → isSilentlyRetryable).
        WakeWordService.stop(this)
        // UX-05: Kein harter Abbruch mehr. Die Overlay-Berechtigung wird im Einrichtungs-Assistenten
        // (DonnaSetupModule, Schritt "overlay") vorab erteilt. Fehlt sie trotzdem (z. B. nach einer
        // Neuinstallation), läuft der Overlay als normales App-Fenster weiter (siehe setupWindow) —
        // die Spracheingabe funktioniert, nur das Einblenden über fremden Apps entfällt.
        val overlayGranted = Settings.canDrawOverlays(this)
        if (!overlayGranted) {
            Toast.makeText(
                this,
                "Overlay-Berechtigung fehlt — bitte in Donna unter „Einrichtung“ erteilen",
                Toast.LENGTH_LONG
            ).show()
        }
        // Sperrbildschirm: Overlay wie Bixby über Lock-Screen anzeigen
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        // System-Default-Engine nutzen (auf S25 Ultra = Samsung SMT) — kein expliziter Engine-Call
        // Falls Samsung SMT als System-Default erlaubt ist, kommen die natürlichen Stimmen
        tts = TextToSpeech(this, this)
        setupWindow(overlayGranted)
        val root = buildUI()
        setContentView(root)
        // Den Start-Fokus nimmt der Wurzel-Container, nicht das Eingabefeld (sonst öffnet sich die Tastatur
        // und verdeckt den Orb, und Fokus-Wechsel würden das Zuhören stören)
        root.isFocusable = true
        root.isFocusableInTouchMode = true
        root.requestFocus()
        renderState(OverlayState.IDLE, null)

        val km = getSystemService(android.app.KeyguardManager::class.java)
        if (km?.isKeyguardLocked == true) {
            authenticateWithBiometric()
        } else {
            // Gerät ist bereits entsperrt — kein zusätzlicher Biometrie-Prompt nötig
            isAuthenticated = true
        }

        // Nav-Bar + IME-Inset: Content-Panel springt über Tastatur wenn Textfeld aktiv
        // Padding auf contentPanel, nicht auf dimRoot (der bleibt Vollbild)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomPad = if (ime.bottom > 0) ime.bottom + dp(8) else (dp(8) + navBar.bottom)
            contentPanel.setPadding(dp(12), dp(8), dp(12), bottomPad)
            // Die schlanke Leiste (Mini-Orb, Aktions-Taste) bleibt auch bei offener Tastatur sichtbar:
            // Stopp/Fertig/Abbrechen sind IMMER erreichbar
            val wasVisible = imeVisible
            imeVisible = ime.bottom > 0
            // Oben Platz für die Statusleiste lassen; der Körper des Panels nimmt den Rest
            adaptivePanel.topReservePx = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top + dp(24)
            adaptivePanel.requestLayout()
            updateKeepScreenOn()
            // Wer tippt, will nicht gleichzeitig angehört werden
            if (imeVisible && !wasVisible && machine.state == OverlayState.LISTENING) {
                fire(OverlayEvent.StopRequested)
            }
            WindowInsetsCompat.CONSUMED
        }

        // Swipe-Down auf dem Content-Panel → schließen
        // Außerhalb-Tippen wird direkt vom dimRoot-ClickListener in buildUI() übernommen.
        val swipeDetector = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onFling(
                    e1: MotionEvent?, e2: MotionEvent,
                    velocityX: Float, velocityY: Float,
                ): Boolean {
                    if (velocityY > 600 && (e2.y - (e1?.y ?: e2.y)) > 80) {
                        finish()
                        return true
                    }
                    return false
                }
            })
        root.setOnTouchListener { v, event ->
            swipeDetector.onTouchEvent(event)
            v.onTouchEvent(event)
            true
        }

        // Sofort zuhören (UX-04: keine 300-ms-Wartezeit mehr). Läuft noch ein Biometrie-Prompt,
        // beginnt das Zuhören erst nach dessen Ende (siehe authenticateWithBiometric).
        ui.post {
            if (!isDestroyed && !isFinishing && !biometricPending && machine.state == OverlayState.IDLE) {
                fire(OverlayEvent.MicRequested)
            }
        }
    }

    /**
     * Seitentaste (KEYCODE_ASSIST) loslassen: Halten ist KEINE Voraussetzung mehr — das Sprachende erkennt der
     * Spracherkenner selbst. Wer die Taste nach dem Sprechen loslässt, beschleunigt nur: „Fertig“, sofern
     * schon Sprache erkannt wurde und das Zuhören länger als [KEY_RELEASE_MIN_LISTEN_MS] läuft (ein kurzer Tipp
     * auf die Taste darf die Aufnahme nicht abwürgen).
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return when {
            event.keyCode == KeyEvent.KEYCODE_ASSIST &&
            event.action == KeyEvent.ACTION_UP -> {
                // DONNA-30: Biometrie noch ausstehend — nicht interagieren.
                // onAuthenticationSucceeded() setzt isAuthenticated; bis dahin ignorieren.
                if (!isAuthenticated) return super.dispatchKeyEvent(event)
                if (machine.state == OverlayState.LISTENING && heardSpeech &&
                    SystemClock.elapsedRealtime() - listenStartedAt > KEY_RELEASE_MIN_LISTEN_MS
                ) {
                    fire(OverlayEvent.SpeechEnded(userInitiated = true))
                }
                true
            }
            // Zurück-Taste: Overlay IMMER in einem Druck schließen (vorher brauchte es
            // je nach Fokus/Tastatur mehrere Drücke). Tastatur offen → erst Tastatur schließen.
            event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP -> {
                if (imeVisible) hideKeyboard()
                finish()
                true
            }
            // Volume-Tasten: NICHT abfangen → System regelt Lautstärke normal
            else -> super.dispatchKeyEvent(event)
        }
    }

    override fun onPause() {
        super.onPause()
        // finish() absichtlich NICHT hier — BiometricPrompt, Tastatur und andere
        // System-Dialoge rufen onPause() aus, würden die Activity sonst sofort töten.
        // Zuhören, Sprachausgabe und laufende Anfrage werden angehalten (Mikrofon/Ton nie im Hintergrund).
        if (machine.state != OverlayState.IDLE) {
            fire(OverlayEvent.Aborted)
        } else {
            cancelListening()
            stopSpeaking()
        }
    }

    override fun onResume() {
        super.onResume()
        // Nach fremdem onPause (Bildschirm aus, Anruf, Systemdialog): Anzeige konsistent halten.
        // Das Zuhören startet NICHT von selbst neu (Mikrofon nie ungefragt).
        refreshControls()
    }

    /**
     * Zweiter Seitentasten-Druck, während das Overlay schon offen ist (launchMode singleTask):
     * im Leerlauf/Fehler Zuhören starten, beim Denken/Sprechen unterbrechen.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (biometricPending || isDestroyed || isFinishing) return
        when (machine.state) {
            OverlayState.LISTENING -> Unit
            else -> { hideKeyboard(); fire(OverlayEvent.MicRequested) }
        }
    }

    /** Nur wenn der User bewusst wegnavigiert (Home-Button) — nicht bei System-Dialogen. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        stopBargeInDetector()
        // disconnect() kann das Netzwerk berühren → nie auf dem UI-Thread (NetworkOnMainThread/ANR)
        activeConn?.let { c -> Thread { try { c.disconnect() } catch (_: Exception) {} }.start() }
        activeConn = null
        speechRecognizer?.destroy()
        speechRecognizer = null
        tts?.shutdown()
        // WakeWordService nach Sprachinput wieder starten
        WakeWordService.start(this)
        mediaPlayer?.release()
        mediaPlayer = null
        scope.cancel()
    }

    // ── Window-Setup ──────────────────────────────────────────────────────────

    private fun setupWindow(overlayGranted: Boolean) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Kein FLAG_DIM_BEHIND — wir zeichnen den Dim-Layer (Scrim) selbst im Root-View.
        // Das Fenster ist durchscheinend (Theme: windowIsTranslucent=true), die App dahinter bleibt sichtbar.

        val params = window.attributes
        // TYPE_APPLICATION_OVERLAY (API 26+) → system-weites Overlay über allen Apps.
        // Ohne diesen Type erscheint das Fenster nur im eigenen App-Kontext und nicht
        // über fremden Apps (YouTube, Chrome etc.) wenn Donna via Seitentaste gerufen wird.
        // Für API < 26 nutzen wir TYPE_PHONE als Fallback (deprecated, aber funktional).
        // Nur mit erteilter SYSTEM_ALERT_WINDOW-Berechtigung: ohne sie wirft das WindowManager-
        // Attach eine BadTokenException ("permission denied for window type 2038").
        if (overlayGranted) {
            @Suppress("DEPRECATION")
            params.type = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
        }
        params.gravity = Gravity.BOTTOM or Gravity.FILL_HORIZONTAL
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        params.height = ViewGroup.LayoutParams.MATCH_PARENT  // Vollbild für Dim-Layer
        // DURCHSCHEINEND: Zusammen mit windowIsTranslucent=true im Theme blendet der ~28%-Scrim über die
        // App dahinter (Bixby/Gemini-Look) statt über Schwarz.
        params.format = android.graphics.PixelFormat.TRANSLUCENT
        window.attributes = params

        window.setBackgroundDrawableResource(android.R.color.transparent)
    }


    // ── UI-Aufbau (Pulse Design) ──────────────────────────────────────────────

    /**
     * Adaptives Panel: der FUSS (Statuszeile mit Stopp, Eingabe, Live-Transkript) behält immer seine Höhe, der
     * Körper ([body]: Verlauf, Karten, Frage, Antwort) bekommt den verbleibenden Platz und scrollt. So werden
     * „Stopp“ und Eingabe nie abgeschnitten — auch bei Tastatur + Karte + langer Antwort + großer Schrift.
     * (Ersetzt die festen Höhen-Bruchteile der Teilbereiche.)
     */
    private class AdaptivePanel(ctx: android.content.Context) : LinearLayout(ctx) {
        var body: View? = null
        /** Oben freizuhaltender Platz (Statusleiste + Abstand), damit das Panel nie unter die Statusleiste ragt. */
        var topReservePx = 0

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val b = body
            if (b == null || b.visibility == View.GONE) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                return
            }
            val parentH = View.MeasureSpec.getSize(heightMeasureSpec).takeIf { it > 0 }
                ?: resources.displayMetrics.heightPixels
            val avail = (parentH - topReservePx).coerceAtLeast(0)
            var fixed = paddingTop + paddingBottom
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (c === b || c.visibility == View.GONE) continue
                val lp = c.layoutParams as LayoutParams
                c.measure(
                    getChildMeasureSpec(widthMeasureSpec, paddingLeft + paddingRight + lp.leftMargin + lp.rightMargin, lp.width),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                fixed += c.measuredHeight + lp.topMargin + lp.bottomMargin
            }
            val bl = b.layoutParams as LayoutParams
            val bodyMax = (avail - fixed - bl.topMargin - bl.bottomMargin).coerceAtLeast(0)
            b.measure(
                getChildMeasureSpec(widthMeasureSpec, paddingLeft + paddingRight + bl.leftMargin + bl.rightMargin, bl.width),
                View.MeasureSpec.makeMeasureSpec(bodyMax, View.MeasureSpec.AT_MOST),
            )
            setMeasuredDimension(
                View.MeasureSpec.getSize(widthMeasureSpec),
                fixed + b.measuredHeight + bl.topMargin + bl.bottomMargin,
            )
        }
    }

    private fun roundedBg(fill: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            if (stroke != null) setStroke(1, stroke)
        }

    /**
     * Kleine Text-Taste (Fertig / Abbrechen / Stopp / Nochmal). Optisch schlank (~34 dp), das Touchziel
     * bleibt >= 48 dp: der Hintergrund ist oben/unten eingerückt.
     */
    private fun buildPill(label: String, primary: Boolean): TextView = TextView(this).apply {
        text = label
        setTextColor(if (primary) colorOnAccent else colorText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minHeight = dp(48)
        minWidth = dp(48)
        background = android.graphics.drawable.InsetDrawable(
            if (primary) roundedBg(colorAccent, 20) else roundedBg(colorSurface, 20, colorBorder),
            0, dp(7), 0, dp(7),
        )
        setPadding(dp(14), 0, dp(14), 0)
        isClickable = true
        isFocusable = true
    }

    /** Dezenter Chip (Gesprächsmodus, Verlauf): schlank sichtbar, Touchziel >= 48 dp. */
    private fun buildChip(): TextView = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        minHeight = dp(48)
        minWidth = dp(48)
        setPadding(dp(10), 0, dp(10), 0)
        isClickable = true
        isFocusable = true
    }

    /**
     * Schlanke Bodenleiste wie bei Gemini: Antwort/Karten nur wenn vorhanden, darunter eine kleine
     * Statuszeile mit Mini-Orb, darunter die Eingabekapsel (Mic · „Donna fragen…“ · Senden).
     * Keine Kopfzeile, kein Handle.
     */
    private fun buildUI(): View {
        val ctx = this

        // ── Vollbild-Wrapper mit dezentem Scrim (ca. 28 %, UX-02) ────────────
        // Das Fenster ist durchscheinend (windowIsTranslucent + TRANSLUCENT): der Scrim liegt auf der App
        // dahinter, nicht auf Schwarz.
        val dimRoot = android.widget.FrameLayout(ctx).apply {
            setBackgroundColor(colorScrim)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            // Tippen auf den Scrim (außerhalb der Leiste) → schließen
            setOnClickListener { finish() }
        }

        // ── Bodenleiste — abgerundet, schwebend, so niedrig wie möglich ──────
        adaptivePanel = AdaptivePanel(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedBg(colorCard, 28, colorBorder)
            topReservePx = dp(72)   // wird mit den echten Insets aktualisiert
            // Touches auf der Leiste NICHT an dimRoot weitergeben
            setOnClickListener { /* consume */ }
        }
        contentPanel = adaptivePanel

        // ── Körper: ein ScrollView für Verlauf, Karten, Frage und Antwort (nimmt den Restplatz) ──
        bodyContent = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        bodyScroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_NEVER
            // Hat der Nutzer selbst gescrollt, folgt die Anzeige dem Streaming nicht mehr
            setOnTouchListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_MOVE) userScrolledResponse = true
                false
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(bodyContent)
        }
        adaptivePanel.body = bodyScroll
        contentPanel.addView(bodyScroll)

        // ── Gesprächsverlauf-Panel (initial unsichtbar) ──
        historyPanel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(6) }
        }
        historyScroll = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(historyPanel)
        }
        bodyContent.addView(historyScroll)

        // ── Karten-Container (Wetter / Maps / Aktionen) — nur sichtbar, wenn Karten da sind ──
        cardContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(6) }
        }
        cardScroll = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(cardContainer)
        }
        bodyContent.addView(cardScroll)

        // ── Frage (klein, 1 Zeile) über der Antwort ──
        questionText = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(colorMuted)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.GONE
            setPadding(dp(4), 0, dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(4) }
        }
        bodyContent.addView(questionText)

        // ── Antwort (dezent, kompakt) — nur sichtbar, wenn Text da ist; scrollt mit dem Körper ──
        responseText = TextView(ctx).apply {
            text = ""
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(colorText)
            setTextIsSelectable(true)      // Long-Press → kopieren
            movementMethod = LinkMovementMethod.getInstance()  // Links anklickbar
            background = roundedBg(colorSurface, 16, colorBorder)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        responseScroll = FrameLayout(ctx).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(4) }
            addView(responseText)
        }
        bodyContent.addView(responseScroll)

        // ── Live-Transkript (nur beim Zuhören, 1–2 Zeilen) ──
        partialText = TextView(ctx).apply {
            text = ""
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(colorAccent2)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.GONE
            setPadding(dp(4), 0, dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(2) }
        }
        contentPanel.addView(partialText)

        // ── Statuszeile: [Mini-Orb] Status … [Aktion] [Verlauf] [Gespräch] ──
        val statusRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        orb = DonnaOrbView(ctx).apply {
            setPalette(colorAccent, colorAccent2, colorMuted, colorError)
            isClickable = true
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnClickListener { onPrimaryAction() }
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        }
        statusRow.addView(orb)

        // Statustext (TalkBack-Live-Region: Zustandswechsel werden angesagt)
        statusText = TextView(ctx).apply {
            text = "Sag etwas oder tippe."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(colorMuted)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .also { it.marginStart = dp(4); it.marginEnd = dp(4) }
        }
        statusRow.addView(statusText)

        // Aktions-Taste nur wenn relevant (Fertig / Abbrechen / Stopp / Nochmal)
        actionPill = buildPill("Stopp", primary = true).apply {
            visibility = View.GONE
            setOnClickListener { onPrimaryAction() }
        }
        setRole(actionPill, "android.widget.Button")
        statusRow.addView(actionPill)

        // Verlauf (kleiner Chip)
        val historyChip = buildChip().apply {
            text = "Verlauf"
            setTextColor(colorMuted)
            contentDescription = "Gesprächsverlauf öffnen"
            setOnClickListener { showHistoryDialog() }
        }
        setRole(historyChip, "android.widget.Button")
        statusRow.addView(historyChip)

        // Gesprächsmodus (kleiner Schalter-Chip)
        conversationChip = buildChip().apply {
            setOnClickListener { toggleConversationMode() }
            // Lange tippen: Unterbrechen per Stimme ein/aus
            setOnLongClickListener { toggleVoiceBargeIn(); true }
        }
        setRole(conversationChip, "android.widget.Switch") { machine.conversationMode }
        statusRow.addView(conversationChip)
        contentPanel.addView(statusRow)

        // ── Eingabekapsel: Mic | „Donna fragen…“ | Senden — das Hauptelement ──
        val inputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Mic-Button — Tippen startet/beendet das Zuhören (Halten ist nicht mehr nötig)
        micBg = GradientDrawable().apply {
            setColor(colorCard)
            cornerRadius = dp(24).toFloat()
            setStroke(1, colorBorder)
        }
        micButton = TextView(ctx).apply {
            text = "🎤"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            background = micBg
            contentDescription = "Mit Donna sprechen"
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).also {
                it.marginEnd = dp(8)
            }
            setOnClickListener { onMicTapped() }
        }
        setRole(micButton, "android.widget.Button")
        inputRow.addView(micButton)

        // Text-Eingabe
        inputField = EditText(ctx).apply {
            hint = "Donna fragen…"
            setHintTextColor(colorMuted)
            setTextColor(colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = roundedBg(colorSurface, 24, colorBorder)
            minHeight = dp(48)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            maxLines = 3
            // Enter sendet (Senden-Taste der Tastatur); Zeilenumbruch nicht nötig, der Text bricht um
            setRawInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
            setHorizontallyScrolling(false)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, event ->
                val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_SEND || enter) { onSendPressed(); true } else false
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) { updateKeepScreenOn() }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
            highlightColor = ColorUtils.setAlphaComponent(colorAccent, 80)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .also { it.marginEnd = dp(8) }
        }
        // Tippen = bewusste Absicht: laufendes Zuhören sofort beenden (Overlay bleibt offen)
        inputField.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN) {
                ui.removeCallbacks(closeRunnable)
                // Barge-in: wer zu tippen beginnt, will Donna nicht mehr zuhören (LISTENING) und auch nicht mehr
                // von ihr überlagert werden (SPEAKING) → Wiedergabe sofort stoppen. Beim Senden ersetzt die neue
                // Frage ohnehin eine noch laufende Anfrage (TextSubmitted → StopSpeaking + CancelRequest).
                if (machine.state == OverlayState.LISTENING || machine.state == OverlayState.SPEAKING) {
                    fire(OverlayEvent.StopRequested)
                }
            }
            false
        }
        inputRow.addView(inputField)

        // Senden-Button
        sendButton = TextView(ctx).apply {
            text = "➤"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(colorOnAccent)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = roundedBg(colorAccent, 24)
            contentDescription = "Senden"
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { onSendPressed() }
        }
        setRole(sendButton, "android.widget.Button")
        inputRow.addView(sendButton)

        contentPanel.addView(inputRow)
        refreshConversationChip()

        // Leiste unten im dimRoot verankern (schwebend, mit Abstand zum Rand)
        val panelParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ).also { it.setMargins(dp(8), 0, dp(8), dp(8)) }
        dimRoot.addView(contentPanel, panelParams)
        return dimRoot
    }

    // ── Zustand → Darstellung ────────────────────────────────────────────────

    /** TalkBack-Rolle (Button/Switch) für TextViews, die als Bedienelemente dienen. */
    private fun setRole(v: View, className: String, checked: (() -> Boolean)? = null) {
        ViewCompat.setAccessibilityDelegate(v, object : androidx.core.view.AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View,
                info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat,
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = className
                if (checked != null) {
                    info.isCheckable = true
                    info.isChecked = checked()
                }
            }
        })
    }

    /**
     * Statuszeile setzen. [live] = false für „Ich höre zu“: TalkBack soll genau beim Mikrofon-Start nicht
     * sprechen (sonst nimmt der Erkenner die Ansage als Sprache auf).
     */
    private fun setStatus(text: String, color: Int, live: Boolean = true) {
        statusText.accessibilityLiveRegion =
            if (live) View.ACCESSIBILITY_LIVE_REGION_POLITE else View.ACCESSIBILITY_LIVE_REGION_NONE
        statusText.text = text
        statusText.setTextColor(color)
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(inputField.windowToken, 0)
        inputField.clearFocus()
    }

    /** Tastatur offen oder Text im Eingabefeld → der Nutzer tippt (kein Auto-Zuhören, kein Schließen). */
    private fun typingActive(): Boolean = imeVisible || inputField.text.isNotBlank()

    /** Orb, Statustext, Tasten und Mikrofon-Symbol passend zum Zustand setzen. */
    private fun renderState(s: OverlayState, override: String?) {
        orb.setState(s)
        when (s) {
            OverlayState.IDLE -> setStatus(
                override ?: if (machine.hasConversation) "Tippe aufs Mikrofon für die nächste Frage." else "Sag etwas oder tippe.",
                colorMuted,
            )
            // Keine TalkBack-Ansage genau beim Mikrofon-Start (die Ansage würde sonst mit aufgenommen)
            OverlayState.LISTENING -> setStatus("Ich höre zu…", colorAccent2, live = false)
            OverlayState.THINKING -> setStatus("Donna denkt nach…", colorMuted)
            OverlayState.SPEAKING -> setStatus("Donna antwortet…", colorAccent2)
            OverlayState.ERROR -> setStatus(override ?: "Das hat nicht geklappt. Nochmal versuchen?", colorError)
        }
        if (s != OverlayState.LISTENING && s != OverlayState.THINKING) partialText.visibility = View.GONE
        refreshControls()
    }

    /** Tasten-Beschriftungen abhängig von Zustand (und davon, ob schon Sprache erkannt wurde). */
    private fun refreshControls() {
        val s = machine.state
        val listening = s == OverlayState.LISTENING
        micButton.text = if (listening) "⏹" else "🎤"
        micButton.contentDescription = if (listening) "Zuhören beenden" else "Mit Donna sprechen"
        micBg?.setColor(if (listening) colorMicActive else colorCard)
        micBg?.setStroke(if (listening) dp(1) else 1, if (listening) colorAccent else colorBorder)

        val (label, desc) = when (s) {
            OverlayState.IDLE -> "" to "Mit Donna sprechen"
            OverlayState.LISTENING ->
                if (heardSpeech) "Fertig" to "Fertig, Antwort holen" else "Abbrechen" to "Zuhören abbrechen"
            OverlayState.THINKING -> "Abbrechen" to "Antwort abbrechen"
            OverlayState.SPEAKING -> "Stopp" to "Sprachausgabe stoppen"
            OverlayState.ERROR -> when {
                lastError?.kind == UserError.Kind.MIC_PERMISSION -> "Einstellungen" to "Mikrofon-Berechtigung in den Einstellungen erteilen"
                lastError?.kind == UserError.Kind.AUTH -> "Donna öffnen" to "Donna-App öffnen und Kopplung prüfen"
                else -> "Nochmal" to "Nochmal versuchen"
            }
        }
        actionPill.text = label
        actionPill.visibility = if (label.isEmpty()) View.GONE else View.VISIBLE
        actionPill.contentDescription = desc
        orb.contentDescription = desc
    }

    private fun refreshConversationChip() {
        val on = machine.conversationMode
        conversationChip.text = if (on) "Gespräch an" else "Gespräch aus"
        conversationChip.setTextColor(if (on) colorOnAccent else colorMuted)
        conversationChip.background = android.graphics.drawable.InsetDrawable(
            if (on) roundedBg(colorAccent, 20) else roundedBg(colorSurface, 20, colorBorder),
            0, dp(8), 0, dp(8),
        )
        conversationChip.contentDescription =
            if (on) "Gesprächsmodus ist an. Donna hört nach jeder Antwort automatisch wieder zu. Zum Ausschalten tippen, lange tippen schaltet das Unterbrechen per Stimme um."
            else "Gesprächsmodus ist aus. Zum Einschalten tippen."
    }

    // ── Voice-Barge-in (Donna per STIMME unterbrechen) ───────────────────────

    /**
     * Gesprächsmodus an + Voice-Barge-in nicht abgeschaltet. `disabledForProcess` ist der MANUELLE Aus-Schalter
     * (Chip-Langdruck / RN-Bridge). `echoStrikes.isPaused()` ist die TEMPORÄRE, selbst-erholende Echo-Pause
     * (A3) — kein Dauer-Aus mehr nach wenigen Fehlauslösern.
     */
    private fun voiceBargeInEnabled(): Boolean =
        machine.conversationMode && prefs.getBoolean(PREF_VOICE_BARGE_IN, VOICE_BARGE_IN_DEFAULT) &&
            !BargeInDetector.disabledForProcess && !BargeInDetector.echoStrikes.isPaused() && machine.canVoiceBargeIn

    /** Ton laeuft (erster Satz): Mikrofon-Lauschen starten. Nur im Zustand SPEAKING. */
    private fun onTtsAudioStarted() {
        if (isDestroyed || isFinishing) return
        if (machine.state != OverlayState.SPEAKING || !voiceBargeInEnabled() || bargeInDetector.isRunning) return
        // false → kein Echo-Unterdruecker/keine Berechtigung: Fallback = Mikrofon erst nach TTS-Ende
        if (bargeInDetector.start()) showBargeInHintOnce()
    }

    /** Erst-Hinweis (einmalig, gemerkt): Unterbrechen per Stimme ist jetzt standardmaessig an. */
    private fun showBargeInHintOnce() {
        if (prefs.getBoolean(PREF_BARGE_IN_HINT_SHOWN, false)) return
        prefs.edit().putBoolean(PREF_BARGE_IN_HINT_SHOWN, true).apply()
        Toast.makeText(
            this,
            "Du kannst Donna jederzeit unterbrechen — einfach reinreden. Ausschalten: lange auf „Gespräch“ tippen.",
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun stopBargeInDetector() {
        if (bargeInDetector.isRunning) bargeInDetector.stop()
    }

    /** Mikes Stimme ueber Donnas Ausgabe erkannt: Ausgabe sofort stoppen, neue Aeusserung als neuen Turn hoeren. */
    private fun onVoiceBargeIn() {
        if (isDestroyed || isFinishing || machine.state != OverlayState.SPEAKING) return
        hideKeyboard()
        // Eigenes Ereignis (KEINE Beruehrung): zaehlt zum Freisprech-Limit und zu max. 3 Barge-ins pro Beruehrung
        fire(OverlayEvent.VoiceBargeIn)
        if (machine.state == OverlayState.LISTENING) {
            bargeInSession = true
            echoFilterArmed = true
        }
    }

    /**
     * Ende einer Barge-in-Sitzung. Ohne echte Äußerung (Stille, Fehler, Echo) zählt ein Strike. Bei zu vielen
     * Fehlauslösern in Folge wird Voice-Barge-in NICHT mehr dauerhaft abgeschaltet, sondern nur kurz pausiert
     * (A3: [EchoStrikes] mit Cooldown + selbst-erholend). Echte Sprache setzt die Strikes zurück.
     */
    private fun endBargeInSession(genuine: Boolean) {
        if (!bargeInSession) return
        bargeInSession = false
        if (genuine) {
            BargeInDetector.echoStrikes.reset()
        } else if (BargeInDetector.echoStrikes.record()) {
            // Kein Dauer-Aus: nur temporäre Pause (echoStrikes.isPaused() gated das Lauschen), danach wieder scharf.
            android.util.Log.w("VoiceInputActivity", "Voice-Barge-in nach Fehlausloesern kurz pausiert (Cooldown)")
            Toast.makeText(this, "Unterbrechen kurz pausiert (Echo erkannt)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleVoiceBargeIn() {
        val now = !prefs.getBoolean(PREF_VOICE_BARGE_IN, VOICE_BARGE_IN_DEFAULT)
        prefs.edit().putBoolean(PREF_VOICE_BARGE_IN, now).apply()
        if (now) {
            // Wieder-Einschalten: frischer Start (Strikes und Abschaltung zurücksetzen)
            BargeInDetector.disabledForProcess = false
            BargeInDetector.echoStrikes.reset()
        }
        if (!now) stopBargeInDetector()
        Toast.makeText(
            this,
            if (now) "Unterbrechen per Stimme: an" else "Unterbrechen per Stimme: aus",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun toggleConversationMode() {
        machine.conversationMode = !machine.conversationMode
        prefs.edit().putBoolean(PREF_CONVERSATION_MODE, machine.conversationMode).apply()
        refreshConversationChip()
    }

    // ── Bedienung ────────────────────────────────────────────────────────────

    /** Orb und Aktions-Taste: je nach Zustand starten, fertig melden, stoppen oder den Fehler beheben. */
    private fun onPrimaryAction() {
        when (machine.state) {
            OverlayState.IDLE -> { hideKeyboard(); fire(OverlayEvent.MicRequested) }
            OverlayState.ERROR -> when {
                lastError?.kind == UserError.Kind.MIC_PERMISSION -> openAppSettings()
                lastError?.kind == UserError.Kind.AUTH -> openDonnaApp()
                pendingRetry != null -> retryLast()
                else -> { hideKeyboard(); fire(OverlayEvent.MicRequested) }
            }
            OverlayState.LISTENING ->
                if (heardSpeech) fire(OverlayEvent.SpeechEnded(userInitiated = true)) else fire(OverlayEvent.StopRequested)
            OverlayState.THINKING, OverlayState.SPEAKING -> fire(OverlayEvent.StopRequested)
        }
    }

    private fun onMicTapped() {
        hideKeyboard()
        if (machine.state == OverlayState.LISTENING) onPrimaryAction() else fire(OverlayEvent.MicRequested)
    }

    /** „Nochmal“ nach Server-/Netzfehler: dieselbe Frage erneut senden (Erkennerfehler → weiter Mikrofon). */
    private fun retryLast() {
        val (message, mode) = pendingRetry ?: return
        pendingRetry = null
        inputField.setText("")
        sendToBackend(message, mode)
    }

    private fun openAppSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            Toast.makeText(this, "Einstellungen konnten nicht geöffnet werden", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openDonnaApp() {
        try {
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(it)
            }
        } catch (_: Exception) {
            Toast.makeText(this, "Donna konnte nicht geöffnet werden", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Zustandsautomat anwenden ─────────────────────────────────────────────

    /**
     * Ereignis in den Automaten geben: erst Darstellung, dann Effekte (damit ein Effekt, der selbst einen
     * Fehler meldet, die Anzeige nicht mit dem alten Zustand überschreibt).
     */
    private fun fire(event: OverlayEvent, statusOverride: String? = null) {
        // Jedes neue Ereignis macht ausstehende Zeitgeber (verzögertes Schließen, Auto-Relisten) hinfällig
        ui.removeCallbacks(closeRunnable)
        ui.removeCallbacks(autoListenRunnable)
        ui.removeCallbacks(recognizerWatchdog)
        if (event is OverlayEvent.MicRequested) {
            // Manuell (Tippen, Seitentaste, Start): Mike spricht garantiert selbst → kein Echo-Abgleich
            echoFilterArmed = false
            lastSpokenText = ""
            bargeInSession = false
        }
        val t = machine.dispatch(event)
        // Leerlauf nach einem Gespräch: nach ~60 s ohne Aktivität schließen (Akku); sonst Bildschirm nicht wachhalten
        ui.removeCallbacks(idleCloseRunnable)
        if (t.state == OverlayState.IDLE && machine.hasConversation) ui.postDelayed(idleCloseRunnable, IDLE_CLOSE_MS)
        updateKeepScreenOn(t.state)
        // Das Mikrofon-Lauschen fuer Voice-Barge-in laeuft nur, solange Donna spricht
        if (t.state != OverlayState.SPEAKING) stopBargeInDetector()
        renderState(t.state, statusOverride)
        // Wartet der Erkenner nach dem Sprachende auf sein Ergebnis (noch keine Anfrage), läuft ein Watchdog
        if (t.state == OverlayState.THINKING && !requestActive) {
            ui.postDelayed(recognizerWatchdog, RECOGNIZER_WATCHDOG_MS)
        }
        t.effects.forEach { applyEffect(it) }
    }

    /**
     * Fehler melden. [retry] = (Nachricht, Eingabemodus) der fehlgeschlagenen Anfrage → „Nochmal“ sendet
     * dieselbe Frage erneut; getippter Text kehrt ins Eingabefeld zurück.
     */
    private fun fail(error: UserError, retry: Pair<String, String>? = null) {
        endBargeInSession(genuine = false)
        lastError = error
        pendingRetry = retry
        if (retry != null && retry.second == INPUT_MODE_TEXT && inputField.text.isBlank()) {
            inputField.setText(retry.first)
        }
        fire(OverlayEvent.Failed(error), error.message)
    }

    private fun applyEffect(effect: OverlayEffect) {
        when (effect) {
            OverlayEffect.StartListening -> { listenRetries = 0; resetHeardSpeech(); requestMicAndListen() }
            OverlayEffect.StartListeningAfterEcho -> {
                // Im Echo-Fenster darf die Taste nicht noch „Fertig“ vom letzten Zuhören zeigen
                resetHeardSpeech()
                ui.postDelayed(autoListenRunnable, ECHO_GUARD_MS)
            }
            OverlayEffect.FinishListening -> {
                ui.removeCallbacks(noSpeechRunnable)
                ui.removeCallbacks(maxListenRunnable)
                try { speechRecognizer?.stopListening() } catch (_: Exception) {}
            }
            OverlayEffect.CancelListening -> cancelListening()
            OverlayEffect.StopSpeaking -> stopSpeaking()
            OverlayEffect.CancelRequest -> cancelRequest()
            OverlayEffect.CloseOverlayDelayed -> ui.postDelayed(closeRunnable, CLOSE_AFTER_NOTHING_HEARD_MS)
        }
    }

    private fun resetHeardSpeech() {
        heardSpeech = false
        refreshControls()
    }

    /** Bildschirm nur wachhalten, solange etwas läuft (Zuhören/Denken/Sprechen/Fehler) oder getippt wird. */
    private fun updateKeepScreenOn(state: OverlayState = machine.state) {
        if (state != OverlayState.IDLE || typingActive()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** Lange Ruhe im Leerlauf: Overlay schließen, außer es wird getippt, der Verlauf ist offen oder Karten warten. */
    private fun onIdleCloseDue() {
        if (isDestroyed || isFinishing || machine.state != OverlayState.IDLE) return
        if (typingActive() || historyPanel.visibility == View.VISIBLE || hasPendingCards()) {
            ui.postDelayed(idleCloseRunnable, IDLE_CLOSE_MS)   // später erneut prüfen
        } else {
            finish()
        }
    }

    /** Auto-Relisten nach dem Echo-Schutz: nur, wenn der Nutzer nicht gerade tippt. */
    private fun onAutoListenDue() {
        if (isDestroyed || isFinishing || machine.state != OverlayState.LISTENING) return
        if (typingActive()) {
            fire(OverlayEvent.StopRequested)
        } else {
            listenRetries = 0
            echoFilterArmed = true   // automatisch geöffnet: Donnas Ausklang könnte als Sprache erkannt werden
            requestMicAndListen()
        }
    }

    /** „Ich habe nichts gehört“ wurde gezeigt; rührt sich nichts, schließt das Overlay leise. */
    private fun onCloseTimer() {
        if (machine.state == OverlayState.IDLE && !typingActive() && historyPanel.visibility != View.VISIBLE) finish()
    }

    /** Zuhören läuft zu lange am Stück. */
    private fun onMaxListenReached() {
        if (machine.state != OverlayState.LISTENING) return
        if (heardSpeech) fire(OverlayEvent.SpeechEnded(userInitiated = true)) else fireSilence()
    }

    /** Erkenner meldet sich nach dem Sprachende nicht mehr. */
    private fun onRecognizerStuck() {
        if (machine.state == OverlayState.THINKING && !requestActive) {
            cancelListening()
            fail(UserError(UserError.Kind.UNKNOWN, "Die Spracherkennung hat nicht geantwortet. Probier es nochmal oder tippe."))
        }
    }

    private fun cancelListening() {
        listenGeneration++   // spätere Callbacks des alten Erkenners (Ergebnis/Fehler) werden verworfen
        ui.removeCallbacks(noSpeechRunnable)
        ui.removeCallbacks(maxListenRunnable)
        isListening = false
        heardSpeech = false
        partialText.visibility = View.GONE
        orb.setLevel(0f)
        try { speechRecognizer?.cancel() } catch (_: Exception) {}
    }

    private fun stopSpeaking() {
        turn?.pipeline?.stop()
        try { tts?.stop() } catch (_: Exception) {}
    }

    private fun cancelRequest() {
        requestActive = false
        requestJob?.cancel()
        requestJob = null
        turn?.let {
            ui.removeCallbacks(it.renderRunnable)
            it.pipeline.stop()
        }
        turn = null
        // Bereits sichtbare Karten sollen nach dem Abbruch bedienbar bleiben
        armCardsLater()
        // disconnect() kann blockieren/Netzwerk berühren → nicht auf dem UI-Thread
        activeConn?.let { conn -> scope.launch(Dispatchers.IO) { try { conn.disconnect() } catch (_: Exception) {} } }
        activeConn = null
    }

    /** Karten-Buttons kurz nach Streaming-Ende scharf schalten (Fehltipp-Schutz). */
    private fun armCardsLater() {
        ui.removeCallbacks(armCardsRunnable)
        ui.postDelayed(armCardsRunnable, ARM_DELAY_MS)
    }

    private fun armActionButtons() {
        fun walk(v: View) {
            if (v.tag == ARM_TAG) { v.isEnabled = true; v.alpha = 1f }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(cardContainer)
    }

    // ── Spracherkennung ───────────────────────────────────────────────────────

    private fun requestMicAndListen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                MIC_PERMISSION_RC,
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != MIC_PERMISSION_RC) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // Der Berechtigungs-Dialog hat onPause ausgelöst (→ Leerlauf) — jetzt wirklich zuhören
            when (machine.state) {
                OverlayState.LISTENING -> startListening()
                OverlayState.IDLE, OverlayState.ERROR -> fire(OverlayEvent.MicRequested)
                else -> Unit
            }
        } else {
            fail(OverlayErrors.micPermissionDenied())
        }
    }

    /**
     * Erstellt den RecognitionListener. Jeder Listener gehört zu einer „Generation“: wird das Zuhören
     * abgebrochen oder neu gestartet (Tippen, Stopp, Retry), verfallen alle späteren Callbacks des alten
     * Erkenners — ein spätes Ergebnis darf weder eine getippte Anfrage ersetzen noch eine frische abbrechen.
     */
    private fun buildRecognitionListener(): RecognitionListener {
        val gen = listenGeneration
        fun stale() = gen != listenGeneration
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (stale()) return
                isListening = true
                listenStartedAt = SystemClock.elapsedRealtime()
                refreshControls()
            }
            override fun onBeginningOfSpeech() {
                if (stale()) return
                heardSpeech = true
                ui.removeCallbacks(noSpeechRunnable)
                refreshControls()
            }
            override fun onRmsChanged(rmsdB: Float) {
                if (stale()) return
                if (machine.state == OverlayState.LISTENING) orb.setLevel(OrbMath.levelFromRms(rmsdB))
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                if (stale()) return
                // Sprachende automatisch erkannt (UX-04) → Denken; die Ergebnisse folgen in onResults
                isListening = false
                ui.removeCallbacks(noSpeechRunnable)
                ui.removeCallbacks(maxListenRunnable)
                orb.setLevel(0f)
                fire(OverlayEvent.SpeechEnded(userInitiated = false))
            }
            override fun onPartialResults(partialResults: Bundle?) {
                if (stale()) return
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: return
                if (partial.isNotBlank()) {
                    if (!heardSpeech) { heardSpeech = true; refreshControls() }
                    ui.removeCallbacks(noSpeechRunnable)
                }
                partialText.text = partial
                partialText.visibility = if (partial.isNotBlank()) View.VISIBLE else View.GONE
            }
            override fun onResults(results: Bundle?) {
                if (stale()) return
                // Ergebnis verbraucht: ein zweites onResults/onError desselben Erkenners wird verworfen
                listenGeneration++
                isListening = false
                ui.removeCallbacks(noSpeechRunnable)
                ui.removeCallbacks(maxListenRunnable)
                partialText.visibility = View.GONE
                // Manche Erkenner liefern das Ergebnis ohne vorheriges onEndOfSpeech → Sprachende nachholen
                if (machine.state == OverlayState.LISTENING) fire(OverlayEvent.SpeechEnded(userInitiated = false))
                // Veraltetes Ergebnis (Nutzer hat inzwischen abgebrochen/getippt) ignorieren
                if (machine.state != OverlayState.THINKING) return
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (text.isNullOrBlank()) {
                    fail(OverlayErrors.fromRecognizer(OverlayErrors.ERROR_NO_MATCH, heardSpeech = true))
                } else if (echoFilterArmed && SpokenTextMatcher.isEcho(text, lastSpokenText, bargeIn = bargeInSession)) {
                    // Nur Donnas eigene Stimme gehört (Echo): NICHT senden — sonst redet Donna mit sich selbst
                    // (und im Gesprächsmodus entstünde eine Endlosschleife auf API-Kosten). Der Filter gilt nur
                    // bei Auto-Relisten/Barge-in; bei manuellem Mic-Tap ist echoFilterArmed=false.
                    echoFilterArmed = false
                    endBargeInSession(genuine = false)   // mehrere solche Fehlauslöser → nur temporäre Pause (A3)
                    fire(
                        OverlayEvent.StopRequested,
                        "Ich habe nur mich selbst gehört. Tippe aufs Mikrofon, wenn du etwas sagen willst.",
                    )
                } else {
                    // Echte Äußerung von Mike → Freisprech-Schleife läuft weiter (Echo-Zähler zurück; das harte
                    // Freisprech-Limit bleibt bestehen)
                    echoFilterArmed = false
                    endBargeInSession(genuine = true)
                    sendToBackend(text, INPUT_MODE_VOICE, genuine = true)
                }
            }
            override fun onError(error: Int) {
                if (stale()) return
                isListening = false
                ui.removeCallbacks(noSpeechRunnable)
                ui.removeCallbacks(maxListenRunnable)
                partialText.visibility = View.GONE
                val state = machine.state
                if (state != OverlayState.LISTENING && state != OverlayState.THINKING) return // veraltet (z. B. nach Abbruch)

                // Einmaliger stiller Neustart (Samsung ERROR_SERVER_DISCONNECTED, belegtes Mikrofon …)
                if (state == OverlayState.LISTENING && OverlayErrors.isSilentlyRetryable(error) &&
                    listenRetries < 1 && !isDestroyed && !isFinishing
                ) {
                    listenRetries++
                    val delay = if (error == OverlayErrors.ERROR_SERVER_DISCONNECTED) 0L else 300L
                    ui.postDelayed({
                        if (!isDestroyed && !isFinishing && machine.state == OverlayState.LISTENING) {
                            startListening()
                        }
                    }, delay)
                    return
                }
                if (state == OverlayState.LISTENING && OverlayErrors.isSilence(error, heardSpeech)) {
                    fireSilence()
                } else {
                    fail(OverlayErrors.fromRecognizer(error, heardSpeech))
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    /**
     * Stille: Tippt der Nutzer gerade, bleibt das Overlay offen und hört nur auf; sonst „Ich habe nichts gehört“
     * (beim ersten Mal schließt das Overlay danach leise).
     */
    private fun fireSilence() {
        endBargeInSession(genuine = false)   // Barge-in ohne anschliessende Aeusserung = Fehlausloeser
        if (typingActive()) {
            fire(OverlayEvent.StopRequested)
        } else {
            fire(OverlayEvent.NoSpeechTimeout, "Ich habe nichts gehört. Tippe aufs Mikrofon oder schreib mir.")
        }
    }

    private fun onNoSpeechTimeout() {
        if (machine.state == OverlayState.LISTENING && !heardSpeech) fireSilence()
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            fail(OverlayErrors.recognizerUnavailable())
            return
        }
        heardSpeech = false
        val silence = SilenceProfile.fromKey(prefs.getString(PREF_SILENCE_PROFILE, null))
        speechRecognizer?.destroy()
        listenGeneration++
        // Zeitgeber laufen ab HIER (nicht erst ab onReadyForSpeech): bleibt der Erkenner stumm, hängt nichts fest
        ui.removeCallbacks(noSpeechRunnable)
        ui.removeCallbacks(maxListenRunnable)
        ui.postDelayed(noSpeechRunnable, OverlayTimings.NO_SPEECH_TIMEOUT_MS)
        ui.postDelayed(maxListenRunnable, MAX_LISTEN_MS)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(buildRecognitionListener())
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Großzügige Sprechpause (Denkpausen sind ok); vom Erkenner ggf. nur als Hinweis gewertet
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silence.completeMs)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silence.possiblyCompleteMs)
            }
            // Raum-/Gerätenamen bevorzugen (API 33+), gemeinsame Liste mit dem Haupt-Chat
            SttBias.applyTo(intent)
            try {
                startListening(intent)
            } catch (e: Exception) {
                // Erkenner liess sich nicht starten (z. B. Dienst nicht gebunden): aufräumen, lesbarer Fehler
                android.util.Log.w("VoiceInputActivity", "startListening fehlgeschlagen: ${e.message}")
                try { destroy() } catch (_: Exception) {}
                ui.removeCallbacks(noSpeechRunnable)
                ui.removeCallbacks(maxListenRunnable)
                fail(OverlayErrors.fromRecognizer(OverlayErrors.ERROR_CLIENT, heardSpeech = false))
            }
        }
    }

    // ── Nachricht senden ──────────────────────────────────────────────────────

    private fun onSendPressed() {
        val text = inputField.text.toString().trim()
        if (text.isBlank()) return
        inputField.setText("")
        // Tastatur schließen: Orb und Stopp-Taste sollen während der Antwort sichtbar sein
        hideKeyboard()
        sendToBackend(text, INPUT_MODE_TEXT)
    }

    /** Letzten bekannten Standort holen (best-effort, kein Timeout-Warten). */
    private fun getLastLocation(): Location? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        val lm = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    // ── Native Wetterkarte ────────────────────────────────────────────────────
    private fun buildWeatherCard(d: JSONObject): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(colorSurface)
                cornerRadius = dp(14).toFloat()
                setStroke(1, colorBorder)
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Standort
        card.addView(TextView(this).apply {
            text = d.safeString("location", "")
            setTextColor(colorMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(4) }
        })

        // Temperatur + Icon
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(4) }
        }
        topRow.addView(TextView(this).apply {
            text = "${JsonSafe.numText(d, "temp_c")}°"
            setTextColor(colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 52f)
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        topRow.addView(TextView(this).apply {
            text = d.safeString("condition_icon", "🌡️")
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
        })
        card.addView(topRow)

        // Zustand
        card.addView(TextView(this).apply {
            text = d.safeString("condition", "")
            setTextColor(colorAccent2)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(10) }
        })

        // Trennlinie
        card.addView(View(this).apply {
            background = GradientDrawable().apply { setColor(colorBorder) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1
            ).also { it.bottomMargin = dp(10) }
        })

        // Details-Reihe
        val detailsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        listOf(
            "↓${JsonSafe.numText(d, "temp_min")}° ↑${JsonSafe.numText(d, "temp_max")}°",
            "💧 ${JsonSafe.numText(d, "humidity")}%",
            "💨 ${JsonSafe.numText(d, "wind_kmh")} km/h",
            "Gefühlt ${JsonSafe.numText(d, "feels_like_c")}°",
        ).forEach { detail ->
            detailsRow.addView(TextView(this).apply {
                text = detail
                setTextColor(colorMuted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        card.addView(detailsRow)
        return card
    }

    // ── Maps-Karte ────────────────────────────────────────────────────────────
    private fun buildMapCard(mapsUrl: String): View {
        // Nur erlaubte Schemata (https/geo/google.navigation); den Ziel-Host zeigen wir VOR dem Öffnen
        val checked = UrlPolicy.check(mapsUrl)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(colorSurface)
                cornerRadius = dp(14).toFloat()
                setStroke(1, colorBorder)
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { openCheckedUrl(checked) }
            addView(TextView(this@VoiceInputActivity).apply {
                text = "🗺️"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.marginEnd = dp(12) }
            })
            addView(LinearLayout(this@VoiceInputActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@VoiceInputActivity).apply {
                    text = "In Google Maps öffnen"
                    setTextColor(colorAccent)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                })
                addView(TextView(this@VoiceInputActivity).apply {
                    text = if (checked != null) "Ziel: ${checked.displayTarget()}" else "Link nicht erlaubt"
                    setTextColor(colorMuted)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                })
            })
            addView(TextView(this@VoiceInputActivity).apply {
                text = "›"
                setTextColor(colorAccent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            })
        }
    }

    // ── Tesla-Navigation + Geocoding-Rueckfragen ────────────────────────────

    /** Sendeziel einer (tesla_)navigate-Action: nav_address > resolved_address > destination > query (wie der Haupt-Chat). */
    private fun teslaTargetOf(action: JSONObject): String = NavActionLogic.targetOfAction(action)

    /** Ziel lautlos an die Tesla-App senden (Intent siehe [TeslaNav]) und das Ergebnis kurz anzeigen. */
    private fun sendToTesla(target: String) {
        val msg = when (TeslaNav.send(this, target)) {
            // Intent-Erfolg = an die Tesla-App uebergeben (ob das Auto es uebernimmt, wissen wir nicht)
            TeslaNav.Result.SENT -> "An Tesla-App übergeben"
            TeslaNav.Result.NOT_INSTALLED -> "Tesla-App nicht installiert"
            TeslaNav.Result.EMPTY -> "Kein Ziel angegeben"
            TeslaNav.Result.FAILED -> "Senden an Tesla fehlgeschlagen"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** Meldung zu einer fehlgeschlagenen Aktion — freundlich statt Absturz. */
    private fun startFailureMessage(e: Exception): String = when (e) {
        is ActivityNotFoundException -> "Dafür ist keine passende App installiert."
        is SecurityException -> "Diese Aktion ist nicht erlaubt."
        else -> "App konnte nicht geöffnet werden"
    }

    /** startActivity mit freundlicher Fehlermeldung (ActivityNotFound/Security/sonstiges). */
    private fun safeStart(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: Exception) {
        toast(startFailureMessage(e))
        false
    }

    /** Geprüfte URL öffnen; fremde Schemata werden abgelehnt. */
    private fun openCheckedUrl(checked: UrlPolicy.Checked?) {
        if (checked == null) {
            toast("Dieser Link wird aus Sicherheitsgründen nicht geöffnet.")
            return
        }
        safeStart(Intent(Intent.ACTION_VIEW, Uri.parse(checked.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Handy-Navigation zu einem gewaehlten Treffer (Koordinaten, wenn vorhanden). */
    private fun startMapsNavigation(dest: String, lat: Double?, lon: Double?) {
        val q = if (lat != null && lon != null) "$lat,$lon" else android.net.Uri.encode(dest)
        val uri = android.net.Uri.parse("google.navigation:q=$q&mode=d")
        safeStart(Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.google.android.apps.maps") })
    }

    // ── Streaming-Antwort (UX-03) ─────────────────────────────────────────────

    /** Zustand EINER Antwort: Rohtext, Satz-Zerleger, Sprach-Pipeline, Karten. */
    private inner class Turn(val message: String, val inputMode: String) {
        val raw = StringBuilder()
        var chunker = SentenceChunker()
        var sawDone = false
        var started = false
        var streamEnded = false
        var infoCardShown = false
        var sawAction = false
        var truncated = false
        var renderScheduled = false
        val renderRunnable: Runnable = Runnable {
            renderScheduled = false
            if (turn === this@Turn) renderResponse(this@Turn)
        }
        fun newPipeline() = SentenceSpeechPipeline(
            scope = scope,
            synthesize = { sentenceSynth.synthesize(it) },
            play = { playAudio(it) },
            fallbackSpeak = { speakSystem(it) },
            onFinished = { spoke -> onSpeechFinished(this@Turn, spoke) },
        )
        // var: bei einer Live-Guard-Korrektur wird die Sprachausgabe mit dem bereinigten Text neu aufgesetzt
        var pipeline: SentenceSpeechPipeline = newPipeline()
    }

    private sealed class StreamMsg {
        class Event(val event: SseEvent) : StreamMsg()
        class Failure(val error: UserError) : StreamMsg()
        object End : StreamMsg()
    }

    private fun sendToBackend(message: String, inputMode: String, genuine: Boolean = false) {
        // Zustand zuerst: räumt eine alte Anfrage/Sprachausgabe ab (Unterbrechen) und zeigt „Denkt nach“.
        // Ein Erkennungsergebnis beendet den Erkenner selbst; getippter Text verwirft einen laufenden.
        fire(OverlayEvent.TextSubmitted(fromRecognizer = inputMode == INPUT_MODE_VOICE, genuineSpeech = genuine))
        // Neue Frage: Echo-Abgleich und Barge-in-Sitzung der alten Antwort zuruecksetzen
        lastSpokenText = ""
        bargeInSession = false

        cardContainer.removeAllViews()
        cardContainer.visibility = View.GONE
        responseText.text = ""
        responseScroll.visibility = View.GONE
        userScrolledResponse = false
        lastError = null
        pendingRetry = null
        // Die Frage bleibt dezent über der Antwort sichtbar
        questionText.text = message
        questionText.visibility = View.VISIBLE

        val myTurn = Turn(message, inputMode)
        turn = myTurn
        requestActive = true
        // Ein Sprach-Submit beendet den Erkenner: ein spätes onError des alten Erkenners darf die frische
        // Anfrage nicht abbrechen
        if (inputMode == INPUT_MODE_VOICE) listenGeneration++
        ui.removeCallbacks(recognizerWatchdog)
        lastRenderAt = 0L

        val location = getLastLocation()
        val token = TokenStore.getToken(this) ?: ""
        val sessionId = currentSessionId
        val authed = isAuthenticated
        val messages = Channel<StreamMsg>(Channel.UNLIMITED)

        requestJob = scope.launch {
            launch(Dispatchers.IO) { readStream(messages, message, inputMode, location, token, sessionId, authed) }
            for (msg in messages) {
                if (turn !== myTurn) break
                handleStreamMsg(myTurn, msg)
            }
        }
    }

    /** Liest den SSE-Stream Zeile für Zeile und reicht jedes Ereignis SOFORT weiter (läuft auf dem IO-Thread). */
    private suspend fun readStream(
        out: Channel<StreamMsg>,
        message: String,
        inputMode: String,
        location: Location?,
        token: String,
        sessionId: String?,
        authed: Boolean,
    ) {
        var conn: HttpURLConnection? = null
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            val url = URL(BuildConfig.DONNA_API_URL + "/chat")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream")
                // Keine Kompression: sonst puffert die Entpackung kleine SSE-Häppchen und der Text käme verzögert
                setRequestProperty("Accept-Encoding", "identity")
                sessionId?.let { setRequestProperty("X-Session-ID", it) }
                if (authed) setRequestProperty("X-Biometric-Auth", "true")
                doOutput = true
                connectTimeout = 20_000
                readTimeout = 120_000
            }
            activeConn = conn
            val body = JSONObject().apply {
                put("message", message)
                put("stream", true)
                // Client-Kennung: Teil der Live-Guard-Positiv-Allowlist im Backend
                // (nur client=="android" + input_mode∈{voice,text} darf im Stream schalten).
                // OHNE dieses Feld wäre das Overlay nach der Allowlist-Umstellung untrusted.
                put("client", "android")
                // Sprachmodus-Signal: Backend kann kurze, sprechbare Antworten liefern (Auswertung folgt im Backend)
                put("input_mode", inputMode)
                // A/B-Stimme: das Backend synthetisiert NUR bei "edge" vor (und mit dieser Stimme → Cache-Treffer);
                // bei "piper" verlaesst der Antworttext den Server nicht Richtung Edge-TTS
                put("tts_engine", TtsEngines.normalize(prefs.getString(TtsEngines.PREF_ENGINE, TtsEngines.DEFAULT)))
                put("tts_voice", TtsEngines.normalizeEdgeVoice(prefs.getString(TtsEngines.PREF_EDGE_VOICE, null)))
                location?.let { put("lat", it.latitude); put("lon", it.longitude) }
            }.toString()
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

            val streamConn = conn
            val completed = coroutineScope {
                // Gesamtzeitgrenze: blockierendes Lesen ist nicht abbrechbar — nach STREAM_MAX_MS trennen
                val limiter = launch(Dispatchers.IO) {
                    delay(STREAM_MAX_MS)
                    timedOut.set(true)
                    try { streamConn.disconnect() } catch (_: Exception) {}
                }
                try {
                    val code = streamConn.responseCode
                    if (code != 200) {
                        out.trySend(StreamMsg.Failure(OverlayErrors.fromHttp(code)))
                        return@coroutineScope false
                    }
                    // Session-ID aus Response-Header lesen und merken
                    streamConn.getHeaderField("X-Session-ID")?.let { currentSessionId = it }

                    streamConn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val line = SseLineReader.readLine(reader) ?: break
                            val event = SseLineParser.parse(line) ?: continue
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
            // Wurde die Anfrage bewusst abgebrochen (disconnect), ist der Fehler erwartet → nichts melden
            if (currentCoroutineContext().isActive) {
                out.trySend(
                    StreamMsg.Failure(
                        if (timedOut.get()) UserError(UserError.Kind.NETWORK, "Donna braucht zu lange. Nochmal versuchen?")
                        else OverlayErrors.fromException(e)
                    )
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
                is SseEvent.WeatherCard -> addInfoCard(t) { buildWeatherCard(e.data) }
                is SseEvent.MapCard -> if (UrlPolicy.check(e.url) != null) addInfoCard(t) { buildMapCard(e.url) }
                is SseEvent.Action -> { t.sawAction = true; addActionCard(e.action) }
                is SseEvent.Error -> failStream(
                    t,
                    UserError(
                        UserError.Kind.SERVER,
                        e.message.ifBlank { "Donna konnte gerade nicht antworten. Nochmal versuchen?" },
                    ),
                )
                is SseEvent.Replace -> replaceText(t, e.text)
                SseEvent.Done -> { t.sawDone = true; finishStream(t) }
            }
            is StreamMsg.End -> {
                // Ohne sauberes Ende ({"type":"done"}) ist der Stream abgeschnitten — das ist KEINE vollständige Antwort
                if (!t.sawDone && !t.streamEnded) {
                    failStream(t, UserError(UserError.Kind.NETWORK, "Die Verbindung wurde unterbrochen, die Antwort ist unvollständig. Nochmal versuchen?"))
                } else {
                    finishStream(t)
                }
            }
            is StreamMsg.Failure -> failStream(t, msg.error)
        }
    }

    /** Fehler im Stream: bis dahin empfangenen Text stehen lassen, Fehler zeigen, „Nochmal“ sendet die Frage erneut. */
    private fun failStream(t: Turn, error: UserError) {
        ui.removeCallbacks(t.renderRunnable)
        if (t.raw.isNotEmpty()) renderResponse(t)
        // AUTH hat einen eigenen Weg (Kopplung prüfen) — kein blindes Wiederholen
        val retry = if (error.kind == UserError.Kind.AUTH) null else t.message to t.inputMode
        requestActive = false
        turn = null
        fail(error, retry)
    }

    /**
     * Live-Guard-Korrektur: der bereinigte Text ERSETZT den bisherigen (er wird nicht angehängt). Bereits
     * vorgemerkte Sprachausgabe wird verworfen und mit dem bereinigten Text neu aufgesetzt — gesperrte Inhalte
     * dürfen nicht weitergesprochen werden.
     */
    private fun replaceText(t: Turn, text: String) {
        t.pipeline.stop()
        try { tts?.stop() } catch (_: Exception) {}
        t.pipeline = t.newPipeline()
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
        // Obergrenze: fehlerhafte Server dürfen UI und Speicher nicht fluten
        val room = MAX_RESPONSE_CHARS - t.raw.length
        if (text.length > room) {
            t.raw.append(text, 0, room.coerceAtLeast(0))
            t.truncated = true
        } else {
            t.raw.append(text)
        }
        if (!t.started) {
            t.started = true
            fire(OverlayEvent.ResponseStarted)   // THINKING → SPEAKING
        }
        scheduleRender(t)
        // Satzweise Sprachausgabe: der erste vollständige Satz startet sofort
        t.chunker.update(SpeechText.displayText(t.raw.toString()), final = false).forEach { speakChunk(t, it) }
    }

    private fun speakChunk(t: Turn, chunk: String) {
        val clean = SpeechText.cleanForSpeech(chunk)
        if (clean.isNotEmpty()) {
            lastSpokenText = if (lastSpokenText.isEmpty()) clean else "$lastSpokenText $clean"
            t.pipeline.enqueue(clean)
        }
    }

    /** Text gedrosselt (~50 ms) anzeigen: sofort, wenn die letzte Darstellung lange her ist, sonst gebündelt. */
    private fun scheduleRender(t: Turn) {
        // Drossel-Entscheidung in TurnRenderer extrahiert (B1); Turn-Lebenszyklus-Buchführung bleibt hier.
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
            TurnRenderer.Throttle.Skip -> { /* innerhalb der Drosselzeit, bereits geplant */ }
        }
    }

    private fun renderResponse(t: Turn) {
        lastRenderAt = SystemClock.uptimeMillis()
        // Markdown→HTML-Darstellung + Autoscroll in TurnRenderer extrahiert (B1), verhaltensneutral.
        turnRenderer.render(t.raw.toString(), t.streamEnded, userScrolledResponse)
    }

    /** Wetter-/Maps-Karte (nur eine Info-Karte pro Antwort, wie bisher). */
    private fun addInfoCard(t: Turn, build: () -> View) {
        if (t.infoCardShown) return
        t.infoCardShown = true
        cardContainer.addView(build())
        cardContainer.visibility = View.VISIBLE
    }

    /**
     * Aktions-Karte (Ja/Nein, Tesla, Rückfragen) — inkrementell ergänzt, bestehende Karten bleiben unberührt.
     * Die Bestätigen-Tasten sind erst nach Streaming-Ende scharf ([armActionButtons]): beim Streamen rutschen
     * die Karten, ein Fehltipp auf „Anrufen“/„An Tesla senden“ soll nicht möglich sein.
     */
    private fun addActionCard(action: JSONObject) {
        val card = actionCardBuilder.buildActionCard(action) ?: return
        card.tag = ACTION_CARD_TAG
        cardContainer.addView(card)
        cardContainer.visibility = View.VISIBLE
    }

    private fun hasPendingCards(): Boolean =
        (0 until cardContainer.childCount).any { cardContainer.getChildAt(it).tag == ACTION_CARD_TAG }

    /** Stream zu Ende: Rest-Text sprechen lassen und der Pipeline das Ende melden. */
    private fun finishStream(t: Turn) {
        if (t.streamEnded) return
        t.streamEnded = true
        ui.removeCallbacks(t.renderRunnable)
        t.renderScheduled = false
        renderResponse(t)
        armCardsLater()
        val finalText = SpeechText.displayText(t.raw.toString())
        t.chunker.update(finalText, final = true).forEach { speakChunk(t, it) }
        // Nur wenn WIRKLICH nichts kam (kein Text, keine Karte, keine Aktion). Antworten, die nur eine
        // Aktion auslösen (z. B. Smarthome, Merken), sind gültig und zeigen keinen Fehler.
        if (finalText.isBlank() && cardContainer.childCount == 0 && !t.sawAction) {
            failStream(t, UserError(UserError.Kind.SERVER, "Donna hat nichts geantwortet. Nochmal versuchen?"))
            return
        }
        // Sobald auch die Sprachausgabe WIRKLICH fertig ist, meldet onSpeechFinished das Ende an den Automaten
        t.pipeline.finish()
    }

    private fun onSpeechFinished(t: Turn, spokeAnything: Boolean) {
        if (turn !== t) return
        requestActive = false
        turn = null
        fire(OverlayEvent.ResponseFinished(spokeAnything, hasPendingCards(), typingActive()))
    }

    // ── Biometric-Authentifizierung ──────────────────────────────────────────

    private fun authenticateWithBiometric() {
        val biometricManager = BiometricManager.from(this)
        // BIOMETRIC_STRONG (Fingerabdruck) + BIOMETRIC_WEAK (Samsung Face ID) + PIN als Fallback
        val allowedAuth =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val canAuth = biometricManager.canAuthenticate(allowedAuth)
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            // Kein Biometric verfügbar — Overlay trotzdem öffnen, kein Brain-Zugriff
            isAuthenticated = false
            return
        }
        // Zuhören beginnt erst, wenn der Prompt fertig ist (Mikrofon + System-Prompt gleichzeitig wäre Unsinn)
        biometricPending = true
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                isAuthenticated = true
                onBiometricFinished()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                isAuthenticated = false
                onBiometricFinished()
            }
            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                isAuthenticated = false
            }
        })
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Donna")
            .setSubtitle("Fingerabdruck oder Gesicht")
            .setConfirmationRequired(false)  // kein "Bestätigen"-Button nach Gesichtserkennung
            .setAllowedAuthenticators(allowedAuth)
            .build()
        prompt.authenticate(promptInfo)
    }

    /** Biometrie-Prompt ist durch (Erfolg oder Abbruch) → jetzt zuhören, falls nichts anderes läuft. */
    private fun onBiometricFinished() {
        biometricPending = false
        if (!isDestroyed && !isFinishing && machine.state == OverlayState.IDLE) {
            fire(OverlayEvent.MicRequested)
        }
    }

    // ── Gesprächsverlauf ─────────────────────────────────────────────────────

    private data class SessionInfo(
        val sessionId: String,
        val startedAt: Double,
        val preview: String,
        val messageCount: Int,
    )

    private fun showHistoryDialog() {
        scope.launch {
            val sessions = withContext(Dispatchers.IO) { fetchSessions() }
            showHistoryPanel(sessions)
        }
    }

    private fun showHistoryPanel(sessions: List<SessionInfo>) {
        val ctx = this
        historyPanel.removeAllViews()
        historyPanel.visibility = View.VISIBLE

        // ── Trennlinie ──
        historyPanel.addView(View(ctx).apply {
            setBackgroundColor(colorBorder)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
            ).also { it.bottomMargin = dp(10) }
        })

        // ── Header: "GESPRÄCHE HEUTE" + ×-Button ──
        val historyHeader = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(8) }
        }
        historyHeader.addView(TextView(ctx).apply {
            text = "Gespräche heute"
            setTextColor(colorMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        historyHeader.addView(TextView(ctx).apply {
            text = "×"
            setTextColor(colorMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            minWidth = dp(48)
            minHeight = dp(48)
            contentDescription = "Verlauf schließen"
            isClickable = true
            isFocusable = true
            setOnClickListener { hideHistoryPanel() }
        })
        historyPanel.addView(historyHeader)

        // ── "Neues Gespräch"-Button ──
        historyPanel.addView(buildHistoryItem(
            time = "+",
            preview = "Neues Gespräch starten",
            accent = true
        ) {
            currentSessionId = null
            hideHistoryPanel()
            setStatus("Neues Gespräch. Sag etwas oder tippe.", colorMuted)
        })

        if (sessions.isEmpty()) {
            historyPanel.addView(TextView(ctx).apply {
                text = "Keine Gespräche der letzten 24 Stunden"
                setTextColor(colorMuted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(6), 0, dp(6))
            })
        } else {
            val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.GERMAN)
            sessions.forEach { s ->
                val time = fmt.format(java.util.Date((s.startedAt * 1000).toLong()))
                val preview = s.preview.take(58) + if (s.preview.length > 58) "…" else ""
                historyPanel.addView(buildHistoryItem(time, preview, accent = false) {
                    currentSessionId = s.sessionId
                    hideHistoryPanel()
                    responseScroll.visibility = View.GONE
                    cardContainer.removeAllViews()
                    cardContainer.visibility = View.GONE
                    setStatus("Gespräch geht weiter. Sag etwas oder tippe.", colorAccent)
                })
            }
        }
    }

    private fun buildHistoryItem(
        time: String,
        preview: String,
        accent: Boolean,
        onClick: () -> Unit,
    ): LinearLayout {
        val ctx = this
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            minimumHeight = dp(48)
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = GradientDrawable().apply {
                setColor(colorSurface)
                cornerRadius = dp(8).toFloat()
                setStroke(1, colorBorder)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(6) }
            setOnClickListener { onClick() }

            // Zeit / Icon
            addView(TextView(ctx).apply {
                text = time
                setTextColor(if (accent) colorAccent else colorMuted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                minWidth = dp(36)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.marginEnd = dp(10) }
            })
            // Preview
            addView(TextView(ctx).apply {
                text = preview
                setTextColor(if (accent) colorAccent2 else colorText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
    }

    private fun hideHistoryPanel() {
        historyPanel.visibility = View.GONE
        historyPanel.removeAllViews()
    }

    private fun fetchSessions(): List<SessionInfo> {
        return try {
            val url = java.net.URL(BuildConfig.DONNA_API_URL + "/stm/sessions")
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer ${TokenStore.getToken(this@VoiceInputActivity) ?: ""}")
                connectTimeout = 5_000
                readTimeout = 5_000
            }
            if (conn.responseCode != 200) return emptyList()
            val json = conn.inputStream.bufferedReader().readText()
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                SessionInfo(
                    sessionId = obj.getString("session_id"),
                    startedAt = obj.getDouble("started_at"),
                    preview = obj.safeString("preview", "…"),
                    messageCount = obj.optInt("message_count", 0),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
