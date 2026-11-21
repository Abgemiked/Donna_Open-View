package com.yourcompany.donna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #134 P7: Pille im Ruhezustand, Karte erst bei Inhalt; welche Bedienelemente wann da sind (reine Regeln). */
class PanelLayoutTest {
    private fun mode(
        state: OverlayState = OverlayState.LISTENING,
        transcript: Boolean = false,
        answer: Boolean = false,
        cards: Boolean = false,
        typing: Boolean = false,
    ) = PanelLayoutMode.of(state, transcript, answer, cards, typing)

    @Test
    fun `ohne Inhalt bleibt es die schlanke Pille - in jedem Zustand ausser Sprechen`() {
        for (s in OverlayState.values()) {
            if (s == OverlayState.SPEAKING) continue
            assertEquals(s.name, PanelLayoutMode.COMPACT, mode(state = s))
        }
    }

    @Test
    fun `Mitschrift, Antwort, Karten oder Tippmodus machen daraus die Karte`() {
        assertEquals(PanelLayoutMode.CARD, mode(transcript = true))
        assertEquals(PanelLayoutMode.CARD, mode(answer = true))
        assertEquals(PanelLayoutMode.CARD, mode(cards = true))
        assertEquals(PanelLayoutMode.CARD, mode(typing = true))
    }

    @Test
    fun `Sprechen zeigt immer die Karte, damit die Stopp-Taste da ist`() {
        assertEquals(PanelLayoutMode.CARD, mode(state = OverlayState.SPEAKING))
    }

    @Test
    fun `Stopp nur waehrend Donna spricht, nie im Tippmodus`() {
        assertTrue(PanelLayoutRules.showStop(OverlayState.SPEAKING, typing = false))
        assertFalse(PanelLayoutRules.showStop(OverlayState.SPEAKING, typing = true))
        for (s in OverlayState.values()) if (s != OverlayState.SPEAKING) assertFalse(s.name, PanelLayoutRules.showStop(s, false))
    }

    @Test
    fun `Mikrofon-Taste nur in der Karte, nicht beim Tippen, nur wo sie etwas bewirkt`() {
        for (s in listOf(OverlayState.IDLE, OverlayState.ERROR, OverlayState.SPEAKING)) {
            assertTrue(s.name, PanelLayoutRules.showMic(PanelLayoutMode.CARD, typing = false, state = s))
        }
        for (s in listOf(OverlayState.LISTENING, OverlayState.FOLLOW_UP, OverlayState.THINKING, OverlayState.ENDING, OverlayState.ENDED)) {
            assertFalse(s.name, PanelLayoutRules.showMic(PanelLayoutMode.CARD, typing = false, state = s))
        }
        assertFalse(PanelLayoutRules.showMic(PanelLayoutMode.CARD, typing = true, state = OverlayState.IDLE))
        assertFalse(PanelLayoutRules.showMic(PanelLayoutMode.COMPACT, typing = false, state = OverlayState.IDLE))
    }

    @Test
    fun `Pille zeigt die Mikrofon-Taste in Bereit, Stumm und Fehler, nie beim Zuhoeren oder Denken`() {
        for (s in listOf(OverlayState.IDLE, OverlayState.ERROR)) {
            assertTrue(s.name, PanelLayoutRules.showHeaderMic(PanelLayoutMode.COMPACT, typing = false, state = s))
        }
        for (s in listOf(OverlayState.LISTENING, OverlayState.FOLLOW_UP, OverlayState.THINKING, OverlayState.ENDING, OverlayState.ENDED)) {
            assertFalse(s.name, PanelLayoutRules.showHeaderMic(PanelLayoutMode.COMPACT, typing = false, state = s))
        }
        assertFalse(PanelLayoutRules.showHeaderMic(PanelLayoutMode.CARD, typing = false, state = OverlayState.IDLE))
        assertFalse(PanelLayoutRules.showHeaderMic(PanelLayoutMode.COMPACT, typing = true, state = OverlayState.IDLE))
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
        val mode = PanelLayoutMode.of(muted, false, false, false, false)
        assertEquals(PanelLayoutMode.COMPACT, mode)
        assertTrue(PanelLayoutRules.showHeaderMic(mode, typing = false, state = muted))
        assertEquals("Stumm", ConversationController.MUTED_STATUS)
    }

    @Test
    fun `nach dem Senden bleibt die Karte, bis die Anfrage laeuft - kein Sprung auf die Pille`() {
        // Zwischen SENT und der Mitschrift: Tippmodus aus, noch keine Mitschrift, Zustand Ruhe
        assertEquals(PanelLayoutMode.COMPACT, mode(state = OverlayState.IDLE))
        assertEquals(PanelLayoutMode.CARD, PanelLayoutMode.of(OverlayState.IDLE, false, false, false, false, holdCard = true))
        assertFalse(PanelLayoutRules.holdReleasedBy(OverlayState.THINKING))
        assertFalse(PanelLayoutRules.holdReleasedBy(OverlayState.SPEAKING))
        for (s in listOf(OverlayState.IDLE, OverlayState.ERROR, OverlayState.LISTENING, OverlayState.FOLLOW_UP, OverlayState.ENDING, OverlayState.ENDED)) {
            assertTrue(s.name, PanelLayoutRules.holdReleasedBy(s))
        }
    }

    @Test
    fun `Breitenwechsel haelt den naeheren Rand, Hin und Zurueck landet wieder am Ausgangspunkt`() {
        val screen = 411
        // Pille 320 dp links: linker Rand bleibt, rechts: rechte Kante bleibt
        assertFalse(PanelLayoutRules.anchorRight(x = 10, width = 320, screenWidth = screen))
        assertEquals(10, PanelLayoutRules.xAfterWidthChange(10, 320, 360, anchorRight = false, screenWidth = screen))
        assertTrue(PanelLayoutRules.anchorRight(x = 80, width = 320, screenWidth = screen))
        assertEquals(40, PanelLayoutRules.xAfterWidthChange(80, 320, 360, anchorRight = true, screenWidth = 440))
        // Rundreise ohne Klemmen: exakt zurueck (nicht 40 dp versetzt)
        for (right in listOf(false, true)) {
            val grown = PanelLayoutRules.xAfterWidthChange(60, 320, 360, right, screenWidth = 600)
            assertEquals(right.toString(), 60, PanelLayoutRules.xAfterWidthChange(grown, 360, 320, right, screenWidth = 600))
        }
        // geklemmt: nie ausserhalb des Bildschirms
        assertEquals(0, PanelLayoutRules.xAfterWidthChange(10, 320, 360, anchorRight = true, screenWidth = 411))
        assertEquals(51, PanelLayoutRules.xAfterWidthChange(300, 320, 360, anchorRight = false, screenWidth = 411))
    }

    @Test
    fun `Fensterbreite - Pille schmaler als Karte, beide durch Bildschirmbreite begrenzt`() {
        assertEquals(320, PanelLayoutRules.windowWidthDp(PanelLayoutMode.COMPACT, screenWidthDp = 411))
        assertEquals(360, PanelLayoutRules.windowWidthDp(PanelLayoutMode.CARD, screenWidthDp = 411))
        assertEquals(336, PanelLayoutRules.windowWidthDp(PanelLayoutMode.CARD, screenWidthDp = 360))
        assertEquals(240, PanelLayoutRules.windowWidthDp(PanelLayoutMode.COMPACT, screenWidthDp = 200))
    }

    @Test
    fun `Zielgroesse mindestens 48 dp`() {
        assertTrue(PanelLayoutRules.TOUCH_MIN_DP >= 48)
        assertEquals(40, PanelLayoutRules.orbDp(PanelLayoutMode.COMPACT))
        assertTrue(PanelLayoutRules.orbDp(PanelLayoutMode.CARD) < PanelLayoutRules.orbDp(PanelLayoutMode.COMPACT))
    }
}
