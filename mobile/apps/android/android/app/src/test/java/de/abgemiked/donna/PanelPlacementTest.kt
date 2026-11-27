package com.yourcompany.donna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #134 Folgepunkt: Panel unten mittig. Reine Positionsregeln (Pixel, ohne Android-Typen). Ursprung: linke untere
 * Bildschirmecke, "bottom" = Abstand der Panel-Unterkante vom echten unteren Bildschirmrand.
 */
class PanelPlacementTest {
    // Hochformat 1080x2340, Statusleiste 80, Gestenleiste 60
    private val portrait = ScreenArea(1080, 2340, SysInsets(0, 80, 0, 60))
    private val gap = 48 // 16 dp bei 3x
    private val w = 960
    private val h = 200

    private fun spot(
        screen: ScreenArea = portrait,
        width: Int = w,
        height: Int = h,
        user: UserSpot? = null,
        keyboard: Int = 0,
        typing: Boolean = false,
        topFallback: Boolean = false,
    ) = PanelPlacement.spot(screen, width, height, user, gap, typing, keyboard, topGapPx = 72, topFallback = topFallback)

    @Test
    fun `Standard ist unten mittig mit Abstand ueber der Gestenleiste`() {
        val s = spot()
        assertTrue(s.centered)
        assertEquals(0, s.x)
        assertEquals(60 + gap, s.bottom)
    }

    @Test
    fun `Standard ohne Systemleiste sitzt mit dem Abstand am Rand`() {
        val s = spot(screen = ScreenArea(1080, 2340, SysInsets(0, 0, 0, 0)))
        assertEquals(gap, s.bottom)
    }

    @Test
    fun `Querformat mit seitlicher Navigationsleiste zentriert im nutzbaren Bereich`() {
        val land = ScreenArea(2340, 1080, SysInsets(0, 60, 120, 0))
        val s = spot(screen = land)
        assertTrue(s.centered)
        assertEquals(-60, s.x) // Mitte des Bereichs liegt 60 px links der Bildschirmmitte
        assertEquals(gap, s.bottom)
    }

    @Test
    fun `Breitenwechsel Pille Karte bleibt zentriert und die Unterkante steht`() {
        val pill = spot(width = 960, height = 150)
        val card = spot(width = 1000, height = 900)
        assertTrue(pill.centered && card.centered)
        assertEquals(pill.x, card.x)
        assertEquals(pill.bottom, card.bottom) // Karte waechst nach oben: Unterkante bleibt
    }

    @Test
    fun `hohe Karte wird oben unter der Statusleiste geklemmt`() {
        assertEquals(108, spot(height = 2000).bottom) // passt: Standardabstand
        assertEquals(60, spot(height = 2200).bottom) // sonst rutscht sie bis an die Gestenleiste, Oberkante an der Statusleiste
        assertEquals(60, spot(height = 2300).bottom) // passt gar nicht: Unterkante ueber der Gestenleiste
    }

    @Test
    fun `vom Nutzer verschobene Position gilt weiter`() {
        val s = spot(user = UserSpot(left = 40, bottom = 500))
        assertFalse(s.centered)
        assertEquals(40, s.x)
        assertEquals(500, s.bottom)
    }

    @Test
    fun `verschobene Position wird geklemmt`() {
        val s = spot(user = UserSpot(left = 900, bottom = 5000))
        assertEquals(1080 - w, s.x)
        assertEquals(2340 - 80 - h, s.bottom)
        val t = spot(user = UserSpot(left = -50, bottom = -10))
        assertEquals(0, t.x)
        assertEquals(60, t.bottom) // nie unter die Gestenleiste
    }

    @Test
    fun `Tippmodus mit bekannter Tastatur sitzt direkt ueber der Tastatur`() {
        val s = spot(typing = true, keyboard = 900)
        assertEquals(900 + gap, s.bottom)
        assertTrue(s.centered)
    }

    @Test
    fun `Tippmodus laesst eine hoehere Nutzerposition stehen`() {
        val s = spot(typing = true, keyboard = 900, user = UserSpot(40, 1500))
        assertEquals(1500, s.bottom)
    }

    @Test
    fun `Tippmodus hebt eine tiefere Nutzerposition ueber die Tastatur`() {
        val s = spot(typing = true, keyboard = 900, user = UserSpot(40, 300))
        assertEquals(900 + gap, s.bottom)
        assertEquals(40, s.x)
    }

    @Test
    fun `Tippmodus ohne bekannte Tastaturhoehe weicht nach oben aus statt verdeckt zu werden`() {
        val s = spot(typing = true, keyboard = 0, topFallback = true)
        assertEquals(2340 - 80 - 72 - h, s.bottom)
    }

    @Test
    fun `nach dem Tippen ist die vorige Position von selbst wieder da`() {
        val user = UserSpot(40, 300)
        val before = spot(user = user)
        spot(typing = true, keyboard = 900, user = user)
        assertEquals(before, spot(user = user, typing = false))
    }

    @Test
    fun `Tastatur hoeher als Platz fuer das Panel klemmt unter die Statusleiste`() {
        val s = spot(typing = true, keyboard = 2200, height = 400)
        assertEquals(2340 - 80 - 400, s.bottom)
    }

    @Test
    fun `Ziehen verschiebt links plus dx und Unterkante minus dy`() {
        val start = PanelPlacement.leftOf(spot(), portrait, w)
        assertEquals(60, start) // zentriert: (1080-960)/2
        val u = PanelPlacement.dragged(start, 108, dx = 20, dy = -50, screen = portrait, width = w, height = h)
        assertEquals(UserSpot(80, 158), u)
    }

    @Test
    fun `Ziehen wird geklemmt`() {
        val u = PanelPlacement.dragged(60, 108, dx = 5000, dy = 9000, screen = portrait, width = w, height = h)
        assertEquals(UserSpot(1080 - w, 60), u)
    }

    @Test
    fun `neue Frage leert Antwort und Karten des vorigen Zugs - nur der aktuelle Zug ist zu sehen`() {
        val old = PanelContent("alte Frage", "alte Antwort", 2)
        assertEquals(PanelContent("neue Frage", "", 0), old.afterUtterance("neue Frage"))
    }

    @Test
    fun `neues Zuhoeren verwirft die alte Mitschrift nur, wenn nichts Vorheriges mehr da ist`() {
        assertEquals(PanelContent("", "", 0), PanelContent("alte Frage", "", 0).afterListeningStarted())
        val shown = PanelContent("alte Frage", "Antwort", 0)
        assertEquals(shown, shown.afterListeningStarted())
        val cards = PanelContent("alte Frage", "", 1)
        assertEquals(cards, cards.afterListeningStarted())
    }

    @Test
    fun `alte gespeicherte Position von vor der Aenderung wird einmalig verworfen`() {
        assertTrue(PanelPlacement.discardStored(0))
        assertTrue(PanelPlacement.discardStored(PanelPlacement.POS_VERSION - 1))
        assertFalse(PanelPlacement.discardStored(PanelPlacement.POS_VERSION))
    }

    @Test
    fun `gespeicherte Position ohne beide Werte gilt nicht`() {
        assertNull(PanelPlacement.stored(null, 5))
        assertNull(PanelPlacement.stored(5, null))
        assertEquals(UserSpot(5, 6), PanelPlacement.stored(5, 6))
    }

    // ── Korrekturrunde Review-Rat ──────────────────────────────────────────────

    @Test
    fun `hochgezogenes Panel, dessen Inhalt waechst, ragt oben nie aus dem Bild`() {
        for (height in listOf(300, 800, 1200, 1900)) {
            val s = spot(user = UserSpot(40, 1500), height = height)
            assertTrue("h=$height", s.bottom + height <= 2340 - 80)
            assertTrue("h=$height", s.bottom >= 60)
        }
        // waechst der Inhalt weiter, wandert die Unterkante nach unten (nie nach oben aus dem Bild)
        val small = spot(user = UserSpot(40, 1500), height = 500).bottom
        val big = spot(user = UserSpot(40, 1500), height = 1500).bottom
        assertTrue(big < small)
    }

    @Test
    fun `Inhaltshoehe wird aus dem Platz ueber der Unterkante abgeleitet - dann scrollt der Inhalt`() {
        assertEquals(1852, PanelPlacement.contentLimit(portrait, bottomPx = 108, chromePx = 300, minPx = 200))
        // hochgezogen: weniger Platz
        assertEquals(2340 - 80 - 1000 - 300, PanelPlacement.contentLimit(portrait, 1000, 300, 200))
        // nie unter das Minimum (dann wandert stattdessen das Panel nach unten, siehe spot)
        assertEquals(200, PanelPlacement.contentLimit(portrait, 2000, 300, 200))
    }

    @Test
    fun `Tippmodus ohne bekannte Hoehe weicht NICHT sofort nach oben aus - das Panel bleibt unten`() {
        val base = spot()
        assertEquals(base, spot(typing = true, keyboard = 0, topFallback = false))
    }

    private fun update(prev: KeyboardEstimate, overlap: Int, offset: Int, metrics: Int = 0, visible: Boolean = true, windowH: Int = h) =
        PanelPlacement.keyboardUpdate(prev, visible, overlap, windowH, offset, metrics)

    @Test
    fun `Ueberlappung kleiner als das Fenster ist exakt - absolut = Ueberlappung plus unterer Versatz`() {
        assertEquals(KeyboardEstimate(220, false), update(KeyboardEstimate.NONE, overlap = 180, offset = 40, windowH = 220))
        assertEquals(KeyboardEstimate.NONE, update(KeyboardEstimate.NONE, overlap = 0, offset = 40))
    }

    @Test
    fun `vollstaendig ueberdeckt ist nur eine Untergrenze - das Panel wird grosszuegig angehoben`() {
        // Ueberlappung = Fensterhoehe: Untergrenze (200 + 108) plus die halbe Fensterhoehe, vorlaeufig
        assertEquals(KeyboardEstimate(200 + 108 + 100, true), update(KeyboardEstimate.NONE, overlap = 200, offset = 108))
        // auch ein Wert groesser als das Fenster ist nur Untergrenze
        assertEquals(KeyboardEstimate(900 + 40 + 100, true), update(KeyboardEstimate.NONE, overlap = 900, offset = 40))
    }

    @Test
    fun `eine exakte Messung ersetzt einen vorlaeufigen Wert, sonst gilt das Maximum`() {
        assertEquals(KeyboardEstimate(800, false), update(KeyboardEstimate(900, true), overlap = 100, offset = 700))
        // exakter Wert bleibt: kleinere Folgemeldung (Emoji-Leiste zu) oder 0 aendert nichts
        assertEquals(KeyboardEstimate(220, false), update(KeyboardEstimate(220, false), overlap = 50, offset = 90))
        assertEquals(KeyboardEstimate(220, false), update(KeyboardEstimate(220, false), overlap = 0, offset = 228))
        assertEquals(KeyboardEstimate(300, false), update(KeyboardEstimate(220, false), overlap = 100, offset = 200))
        // nicht sichtbar: unveraendert
        assertEquals(KeyboardEstimate(220, false), update(KeyboardEstimate(220, false), overlap = 100, offset = 200, visible = false))
    }

    @Test
    fun `Metriken (API 30+) sind eine exakte Quelle`() {
        assertEquals(KeyboardEstimate(900, false), update(KeyboardEstimate.NONE, overlap = 0, offset = 108, metrics = 900))
        assertEquals(KeyboardEstimate(900, false), update(KeyboardEstimate(1200, true), overlap = 0, offset = 108, metrics = 900))
    }

    /** Simuliert Tastatur der Hoehe [kb]: Der Listener meldet die Ueberlappung mit dem Fenster (gedeckelt auf die Fensterhoehe). */
    private fun converge(kb: Int): Pair<KeyboardEstimate, Int> {
        var est = KeyboardEstimate.NONE
        var bottom = spot(typing = true, keyboard = 0).bottom
        for (step in 1..12) {
            val overlap = (kb - bottom).coerceIn(0, h)
            val next = update(est, overlap, bottom)
            if (next == est) return est to bottom
            est = next
            bottom = spot(typing = true, keyboard = est.px).bottom
        }
        return est to bottom
    }

    @Test
    fun `Sequenz - vollstaendig ueberdeckt, Panel konvergiert, am Ende nicht verdeckt und die Hoehe exakt`() {
        // Tippmodus an: Panel bleibt unten, Hoehe 0
        assertEquals(60 + gap, spot(typing = true, keyboard = 0).bottom)
        val (est, bottom) = converge(1000)
        assertEquals(KeyboardEstimate(1000, false), est)
        assertTrue("Panel nicht verdeckt", bottom >= 1000)
        // Folgemeldung 0 (Panel sitzt darueber) aendert die Hoehe nicht
        assertEquals(est, update(est, overlap = 0, offset = bottom))
    }

    @Test
    fun `Sequenz - teilweise ueberlappt - ein Schritt, exakt`() {
        val (est, bottom) = converge(220 + 60) // Tastatur knapp ueber dem Standardabstand
        assertEquals(280, est.px)
        assertTrue(bottom >= 280)
    }

    @Test
    fun `Sequenz - knapp ueber dem Fenster - darf grosszuegig sein, ist aber nie verdeckt`() {
        for (kb in listOf(330, 360, 400, 450, 520, 700, 1500)) {
            val (est, bottom) = converge(kb)
            assertTrue("kb=$kb: nicht verdeckt", bottom >= kb)
            assertTrue("kb=$kb: Hoehe plus Abstand deckt die Tastatur", est.px + gap >= kb)
        }
    }

    @Test
    fun `nie eine Meldung - erst nach der Wartezeit Rueckfall nach oben`() {
        assertFalse(PanelPlacement.needsTopFallback(typing = true, knownPx = 0, metricsPx = 0, waitedMs = 599, waitMs = 600))
        assertTrue(PanelPlacement.needsTopFallback(true, 0, 0, 600, 600))
        // bekannte Hoehe oder Metriken verhindern den Rueckfall
        assertFalse(PanelPlacement.needsTopFallback(true, 220, 0, 5000, 600))
        assertFalse(PanelPlacement.needsTopFallback(true, 0, 900, 5000, 600))
        assertFalse(PanelPlacement.needsTopFallback(false, 0, 0, 5000, 600))
    }

    @Test
    fun `Tippmodus aus - Tastaturhoehe zaehlt nicht mehr, das Panel ist zurueck`() {
        val user = UserSpot(40, 300)
        assertEquals(spot(user = user), spot(user = user, typing = false, keyboard = 900))
        assertEquals(spot(), spot(typing = false, keyboard = 900, topFallback = true))
    }

    @Test
    fun `Inhaltsgrenze im Rueckfall oben ist fest und waechst nicht pro Durchgang`() {
        assertEquals(Int.MAX_VALUE, PanelPlacement.contentCap(portrait, bottomPx = 1988, chromePx = 100, minPx = 120, topFallbackActive = true))
        assertEquals(PanelPlacement.contentLimit(portrait, 108, 100, 120), PanelPlacement.contentCap(portrait, 108, 100, 120, false))
    }

    @Test
    fun `nahe der Standardposition rastet das Panel ein, weit davon nicht`() {
        val snap = 72
        val centerLeft = (1080 - w) / 2 // 60
        val defBottom = 60 + gap
        assertTrue(PanelPlacement.snapsToDefault(UserSpot(centerLeft + 10, defBottom + 20), portrait, w, gap, snap))
        assertTrue(PanelPlacement.snapsToDefault(UserSpot(centerLeft, defBottom), portrait, w, gap, snap))
        assertFalse(PanelPlacement.snapsToDefault(UserSpot(centerLeft + 200, defBottom), portrait, w, gap, snap))
        assertFalse(PanelPlacement.snapsToDefault(UserSpot(centerLeft, defBottom + 500), portrait, w, gap, snap))
    }

    @Test
    fun `die Antwort bleibt beim Weiterhoeren und bei Teil-Mitschrift stehen, erst eine neue Aeusserung leert sie`() {
        val shown = PanelContent("Frage", "Antwort", 1)
        assertEquals(shown, shown.afterListeningStarted())
        assertEquals(PanelContent("Fra", "Antwort", 1), shown.afterPartial("Fra"))
        assertEquals(PanelContent("neue Frage", "", 0), shown.afterPartial("Fra").afterUtterance("neue Frage"))
    }
}
