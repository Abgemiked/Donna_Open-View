package com.yourcompany.donna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM-Unit-Tests für die Orb-Animationsmathematik (UX-02). Das Zeichnen selbst ist nur am Gerät prüfbar. */
class OrbMathTest {

    @Test
    fun `Lautstaerke wird auf 0 bis 1 abgebildet`() {
        assertEquals(0f, OrbMath.levelFromRms(-2f), 0.001f)
        assertEquals(0f, OrbMath.levelFromRms(-10f), 0.001f)
        assertEquals(1f, OrbMath.levelFromRms(10f), 0.001f)
        assertEquals(1f, OrbMath.levelFromRms(40f), 0.001f)
        assertEquals(0.5f, OrbMath.levelFromRms(4f), 0.001f)
        assertEquals(0f, OrbMath.levelFromRms(Float.NaN), 0.001f)
    }

    @Test
    fun `Glaettung steigt schnell und faellt langsam`() {
        val up = OrbMath.smooth(0f, 1f)
        val down = 1f - OrbMath.smooth(1f, 0f)
        assertTrue("Attack ist schneller als Release", up > down)
        assertEquals(1f, OrbMath.smooth(1f, 1f), 0.0001f)
    }

    @Test
    fun `Atmung ist periodisch zwischen 0 und 1`() {
        assertEquals(0f, OrbMath.breathe(0f), 0.001f)
        assertEquals(1f, OrbMath.breathe(0.5f), 0.001f)
        assertEquals(0f, OrbMath.breathe(1f), 0.001f)
    }

    @Test
    fun `Phase aus Zeit und Periode`() {
        assertEquals(0f, OrbMath.phase(0, 1000), 0.001f)
        assertEquals(0.25f, OrbMath.phase(250, 1000), 0.001f)
        assertEquals(0.25f, OrbMath.phase(1250, 1000), 0.001f)
        assertEquals(0f, OrbMath.phase(500, 0), 0.001f)
    }

    @Test
    fun `Schuetteln klingt ab und endet`() {
        assertEquals(0f, OrbMath.shakeOffset(OrbMath.ERROR_SHAKE_MS, 10f), 0.0001f)
        assertEquals(0f, OrbMath.shakeOffset(-5, 10f), 0.0001f)
        val early = (0L until OrbMath.ERROR_SHAKE_MS / 3).maxOf { Math.abs(OrbMath.shakeOffset(it, 10f)) }
        val late = (OrbMath.ERROR_SHAKE_MS * 2 / 3 until OrbMath.ERROR_SHAKE_MS).maxOf { Math.abs(OrbMath.shakeOffset(it, 10f)) }
        assertTrue("Amplitude nimmt ab", early > late)
    }

    @Test
    fun `Kern waechst beim Zuhoeren mit der Lautstaerke`() {
        val quiet = OrbMath.coreScale(OverlayState.LISTENING, 0f, 0f, 0f, 0f)
        val loud = OrbMath.coreScale(OverlayState.LISTENING, 1f, 0f, 0f, 0f)
        assertTrue(loud > quiet)
        assertEquals(1f, OrbMath.coreScale(OverlayState.IDLE, 1f, 0.5f, 0.5f, 0.5f), 0.0001f)
        assertEquals(1f, OrbMath.coreScale(OverlayState.ERROR, 1f, 0.5f, 0.5f, 0.5f), 0.0001f)
    }

    @Test
    fun `Sprechen pulsiert, Denken atmet langsamer und kleiner`() {
        val speakMax = OrbMath.coreScale(OverlayState.SPEAKING, 0f, 0f, 0.5f, 0f)
        val thinkMax = OrbMath.coreScale(OverlayState.THINKING, 0f, 0.5f, 0f, 0f)
        assertTrue(speakMax > 1f)
        assertTrue(thinkMax > 1f)
        assertTrue(speakMax > thinkMax)
        assertEquals(1_000L, OrbMath.SPEAKING_PERIOD_MS)   // ~1 Hz
        assertEquals(1_600L, OrbMath.THINKING_PERIOD_MS)   // ~1,6 s
    }

    @Test
    fun `Dauer-Animation nur in aktiven Zustaenden`() {
        assertTrue(OrbMath.isContinuous(OverlayState.LISTENING))
        assertTrue(OrbMath.isContinuous(OverlayState.THINKING))
        assertTrue(OrbMath.isContinuous(OverlayState.SPEAKING))
        assertFalse(OrbMath.isContinuous(OverlayState.IDLE))
        assertFalse(OrbMath.isContinuous(OverlayState.ERROR))
    }

    @Test
    fun `Glow liegt immer im gueltigen Bereich`() {
        for (s in OverlayState.values()) {
            for (l in listOf(0f, 0.5f, 1f)) {
                for (ph in listOf(0f, 0.3f, 0.5f, 0.9f)) {
                    val g = OrbMath.glowStrength(s, l, ph)
                    assertTrue("$s $l $ph → $g", g in 0f..1f)
                }
            }
        }
    }
}
