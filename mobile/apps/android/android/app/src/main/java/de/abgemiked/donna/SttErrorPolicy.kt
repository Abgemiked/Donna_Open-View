package com.yourcompany.donna

/**
 * Was passiert bei einem Erkennerfehler (#146) — reine Entscheidung OHNE Android-Typen, JVM-getestet (SttErrorPolicyTest).
 * [ConversationController] setzt sie nur um. Grundsatz: nie pauschal „kein Netz“; im Weiterhoeren oder nach vorliegender
 * Antwort nie eine Meldung (still in Ruhe); stille Neustarts haben eine Obergrenze.
 */
sealed class SttErrorAction {
    /** Keine Sprache gehoert: bisheriger Stille-Pfad (Weiterhoeren neu starten, bis das Fenster um ist, sonst Zeitablauf). */
    object Silence : SttErrorAction()
    /** Erkenner still neu starten (eigener Zaehler je Start, Obergrenze [SttErrorPolicy.MAX_SILENT_RESTARTS]). */
    object RestartSilently : SttErrorAction()
    /** Einmal auf die Erkennung auf dem Geraet wechseln (wie bisher). */
    object OfflineRetry : SttErrorAction()
    /** Keine Meldung: still zurueck in Ruhe. */
    object QuietIdle : SttErrorAction()
    class Show(val error: UserError) : SttErrorAction()
}

/**
 * Sperre „fuer diesen Zug liegt schon eine Antwort vor“ (#146) — kleiner Zustandshalter, JVM-getestet. Sie gilt nur im
 * Weiterhoeren nach einer Antwort desselben Zugs; JEDER vom Nutzer ausgeloeste neue Hoerstart (Funktionstaste/Mikrofon-Taste,
 * Unterbrechen per Stimme) und jede neue Anfrage hebt sie auf, sonst bliebe ein echter Fehler im ersten Zuhoeren stumm.
 */
class TurnGuard {
    var answered: Boolean = false
        private set

    fun onAnswer(text: CharSequence) { if (text.isNotBlank()) answered = true }
    fun onRequestStarted() { answered = false }
    fun onUserListenStart() { answered = false }
}

object SttErrorPolicy {
    /** Obergrenze stiller Neustarts je Erkenner-Start (verhindert Endlosschleifen). */
    const val MAX_SILENT_RESTARTS = 2
    const val TEXT_NO_NET = "Kein Netz für die Spracherkennung."
    const val TEXT_HICCUP = "Die Spracherkennung hakt gerade."

    /** Online = Internet-Faehigkeit UND (vom System validiert ODER wenigstens verbunden: VPN, privates DNS, Netzwechsel). */
    fun isOnline(hasInternet: Boolean, validated: Boolean, connected: Boolean): Boolean = hasInternet && (validated || connected)

    fun hiccup(): UserError = UserError(UserError.Kind.UNKNOWN, TEXT_HICCUP)

    fun decide(
        code: Int,
        state: OverlayState,
        online: Boolean,
        turnAnswered: Boolean,
        triedOffline: Boolean,
        silentRestarts: Int,
        heardSpeech: Boolean,
        requestActive: Boolean = false,
    ): SttErrorAction {
        if (state.isListening && OverlayErrors.isSilence(code, heardSpeech)) return SttErrorAction.Silence
        if (state.isListening && OverlayErrors.isSilentlyRetryable(code) && silentRestarts < MAX_SILENT_RESTARTS) {
            return SttErrorAction.RestartSilently
        }
        val netErr = OverlayErrors.isRecognizerNetworkError(code)
        val canReListen = state.isListening || (state == OverlayState.THINKING && !requestActive)
        if (canReListen && netErr && !triedOffline) return SttErrorAction.OfflineRetry
        // Fehlende Berechtigung und nicht verfuegbare Sprache sind nie voruebergehend: IMMER melden (auch im Weiterhoeren)
        if (code == OverlayErrors.ERROR_INSUFFICIENT_PERMISSIONS || code == OverlayErrors.ERROR_LANGUAGE_NOT_SUPPORTED ||
            code == OverlayErrors.ERROR_LANGUAGE_UNAVAILABLE
        ) return SttErrorAction.Show(OverlayErrors.fromRecognizer(code, heardSpeech))
        // Weiterhoeren oder Antwort liegt schon vor: der Nutzer hat sein Ergebnis, ein Erkennerfehler ist kein Anlass fuer eine Meldung
        if (state == OverlayState.FOLLOW_UP || turnAnswered) return SttErrorAction.QuietIdle
        if (netErr) return SttErrorAction.Show(OverlayErrors.sttUnavailable(online))
        if (code == OverlayErrors.ERROR_SERVER_DISCONNECTED) return SttErrorAction.Show(hiccup())
        if (triedOffline && !OverlayErrors.isSilentlyRetryable(code)) {
            return SttErrorAction.Show(UserError(UserError.Kind.NO_MATCH, "Ich kann dich gerade nicht verstehen. Ruf mich neu auf und sprich noch einmal."))
        }
        return SttErrorAction.Show(OverlayErrors.fromRecognizer(code, heardSpeech))
    }
}
