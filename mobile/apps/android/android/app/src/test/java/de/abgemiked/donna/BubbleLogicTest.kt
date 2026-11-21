package com.yourcompany.donna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #133: (1) Der leuchtende Rand folgt der Panelgroesse und bestimmt nie die Fenstergroesse; (2) die schwebende
 * Blase ist abschaltbar (Standard AUS) und das Panel bleibt ohne sie vollstaendig bedienbar.
 */
class BubbleLogicTest {

    // ── Rand (EdgeBounds) ────────────────────────────────────────────────────────
    @Test
    fun `Rand bei fester Vorgabe hat genau die vorgegebene Groesse`() {
        assertEquals(420, EdgeBounds.measured(exact = true, specSize = 420))
        assertEquals(0, EdgeBounds.measured(exact = true, specSize = 0))
    }

    @Test
    fun `Rand beansprucht bei hoechstens-Vorgabe nichts, also nie die verfuegbare Flaeche`() {
        // Fenster WRAP_CONTENT: der Rahmen reicht dem Rand die ganze Bildschirmhoehe als Obergrenze
        assertEquals(0, EdgeBounds.measured(exact = false, specSize = 2400))
        assertEquals(0, EdgeBounds.measured(exact = false, specSize = 0))
    }

    @Test
    fun `Rand ist nie negativ`() {
        assertEquals(0, EdgeBounds.measured(exact = true, specSize = -5))
    }

    // ── Schwebende Blase ─────────────────────────────────────────────────────────
    @Test
    fun `Blase - Schluessel und Standard AUS`() {
        assertEquals("floating_bubble", FloatingBubbleSetting.PREF_KEY)
        assertFalse(FloatingBubbleSetting.DEFAULT)
    }

    @Test
    fun `Blase wird nur bei eingeschalteter Einstellung gezeigt`() {
        assertTrue(BubbleVisibility.showFloatingBubble(true))
        assertFalse(BubbleVisibility.showFloatingBubble(false))
    }

    @Test
    fun `Statustipp weckt das Zuhoeren nur ohne Blase und nie im Tippmodus`() {
        assertTrue(BubbleVisibility.statusTapResumes(bubbleShown = false, typing = false))
        assertFalse(BubbleVisibility.statusTapResumes(bubbleShown = true, typing = false))
        assertFalse(BubbleVisibility.statusTapResumes(bubbleShown = false, typing = true))
    }

    @Test
    fun `Sitzung ist sichtbar, solange Panel oder Blase da ist - sonst muss der Dienst enden`() {
        assertTrue(BubbleVisibility.sessionVisible(panelShown = true, bubbleShown = false))
        assertTrue(BubbleVisibility.sessionVisible(panelShown = false, bubbleShown = true))
        assertTrue(BubbleVisibility.sessionVisible(panelShown = true, bubbleShown = true))
        assertFalse(BubbleVisibility.sessionVisible(panelShown = false, bubbleShown = false))
    }

    @Test
    fun `Zustandstexte sind kurz und ohne Anleitung`() {
        val labels = mapOf(
            "LISTENING" to OrbVisuals.LABEL_LISTENING, "FOLLOW_UP" to OrbVisuals.LABEL_FOLLOW_UP,
            "THINKING" to OrbVisuals.LABEL_THINKING, "SPEAKING" to OrbVisuals.LABEL_SPEAKING,
            "IDLE" to OrbVisuals.LABEL_IDLE, "ERROR" to OrbVisuals.LABEL_ERROR,
            "MUTED" to ConversationController.MUTED_STATUS,
        )
        for ((name, label) in labels) {
            assertTrue("$name: kurz", label.length <= 24)
            assertFalse("$name: keine Anleitung ($label)", Regex("(?i)tipp|blase|klick|drück|versuchen|um weiterzureden").containsMatchIn(label))
            assertFalse("$name: keine Auslassungspunkte ($label)", label.contains("…") || label.endsWith("."))
        }
        assertEquals("Ich höre zu", OrbVisuals.LABEL_LISTENING)
        assertEquals("Donna denkt nach", OrbVisuals.LABEL_THINKING)
        assertEquals("Donna spricht", OrbVisuals.LABEL_SPEAKING)
        assertEquals("Bereit", OrbVisuals.LABEL_IDLE)
    }

    @Test
    fun `Stumm - die Pille bietet die Mikrofon-Taste, um wieder zuzuhoeren`() {
        val muted = OverlayStateMachine(initial = OverlayState.LISTENING).dispatch(OverlayEvent.CancelRequested).state
        assertEquals(OverlayState.IDLE, muted)
        assertTrue(PanelLayoutRules.showHeaderMic(PanelLayoutMode.COMPACT, typing = false, state = muted))
        // ohne Blase fuehrt zusaetzlich der Statustipp zurueck ins Zuhoeren
        assertTrue(BubbleVisibility.statusTapResumes(bubbleShown = false, typing = false))
    }
}
