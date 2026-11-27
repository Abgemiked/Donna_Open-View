package com.yourcompany.donna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #143 — Empfindlichkeit des „Unterbrechens per Stimme“: leise/entfernte/kurze Geraeusche (Kind im Nebenraum,
 * Mundgeraeusche) loesen im Standard nicht aus, deutliche zusammenhaengende Sprache schon. Mikrofon: nur am Geraet pruefbar.
 */
class BargeInSensitivityTest {

    /** Donna spricht leise im Nebenraum-Echo (-45 dB) -> Lernphase fertig; danach pruefen. */
    private fun gelernt(s: BargeInSensitivity): BargeInVad {
        val vad = s.newVad()
        repeat(40) { vad.onFrame(-45.0, true) }
        assertTrue(vad.isLearningDone)
        return vad
    }

    private fun feuert(vad: BargeInVad, db: Double, ms: Int, voiceLike: Boolean = true): Boolean {
        var fired = false
        repeat(ms / 20) { if (vad.onFrame(db, voiceLike)) fired = true }
        return fired
    }

    @Test
    fun `Standard ist Mittel und unempfindlicher als Hoch`() {
        assertEquals(BargeInSensitivity.MEDIUM, BargeInSensitivity.DEFAULT)
        assertTrue(BargeInSensitivity.MEDIUM.absMinDb > BargeInSensitivity.HIGH.absMinDb)
        assertTrue(BargeInSensitivity.MEDIUM.sustainMs > BargeInSensitivity.HIGH.sustainMs)
        assertTrue(BargeInSensitivity.LOW.absMinDb > BargeInSensitivity.MEDIUM.absMinDb)
        assertTrue(BargeInSensitivity.LOW.sustainMs > BargeInSensitivity.MEDIUM.sustainMs)
    }

    @Test
    fun `Hoch entspricht dem Verhalten vor 143`() {
        val h = BargeInSensitivity.HIGH
        assertEquals(-36.0, h.absMinDb, 0.0)
        assertEquals(6.0, h.marginDb, 0.0)
        assertEquals(6.0, h.ambientMarginDb, 0.0)
        assertEquals(280, h.sustainMs)
    }

    @Test
    fun `leise entfernte Geraeusche (Kind im Nebenraum) loesen bei Mittel und Niedrig nicht aus, bei Hoch wie frueher`() {
        // anhaltend, stimm-aehnlich, aber leise (-33 dB) — durch die Tuer gedaempft
        assertTrue(feuert(gelernt(BargeInSensitivity.HIGH), -33.0, 1_000))
        assertFalse(feuert(gelernt(BargeInSensitivity.MEDIUM), -33.0, 3_000))
        assertFalse(feuert(gelernt(BargeInSensitivity.LOW), -33.0, 3_000))
    }

    @Test
    fun `kurze Mundgeraeusche loesen bei Mittel und Niedrig nicht aus`() {
        // mittellaut (-22 dB), aber nur 300 ms am Stueck, dazwischen 2 s Stille (Leaky-Sustain baut 4x langsamer ab)
        for (s in listOf(BargeInSensitivity.MEDIUM, BargeInSensitivity.LOW)) {
            val vad = gelernt(s)
            var fired = false
            repeat(6) {
                if (feuert(vad, -22.0, 300)) fired = true
                if (feuert(vad, -70.0, 2_000)) fired = true
            }
            assertFalse("Stufe ${s.key}", fired)
        }
        // Gegenprobe: Hoch (280 ms) wuerde ausloesen
        assertTrue(feuert(gelernt(BargeInSensitivity.HIGH), -22.0, 300))
    }

    @Test
    fun `deutliche zusammenhaengende Sprache loest bei jeder Stufe weiter aus`() {
        for (s in BargeInSensitivity.values()) {
            assertTrue("Stufe ${s.key}", feuert(gelernt(s), -17.0, 1_000))
        }
    }

    /** Folge aus Laut- und Pausenrahmen: [lautMs] bei [db], danach [pauseMs] Stille, [zyklen]-mal. */
    private fun zyklen(vad: BargeInVad, db: Double, lautMs: Int, pauseMs: Int, zyklen: Int): Boolean {
        var fired = false
        repeat(zyklen) {
            if (feuert(vad, db, lautMs)) fired = true
            if (feuert(vad, -70.0, pauseMs)) fired = true
        }
        return fired
    }

    @Test
    fun `wiederholte Kurzlaute summieren sich bei Mittel und Niedrig nicht zur Unterbrechung`() {
        for (s in listOf(BargeInSensitivity.MEDIUM, BargeInSensitivity.LOW)) {
            assertFalse("${s.key} 300/500", zyklen(gelernt(s), -24.0, 300, 500, 10))
            assertFalse("${s.key} 300/1000", zyklen(gelernt(s), -24.0, 300, 1_000, 10))
            assertFalse("${s.key} 150/500", zyklen(gelernt(s), -24.0, 150, 500, 30))
        }
    }

    @Test
    fun `zusammenhaengende Sprache und Sprache mit Wortpausen loest bei jeder Stufe aus`() {
        for (s in BargeInSensitivity.values()) {
            assertTrue("${s.key} durchgehend", feuert(gelernt(s), -18.0, 600))
            val vad = gelernt(s)
            var fired = false
            repeat(3) {
                if (feuert(vad, -18.0, 260)) fired = true
                if (it < 2 && feuert(vad, -70.0, 160)) fired = true
            }
            assertTrue("${s.key} mit Wortpausen", fired)
        }
    }

    @Test
    fun `Hoch behaelt das alte Abbauverhalten - Kurzlaute summieren sich weiter`() {
        assertEquals(Int.MAX_VALUE, BargeInSensitivity.HIGH.pauseResetMs)
        assertTrue(BargeInSensitivity.MEDIUM.pauseResetMs <= 300)
        assertTrue(BargeInSensitivity.LOW.pauseResetMs <= 200)
        // alte Zahlen: 150 ms laut / 200 ms Pause summiert sich innerhalb weniger Zyklen zu 280 ms
        assertTrue(zyklen(gelernt(BargeInSensitivity.HIGH), -24.0, 150, 200, 6))
    }

    @Test
    fun `Schwelle liegt je Stufe mindestens bei der Untergrenze`() {
        for (s in BargeInSensitivity.values()) {
            assertTrue(gelernt(s).thresholdDb >= s.absMinDb - 0.001)
        }
    }

    @Test
    fun `normalize und fromKey - ungueltig oder fehlend ergibt den Standard`() {
        assertEquals("medium", BargeInSensitivity.normalizeKey(null))
        assertEquals("medium", BargeInSensitivity.normalizeKey("laut"))
        assertEquals("low", BargeInSensitivity.normalizeKey("low"))
        assertEquals("high", BargeInSensitivity.normalizeKey("high"))
        assertEquals(BargeInSensitivity.HIGH, BargeInSensitivity.fromKey("high"))
    }

    @Test
    fun `Alle Wege nutzen dieselbe Regel - Detektor und Bruecke, Schluessel nur einmal`() {
        val base = "src/main/java/com/yourcompany/donna/"
        val det = java.io.File(base + "BargeInDetector.kt").readText()
        assertTrue(det.contains("BargeInSensitivity.read("))
        assertTrue(det.contains(".newVad("))
        assertFalse("kein eigener Detektor mit eigenen Schwellen", Regex("BargeInVad\\(").containsMatchIn(det))
        val mod = java.io.File(base + "PiperTTSModule.kt").readText()
        assertTrue(mod.contains("fun getBargeInSensitivity(") && mod.contains("fun setBargeInSensitivity("))
        assertTrue(mod.contains("putString(BargeInSensitivity.PREF_KEY"))
        val all = java.io.File(base).listFiles { f -> f.extension == "kt" }!!.map { it.readText() }
        assertEquals(1, all.sumOf { Regex("\"barge_in_sensitivity\"").findAll(it).count() })
        // alle drei Wege (Blase/Overlay, Sperrbild-Activity, RN-Chat) nutzen den Detektor, keiner einen eigenen BargeInVad
        for (f in listOf("ConversationController.kt", "VoiceInputActivity.kt", "BargeInModule.kt")) {
            val src = java.io.File(base + f).readText()
            assertTrue(f, src.contains("BargeInDetector("))
            assertFalse(f, Regex("BargeInVad\\(").containsMatchIn(src))
        }
    }

    @Test
    fun `Stufen und Standard stimmen in Kotlin und TypeScript ueberein`() {
        val ts = java.io.File("../../src/native/ttsEngine.ts").readText()
        val tsKeys = Regex("\\{key: '(low|medium|high)', label:").findAll(ts).map { it.groupValues[1] }.toList()
        assertEquals(BargeInSensitivity.values().map { it.key }, tsKeys)
        val tsDefault = Regex("BARGE_IN_SENSITIVITY_DEFAULT: BargeInSensitivityKey = '([a-z]+)'").find(ts)!!.groupValues[1]
        assertEquals(BargeInSensitivity.DEFAULT.key, tsDefault)
    }
}
