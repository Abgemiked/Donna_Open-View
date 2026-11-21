package com.yourcompany.donna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #122 Paket C: Tippen in der Blase. Die ENTSCHEIDUNGEN (wann darf das Panel Fokus haben, wann wird zurueckgestellt,
 * was wird gesendet) stehen in reinen Funktionen; Fenster, Tastatur und Fokus selbst sind nur am Geraet pruefbar.
 */
class PanelFocusTest {

    // ── Fenster-Beschreibung ────────────────────────────────────────────────────

    @Test
    fun `ohne Tippmodus ist das Panel nicht fokussierbar und das Fenster steht wie bisher`() {
        val s = PanelFocus.spec(typing = false)
        assertFalse(s.focusable)
        assertFalse(s.moveToTop)
        assertEquals(PanelFocus.ANSWER_MAX_PERCENT_NORMAL, s.answerMaxPercent)
    }

    @Test
    fun `im Tippmodus ist das Panel fokussierbar, liegt oben und gibt der Tastatur Platz`() {
        val s = PanelFocus.spec(typing = true)
        assertTrue(s.focusable)
        assertTrue(s.moveToTop)
        assertTrue(s.answerMaxPercent < PanelFocus.ANSWER_MAX_PERCENT_NORMAL)
    }

    // ── Jeder Ausgang stellt zurueck ────────────────────────────────────────────

    @Test
    fun `jeder Ausgang beendet den Tippmodus, nur OPEN startet ihn`() {
        for (e in PanelTypingEvent.values()) {
            val after = PanelFocus.typingAfter(e)
            assertEquals("Ereignis $e", e == PanelTypingEvent.OPEN, after)
            if (e != PanelTypingEvent.OPEN) assertFalse("nach $e nicht fokussierbar", PanelFocus.spec(after).focusable)
        }
    }

    @Test
    fun `ohne Fokus binnen der Frist ist der Tippmodus ein Fehler, mit Fokus oder ohne Tippmodus nicht`() {
        assertEquals(1_500L, PanelFocus.FOCUS_TIMEOUT_MS)
        assertTrue(PanelFocus.focusMissing(typing = true, windowHasFocus = false))
        assertFalse(PanelFocus.focusMissing(typing = true, windowHasFocus = true))
        assertFalse(PanelFocus.focusMissing(typing = false, windowHasFocus = false))
        assertEquals("Tippen geht hier gerade nicht.", PanelFocus.FOCUS_FAILED_STATUS)
    }

    @Test
    fun `waehrend des Tippens kein Stimm-Unterbrechen und keine Sprachausgabe der Antwort`() {
        assertFalse(PanelFocus.voiceBargeInAllowed(typingActive = true))
        assertTrue(PanelFocus.voiceBargeInAllowed(typingActive = false))
        assertFalse(PanelFocus.speaksAnswer(typingActive = true))
        assertTrue(PanelFocus.speaksAnswer(typingActive = false))
    }

    @Test
    fun `eine nur gezeigte Antwort beendet die Runde ohne Weiterhoeren`() {
        val m = OverlayStateMachine(conversationMode = true)
        m.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = false))
        m.dispatch(OverlayEvent.ResponseStarted)
        val f = m.dispatch(OverlayEvent.ResponseFinished(spokeAnything = false, hasPendingCards = false, typingActive = true))
        assertEquals(OverlayState.IDLE, f.state)
    }

    @Test
    fun `die geforderten Ausgaenge existieren`() {
        val names = PanelTypingEvent.values().map { it.name }.toSet()
        for (n in listOf("SENT", "KEYBOARD_CLOSED", "BACK", "PANEL_CLOSED", "CONVERSATION_ENDED", "SERVICE_ENDED",
            "RECORDING_STARTED", "FOCUS_LOST", "ERROR")) assertTrue("fehlt: $n", n in names)
    }

    @Test
    fun `Zustandswechsel beendet den Tippmodus bei Zuhoeren und bei Gespraechsende`() {
        assertEquals(PanelTypingEvent.RECORDING_STARTED, PanelFocus.exitForState(true, OverlayState.LISTENING))
        assertEquals(PanelTypingEvent.RECORDING_STARTED, PanelFocus.exitForState(true, OverlayState.FOLLOW_UP))
        assertEquals(PanelTypingEvent.CONVERSATION_ENDED, PanelFocus.exitForState(true, OverlayState.ENDING))
        assertEquals(PanelTypingEvent.CONVERSATION_ENDED, PanelFocus.exitForState(true, OverlayState.ENDED))
        for (s in listOf(OverlayState.IDLE, OverlayState.THINKING, OverlayState.SPEAKING, OverlayState.ERROR)) {
            assertNull("$s", PanelFocus.exitForState(true, s))
        }
        // ohne Tippmodus gibt es nichts zurueckzustellen
        for (s in OverlayState.values()) assertNull(PanelFocus.exitForState(false, s))
    }

    @Test
    fun `Tippen laesst sich waehrend Bis gleich und nach dem Ende nicht oeffnen`() {
        assertFalse(PanelFocus.canOpen(OverlayState.ENDING))
        assertFalse(PanelFocus.canOpen(OverlayState.ENDED))
        for (s in listOf(OverlayState.IDLE, OverlayState.LISTENING, OverlayState.FOLLOW_UP, OverlayState.THINKING,
            OverlayState.SPEAKING, OverlayState.ERROR)) assertTrue("$s", PanelFocus.canOpen(s))
    }

    @Test
    fun `beim Oeffnen stoppt Mikrofon und Sprachausgabe, ein laufendes Denken nicht`() {
        assertTrue(PanelFocus.stopsAudioOnOpen(OverlayState.LISTENING))
        assertTrue(PanelFocus.stopsAudioOnOpen(OverlayState.FOLLOW_UP))
        assertTrue(PanelFocus.stopsAudioOnOpen(OverlayState.SPEAKING))
        assertFalse(PanelFocus.stopsAudioOnOpen(OverlayState.THINKING))
        assertFalse(PanelFocus.stopsAudioOnOpen(OverlayState.IDLE))
        assertFalse(PanelFocus.stopsAudioOnOpen(OverlayState.ERROR))
    }

    // ── Text-Uebergabe ──────────────────────────────────────────────────────────

    @Test
    fun `leerer oder nur Leerraum-Text wird nicht gesendet`() {
        assertNull(TypedInput.prepare(null))
        assertNull(TypedInput.prepare(""))
        assertNull(TypedInput.prepare("   \n\t "))
    }

    @Test
    fun `Text wird beschnitten und auf die Hoechstlaenge begrenzt`() {
        assertEquals("Wie wird das Wetter?", TypedInput.prepare("  Wie wird das Wetter?  "))
        val long = "a".repeat(TypedInput.MAX_CHARS + 50)
        assertEquals(TypedInput.MAX_CHARS, TypedInput.prepare(long)!!.length)
    }

    // ── Automat bei getippter Frage ─────────────────────────────────────────────

    @Test
    fun `getippter Text nach dem Weiterhoeren zaehlt nicht als Weiterhoeren und Ende-Woerter greifen nicht`() {
        val m = OverlayStateMachine(conversationMode = true)
        m.dispatch(OverlayEvent.MicRequested)
        m.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = true, genuineSpeech = true))
        m.dispatch(OverlayEvent.ResponseStarted)
        m.dispatch(OverlayEvent.ResponseFinished(spokeAnything = true, hasPendingCards = false))
        assertEquals(OverlayState.FOLLOW_UP, m.state)
        // Tippmodus: Zuhoeren abbrechen (kein Gespraechsende), dann getippt senden
        val c = m.dispatch(OverlayEvent.CancelRequested)
        assertEquals(OverlayState.IDLE, c.state)
        assertNull(m.endReason)
        val t = m.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = false))
        assertEquals(OverlayState.THINKING, t.state)
        assertFalse(m.followUpListen)
        assertFalse(ConversationEnd.shouldEnd(m.followUpListen, "tschüss"))
        assertEquals(0, m.stats.followUps)
        assertEquals(2, m.stats.turns)
    }

    @Test
    fun `nach getippter Frage hoert Donna wie nach einer gesprochenen weiter, ausser es wird gerade getippt`() {
        val m = OverlayStateMachine(conversationMode = true)
        m.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = false))
        m.dispatch(OverlayEvent.ResponseStarted)
        val f = m.dispatch(OverlayEvent.ResponseFinished(spokeAnything = true, hasPendingCards = false, typingActive = false))
        assertEquals(OverlayState.FOLLOW_UP, f.state)
        assertTrue(f.effects.contains(OverlayEffect.StartListeningAfterEcho))

        val m2 = OverlayStateMachine(conversationMode = true)
        m2.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = false))
        m2.dispatch(OverlayEvent.ResponseStarted)
        val g = m2.dispatch(OverlayEvent.ResponseFinished(spokeAnything = true, hasPendingCards = false, typingActive = true))
        assertEquals(OverlayState.IDLE, g.state)
    }

    @Test
    fun `Abbrechen des Zuhoerens fuer das Tippen erzeugt weder Ende noch Zusammenfassung`() {
        val m = OverlayStateMachine(conversationMode = true)
        m.dispatch(OverlayEvent.MicRequested)
        val c = m.dispatch(OverlayEvent.CancelRequested)
        assertEquals(OverlayState.IDLE, c.state)
        assertNull(m.endReason)
        assertNull(m.takeSummary())
        assertEquals(0, m.stats.expiredWindows)
        assertEquals(0, m.stats.interruptions)
    }
}
