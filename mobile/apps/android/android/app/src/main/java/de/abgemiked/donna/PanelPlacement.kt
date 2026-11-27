package com.yourcompany.donna

/**
 * Position des Gespraechs-Panels (#134 Folgepunkt: unten mittig) — reine Rechnung in Pixeln OHNE Android-Typen,
 * JVM-getestet (PanelPlacementTest). Bezug ist der ECHTE Bildschirm (Fenster mit FLAG_LAYOUT_IN_SCREEN, Gravity unten):
 * "bottom" = Abstand der Panel-Unterkante vom unteren Bildschirmrand. Die Unterkante bleibt stehen, wenn die Hoehe
 * wechselt: die Karte waechst nach OBEN.
 */
data class SysInsets(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Echte Bildschirmgroesse plus Systemleisten (Status-, Navigations-/Gestenleiste, Ausschnitt). */
data class ScreenArea(val width: Int, val height: Int, val insets: SysInsets)

/** Vom Nutzer gesetzte Position: linke Kante und Unterkante-Abstand. */
data class UserSpot(val left: Int, val bottom: Int)

/**
 * Ergebnis fuer die Fenster-Parameter. [centered] = Gravity unten mittig, [x] = Versatz von der Mitte;
 * sonst Gravity unten links, [x] = linke Kante.
 */
data class PanelSpot(val centered: Boolean, val x: Int, val bottom: Int)

object PanelPlacement {
    /** Version der gespeicherten Position: 2 = unten verankert (Abstand von unten). Aeltere (oben links) werden verworfen. */
    const val POS_VERSION = 2

    fun discardStored(storedVersion: Int): Boolean = storedVersion < POS_VERSION

    /** Gespeicherte Nutzerposition; fehlt einer der Werte, gilt der Standard (unten mittig). */
    fun stored(left: Int?, bottom: Int?): UserSpot? = if (left == null || bottom == null) null else UserSpot(left, bottom)

    private fun minLeft(s: ScreenArea) = s.insets.left
    private fun maxLeft(s: ScreenArea, width: Int) = (s.width - s.insets.right - width).coerceAtLeast(s.insets.left)
    private fun minBottom(s: ScreenArea) = s.insets.bottom
    private fun maxBottom(s: ScreenArea, height: Int) = (s.height - s.insets.top - height).coerceAtLeast(s.insets.bottom)

    /**
     * Wo das Panel sitzt. Ohne Nutzerposition unten mittig mit [gapPx] ueber der unteren Systemleiste.
     * Im Tippmodus: direkt ueber der Tastatur (Unterkante = Tastaturoberkante + [gapPx]), nie tiefer als die
     * Nutzerposition; ist die Tastaturhoehe unbekannt ([keyboardPx] = 0), bleibt es zunaechst stehen und weicht nur mit
     * [topFallback] nach oben aus (Abstand [topGapPx] unter der Statusleiste). Nach dem Tippen ergibt dieselbe Rechnung ohne Tippmodus die
     * vorige Position von selbst. Immer innerhalb der Systemleisten geklemmt.
     */
    fun spot(
        screen: ScreenArea,
        widthPx: Int,
        heightPx: Int,
        user: UserSpot?,
        gapPx: Int,
        typing: Boolean,
        keyboardPx: Int,
        topGapPx: Int,
        topFallback: Boolean = false,
    ): PanelSpot {
        var bottom = user?.bottom ?: (screen.insets.bottom + gapPx)
        if (typing) {
            // Tastaturhoehe unbekannt: das Panel BLEIBT unten (erst die erscheinende Tastatur erzeugt die Ueberlappung, die gemeldet wird);
            // nur nach der Wartezeit ([needsTopFallback]) weicht es nach oben aus.
            bottom = when {
                keyboardPx > 0 -> maxOf(bottom, keyboardPx + gapPx)
                topFallback -> screen.height - screen.insets.top - heightPx - topGapPx
                else -> bottom
            }
        }
        bottom = bottom.coerceIn(minBottom(screen), maxBottom(screen, heightPx))
        return if (user == null) {
            PanelSpot(true, (screen.insets.left - screen.insets.right) / 2, bottom)
        } else {
            PanelSpot(false, user.left.coerceIn(minLeft(screen), maxLeft(screen, widthPx)), bottom)
        }
    }

    /**
     * Hoechsthoehe des scrollenden Inhalts aus dem tatsaechlich verfuegbaren Platz ueber der Unterkante ([bottomPx]):
     * Bildschirm minus Statusleiste minus Unterkante minus feste Teile des Panels ([chromePx], z. B. Bedienzeile). Nie unter
     * [minPx] — fehlt dafuer der Platz, wandert das Panel stattdessen nach unten ([spot]). So scrollt der Inhalt,
     * statt dass das Panel oben aus dem Bild laeuft.
     */
    fun contentLimit(screen: ScreenArea, bottomPx: Int, chromePx: Int, minPx: Int): Int =
        (screen.height - screen.insets.top - bottomPx - chromePx).coerceAtLeast(minPx)

    /**
     * Neue Tastaturhoehen-Schaetzung aus der Ueberlappung, die der Insets-Listener des Panel-Fensters meldet. Der Wert ist
     * FENSTER-relativ: absolut = Ueberlappung + Abstand der Panel-Unterkante vom unteren Bildschirmrand. Ist das Fenster
     * VOLLSTAENDIG ueberdeckt (Ueberlappung >= Fensterhoehe), ist der Wert nur eine UNTERGRENZE: dann in einem Schritt
     * grosszuegig anheben (Untergrenze + halbe Fensterhoehe, [KeyboardEstimate.provisional]); die naechste Messung ist
     * exakt (Ueberlappung < Fensterhoehe) und ersetzt den vorlaeufigen Wert. Sonst gilt das Maximum der Sitzung (nie durch
     * Kleineres oder 0 ersetzt, kein Pendeln). Metriken (API 30+) sind eine exakte Quelle. Nicht sichtbar: unveraendert.
     * Faellt die Tastatur knapp ueber die Anhebung, bleibt der vorlaeufige Wert etwas zu gross: nie verdeckt, nur eine
     * kleine Luecke.
     */
    fun keyboardUpdate(
        prev: KeyboardEstimate,
        visible: Boolean,
        overlapPx: Int,
        windowHeightPx: Int,
        bottomOffsetPx: Int,
        metricsPx: Int,
    ): KeyboardEstimate {
        if (!visible) return prev
        if (metricsPx > 0 && (prev.provisional || metricsPx > prev.px)) return KeyboardEstimate(metricsPx, false)
        if (overlapPx <= 0) return prev
        if (overlapPx >= windowHeightPx) {
            val lifted = overlapPx + bottomOffsetPx + windowHeightPx / 2
            return if (lifted > prev.px) KeyboardEstimate(lifted, true) else prev
        }
        val exact = overlapPx + bottomOffsetPx
        return if (prev.provisional || exact > prev.px) KeyboardEstimate(exact, false) else prev
    }

    /** Frist nach Tippmodus-Start, in der die Tastatur eine Ueberlappung melden muss, bevor das Panel nach oben ausweicht. */
    const val KEYBOARD_WAIT_MS = 600L

    /** Rueckfall nach oben NUR, wenn nach der Wartezeit weder Listener noch Metriken eine Hoehe geliefert haben. */
    fun needsTopFallback(typing: Boolean, knownPx: Int, metricsPx: Int, waitedMs: Long, waitMs: Long): Boolean =
        typing && knownPx <= 0 && metricsPx <= 0 && waitedMs >= waitMs

    /** Inhaltsgrenze: im Rueckfall oben fest (Prozentgrenze des Fensters), sonst aus dem Platz ueber der Unterkante. */
    fun contentCap(screen: ScreenArea, bottomPx: Int, chromePx: Int, minPx: Int, topFallbackActive: Boolean): Int =
        if (topFallbackActive) Int.MAX_VALUE else contentLimit(screen, bottomPx, chromePx, minPx)

    /** Losgelassen nahe „unten mittig“ (innerhalb [snapPx] in beiden Richtungen): rastet ein, die Nutzerposition entfaellt. */
    fun snapsToDefault(user: UserSpot, screen: ScreenArea, widthPx: Int, gapPx: Int, snapPx: Int): Boolean {
        val centerLeft = (screen.width - widthPx) / 2 + (screen.insets.left - screen.insets.right) / 2
        val defaultBottom = screen.insets.bottom + gapPx
        return kotlin.math.abs(user.left - centerLeft) <= snapPx && kotlin.math.abs(user.bottom - defaultBottom) <= snapPx
    }

    /** Linke Kante des Panels auf dem Bildschirm (Startpunkt fuer das Ziehen, auch aus der Mitte heraus). */
    fun leftOf(spot: PanelSpot, screen: ScreenArea, widthPx: Int): Int =
        if (spot.centered) (screen.width - widthPx) / 2 + spot.x else spot.x

    /** Neue Nutzerposition beim Ziehen: nach rechts/unten positives dx/dy; geklemmt innerhalb der Systemleisten. */
    fun dragged(startLeft: Int, startBottom: Int, dx: Int, dy: Int, screen: ScreenArea, width: Int, height: Int): UserSpot =
        UserSpot(
            (startLeft + dx).coerceIn(minLeft(screen), maxLeft(screen, width)),
            (startBottom - dy).coerceIn(minBottom(screen), maxBottom(screen, height)),
        )
}

/**
 * Inhalt des Panels ist immer NUR der aktuelle Zug (#134): eine neue Frage leert Antwort und Karten des vorigen Zugs.
 * Beginnt das Zuhoeren neu und es steht nichts Vorheriges mehr da, wird auch die alte Mitschrift verworfen
 * (sonst stuende sie als „Live-Text“ in der Pille).
 */
data class PanelContent(val transcript: String, val answer: CharSequence, val cardCount: Int) {
    fun afterUtterance(text: String) = PanelContent(text, "", 0)

    /** Teil-Mitschrift: nur die Mitschrift aendert sich, die Antwort bleibt (erst eine fertige Aeusserung leert sie). */
    fun afterPartial(text: String) = copy(transcript = text)

    fun afterListeningStarted(): PanelContent =
        if (answer.isBlank() && cardCount == 0) PanelContent("", answer, 0) else this
}

/** Bekannte Tastaturhoehe der Tippsitzung; [provisional] = nur Untergrenze/angehoben, eine exakte Messung ersetzt sie. */
data class KeyboardEstimate(val px: Int = 0, val provisional: Boolean = false) {
    companion object { val NONE = KeyboardEstimate() }
}
