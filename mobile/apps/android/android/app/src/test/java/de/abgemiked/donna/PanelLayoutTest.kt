package com.yourcompany.donna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #134 P7: Pille im Ruhezustand, Karte erst bei Inhalt; welche Bedienelemente wann da sind (reine Regeln). */
class PanelLayoutTest {
    private fun mode(
        state: OverlayState = OverlayState.LISTENING,
        answer: Boolean = false,
        cards: Boolean = false,
        typing: Boolean = false,
    ) = PanelLayoutMode.of(state, answer, cards, typing)

    @Test
    fun `ohne Inhalt bleibt es die schlanke Pille - in jedem Zustand ausser Sprechen und Denken`() {
        for (s in OverlayState.values()) {
            if (s == OverlayState.SPEAKING || s == OverlayState.THINKING) continue
            assertEquals(s.name, PanelLayoutMode.COMPACT, mode(state = s))
        }
    }

    @Test
    fun `Antwort, Karten oder Tippmodus machen daraus die Karte`() {
        assertEquals(PanelLayoutMode.CARD, mode(answer = true))
        assertEquals(PanelLayoutMode.CARD, mode(cards = true))
        assertEquals(PanelLayoutMode.CARD, mode(typing = true))
    }

    @Test
    fun `Sprechen und Denken zeigen die Karte, Zuhoeren mit Mitschrift bleibt die eine Zeile`() {
        assertEquals(PanelLayoutMode.CARD, mode(state = OverlayState.SPEAKING))
        assertEquals(PanelLayoutMode.CARD, mode(state = OverlayState.THINKING))
        // die Mitschrift ist kein Parameter der Entscheidung mehr: sie oeffnet nie die Karte
        assertEquals(PanelLayoutMode.COMPACT, mode(state = OverlayState.LISTENING))
        assertEquals(PanelLayoutMode.COMPACT, mode(state = OverlayState.FOLLOW_UP))
    }

    @Test
    fun `Zeile der Pille - Live-Text beim Zuhoeren, sonst das Zustandswort`() {
        val c = PanelLayoutMode.COMPACT
        val word = PanelLayoutRules.StatusLine.STATE_WORD
        val live = PanelLayoutRules.StatusLine.TRANSCRIPT
        assertEquals(word, PanelLayoutRules.statusLine(c, OverlayState.LISTENING, ""))
        assertEquals(word, PanelLayoutRules.statusLine(c, OverlayState.LISTENING, "   "))
        assertEquals(live, PanelLayoutRules.statusLine(c, OverlayState.LISTENING, "hallo"))
        assertEquals(live, PanelLayoutRules.statusLine(c, OverlayState.FOLLOW_UP, "hallo"))
        assertEquals(word, PanelLayoutRules.statusLine(c, OverlayState.IDLE, "hallo"))
        assertEquals(word, PanelLayoutRules.statusLine(c, OverlayState.ERROR, "hallo"))
        assertEquals(word, PanelLayoutRules.statusLine(PanelLayoutMode.CARD, OverlayState.LISTENING, "hallo"))
    }

    @Test
    fun `Stopp nur waehrend Donna spricht, nie im Tippmodus`() {
        assertTrue(PanelLayoutRules.showStop(OverlayState.SPEAKING, typing = false))
        assertFalse(PanelLayoutRules.showStop(OverlayState.SPEAKING, typing = true))
        for (s in OverlayState.values()) if (s != OverlayState.SPEAKING) assertFalse(s.name, PanelLayoutRules.showStop(s, false))
    }

    @Test
    fun `Mikrofon in der Bedienzeile nur in Ruhe, Fehler und Sprechen, nie beim Zuhoeren, Denken oder Tippen`() {
        for (s in listOf(OverlayState.IDLE, OverlayState.ERROR, OverlayState.SPEAKING)) {
            assertTrue(s.name, PanelLayoutRules.showBarMic(typing = false, state = s))
        }
        for (s in listOf(OverlayState.LISTENING, OverlayState.FOLLOW_UP, OverlayState.THINKING, OverlayState.ENDING, OverlayState.ENDED)) {
            assertFalse(s.name, PanelLayoutRules.showBarMic(typing = false, state = s))
        }
        assertFalse(PanelLayoutRules.showBarMic(typing = true, state = OverlayState.IDLE))
    }

    @Test
    fun `Tastatur-Taste nur ausserhalb des Tippmodus und nicht bei Bis gleich und Ende`() {
        assertTrue(PanelLayoutRules.showKeyboardButton(typing = false, state = OverlayState.IDLE))
        assertTrue(PanelLayoutRules.showKeyboardButton(typing = false, state = OverlayState.LISTENING))
        assertFalse(PanelLayoutRules.showKeyboardButton(typing = true, state = OverlayState.IDLE))
        assertFalse(PanelLayoutRules.showKeyboardButton(typing = false, state = OverlayState.ENDING))
        assertFalse(PanelLayoutRules.showKeyboardButton(typing = false, state = OverlayState.ENDED))
    }

    @Test
    fun `Stumm ist Ruhe - die Pille zeigt dann die Mikrofon-Taste (zurueck ins Zuhoeren)`() {
        // „Stumm“ (Benachrichtigungs-Aktion/muteMic) = CancelRequested -> IDLE, siehe OverlayStateMachine
        val muted = OverlayStateMachine(initial = OverlayState.LISTENING).dispatch(OverlayEvent.CancelRequested).state
        assertEquals(OverlayState.IDLE, muted)
        val mode = PanelLayoutMode.of(muted, false, false, false)
        assertEquals(PanelLayoutMode.COMPACT, mode)
        assertTrue(PanelLayoutRules.showBarMic(typing = false, state = muted))
        assertEquals("Stumm", ConversationController.MUTED_STATUS)
    }

    @Test
    fun `nach dem Senden bleibt die Karte, bis die Anfrage laeuft - kein Sprung auf die Pille`() {
        // Zwischen SENT und der Mitschrift: Tippmodus aus, noch keine Mitschrift, Zustand Ruhe
        assertEquals(PanelLayoutMode.COMPACT, mode(state = OverlayState.IDLE))
        assertEquals(PanelLayoutMode.CARD, PanelLayoutMode.of(OverlayState.IDLE, false, false, false, holdCard = true))
        assertFalse(PanelLayoutRules.holdReleasedBy(OverlayState.THINKING))
        assertFalse(PanelLayoutRules.holdReleasedBy(OverlayState.SPEAKING))
        for (s in listOf(OverlayState.IDLE, OverlayState.ERROR, OverlayState.LISTENING, OverlayState.FOLLOW_UP, OverlayState.ENDING, OverlayState.ENDED)) {
            assertTrue(s.name, PanelLayoutRules.holdReleasedBy(s))
        }
    }

    @Test
    fun `Fensterbreite ist fuer Pille und Karte dieselbe feste Zahl - Bildschirm minus 2 x Rand, hoechstens 480 dp`() {
        assertEquals(379, PanelLayoutRules.windowWidthDp(screenWidthDp = 411))
        assertEquals(328, PanelLayoutRules.windowWidthDp(screenWidthDp = 360))
        assertEquals(480, PanelLayoutRules.windowWidthDp(screenWidthDp = 900))
        assertEquals(240, PanelLayoutRules.windowWidthDp(screenWidthDp = 200))
        assertEquals(16, PanelLayoutRules.SIDE_MARGIN_DP)
    }

    @Test
    fun `Zielgroesse mindestens 48 dp`() {
        assertTrue(PanelLayoutRules.TOUCH_MIN_DP >= 48)
        // gleiche Orb-Groesse in beiden Darstellungen: die untere Zeile springt beim Wechsel nicht
        assertEquals(PanelLayoutRules.orbDp(PanelLayoutMode.CARD), PanelLayoutRules.orbDp(PanelLayoutMode.COMPACT))
    }

    @Test
    fun `kurze Meldung steht in der Zeile, lange Meldung nur als Kurzwort mit dem vollen Text darueber`() {
        val label = OrbVisuals.LABEL_ERROR
        val short = PanelLayoutRules.statusSplit(label, "Stumm")
        assertEquals("Stumm", short.line)
        assertEquals(null, short.notice)
        val longMsg = "Das hat nicht geklappt, weil keine Verbindung besteht. Pruefe das Netz und versuche es gleich noch einmal ..."
        val split = PanelLayoutRules.statusSplit(label, longMsg)
        assertEquals(label, split.line)
        assertEquals(longMsg, split.notice) // ungekuerzt
        assertTrue(split.line.length <= PanelLayoutRules.SHORT_LINE_MAX)
        // 30 Zeichen passen auf schmalen Geraeten nicht mehr in die Zeile (ca. 13 Zeichen je Zeile, 2 Zeilen): voller Text im Inhalt
        assertEquals("Tippen geht hier gerade nicht.", PanelLayoutRules.statusSplit(label, "Tippen geht hier gerade nicht.").notice)
        assertEquals(null, PanelLayoutRules.statusSplit(label, label).notice)
        assertTrue(PanelLayoutRules.SHORT_LINE_MAX >= label.length && PanelLayoutRules.SHORT_LINE_MAX <= 26)
        assertTrue(PanelLayoutRules.statusSplit(label, "  ").notice == null)
    }

    @Test
    fun `Fehler mit langem Text oeffnet die Karte, ohne Text bleibt es die Pille`() {
        assertEquals(PanelLayoutMode.CARD, PanelLayoutMode.of(OverlayState.ERROR, false, false, false, hasNotice = true))
        assertEquals(PanelLayoutMode.COMPACT, PanelLayoutMode.of(OverlayState.ERROR, false, false, false))
    }
}
