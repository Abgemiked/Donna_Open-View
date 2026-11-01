package com.yourcompany.donna

/**
 * Zustandsautomat des Seitentasten-Overlays (UX-02/UX-04) — reine Logik, ohne Android-Laufzeit
 * JVM-testbar. VoiceInputActivity setzt die zurückgegebenen [OverlayEffect]s um (Mikrofon, Audio,
 * Netzwerk, Fenster) und stellt den Zustand im Orb und im Statustext dar.
 *
 * ```
 * IDLE ─(Mic)→ LISTENING ─(Sprachende | Tippen)→ THINKING ─(erstes Delta)→ SPEAKING
 * LISTENING ─(8 s ohne Sprache)→ IDLE (beim ersten Turn: Overlay leise schließen)
 * SPEAKING ─(Antwort + Sprachausgabe fertig, Gesprächsmodus an)→ LISTENING
 * SPEAKING | THINKING ─(Stopp)→ IDLE
 * * ─(Fehler)→ ERROR ─(Nochmal)→ LISTENING
 * ```
 */
enum class OverlayState { IDLE, LISTENING, THINKING, SPEAKING, ERROR }

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
    ) : OverlayEvent()

    /** 8 s lang keine Sprache erkannt. */
    object NoSpeechTimeout : OverlayEvent()

    /**
     * Mikes STIMME wurde über Donnas Ausgabe erkannt (Voice-Barge-in, opt-in). Zählt zum Limit und setzt es NICHT
     * zurück (keine Berührung!): sonst ließe sich die Schleifen-Grenze durch Echo beliebig umgehen.
     */
    object VoiceBargeIn : OverlayEvent()

    /** Stopp-/Abbrechen-Taste. */
    object StopRequested : OverlayEvent()

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

    /** Darf per Stimme noch unterbrochen werden? (Limit pro Berührung + gemeinsame Freisprech-Grenze) */
    val canVoiceBargeIn: Boolean
        get() = bargeIns < MAX_BARGE_INS_PER_TOUCH && handsFreeTurns < MAX_HANDS_FREE_TURNS

    /** Nutzer hat selbst eingegriffen (Tippen, Mic, Stopp) → alle Zähler zurücksetzen. */
    private fun userTouched() { autoListens = 0; handsFreeTurns = 0; bargeIns = 0 }

    fun dispatch(event: OverlayEvent): OverlayTransition {
        if (event is OverlayEvent.MicRequested || event is OverlayEvent.StopRequested) userTouched()
        val t = next(state, event)
        state = t.state
        return t
    }

    private fun next(s: OverlayState, e: OverlayEvent): OverlayTransition = when (e) {
        is OverlayEvent.MicRequested -> when (s) {
            OverlayState.IDLE, OverlayState.ERROR ->
                OverlayTransition(OverlayState.LISTENING, listOf(OverlayEffect.StartListening))
            // Unterbrechen per Tipp: Antwort/Sprachausgabe abbrechen und sofort zuhören
            OverlayState.THINKING, OverlayState.SPEAKING ->
                OverlayTransition(
                    OverlayState.LISTENING,
                    listOf(OverlayEffect.CancelRequest, OverlayEffect.StopSpeaking, OverlayEffect.StartListening),
                )
            OverlayState.LISTENING -> OverlayTransition(s)
        }

        is OverlayEvent.VoiceBargeIn ->
            if (s == OverlayState.SPEAKING && canVoiceBargeIn) {
                bargeIns++
                handsFreeTurns++
                OverlayTransition(
                    OverlayState.LISTENING,
                    listOf(OverlayEffect.CancelRequest, OverlayEffect.StopSpeaking, OverlayEffect.StartListening),
                )
            } else OverlayTransition(s)

        is OverlayEvent.SpeechEnded ->
            if (s == OverlayState.LISTENING) {
                OverlayTransition(
                    OverlayState.THINKING,
                    if (e.userInitiated) listOf(OverlayEffect.FinishListening) else emptyList(),
                )
            } else OverlayTransition(s)

        is OverlayEvent.TextSubmitted -> {
            hasConversation = true
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
                if (conversationMode && e.spokeAnything && !e.hasPendingCards && !e.typingActive &&
                    autoListens < MAX_AUTO_LISTENS && handsFreeTurns < MAX_HANDS_FREE_TURNS
                ) {
                    autoListens++
                    handsFreeTurns++
                    OverlayTransition(OverlayState.LISTENING, listOf(OverlayEffect.StartListeningAfterEcho))
                } else {
                    OverlayTransition(OverlayState.IDLE)
                }
            } else OverlayTransition(s)

        is OverlayEvent.NoSpeechTimeout ->
            if (s == OverlayState.LISTENING) {
                if (hasConversation) {
                    OverlayTransition(OverlayState.IDLE, listOf(OverlayEffect.CancelListening))
                } else {
                    OverlayTransition(OverlayState.IDLE, listOf(OverlayEffect.CancelListening, OverlayEffect.CloseOverlayDelayed))
                }
            } else OverlayTransition(s)

        is OverlayEvent.StopRequested -> when (s) {
            OverlayState.LISTENING -> OverlayTransition(OverlayState.IDLE, listOf(OverlayEffect.CancelListening))
            // CancelListening auch hier: in THINKING liefert der Erkenner evtl. noch sein Endergebnis nach
            OverlayState.THINKING, OverlayState.SPEAKING ->
                OverlayTransition(
                    OverlayState.IDLE,
                    listOf(OverlayEffect.CancelListening, OverlayEffect.CancelRequest, OverlayEffect.StopSpeaking),
                )
            else -> OverlayTransition(s)
        }

        is OverlayEvent.Failed ->
            OverlayTransition(
                OverlayState.ERROR,
                listOf(OverlayEffect.CancelListening, OverlayEffect.StopSpeaking, OverlayEffect.CancelRequest),
            )

        is OverlayEvent.Aborted ->
            OverlayTransition(
                OverlayState.IDLE,
                listOf(OverlayEffect.CancelListening, OverlayEffect.StopSpeaking, OverlayEffect.CancelRequest),
            )
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

/** Sprechpause („Sprechpause kurz/normal/lang“) — Einstellung folgt in einem späteren Batch, Flag ist vorbereitet. */
enum class SilenceProfile(val key: String, val completeMs: Long, val possiblyCompleteMs: Long) {
    SHORT("short", 900, 700),
    NORMAL("normal", 1300, 1100),
    LONG("long", 1800, 1500);

    companion object {
        /** LONG als Standard (Denkpausen sollen nicht abgeschnitten werden), bis es den Einstellungen-Screen gibt. */
        val DEFAULT = LONG
        fun fromKey(key: String?): SilenceProfile = values().firstOrNull { it.key == key } ?: DEFAULT
    }
}

object OverlayTimings {
    /** Ohne Sprache nach dem Start des Erkenners: leise schließen bzw. in den Leerlauf. */
    const val NO_SPEECH_TIMEOUT_MS = 8_000L
    /** Anzeige-Drosselung beim Streaming (Text token-weise, aber nicht pro Token neu layouten). */
    const val TEXT_THROTTLE_MS = 50L
}
