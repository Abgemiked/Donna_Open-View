package com.yourcompany.donna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #122 Review-Korrekturen: Ende-Woerter bei offener Rueckfrage, „halt“, neue Phrasen, ENDING-Randfaelle. */
class ConversationEndReviewTest {

    private fun src(name: String) = java.io.File("src/main/java/com/yourcompany/donna/$name").readText()

    private fun ends(t: String, pending: Boolean = false) =
        ConversationEnd.shouldEnd(followUpListen = true, text = t, confirmationPending = pending)

    // ── Punkt 1: offene Rueckfrage / Karte ───────────────────────────────────

    @Test fun `bei offener Rueckfrage beenden nur eindeutige Abschiede`() {
        for (t in listOf(
            "tschüss", "Tschüssi", "tschüs", "tschö", "ciao", "tschau", "das war's", "das war alles", "das ist alles",
            "bis gleich", "bis später", "bis bald", "auf wiedersehen", "auf wiederhören", "gute Nacht", "okay, tschüss",
        )) assertTrue("'$t' beendet auch bei offener Rueckfrage", ends(t, pending = true))
    }

    @Test fun `bei offener Rueckfrage gehen mehrdeutige Woerter an die Bestaetigungs-Logik`() {
        for (t in listOf(
            "stopp", "stop", "halt", "ende", "schluss", "fertig", "das reicht", "nein danke", "nichts weiter", "stopp danke",
            "ich bin fertig",
        )) assertFalse("'$t' darf bei offener Rueckfrage NICHT beenden", ends(t, pending = true))
    }

    @Test fun `ohne offene Rueckfrage gelten alle Woerter wie bisher`() {
        for (t in listOf(
            "stopp", "stop", "halt", "ende", "schluss", "fertig", "das reicht", "nein danke", "nichts weiter", "tschüss",
            "gute nacht", "das ist alles",
        )) assertTrue("'$t' beendet ohne Rueckfrage", ends(t, pending = false))
        assertFalse("nur im Weiterhoeren", ConversationEnd.shouldEnd(false, "tschüss", true))
        assertFalse(ConversationEnd.shouldEnd(false, "tschüss"))
    }

    @Test fun `Rueckfrage offen = Antwortfenster ODER wartende Karte`() {
        assertFalse(ConversationEnd.confirmationPending(replyWindowOpen = false, cardsPending = false))
        assertTrue(ConversationEnd.confirmationPending(replyWindowOpen = true, cardsPending = false))
        assertTrue(ConversationEnd.confirmationPending(replyWindowOpen = false, cardsPending = true))
    }

    @Test fun `beide Schleifen reichen den Rueckfrage-Zustand an die gemeinsame Regel`() {
        val c = src("ConversationController.kt")
        assertTrue(c.contains("ConversationEnd.confirmationPending(voiceReplyWindow.isOpen, host.hasPendingActionCards())"))
        assertTrue(c.contains("ConversationEnd.shouldEnd(machine.followUpListen, text, confirmationPending)"))
        val a = src("VoiceInputActivity.kt")
        assertTrue(a.contains("ConversationEnd.confirmationPending(replyWindowOpen = false, cardsPending = hasPendingCards())"))
        assertTrue(a.contains("ConversationEnd.shouldEnd(machine.followUpListen, text, confirmationPending)"))
    }

    // ── Punkt 2: „halt“ ──────────────────────────────────────────────────────

    @Test fun `halt nur allein oder mit hoeflichem Rand`() {
        for (t in listOf("halt", "Halt!", "halt danke", "okay halt", "halt donna", "halt bitte", "ok, halt, danke"))
            assertTrue("'$t'", ends(t))
        for (t in listOf("ja halt", "na halt", "halt so", "also halt", "dann halt", "so halt", "halt ja", "ja halt danke"))
            assertFalse("'$t'", ends(t))
    }

    @Test fun `andere Phrasen vertragen weiter die allgemeinen Fuellwoerter`() {
        assertTrue(ends("ja stopp"))
        assertTrue(ends("also tschüss"))
        assertTrue(ends("ja, das war's dann"))
    }

    // ── Punkt 3: neue Phrasen und Negativfaelle ──────────────────────────────

    @Test fun `neue Phrasen beenden`() {
        for (t in listOf("tschüs", "Tschüs!", "tschö", "Tschö", "das ist alles", "nichts weiter", "gute nacht", "auf wiederhören", "nein danke", "Nein, danke."))
            assertTrue("'$t'", ends(t))
    }

    @Test fun `Negativfaelle beenden nicht`() {
        for (t in listOf("nein", "nein danke das andere", "gute nacht geschichte", "das ist alles falsch", "nichts weiter als das", "nein halt"))
            assertFalse("'$t'", ends(t))
        assertFalse("nein ist kein Fuellwort", ConversationEnd.isEndPhrase("nein"))
    }

    // ── Punkt 4: Failed in ENDING ────────────────────────────────────────────

    private fun inEnding(): OverlayStateMachine {
        val m = OverlayStateMachine(conversationMode = true)
        m.dispatch(OverlayEvent.MicRequested)
        m.dispatch(OverlayEvent.SpeechEnded(false))
        m.dispatch(OverlayEvent.TextSubmitted(fromRecognizer = true, genuineSpeech = true))
        m.dispatch(OverlayEvent.ResponseStarted)
        m.dispatch(OverlayEvent.ResponseFinished(true, false))
        assertEquals(OverlayState.ENDING, m.dispatch(OverlayEvent.EndPhraseHeard).state)
        return m
    }

    @Test fun `spaetes Failed in ENDING wird ignoriert und haelt das Schliessen nicht auf`() {
        val m = inEnding()
        val t = m.dispatch(OverlayEvent.Failed(OverlayErrors.fromHttp(502)))
        assertEquals(OverlayState.ENDING, t.state)
        assertTrue(t.effects.isEmpty())
        assertEquals(OverlayState.ENDING, m.state)
        val e = m.dispatch(OverlayEvent.EndingElapsed)
        assertEquals(OverlayState.ENDED, e.state)
        assertTrue(OverlayEffect.CloseConversation in e.effects)
    }

    // ── Punkt 5: Tipp waehrend „Bis gleich.“ ─────────────────────────────────

    @Test fun `Tipp in ENDING fuehrt zurueck ins Zuhoeren, der Ende-Zeitgeber schliesst danach nicht mehr`() {
        val m = inEnding()
        val t = m.dispatch(OverlayEvent.MicRequested)
        assertEquals(OverlayState.LISTENING, t.state)
        assertTrue(OverlayEffect.StartListening in t.effects)
        val late = m.dispatch(OverlayEvent.EndingElapsed)
        assertEquals(OverlayState.LISTENING, late.state)
        assertFalse(OverlayEffect.CloseConversation in late.effects)
    }

    @Test fun `Blase nimmt ENDING in resumeIfIdle an und raeumt den Ende-Zeitgeber ab`() {
        val c = src("ConversationController.kt")
        val resume = c.substringAfter("fun resumeIfIdle(): Boolean").substringBefore("/** Gesprächsschleife anhalten")
        assertTrue(resume.contains("OverlayState.ENDING"))
        val fire = c.substringAfter("private fun fire(").substringBefore("private fun fail(")
        assertTrue("Zeitgeber wird beim Verlassen von ENDING abgeraeumt",
            fire.contains("if (t.state != OverlayState.ENDING) ui.removeCallbacks(endingRunnable)"))
    }

    // ── Punkt 6 / 7: Animator-Lebenszyklus, Orb-Cache (Quelltext-Vertrag) ────

    @Test fun `Blasen-Puls haelt bei unsichtbarem Fenster an und setzt nur beim Aufnehmen fort`() {
        val s = src("LiveBubbleService.kt")
        assertTrue(s.contains("override fun onWindowVisibilityChanged"))
        assertTrue(s.contains("override fun onDetachedFromWindow"))
        assertTrue(s.contains("bubbleWindowVisible = visible"))
        // Pulsieren nur: Zustand nimmt auf UND Fenster sichtbar (gleiches Kriterium wie Rand/Orb); Wiedererscheinen -> applyVisual
        assertTrue(s.contains("updateBubblePulse(v.bubblePulse && bubbleView != null && bubbleWindowVisible)"))
        assertTrue(s.contains("if (visible) applyVisual() else updateBubblePulse(false)"))
    }

    @Test fun `Orb berechnet die Abbildung beim Zustandswechsel, je Frame nur den Ring`() {
        val o = src("DonnaOrbView.kt")
        val draw = o.substringAfter("override fun onDraw(canvas: Canvas)")
        assertFalse("kein OrbVisuals.of je Frame", draw.contains("OrbVisuals.of("))
        assertTrue(o.contains("OrbVisuals.of("))
        assertTrue(draw.contains("OrbVisuals.ringFraction(") || draw.contains("ringSource"))
    }

    @Test fun `Ring-Anteil ist als eigene reine Funktion verfuegbar`() {
        assertEquals(0.4f, OrbVisuals.ringFraction(OverlayState.FOLLOW_UP, true, 0.4f)!!, 0.0001f)
        assertEquals(1f, OrbVisuals.ringFraction(OverlayState.FOLLOW_UP, false, 0.4f)!!, 0f)
        assertEquals(null, OrbVisuals.ringFraction(OverlayState.LISTENING, true, 0.4f))
    }
}
