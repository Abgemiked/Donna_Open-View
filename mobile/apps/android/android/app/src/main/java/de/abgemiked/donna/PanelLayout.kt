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
         * Karte, sobald Mitschrift, Antwort, Karten oder der Tippmodus da sind — und waehrend Donna spricht (dort sitzt
         * die Stopp-Taste, die die Pille nicht hat). Sonst Pille.
         */
        fun of(
            state: OverlayState,
            hasTranscript: Boolean,
            hasAnswer: Boolean,
            hasCards: Boolean,
            typing: Boolean,
            holdCard: Boolean = false,
        ): PanelLayoutMode =
            if (hasTranscript || hasAnswer || hasCards || typing || holdCard || state == OverlayState.SPEAKING) CARD else COMPACT
    }
}

object PanelLayoutRules {
    /** Mindestgroesse aller Tippziele. */
    const val TOUCH_MIN_DP = 48
    const val COMPACT_WIDTH_DP = 320
    const val CARD_WIDTH_DP = 360
    private const val SIDE_MARGIN_DP = 24
    private const val MIN_WIDTH_DP = 240

    /** Feste Fensterbreite je Darstellung (der Rand braucht eine feste Vorgabe), hoechstens Bildschirmbreite minus Rand. */
    fun windowWidthDp(mode: PanelLayoutMode, screenWidthDp: Int): Int {
        val wanted = if (mode == PanelLayoutMode.COMPACT) COMPACT_WIDTH_DP else CARD_WIDTH_DP
        return minOf(wanted, (screenWidthDp - SIDE_MARGIN_DP).coerceAtLeast(MIN_WIDTH_DP))
    }

    /** Orb-Groesse: in der Pille etwas groesser als in der Kopfzeile der Karte. */
    fun orbDp(mode: PanelLayoutMode): Int = if (mode == PanelLayoutMode.COMPACT) 40 else 32

    /** „Stopp“ nur, solange Donna spricht (und nicht beim Tippen). */
    fun showStop(state: OverlayState, typing: Boolean): Boolean = state == OverlayState.SPEAKING && !typing

    /**
     * Runde Mikrofon-Taste (weiterreden) nur in der Karte, nicht waehrend des Tippens (dort ist „Senden“) und nur, wo
     * sie etwas bewirkt: in Ruhe/Fehler (zurueck ins Zuhoeren) und beim Sprechen (unterbrechen + zuhoeren) — nicht,
     * solange Donna ohnehin zuhoert oder nachdenkt.
     */
    fun showMic(mode: PanelLayoutMode, typing: Boolean, state: OverlayState): Boolean =
        mode == PanelLayoutMode.CARD && micUseful(typing, state)

    /** Mikrofon-Icon-Taste in der Kopfzeile der Pille (Bereit/Stumm/Fehler): dieselbe Regel, nur fuer die Pille. */
    fun showHeaderMic(mode: PanelLayoutMode, typing: Boolean, state: OverlayState): Boolean =
        mode == PanelLayoutMode.COMPACT && micUseful(typing, state)

    private fun micUseful(typing: Boolean, state: OverlayState): Boolean =
        !typing && (state == OverlayState.IDLE || state == OverlayState.ERROR || state == OverlayState.SPEAKING)

    /** Tastatur-Taste (Tippmodus oeffnen) nur, solange nicht getippt wird und Tippen sinnvoll ist (nicht bei „Bis gleich“/Ende). */
    fun showKeyboardButton(typing: Boolean, state: OverlayState): Boolean = !typing && PanelFocus.canOpen(state)

    /**
     * Nach dem Senden im Tippmodus bleibt die Karte stehen ([PanelLayoutMode.of] mit holdCard), bis die Anfrage laeuft
     * bzw. vorbei ist: Der Halt endet, sobald der Zustand wieder Ruhe/Fehler/Zuhoeren/Ende ist. Denken und Sprechen halten.
     */
    fun holdReleasedBy(state: OverlayState): Boolean = state != OverlayState.THINKING && state != OverlayState.SPEAKING

    /** Auf welcher Seite das Panel beim Breitenwechsel verankert wird: am naeheren Bildschirmrand (Mitte rechts der Mitte = rechts). */
    fun anchorRight(x: Int, width: Int, screenWidth: Int): Boolean = x + width / 2 > screenWidth / 2

    /**
     * Neue x-Position nach einem Breitenwechsel: der verankerte Rand bleibt stehen (links: x, rechts: rechte Kante),
     * danach auf den Bildschirm geklemmt. Ohne Klemmen ist Hin- und Rueckwechsel mit derselben Seite exakt umkehrbar.
     */
    fun xAfterWidthChange(x: Int, oldWidth: Int, newWidth: Int, anchorRight: Boolean, screenWidth: Int): Int {
        val nx = if (anchorRight) x + oldWidth - newWidth else x
        return nx.coerceIn(0, (screenWidth - newWidth).coerceAtLeast(0))
    }
}
