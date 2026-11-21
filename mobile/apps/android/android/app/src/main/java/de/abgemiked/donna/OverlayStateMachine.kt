package com.yourcompany.donna

/**
 * Zustandsautomat des Seitentasten-Overlays (UX-02/UX-04) — reine Logik, ohne Android-Laufzeit
 * JVM-testbar. VoiceInputActivity setzt die zurückgegebenen [OverlayEffect]s um (Mikrofon, Audio,
 * Netzwerk, Fenster) und stellt den Zustand im Orb und im Statustext dar.
 *
 * ```
 * IDLE ─(Mic)→ LISTENING ─(Sprachende | Tippen)→ THINKING ─(erstes Delta)→ SPEAKING
 * LISTENING ─(8 s ohne Sprache)→ IDLE (beim ersten Turn: Overlay leise schließen)
 * SPEAKING ─(Antwort + Sprachausgabe fertig, Gesprächsmodus an)→ FOLLOW_UP (Weiterhören, Fenster 6/10/15 s)
 * FOLLOW_UP ─(Sprachende)→ THINKING ; ─(Fenster abgelaufen | Ende-Wort | Limit | 3× gleicher Fehler)→ ENDING
 * ENDING ─(~1,5 s „Bis gleich.“)→ ENDED ; * ─(StopRequested = Ende-Taste)→ ENDED
 * SPEAKING | THINKING ─(CancelRequested)→ IDLE
 * * ─(Fehler)→ ERROR ─(Nochmal)→ LISTENING
 * ```
 * FOLLOW_UP ist ein ZUHÖR-Zustand (Mikrofon offen) wie LISTENING — Prüfungen auf „hört zu“ nutzen [isListening].
 */
enum class OverlayState { IDLE, LISTENING, FOLLOW_UP, THINKING, SPEAKING, ENDING, ENDED, ERROR }

/** Mikrofon ist (oder wird gleich) offen: erstes/manuelles Zuhören ODER Weiterhören nach einer Antwort. */
val OverlayState.isListening: Boolean get() = this == OverlayState.LISTENING || this == OverlayState.FOLLOW_UP

/** Lesbarer Fehler für den Nutzer (kein Rohcode in der Oberfläche). */
data class UserError(val kind: Kind, val message: String) {
    enum class Kind { NO_SPEECH, NO_MATCH, MIC_UNAVAILABLE, MIC_PERMISSION, RECOGNIZER_UNAVAILABLE, NETWORK, SERVER, AUTH, UNKNOWN }
}

sealed class OverlayEvent {
    /** Mikrofon starten: Overlay geöffnet, Mic-Taste, Orb-Tipp im Leerlauf, „Nochmal“, Unterbrechen per Tipp. */
    object MicRequested : OverlayEvent()

    /**
     * Sprache beendet. [userInitiated] = Nutzer hat „Fertig“ getippt oder die Seitentaste nach dem
     * Sprechen losgelassen (Erkenner muss gestoppt werden); false = onEndOfSpeech des Erkenners.
     */
    data class SpeechEnded(val userInitiated: Boolean) : OverlayEvent()

    /**
     * Text liegt vor und wird an Donna geschickt. [fromRecognizer] = Ergebnis des Spracherkenners (der Erkenner
     * ist damit fertig); false = getippt → ein noch laufender Erkenner wird verworfen.
     */
    data class TextSubmitted(
        val fromRecognizer: Boolean = false,
        /**
         * Erkannte Sprache war ECHT Mikes Äußerung (kein Echo von Donnas eigener Stimme). Das hält das
         * Freisprech-Gespräch am Laufen: der Auto-Relisten-Zähler wird zurückgesetzt. Echo-Treffer
         * werden gar nicht erst gesendet.
         */
        val genuineSpeech: Boolean = false,
    ) : OverlayEvent()

    /** Erstes Delta der Antwort ist da → Donna „spricht“ (Text läuft ein, Sprachausgabe startet). */
    object ResponseStarted : OverlayEvent()

    /**
     * Antwort vollständig UND Sprachausgabe wirklich zu Ende (Completion-Callback, keine Schätzung).
     * [spokeAnything] = es wurde tatsächlich etwas gesprochen (nicht Live-Guard-Stille),
     * [hasPendingCards] = Aktions-/Auswahlkarten warten auf eine Entscheidung.
     */
    data class ResponseFinished(
        val spokeAnything: Boolean,
        val hasPendingCards: Boolean,
        /** Tastatur offen oder Eingabefeld gefüllt → nicht automatisch zuhören. */
        val typingActive: Boolean = false,
        /**
         * #52: Die wartende Karte lässt sich per Sprache („Ja"/„Nein") entscheiden → trotz offener Karte
         * zuhören (Bestätigungs-Antwort), begrenzt durch dieselben Auto-Relisten-/Freisprech-Limits.
         * Default false = altes Verhalten (Overlay-Activity: offene Karte → IDLE).
         */
        val voiceConfirmable: Boolean = false,
    ) : OverlayEvent()

    /** 8 s lang keine Sprache erkannt. */
    object NoSpeechTimeout : OverlayEvent()

    /**
     * Mikes STIMME wurde über Donnas Ausgabe erkannt (Voice-Barge-in, opt-in). Zählt zum Limit und setzt es NICHT
     * zurück (keine Berührung!): sonst ließe sich die Schleifen-Grenze durch Echo beliebig umgehen.
     */
    object VoiceBargeIn : OverlayEvent()

    /**
     * Fehlalarm nach Voice-Barge-in: Es kam keine echte Nutzer-Sprache (Stille/NO_MATCH/Echo/Fehler) → Donna setzt
     * den unterbrochenen Turn fort (LISTENING/THINKING → SPEAKING). Zähler bleiben unverändert (das Limit zählt
     * den Auslöser schon).
     */
    object BargeInFalseAlarm : OverlayEvent()

    /**
     * Gespräch BEENDEN (Ende-Taste, Benachrichtigungs-Aktion): aus jedem Zustand → [OverlayState.ENDED], Endegrund BUTTON.
     * Das bloße Abbrechen einer Aufnahme/Antwort ohne Gesprächsende ist [CancelRequested].
     */
    object StopRequested : OverlayEvent()

    /** Aufnahme bzw. laufende Antwort abbrechen, Fenster bleibt offen (→ IDLE). Früher die Bedeutung von StopRequested. */
    object CancelRequested : OverlayEvent()

    /** Die ganze Äußerung war eine Abschlussphrase („tschüss“) — nur im Weiterhören wirksam, wird NICHT gesendet. */
    object EndPhraseHeard : OverlayEvent()

    /** Die „Bis gleich.“-Anzeige ist durch → [OverlayState.ENDED]. */
    object EndingElapsed : OverlayEvent()

    data class Failed(val error: UserError) : OverlayEvent()

    /** Overlay verliert den Fokus (onPause): alles anhalten, ohne Fehler. */
    object Aborted : OverlayEvent()
}

sealed class OverlayEffect {
    object StartListening : OverlayEffect()
    /** Auto-Relisten: kurze Verzögerung (Echo-Schutz), damit der Ausklang der eigenen Stimme nicht erkannt wird. */
    object StartListeningAfterEcho : OverlayEffect()
    /** Erkenner beenden, Ergebnis aber noch abwarten (onResults folgt). */
    object FinishListening : OverlayEffect()
    /** Erkenner verwerfen, kein Ergebnis. */
    object CancelListening : OverlayEffect()
    object StopSpeaking : OverlayEffect()
    object CancelRequest : OverlayEffect()
    /** „Ich habe nichts gehört“ kurz zeigen, dann leise schließen (sofern der Nutzer nichts tut). */
    object CloseOverlayDelayed : OverlayEffect()
    /** Gesprächsende ankündigen: nach [delayMs] muss der Host [OverlayEvent.EndingElapsed] feuern. */
    data class ScheduleEnding(val delayMs: Long) : OverlayEffect()
    /** Gespräch ist zu Ende: Blase bzw. Overlay über den vorhandenen Schließ-Weg schließen. */
    object CloseConversation : OverlayEffect()
}

data class OverlayTransition(val state: OverlayState, val effects: List<OverlayEffect> = emptyList())

/** Maximale Zahl automatischer Relisten in Folge OHNE echte Sprache dazwischen (Echo-Schleife). */
const val MAX_AUTO_LISTENS = 3

/** Maximale Freisprech-Runden in Folge ohne Berührung, auch wenn jede Runde echte Sprache enthält. */
const val MAX_HANDS_FREE_TURNS = 12

/** Voice-Barge-ins pro Berührung (danach nur noch Auto-Relisten nach TTS-Ende bzw. manuell). */
const val MAX_BARGE_INS_PER_TOUCH = 3

class OverlayStateMachine(
    initial: OverlayState = OverlayState.IDLE,
    /** Gesprächsmodus: nach der Sprachausgabe automatisch wieder zuhören. */
    var conversationMode: Boolean = true,
    /** Takt für die Messwerte (Dauer); testbar. */
    clock: () -> Long = { System.currentTimeMillis() },
) {
    var state: OverlayState = initial
        private set

    /** Mindestens ein Turn wurde gesendet — dann schließt die Stille das Overlay nicht mehr. */
    var hasConversation: Boolean = false
        private set

    /** Automatische Relisten in Folge OHNE Nutzerberührung (Schutz vor Echo-Endlosschleife / API-Kosten). */
    var autoListens: Int = 0
        private set

    /**
     * Freisprech-Runden in Folge ohne BERÜHRUNG (auch mit echter Sprache). Harte Obergrenze als Sicherheitsnetz
     * (z. B. Fernseher im Hintergrund als „Gesprächspartner“) — kostet sonst API-Budget.
     */
    var handsFreeTurns: Int = 0
        private set

    /** Voice-Barge-ins seit der letzten Berührung. */
    var bargeIns: Int = 0
        private set

    /**
     * Die laufende Aufnahme (bzw. das Ergebnis, das gerade ausgewertet wird) gehört zum Weiterhören nach einer Antwort.
     * Nur dann dürfen Ende-Wörter wirken. Bleibt über FOLLOW_UP → THINKING (Erkenner liefert sein Ergebnis) erhalten.
     */
    var followUpListen: Boolean = false
        private set

    /** Warum das Gespräch endet/endete (nur in ENDING/ENDED gesetzt). */
    var endReason: EndReason? = null
        private set

    /** Messwerte dieses Gesprächs (Zähler, genau eine Zusammenfassung). */
    val stats = ConversationStats(clock)

    private val errorTracker = RepeatedErrorTracker()

    /** Darf per Stimme noch unterbrochen werden? (Limit pro Berührung + gemeinsame Freisprech-Grenze) */
    val canVoiceBargeIn: Boolean
        get() = bargeIns < MAX_BARGE_INS_PER_TOUCH && handsFreeTurns < MAX_HANDS_FREE_TURNS

    /** Nutzer hat selbst eingegriffen (Tippen, Mic, Stopp) → alle Zähler zurücksetzen. */
    private fun userTouched() { autoListens = 0; handsFreeTurns = 0; bargeIns = 0 }

    /**
     * Die Zusammenfassung dieses Gesprächs, genau einmal (danach null; ohne gesendete Frage ebenfalls null).
     * [fallback] gilt, wenn der Host von außen schließt, ohne dass der Automat ein Ende festgelegt hat.
     */
    fun takeSummary(fallback: EndReason = EndReason.BUTTON): ConversationSummary? =
        stats.takeSummary(endReason ?: fallback)

    fun dispatch(event: OverlayEvent): OverlayTransition {
        // ENDED ist endgültig: späte Rückrufe (Erkenner, Stream) dürfen weder Zähler noch Zustand verändern.
        if (state == OverlayState.ENDED) return OverlayTransition(OverlayState.ENDED)
        // Ein spaeter Fehler (Erkenner/Stream) darf das Schliessen waehrend „Bis gleich.“ nicht aufhalten
        if (state == OverlayState.ENDING && event is OverlayEvent.Failed) return OverlayTransition(OverlayState.ENDING)
        if (event is OverlayEvent.MicRequested || event is OverlayEvent.StopRequested || event is OverlayEvent.CancelRequested) userTouched()
        val before = state
        val t = next(before, event)
        state = t.state
        followUpListen = when {
            t.state == OverlayState.FOLLOW_UP -> true
            t.state == OverlayState.THINKING && before == OverlayState.FOLLOW_UP && event is OverlayEvent.SpeechEnded -> true
            t.state == OverlayState.THINKING && before == OverlayState.THINKING && event !is OverlayEvent.TextSubmitted -> followUpListen
            else -> false
        }
        if (t.state != OverlayState.ENDING && t.state != OverlayState.ENDED) endReason = null
        return t
    }

    private fun ending(reason: EndReason, vararg effects: OverlayEffect): OverlayTransition {
        endReason = reason
        return OverlayTransition(
            OverlayState.ENDING,
            effects.toList() + OverlayEffect.ScheduleEnding(OverlayTimings.endingMs(reason)),
        )
    }

    private fun next(s: OverlayState, e: OverlayEvent): OverlayTransition = when (e) {
        is OverlayEvent.MicRequested -> when (s) {
            OverlayState.IDLE, OverlayState.ERROR, OverlayState.ENDING ->
                OverlayTransition(OverlayState.LISTENING, listOf(OverlayEffect.StartListening))
            // Unterbrechen per Tipp: Antwort/Sprachausgabe abbrechen und sofort zuhören
            OverlayState.THINKING, OverlayState.SPEAKING -> {
                stats.onInterruption()
                OverlayTransition(
                    OverlayState.LISTENING,
                    listOf(OverlayEffect.CancelRequest, OverlayEffect.StopSpeaking, OverlayEffect.StartListening),
                )
            }
            OverlayState.LISTENING, OverlayState.FOLLOW_UP, OverlayState.ENDED -> OverlayTransition(s)
        }

        is OverlayEvent.VoiceBargeIn ->
            if (s == OverlayState.SPEAKING && canVoiceBargeIn) {
                bargeIns++
                handsFreeTurns++
                stats.onInterruption()
                OverlayTransition(
                    OverlayState.LISTENING,
                    listOf(OverlayEffect.CancelRequest, OverlayEffect.StopSpeaking, OverlayEffect.StartListening),
                )
            } else OverlayTransition(s)

        is OverlayEvent.BargeInFalseAlarm ->
            if (s == OverlayState.LISTENING || s == OverlayState.THINKING) {
                OverlayTransition(OverlayState.SPEAKING, listOf(OverlayEffect.CancelListening))
            } else OverlayTransition(s)

        is OverlayEvent.SpeechEnded ->
            if (s.isListening) {
                OverlayTransition(
                    OverlayState.THINKING,
                    if (e.userInitiated) listOf(OverlayEffect.FinishListening) else emptyList(),
                )
            } else OverlayTransition(s)

        is OverlayEvent.TextSubmitted -> {
            hasConversation = true
            stats.onTurn(followUp = followUpListen && e.fromRecognizer)
            if (!e.fromRecognizer) userTouched() else if (e.genuineSpeech) autoListens = 0
            OverlayTransition(
                OverlayState.THINKING,
                // Ein Erkennungsergebnis beendet den Erkenner selbst; getippter Text verwirft einen laufenden
                if (e.fromRecognizer) listOf(OverlayEffect.StopSpeaking, OverlayEffect.CancelRequest)
                else listOf(OverlayEffect.CancelListening, OverlayEffect.StopSpeaking, OverlayEffect.CancelRequest),
            )
        }

        is OverlayEvent.ResponseStarted ->
            if (s == OverlayState.THINKING) OverlayTransition(OverlayState.SPEAKING) else OverlayTransition(s)

        is OverlayEvent.ResponseFinished ->
            if (s == OverlayState.THINKING || s == OverlayState.SPEAKING) {
                errorTracker.reset()
                if (conversationMode && e.spokeAnything && (!e.hasPendingCards || e.voiceConfirmable) && !e.typingActive) {
                    if (autoListens < MAX_AUTO_LISTENS && handsFreeTurns < MAX_HANDS_FREE_TURNS) {
                        autoListens++
                        handsFreeTurns++
                        OverlayTransition(OverlayState.FOLLOW_UP, listOf(OverlayEffect.StartListeningAfterEcho))
                    } else {
                        // Schutzgrenze erreicht: ruhig beenden statt still im Leerlauf hängen zu bleiben
                        ending(EndReason.LIMIT)
                    }
                } else {
                    OverlayTransition(OverlayState.IDLE)
                }
            } else OverlayTransition(s)

        is OverlayEvent.NoSpeechTimeout ->
            if (s == OverlayState.FOLLOW_UP) {
                // Das Hörfenster nach der Antwort ist abgelaufen → ruhiges Ende
                stats.onWindowExpired()
                ending(EndReason.SILENCE, OverlayEffect.CancelListening)
            } else if (s == OverlayState.LISTENING) {
                if (hasConversation) {
                    OverlayTransition(OverlayState.IDLE, listOf(OverlayEffect.CancelListening))
                } else {
                    OverlayTransition(OverlayState.IDLE, listOf(OverlayEffect.CancelListening, OverlayEffect.CloseOverlayDelayed))
                }
            } else OverlayTransition(s)

        is OverlayEvent.EndPhraseHeard ->
            // Nur direkt nach dem Weiterhören (die Auswertung des Erkenner-Ergebnisses läuft in THINKING)
            if (followUpListen && (s == OverlayState.FOLLOW_UP || s == OverlayState.THINKING)) {
                ending(EndReason.KEYWORD, OverlayEffect.CancelListening, OverlayEffect.CancelRequest, OverlayEffect.StopSpeaking)
            } else OverlayTransition(s)

        is OverlayEvent.EndingElapsed ->
            if (s == OverlayState.ENDING) {
                OverlayTransition(OverlayState.ENDED, listOf(OverlayEffect.CloseConversation))
            } else OverlayTransition(s)

        is OverlayEvent.StopRequested -> {
            // Ein laufendes Ende (Stille/Ende-Wort/Limit/Fehler) behält seinen Grund; sonst ist es die Ende-Taste
            if (s != OverlayState.ENDING) endReason = EndReason.BUTTON
            OverlayTransition(
                OverlayState.ENDED,
                listOf(
                    OverlayEffect.CancelListening, OverlayEffect.CancelRequest, OverlayEffect.StopSpeaking,
                    OverlayEffect.CloseConversation,
                ),
            )
        }

        is OverlayEvent.CancelRequested -> when (s) {
            OverlayState.LISTENING, OverlayState.FOLLOW_UP -> OverlayTransition(OverlayState.IDLE, listOf(OverlayEffect.CancelListening))
            // CancelListening auch hier: in THINKING liefert der Erkenner evtl. noch sein Endergebnis nach
            OverlayState.THINKING, OverlayState.SPEAKING ->
                OverlayTransition(
                    OverlayState.IDLE,
                    listOf(OverlayEffect.CancelListening, OverlayEffect.CancelRequest, OverlayEffect.StopSpeaking),
                )
            else -> OverlayTransition(s)
        }

        is OverlayEvent.Failed ->
            if (errorTracker.record(e.error)) {
                // Derselbe Fehler dreimal in Folge: Klartext statt Endlosschleife (Text: ConversationTexts.ERROR_END)
                errorTracker.reset()
                ending(EndReason.ERROR, OverlayEffect.CancelListening, OverlayEffect.StopSpeaking, OverlayEffect.CancelRequest)
            } else {
                OverlayTransition(
                    OverlayState.ERROR,
                    listOf(OverlayEffect.CancelListening, OverlayEffect.StopSpeaking, OverlayEffect.CancelRequest),
                )
            }

        is OverlayEvent.Aborted ->
            if (s == OverlayState.ENDING) {
                OverlayTransition(OverlayState.ENDED, listOf(OverlayEffect.CloseConversation))
            } else {
                OverlayTransition(
                    OverlayState.IDLE,
                    listOf(OverlayEffect.CancelListening, OverlayEffect.StopSpeaking, OverlayEffect.CancelRequest),
                )
            }
    }
}

/** Fehlermeldungen: freundlich, in Satzschreibung, mit nächstem Schritt. */
object OverlayErrors {

    // SpeechRecognizer.ERROR_* (als Konstanten, damit die Zuordnung ohne Android-Laufzeit testbar ist)
    const val ERROR_NETWORK_TIMEOUT = 1
    const val ERROR_NETWORK = 2
    const val ERROR_AUDIO = 3
    const val ERROR_SERVER = 4
    const val ERROR_CLIENT = 5
    const val ERROR_SPEECH_TIMEOUT = 6
    const val ERROR_NO_MATCH = 7
    const val ERROR_RECOGNIZER_BUSY = 8
    const val ERROR_INSUFFICIENT_PERMISSIONS = 9
    const val ERROR_SERVER_DISCONNECTED = 11

    /** Fehler, bei denen ein einmaliger stiller Neustart des Erkenners sinnvoll ist. */
    fun isSilentlyRetryable(code: Int): Boolean =
        code == ERROR_SERVER_DISCONNECTED || code == ERROR_RECOGNIZER_BUSY || code == ERROR_AUDIO || code == ERROR_CLIENT

    /** Keine Sprache gehört (kein echter Fehler → Timeout-Pfad). */
    fun isSilence(code: Int, heardSpeech: Boolean): Boolean =
        code == ERROR_SPEECH_TIMEOUT || (code == ERROR_NO_MATCH && !heardSpeech)

    /**
     * Statustext bei Ablauf der Stille (#116): laeuft ein Gespraech (Weiterhoeren nach einer Antwort), ist das kein
     * Fehler -> `null` (Status faellt auf den normalen Ruhe-Hinweis zurueck). Ohne Gespraech der uebergebene Hinweis.
     */
    fun silenceStatus(hasConversation: Boolean, text: String): String? = if (hasConversation) null else text

    /**
     * Transiente Netz-/Server-Fehler des Erkenners, die einen On-Device-Fallback rechtfertigen. Google liefert
     * ERROR_NETWORK/TIMEOUT und v. a. ERROR_SERVER gelegentlich AUCH bei stabilem Netz (Erkenner-Service-Hiccup/
     * Rate-Limit) — darum NICHT sofort „kein Netz" verkünden, sondern erst On-Device versuchen. (ERROR_SERVER war
     * früher nicht dabei → fiel hart auf „kein Netz, ruf neu auf", obwohl online — Mike-Befund 2026-11-02.)
     */
    fun isRecognizerNetworkError(code: Int): Boolean =
        code == ERROR_NETWORK || code == ERROR_NETWORK_TIMEOUT || code == ERROR_SERVER

    /**
     * Finale Meldung, wenn weder Online- noch On-Device-STT ging. NUR bei ECHTEM Offline von „kein Netz" sprechen;
     * ist das Gerät online, streikt der Erkenner — dann nicht fälschlich „wenn du wieder online bist" vertrösten.
     */
    fun sttUnavailable(online: Boolean): UserError =
        if (online)
            UserError(UserError.Kind.NETWORK, "Die Spracherkennung streikt gerade — dein Netz ist da. Ruf mich gleich nochmal auf.")
        else
            UserError(UserError.Kind.NETWORK, "Ich habe gerade kein Netz für die Spracherkennung. Ruf mich neu auf, wenn du wieder online bist.")

    fun fromRecognizer(code: Int, heardSpeech: Boolean): UserError = when (code) {
        ERROR_NO_MATCH, ERROR_SPEECH_TIMEOUT ->
            if (heardSpeech) UserError(UserError.Kind.NO_MATCH, "Das habe ich nicht verstanden. Magst du es nochmal sagen oder tippen?")
            else UserError(UserError.Kind.NO_SPEECH, "Ich habe nichts gehört. Tippe auf „Nochmal“ oder schreib mir.")
        ERROR_AUDIO, ERROR_RECOGNIZER_BUSY ->
            UserError(UserError.Kind.MIC_UNAVAILABLE, "Das Mikrofon ist gerade nicht verfügbar. Versuch es gleich nochmal oder tippe.")
        ERROR_INSUFFICIENT_PERMISSIONS ->
            UserError(UserError.Kind.MIC_PERMISSION, "Donna darf das Mikrofon nicht nutzen. Du kannst stattdessen tippen.")
        ERROR_NETWORK, ERROR_NETWORK_TIMEOUT, ERROR_SERVER, ERROR_SERVER_DISCONNECTED ->
            UserError(UserError.Kind.NETWORK, "Die Spracherkennung braucht gerade Internet. Probier es nochmal oder tippe.")
        else ->
            UserError(UserError.Kind.UNKNOWN, "Die Spracherkennung hat nicht geklappt. Probier es nochmal oder tippe.")
    }

    fun recognizerUnavailable(): UserError =
        UserError(UserError.Kind.RECOGNIZER_UNAVAILABLE, "Die Spracherkennung ist auf diesem Gerät nicht verfügbar. Du kannst tippen.")

    fun micPermissionDenied(): UserError =
        UserError(UserError.Kind.MIC_PERMISSION, "Ohne Mikrofon-Berechtigung kann ich nicht zuhören. Du kannst stattdessen tippen.")

    fun fromHttp(code: Int): UserError = when (code) {
        401, 403 -> UserError(UserError.Kind.AUTH, "Donna kennt dieses Gerät gerade nicht. Bitte in der Donna-App die Kopplung prüfen.")
        in 500..599 -> UserError(UserError.Kind.SERVER, "Donna ist gerade nicht erreichbar (Fehler $code). Nochmal versuchen?")
        else -> UserError(UserError.Kind.SERVER, "Das hat nicht geklappt (Fehler $code). Nochmal versuchen?")
    }

    fun fromException(e: Throwable): UserError = when (e) {
        is java.net.SocketTimeoutException ->
            UserError(UserError.Kind.NETWORK, "Donna antwortet gerade nicht. Nochmal versuchen?")
        is java.io.IOException ->
            UserError(UserError.Kind.NETWORK, "Keine Verbindung zu Donna. Prüf dein Netz und versuch es nochmal.")
        else ->
            UserError(UserError.Kind.UNKNOWN, "Etwas ist schiefgelaufen. Nochmal versuchen?")
    }
}

/**
 * Sprechpause („Kurz/Normal/Lang“) — einstellbar in der App (Paket C). Liegt als String in [TtsEngines.PREFS] und wird bei
 * JEDEM Start des Zuhoerens frisch gelesen. Der Schluessel steht NUR hier.
 */
enum class SilenceProfile(val key: String, val completeMs: Long, val possiblyCompleteMs: Long, val minimumMs: Long) {
    SHORT("short", 1200, 900, 600),
    NORMAL("normal", 1800, 1300, 900),
    // Finding B (Gerätetest): eine ~1s-Denkpause schnitt die Eingabe zu früh ab → Schwellen hochgesetzt.
    LONG("long", 2200, 1600, 1200);

    companion object {
        const val PREF_KEY = "silence_profile"
        /** LONG als Standard (Denkpausen sollen nicht abgeschnitten werden). */
        val DEFAULT = LONG
        fun fromKey(key: String?): SilenceProfile = values().firstOrNull { it.key == key } ?: DEFAULT
        /** Ungueltig/fehlend -> Standard; sonst der Schluessel selbst. */
        fun normalizeKey(raw: String?): String = fromKey(raw).key
        fun read(prefs: android.content.SharedPreferences): SilenceProfile =
            try { fromKey(prefs.getString(PREF_KEY, null)) } catch (_: Exception) { DEFAULT }
    }
}

object OverlayTimings {
    /** Ohne Sprache nach dem Start des Erkenners: leise schließen bzw. in den Leerlauf. */
    const val NO_SPEECH_TIMEOUT_MS = 8_000L
    /** „Bis gleich.“ so lange zeigen, dann schließen. */
    const val ENDING_MS = 1_500L
    /** Der Klartext bei Fehler-Ende braucht Lesezeit. */
    const val ENDING_ERROR_MS = 3_500L

    fun endingMs(reason: EndReason): Long = if (reason == EndReason.ERROR) ENDING_ERROR_MS else ENDING_MS

    /** Anzeige-Drosselung beim Streaming (Text token-weise, aber nicht pro Token neu layouten). */
    const val TEXT_THROTTLE_MS = 50L
}
