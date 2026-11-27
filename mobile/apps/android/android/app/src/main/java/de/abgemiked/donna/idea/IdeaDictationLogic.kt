package com.yourcompany.donna.idea

/**
 * #243: reine Entscheidungen des Ideen-Diktats (ohne Android-Typen, JVM-getestet). Fehlercodes = Werte von
 * `android.speech.SpeechRecognizer.ERROR_*` (stabil seit API 8/31).
 */
object IdeaDictationLogic {
    const val ERROR_NETWORK_TIMEOUT = 1
    const val ERROR_NETWORK = 2
    const val ERROR_SERVER = 4
    const val ERROR_SPEECH_TIMEOUT = 6
    const val ERROR_NO_MATCH = 7
    const val ERROR_INSUFFICIENT_PERMISSIONS = 9
    const val ERROR_SERVER_DISCONNECTED = 11
    const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
    const val ERROR_LANGUAGE_UNAVAILABLE = 13

    /** Ohne Sprache so lange warten, bevor das Diktat mit „nichts gehört“ endet (gleicher Wert wie das Overlay). */
    const val NO_SPEECH_MS = com.yourcompany.donna.OverlayTimings.NO_SPEECH_TIMEOUT_MS
    /** Längste Aufnahme eines Abschnitts. */
    const val MAX_SEGMENT_MS = 60_000L

    const val TEXT_NOTHING = "Ich habe nichts gehört. Tipp aufs Mikro und sprich noch einmal."
    const val TEXT_PERMISSION = "Donna darf das Mikrofon nicht nutzen. Erlaube es in den Einstellungen oder tippe die Idee."
    const val TEXT_NET = "Die Spracherkennung braucht gerade Netz. Tippe die Idee oder versuch es gleich noch einmal."
    const val TEXT_LANGUAGE = "Deutsche Spracherkennung ist auf dem Handy nicht verfügbar. Tippe die Idee."
    const val TEXT_UNAVAILABLE = "Die Spracherkennung ist auf dem Handy gerade nicht verfügbar. Tippe die Idee."
    const val TEXT_HICCUP = "Die Spracherkennung hakt gerade. Tipp aufs Mikro und versuch es noch einmal."

    fun isNetwork(code: Int): Boolean =
        code == ERROR_NETWORK || code == ERROR_NETWORK_TIMEOUT || code == ERROR_SERVER || code == ERROR_SERVER_DISCONNECTED

    /** Bei Netzfehler einmal auf die Erkennung auf dem Gerät wechseln (wie das Overlay). */
    fun shouldRetryOffline(code: Int, triedOffline: Boolean): Boolean = isNetwork(code) && !triedOffline

    /** Lesbare Meldung zu einem Erkennerfehler (ohne gesammelten Text). */
    fun errorText(code: Int): String = when {
        code == ERROR_NO_MATCH || code == ERROR_SPEECH_TIMEOUT -> TEXT_NOTHING
        code == ERROR_INSUFFICIENT_PERMISSIONS -> TEXT_PERMISSION
        code == ERROR_LANGUAGE_NOT_SUPPORTED || code == ERROR_LANGUAGE_UNAVAILABLE -> TEXT_LANGUAGE
        isNetwork(code) -> TEXT_NET
        else -> TEXT_HICCUP
    }

    /**
     * Ende des Diktats (Ergebnis, Fehler, Stille oder „Fertig“): gesammelter Text bzw. letztes Teilergebnis wird
     * übernommen (nichts geht verloren) — nur ohne jeden Text gibt es eine Meldung. null = kein Text.
     */
    fun salvage(collected: String): String? = collected.trim().ifEmpty { null }
}
