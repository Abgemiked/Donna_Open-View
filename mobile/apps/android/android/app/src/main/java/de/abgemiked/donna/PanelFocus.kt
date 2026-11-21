package com.yourcompany.donna

/**
 * Tippen in der Blase (#122 Paket C) — reine Entscheidungen OHNE Android-Typen, JVM-testbar.
 *
 * Das Panel der Blase ist normalerweise NICHT fokussierbar (die App dahinter bleibt bedienbar, keine Tastatur).
 * Nur im Tippmodus wird es fokussierbar; [LiveBubbleService] uebersetzt die [PanelWindowSpec] in echte Fenster-Flags
 * und stellt bei JEDEM [PanelTypingEvent] ueber eine einzige Funktion zurueck.
 */

/** Was das Panel-Fenster gerade koennen soll (der Dienst uebersetzt das in Flags, Position und Hoehen). */
data class PanelWindowSpec(
    /** Fenster darf Tastaturfokus haben (nur im Tippmodus). */
    val focusable: Boolean,
    /** Panel nach oben legen, damit die Tastatur Zustandsanzeige und Antwort nicht verdeckt. */
    val moveToTop: Boolean,
    /** Hoechsthoehe des Antwortbereichs in Prozent der Bildschirmhoehe. */
    val answerMaxPercent: Int,
)

/** Ereignisse, die den Tippmodus oeffnen bzw. beenden. Alles ausser [OPEN] stellt das Fenster zurueck. */
enum class PanelTypingEvent {
    OPEN,
    /** Nachricht gesendet. */
    SENT,
    /** Tastatur wurde (z. B. ueber die Tastatur selbst) geschlossen. */
    KEYBOARD_CLOSED,
    /** Zurueck-Taste. */
    BACK,
    /** Panel wird geschlossen/neu gebaut. */
    PANEL_CLOSED,
    /** Gespraech endet ("Bis gleich", Ende). */
    CONVERSATION_ENDED,
    /** Dienst endet (Blase wird entfernt). */
    SERVICE_ENDED,
    /** Eine Sprachaufnahme beginnt (Mikrofon offen). */
    RECORDING_STARTED,
    /** Das Fenster verliert den Fokus (z. B. Tipp in die App dahinter). */
    FOCUS_LOST,
    /** Fehler beim Umstellen. */
    ERROR,
}

object PanelFocus {
    const val ANSWER_MAX_PERCENT_NORMAL = 55
    const val ANSWER_MAX_PERCENT_TYPING = 22

    fun spec(typing: Boolean): PanelWindowSpec =
        PanelWindowSpec(
            focusable = typing,
            moveToTop = typing,
            answerMaxPercent = if (typing) ANSWER_MAX_PERCENT_TYPING else ANSWER_MAX_PERCENT_NORMAL,
        )

    /** Neuer Tippmodus nach einem Ereignis: nur [PanelTypingEvent.OPEN] schaltet ein, JEDES andere aus. */
    fun typingAfter(event: PanelTypingEvent): Boolean = event == PanelTypingEvent.OPEN

    /** Frist, in der das Fenster nach dem Oeffnen den Fokus bekommen muss. */
    const val FOCUS_TIMEOUT_MS = 1_500L
    const val FOCUS_FAILED_STATUS = "Tippen geht hier gerade nicht."

    /** Sicherheitsnetz: Tippmodus offen, aber das Fenster hat nie Fokus bekommen -> beenden (Ausgang ERROR). */
    fun focusMissing(typing: Boolean, windowHasFocus: Boolean): Boolean = typing && !windowHasFocus

    /** Waehrend des Tippens darf kein Stimm-Unterbrechen greifen. */
    fun voiceBargeInAllowed(typingActive: Boolean): Boolean = !typingActive

    /** Trifft die Antwort waehrend des Tippens ein, wird sie nur als Text gezeigt (keine Sprachausgabe ueber das Tippen hinweg). */
    fun speaksAnswer(typingActive: Boolean): Boolean = !typingActive

    /** Tippen ist waehrend „Bis gleich.“ und nach dem Ende nicht mehr sinnvoll. */
    fun canOpen(state: OverlayState): Boolean = state != OverlayState.ENDING && state != OverlayState.ENDED

    /** Beim Oeffnen Mikrofon (Zuhoeren) bzw. Sprachausgabe anhalten, ein laufendes Denken nicht. */
    fun stopsAudioOnOpen(state: OverlayState): Boolean = state.isListening || state == OverlayState.SPEAKING

    /**
     * Zustandsregel fuer den Tippmodus: beginnt eine Aufnahme oder endet das Gespraech, wird zurueckgestellt.
     * @return das Ereignis, das den Tippmodus beenden muss, sonst null
     */
    fun exitForState(typing: Boolean, state: OverlayState): PanelTypingEvent? {
        if (!typing) return null
        return when {
            state.isListening -> PanelTypingEvent.RECORDING_STARTED
            state == OverlayState.ENDING || state == OverlayState.ENDED -> PanelTypingEvent.CONVERSATION_ENDED
            else -> null
        }
    }
}

/** Getippter Text: beschneiden, leer = nichts senden, Laenge begrenzen. */
object TypedInput {
    /** Obergrenze fuer eine getippte Nachricht (das Activity-Overlay begrenzt nur die Zeilenzahl; hier zusaetzlich die Zeichen). */
    const val MAX_CHARS = 2_000

    fun prepare(raw: String?): String? {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return null
        return if (t.length > MAX_CHARS) t.substring(0, MAX_CHARS).trimEnd() else t
    }
}
