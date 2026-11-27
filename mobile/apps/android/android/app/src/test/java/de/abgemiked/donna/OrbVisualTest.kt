package com.yourcompany.donna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #122 Paket B: reine Darstellungsentscheidungen (Zustand -> Orb/Rand/Ring/Haptik/Benachrichtigung).
 * Das Zeichnen selbst (Views, Animator) ist nur am Geraet pruefbar; hier steht, WAS gezeigt wird.
 */
class OrbVisualTest {

    private fun v(s: OverlayState, anim: Boolean = true, frac: Float = 1f) = OrbVisuals.of(s, anim, frac)

    // ── Zustand -> Anzeige (Animationen an) ─────────────────────────────────────

    @Test
    fun `Zuhoeren pulsiert mit Lautstaerke, Akzentfarbe, Rand an und rotierend, Blase pulsiert`() {
        val l = v(OverlayState.LISTENING)
        assertEquals(OrbTone.ACCENT, l.tone)
        assertEquals(OrbMotion.LEVEL, l.motion)
        assertTrue(l.edgeGlow)
        assertTrue(l.edgeRotating)
        assertTrue(l.bubblePulse)
        assertNull("kein Ring im ersten Zuhoeren", l.ringFraction)
    }

    @Test
    fun `Weiterhoeren zeigt zusaetzlich den ablaufenden Ring`() {
        val f = v(OverlayState.FOLLOW_UP, frac = 0.4f)
        assertEquals(OrbTone.ACCENT, f.tone)
        assertEquals(OrbMotion.LEVEL, f.motion)
        assertTrue(f.edgeGlow && f.edgeRotating && f.bubblePulse)
        assertEquals(0.4f, f.ringFraction!!, 0.0001f)
        assertEquals("Ich höre weiter zu", f.label)
    }

    @Test
    fun `Denken atmet, Sprechen pulsiert im Takt - beide ohne Rand und ohne Blasenpuls`() {
        val t = v(OverlayState.THINKING)
        assertEquals(OrbTone.ACCENT2, t.tone)
        assertEquals(OrbMotion.BREATHE, t.motion)
        val s = v(OverlayState.SPEAKING)
        assertEquals(OrbTone.ACCENT, s.tone)
        assertEquals(OrbMotion.SPEAK_PULSE, s.motion)
        for (x in listOf(t, s)) {
            assertFalse(x.edgeGlow); assertFalse(x.edgeRotating); assertFalse(x.bubblePulse); assertNull(x.ringFraction)
        }
    }

    @Test
    fun `Ende ist grau und still, Fehler bernstein mit Schuetteln`() {
        for (s in listOf(OverlayState.IDLE, OverlayState.ENDING, OverlayState.ENDED)) {
            val x = v(s)
            assertEquals(s.name, OrbTone.MUTED, x.tone)
            assertEquals(s.name, OrbMotion.NONE, x.motion)
            assertFalse(s.name, x.edgeGlow); assertFalse(x.bubblePulse); assertNull(x.ringFraction)
        }
        val e = v(OverlayState.ERROR)
        assertEquals(OrbTone.ERROR, e.tone)
        assertEquals(OrbMotion.SHAKE, e.motion)
        assertFalse(e.edgeGlow)
    }

    @Test
    fun `Rand und Blasenpuls genau dann, wenn das Mikrofon aufnimmt`() {
        for (s in OverlayState.values()) {
            val x = v(s)
            assertEquals(s.name, s.isListening, x.edgeGlow)
            assertEquals(s.name, s.isListening, x.bubblePulse)
            assertEquals(s.name, s.isListening, x.edgeRotating)
        }
    }

    @Test
    fun `jeder Zustand hat einen deutschen Text`() {
        for (s in OverlayState.values()) assertTrue(s.name, v(s).label.isNotBlank())
        assertEquals(ConversationTexts.BYE, v(OverlayState.ENDING).label)
        assertEquals(ConversationTexts.BYE, v(OverlayState.ENDED).label)
        assertEquals("Ich höre zu", v(OverlayState.LISTENING).label)
    }

    // ── Ring ────────────────────────────────────────────────────────────────────

    @Test
    fun `Ring nur im Weiterhoeren und auf 0 bis 1 begrenzt`() {
        assertEquals(1f, v(OverlayState.FOLLOW_UP, frac = 1f).ringFraction!!, 0f)
        assertEquals(0f, v(OverlayState.FOLLOW_UP, frac = 0f).ringFraction!!, 0f)
        assertEquals(1f, v(OverlayState.FOLLOW_UP, frac = 7f).ringFraction!!, 0f)
        assertEquals(0f, v(OverlayState.FOLLOW_UP, frac = -3f).ringFraction!!, 0f)
        assertEquals(0f, v(OverlayState.FOLLOW_UP, frac = Float.NaN).ringFraction!!, 0f)
        for (s in OverlayState.values().filter { it != OverlayState.FOLLOW_UP }) assertNull(s.name, v(s, frac = 0.5f).ringFraction)
    }

    @Test
    fun `Ring-Anteil kommt aus FollowUpWindow remainingMs - ein Zeitgeber`() {
        // Fenster 10 s, Start bei 1000 ms
        assertEquals(1f, FollowUpRing.fraction(10_000, 1_000, 1_000), 0.0001f)
        assertEquals(0.5f, FollowUpRing.fraction(10_000, 1_000, 6_000), 0.0001f)
        assertEquals(0f, FollowUpRing.fraction(10_000, 1_000, 11_000), 0.0001f)
        assertEquals(0f, FollowUpRing.fraction(10_000, 1_000, 99_000), 0.0001f)
        // dieselbe Rechnung wie die Steuerung
        val now = 7_300L
        assertEquals(
            FollowUpWindow.remainingMs(15_000, 2_000, now).toFloat() / 15_000f,
            FollowUpRing.fraction(15_000, 2_000, now), 0.0001f,
        )
    }

    @Test
    fun `Ring bevor das Fenster begonnen hat (Echo-Schutz) ist voll, ungueltiges Fenster ebenso`() {
        assertEquals(1f, FollowUpRing.fraction(10_000, 0L, 5_000), 0f)
        assertEquals(1f, FollowUpRing.fraction(0L, 1_000, 2_000), 0f)
    }

    // ── Animationen entfernen ───────────────────────────────────────────────────

    @Test
    fun `ohne Animationen keine Bewegung, kein Rotieren, kein Blasenpuls - Zustand bleibt sichtbar`() {
        for (s in OverlayState.values()) {
            val x = v(s, anim = false, frac = 0.3f)
            assertEquals(s.name, OrbMotion.NONE, x.motion)
            assertFalse(s.name, x.edgeRotating)
            assertFalse(s.name, x.bubblePulse)
            assertEquals("Rand bleibt als statischer Zustand sichtbar: " + s.name, s.isListening, x.edgeGlow)
            assertEquals(v(s).tone, x.tone)
            assertEquals(v(s).label, x.label)
        }
    }

    @Test
    fun `ohne Animationen bleibt der Ring voll - keine Zeitgeber, kein Dauer-Neuzeichnen`() {
        assertEquals(1f, v(OverlayState.FOLLOW_UP, anim = false, frac = 0.2f).ringFraction!!, 0f)
        assertNull(v(OverlayState.LISTENING, anim = false).ringFraction)
    }

    // ── Regenbogen-Rand, Blasenpuls ─────────────────────────────────────────────

    @Test
    fun `Regenbogen-Farben sind benannt, deckend und schliessen den Kreis`() {
        val c = RainbowEdge.COLORS
        assertTrue(c.size >= 6)
        assertEquals("nahtloser Sweep: erste = letzte Farbe", c.first(), c.last())
        for (x in c) assertEquals(0xFF, (x ushr 24) and 0xFF)
        assertEquals(c.size - 1, c.toSet().size) // genau die Schlussfarbe ist doppelt
    }

    @Test
    fun `Rotation ist langsam und periodisch`() {
        assertEquals(0f, RainbowEdge.rotationDeg(0), 0.001f)
        assertEquals(180f, RainbowEdge.rotationDeg(RainbowEdge.PERIOD_MS / 2), 0.001f)
        assertEquals(0f, RainbowEdge.rotationDeg(RainbowEdge.PERIOD_MS), 0.001f)
        assertTrue("langsam: mindestens 4 s je Umlauf", RainbowEdge.PERIOD_MS >= 4_000L)
    }

    @Test
    fun `Blasenpuls bleibt zwischen 1 und Maximum`() {
        assertEquals(1f, BubblePulse.scale(0f), 0.0001f)
        assertEquals(BubblePulse.MAX_SCALE, BubblePulse.scale(0.5f), 0.0001f)
        assertEquals(1f, BubblePulse.scale(1f), 0.0001f)
        assertTrue(BubblePulse.MAX_SCALE in 1.05f..1.25f)
    }

    // ── Haptik ──────────────────────────────────────────────────────────────────

    @Test
    fun `Haptik beim Start des Zuhoerens nach einem Aufruf, nicht bei jedem Zustandswechsel`() {
        val h = HapticCues()
        assertEquals(HapticCue.LISTEN_START, h.onState(OverlayState.LISTENING))
        assertNull(h.onState(OverlayState.THINKING))
        assertNull(h.onState(OverlayState.SPEAKING))
        assertNull("Weiterhoeren: kein Signal", h.onState(OverlayState.FOLLOW_UP))
        assertNull(h.onState(OverlayState.THINKING))
        assertNull(h.onState(OverlayState.SPEAKING))
        assertNull(h.onState(OverlayState.FOLLOW_UP))
    }

    @Test
    fun `Haptik doppelte Meldung desselben Zustands und Unterbrechen per Tipp loesen nichts aus`() {
        val h = HapticCues()
        assertEquals(HapticCue.LISTEN_START, h.onState(OverlayState.LISTENING))
        assertNull(h.onState(OverlayState.LISTENING))
        assertNull(h.onState(OverlayState.SPEAKING))
        assertNull("Unterbrechen (SPEAKING -> LISTENING) ist kein neuer Aufruf", h.onState(OverlayState.LISTENING))
    }

    @Test
    fun `Haptik neuer Aufruf aus Leerlauf oder Fehler signalisiert wieder`() {
        val h = HapticCues()
        h.onState(OverlayState.LISTENING)
        assertNull(h.onState(OverlayState.IDLE))
        assertEquals(HapticCue.LISTEN_START, h.onState(OverlayState.LISTENING))
        assertNull(h.onState(OverlayState.ERROR))
        assertEquals(HapticCue.LISTEN_START, h.onState(OverlayState.LISTENING))
    }

    @Test
    fun `Haptik am Gespraechsende genau einmal`() {
        val h = HapticCues()
        h.onState(OverlayState.LISTENING)
        h.onState(OverlayState.FOLLOW_UP)
        assertEquals(HapticCue.CONVERSATION_END, h.onState(OverlayState.ENDING))
        assertNull(h.onState(OverlayState.ENDED))
        val h2 = HapticCues()
        h2.onState(OverlayState.SPEAKING)
        assertEquals("Ende-Taste: direkt ENDED", HapticCue.CONVERSATION_END, h2.onState(OverlayState.ENDED))
    }

    // ── Benachrichtigung ────────────────────────────────────────────────────────

    @Test
    fun `Benachrichtigung Donna hoert zu waehrend der Aufnahme, sonst wie bisher`() {
        assertEquals("Donna hört zu", BubbleNotification.spec(OverlayState.LISTENING).text)
        assertEquals("Donna hört zu", BubbleNotification.spec(OverlayState.FOLLOW_UP).text)
        for (s in OverlayState.values().filter { !it.isListening }) {
            assertEquals(s.name, "Donna-Gespräch aktiv", BubbleNotification.spec(s).text)
        }
    }

    @Test
    fun `Benachrichtigung Aktionen - Stumm nur beim Zuhoeren, Beenden immer`() {
        assertEquals(listOf(NotificationAction.MUTE, NotificationAction.END), BubbleNotification.spec(OverlayState.LISTENING).actions)
        assertEquals(listOf(NotificationAction.MUTE, NotificationAction.END), BubbleNotification.spec(OverlayState.FOLLOW_UP).actions)
        for (s in OverlayState.values().filter { !it.isListening }) {
            assertEquals(s.name, listOf(NotificationAction.END), BubbleNotification.spec(s).actions)
        }
        assertEquals("Beenden", NotificationAction.END.label)
        assertEquals("Stumm", NotificationAction.MUTE.label)
    }
}
