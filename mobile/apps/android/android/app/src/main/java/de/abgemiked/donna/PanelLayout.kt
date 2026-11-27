package com.yourcompany.donna

/**
 * Aufbau des Gespraechs-Panels (#134 P7) — reine Entscheidungen OHNE Android-Typen, JVM-getestet (PanelLayoutTest).
 * [LiveBubbleService] setzt sie nur um (Sichtbarkeiten, Fensterbreite).
 *
 * Zwei Darstellungen desselben Fensters: [PanelLayoutMode.COMPACT] = schlanke Pille (nur Orb + Zustand, im Ruhezustand),
 * [PanelLayoutMode.CARD] = Karte, sobald es etwas zu zeigen oder zu bedienen gibt.
 */
enum class PanelLayoutMode {
    COMPACT, CARD;

    companion object {
        /**
         * Karte, sobald Antwort, Karten oder der Tippmodus da sind — und waehrend Donna spricht oder denkt (dort sitzen
         * Stopp bzw. die Antwort-Flaeche). Die Mitschrift allein oeffnet KEINE Karte: beim Zuhoeren steht sie als
         * Live-Text in der Zeile der Pille ([PanelLayoutRules.statusLine]). Sonst Pille.
         */
        fun of(
            state: OverlayState,
            hasAnswer: Boolean,
            hasCards: Boolean,
            typing: Boolean,
            holdCard: Boolean = false,
            hasNotice: Boolean = false,
        ): PanelLayoutMode =
            if (hasAnswer || hasCards || typing || holdCard || hasNotice || state == OverlayState.SPEAKING || state == OverlayState.THINKING) CARD else COMPACT
    }
}

object PanelLayoutRules {
    /** Mindestgroesse aller Tippziele. */
    const val TOUCH_MIN_DP = 48
    /** Seitenrand links/rechts (entspricht donna_space_lg). */
    const val SIDE_MARGIN_DP = 16
    /** Obergrenze fuer Tablets und Querformat. */
    const val MAX_WIDTH_DP = 480
    private const val MIN_WIDTH_DP = 240

    /**
     * EINE feste, berechnete Fensterbreite fuer Pille UND Karte: Bildschirmbreite minus 2 x Seitenrand, hoechstens
     * [MAX_WIDTH_DP]. Eine Zahl, kein MATCH_PARENT-Fenster (#133); die Karte waechst nur noch nach oben, es gibt keinen
     * Breitenwechsel mehr.
     */
    fun windowWidthDp(screenWidthDp: Int): Int =
        (screenWidthDp - 2 * SIDE_MARGIN_DP).coerceAtLeast(MIN_WIDTH_DP).coerceAtMost(MAX_WIDTH_DP)

    /** Orb-Groesse: in beiden Darstellungen gleich, damit die untere Zeile beim Wechsel nicht springt. */
    const val ORB_DP = 36
    fun orbDp(@Suppress("UNUSED_PARAMETER") mode: PanelLayoutMode): Int = ORB_DP

    /** „Stopp“ nur, solange Donna spricht (und nicht beim Tippen). */
    fun showStop(state: OverlayState, typing: Boolean): Boolean = state == OverlayState.SPEAKING && !typing

    /**
     * Mikrofon in der Bedienzeile (untere Zeile, in beiden Darstellungen gleich): nur, wo es etwas bewirkt — in
     * Ruhe/Fehler (zurueck ins Zuhoeren) und beim Sprechen (unterbrechen + zuhoeren), nicht beim Tippen (dort „Senden“).
     */
    fun showBarMic(typing: Boolean, state: OverlayState): Boolean = micUseful(typing, state)

    private fun micUseful(typing: Boolean, state: OverlayState): Boolean =
        !typing && (state == OverlayState.IDLE || state == OverlayState.ERROR || state == OverlayState.SPEAKING)

    /** Tastatur-Taste (Tippmodus oeffnen) nur, solange nicht getippt wird und Tippen sinnvoll ist (nicht bei „Bis gleich“/Ende). */
    fun showKeyboardButton(typing: Boolean, state: OverlayState): Boolean = !typing && PanelFocus.canOpen(state)

    /**
     * Nach dem Senden im Tippmodus bleibt die Karte stehen ([PanelLayoutMode.of] mit holdCard), bis die Anfrage laeuft
     * bzw. vorbei ist: Der Halt endet, sobald der Zustand wieder Ruhe/Fehler/Zuhoeren/Ende ist. Denken und Sprechen halten.
     */
    fun holdReleasedBy(state: OverlayState): Boolean = state != OverlayState.THINKING && state != OverlayState.SPEAKING

    /** Laengste Meldung, die noch in die Bedienzeile passt (schmales Geraet: ca. 110 dp Text = ca. 13 Zeichen je Zeile, 2 Zeilen). */
    const val SHORT_LINE_MAX = 24

    /** Aufteilung einer Meldung: [line] steht in der Bedienzeile, [notice] (falls vorhanden) vollstaendig im Inhalt darueber. */
    data class StatusSplit(val line: String, val notice: String?)

    /** Kurze Meldung bleibt in der Zeile; eine lange ersetzt dort das Kurzwort des Zustands und steht ungekuerzt im Inhalt. */
    fun statusSplit(stateLabel: String, message: String): StatusSplit {
        val m = message.trim()
        return if (m.isEmpty() || m.length <= SHORT_LINE_MAX) StatusSplit(if (m.isEmpty()) stateLabel else m, null) else StatusSplit(stateLabel, m)
    }

    /** Was die Zeile der Pille zeigt: der Live-Text (neuester Teil) oder das Zustandswort. */
    enum class StatusLine { STATE_WORD, TRANSCRIPT }

    /** Live-Text nur in der Pille, solange zugehoert wird und schon etwas gesprochen wurde; sonst das Zustandswort. */
    fun statusLine(mode: PanelLayoutMode, state: OverlayState, transcript: String): StatusLine =
        if (mode == PanelLayoutMode.COMPACT && state.isListening && transcript.isNotBlank()) StatusLine.TRANSCRIPT else StatusLine.STATE_WORD
}
