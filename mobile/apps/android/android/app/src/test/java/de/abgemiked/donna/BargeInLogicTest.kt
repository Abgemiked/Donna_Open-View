package com.yourcompany.donna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-Unit-Tests für Voice-Barge-in (Echo-Schutz) und STT-Biasing. Mikrofon, AEC und Ton sind nur am Gerät prüfbar.
 */
class BargeInLogicTest {

    private val frameMs = 20

    /** Speist [count] Frames mit festem Pegel ein; gibt die Nummer des Frames zurück, der ausgelöst hat (sonst -1). */
    private fun feed(vad: BargeInVad, db: Double, count: Int): Int {
        for (i in 1..count) if (vad.onFrame(db)) return i
        return -1
    }

    // ── VAD ──────────────────────────────────────────────────────────────────

    @Test
    fun `gleichbleibendes Echo loest nie aus - egal wie laut`() {
        val vad = BargeInVad()
        assertEquals(-1, feed(vad, -25.0, 500))   // 10 s lautes Echo
        assertFalse(vad.triggered)
    }

    @Test
    fun `Echo mit Dynamik (Betonungen) loest nicht aus`() {
        val vad = BargeInVad()
        var hit = -1
        // Echo schwankt zwischen -34 und -28 dB (6 dB Hub) in 100-ms-Wechseln
        for (i in 1..400) {
            val db = if ((i / 5) % 2 == 0) -34.0 else -28.0
            if (vad.onFrame(db)) { hit = i; break }
        }
        assertEquals(-1, hit)
    }

    @Test
    fun `deutlich lautere Stimme ueber dem Echo loest nach der Mindestdauer aus`() {
        val vad = BargeInVad()
        assertEquals(-1, feed(vad, -34.0, 50))          // 1 s Echo-Rest (Einschwingen + Lernen)
        val hit = feed(vad, -16.0, 50)                  // Mike spricht, +18 dB
        assertTrue("sollte ausloesen", hit > 0)
        // erst nach sustainMs (380 ms = 19 Frames), nicht sofort
        assertTrue("zu frueh: Frame $hit", hit * frameMs >= 380)
        assertTrue(vad.triggered)
    }

    // ── Voice-Batch: Sprachband, Stimm-Aehnlichkeit, Umgebungsgeraeusch ───────

    @Test
    fun `laute Nicht-Sprache (voiceLike=false) loest nie aus - egal wie lange`() {
        val vad = BargeInVad()
        feed(vad, -34.0, 50)
        var hit = -1
        for (i in 1..300) if (vad.onFrame(-12.0, voiceLike = false)) { hit = i; break }   // 6 s Rauschen/Brummen
        assertEquals(-1, hit)
        assertFalse(vad.triggered)
    }

    @Test
    fun `laute Nicht-Sprache hebt die Echo-Spitze nicht an - danach loest Sprache weiter aus`() {
        val vad = BargeInVad()
        feed(vad, -34.0, 50)
        for (i in 1..30) vad.onFrame(-12.0, voiceLike = false)    // Knall/Tuer
        assertTrue("Stimme muss danach noch ausloesen", feed(vad, -16.0, 60) > 0)
    }

    @Test
    fun `Sprache mit kurzen Nicht-Sprache-Frames (Zischlaute) loest trotzdem aus`() {
        val vad = BargeInVad()
        feed(vad, -34.0, 50)
        var hit = -1
        // 4 stimmhafte Frames, 1 Zischlaut-Frame (laut, aber ZCR zu hoch)
        for (i in 1..80) {
            val voiced = i % 5 != 0
            if (vad.onFrame(-16.0, voiceLike = voiced)) { hit = i; break }
        }
        assertTrue(hit > 0)
    }

    @Test
    fun `lauter Dauerlaerm hebt den Umgebungsboden - nur deutlich lautere Sprache loest aus`() {
        val vad = BargeInVad()
        // 20 s gleichmaessiger Laerm bei -26 dB (Strasse/Kueche/Fernseher): Echo-Spitze und Boden lernen den Pegel
        assertEquals(-1, feed(vad, -26.0, 1000))
        // Schwelle liegt ueber dem Laerm (mind. +11 dB bzw. Boden + 12 dB), nicht auf dem Mindestwert -36 dB
        assertTrue("Schwelle=${vad.thresholdDb}", vad.thresholdDb >= -15.0)
        // Sprache nur 9 dB ueber dem Laerm: zu wenig
        assertEquals(-1, feed(vad, -17.0, 100))
        // deutlich lauter (weit ueber der Schwelle) loest aus
        assertTrue(feed(vad, -3.0, 60) > 0)
    }

    @Test
    fun `tiefe Echo-Spitze und langsam steigender Laerm - keine Selbst-Ausloesung, Sprache ueber dem Laerm loest aus`() {
        val vad = BargeInVad()
        // leises TTS (Echo-Spitze ~ -50 dB): die Schwelle faellt NICHT unter die Untergrenze
        assertEquals(-1, feed(vad, -50.0, 100))
        assertTrue("Schwelle=${vad.thresholdDb}", vad.thresholdDb >= -36.0)
        // Laerm steigt ueber 20 s langsam von -50 auf -25 dB (Bus faehrt vorbei, Spuelmaschine, Fernseher an)
        var hit = -1
        for (i in 0 until 1000) {
            val db = -50.0 + 25.0 * i / 1000.0
            if (vad.onFrame(db)) { hit = i; break }
        }
        assertEquals("langsam steigender Laerm darf nie ausloesen", -1, hit)
        // Sprache nur 5 dB ueber dem Laerm: zu wenig
        assertEquals(-1, feed(vad, -20.0, 100))
        // deutlich ueber Boden und Echo-Spitze: loest aus
        assertTrue(feed(vad, -3.0, 60) > 0)
    }

    @Test
    fun `plötzlicher Laermsprung ohne Stimm-Merkmale loest nicht aus, auch nicht nach langem Dauerlaerm`() {
        val vad = BargeInVad()
        assertEquals(-1, feed(vad, -30.0, 500))
        var hit = -1
        for (i in 1..200) if (vad.onFrame(-5.0, voiceLike = false)) { hit = i; break }   // Kreissaege, Hupe
        assertEquals(-1, hit)
    }

    @Test
    fun `Umgebungsboden allein verhindert das Ausloesen - Echo-Spitze tief, 10 s Nicht-Sprache bei -20 dB, dann Stimme bei -20 dB`() {
        // Kontrolle: OHNE den Laerm-Vorlauf loest Sprache bei -20 dB ueber der tiefen Echo-Spitze (-34 + 11 = -23 dB) aus
        val control = BargeInVad()
        feed(control, -34.0, 50)
        assertTrue("Kontrolle: ohne Boden wuerde -20 dB ausloesen", feed(control, -20.0, 60) > 0)

        val vad = BargeInVad()
        feed(vad, -34.0, 50)                                   // tiefe Echo-Spitze (leises TTS)
        for (i in 1..500) vad.onFrame(-20.0, voiceLike = false) // ~10 s Dauerlaerm ohne Stimm-Merkmale (Motor, Wind)
        // Die Echo-Spitze ist durch den Laerm NICHT angehoben worden — nur der Umgebungsboden hebt die Schwelle
        var hit = -1
        for (i in 1..100) if (vad.onFrame(-20.0, voiceLike = true)) { hit = i; break }
        assertEquals("im 10-s-Dauerlaerm darf Sprache auf Laermniveau nicht ausloesen", -1, hit)
        assertTrue("Schwelle muss ueber dem Laerm liegen: ${vad.thresholdDb}", vad.thresholdDb > -20.0)
        // Deutlich ueber dem Laerm (> Boden + 12 dB) loest weiterhin aus
        assertTrue(feed(vad, -3.0, 60) > 0)
    }

    @Test
    fun `Schwelle liegt mindestens bei der Untergrenze`() {
        val vad = BargeInVad()
        feed(vad, -60.0, 100)    // sehr leises Echo
        assertTrue(vad.thresholdDb >= -36.0)
    }

    // ── SpeechFrameAnalyzer (Hochpass + Nulldurchgangsrate) ───────────────────

    private fun sine(freq: Double, amp: Double, n: Int = 320, rate: Int = 16_000, phase: Int = 0): ShortArray =
        ShortArray(n) { (amp * 32767 * Math.sin(2 * Math.PI * freq * (it + phase) / rate)).toInt().toShort() }

    private fun analyzeSteady(freq: Double, amp: Double, frames: Int = 10): SpeechFrameAnalyzer.Result {
        val a = SpeechFrameAnalyzer()
        var r = a.analyze(sine(freq, amp), 320)
        for (i in 1 until frames) r = a.analyze(sine(freq, amp, phase = i * 320), 320)
        return r
    }

    @Test
    fun `Sprachfrequenz (500 Hz) ist stimm-aehnlich`() {
        val r = analyzeSteady(500.0, 0.2)
        assertTrue("zcr=${r.zcr}", r.voiceLike)
        assertTrue("db=${r.db}", r.db > -20.0)
    }

    @Test
    fun `tiefes Brummen (60 Hz) ist nicht stimm-aehnlich und wird gedaempft`() {
        val hum = analyzeSteady(60.0, 0.2)
        val speech = analyzeSteady(500.0, 0.2)
        assertFalse("zcr=${hum.zcr}", hum.voiceLike)
        assertTrue("Brummen muss im Sprachband deutlich leiser sein", hum.db < speech.db - 10.0)
    }

    @Test
    fun `Gleichanteil (DC) wird komplett entfernt`() {
        val a = SpeechFrameAnalyzer()
        var r = a.analyze(ShortArray(320) { 8000 }, 320)
        for (i in 1..20) r = a.analyze(ShortArray(320) { 8000 }, 320)
        assertTrue("db=${r.db}", r.db < -60.0)
        assertFalse(r.voiceLike)
    }

    @Test
    fun `weisses Rauschen und Zischen (hohe ZCR) ist nicht stimm-aehnlich`() {
        val rnd = java.util.Random(42)
        val a = SpeechFrameAnalyzer()
        var r = a.analyze(ShortArray(320) { ((rnd.nextDouble() * 2 - 1) * 8000).toInt().toShort() }, 320)
        for (i in 1..10) r = a.analyze(ShortArray(320) { ((rnd.nextDouble() * 2 - 1) * 8000).toInt().toShort() }, 320)
        assertTrue("zcr=${r.zcr}", r.zcr > SpeechFrameAnalyzer.VOICE_ZCR_MAX)
        assertFalse(r.voiceLike)
    }

    @Test
    fun `Stille liefert -90 dB und ist nicht stimm-aehnlich`() {
        val r = SpeechFrameAnalyzer().analyze(ShortArray(320), 320)
        assertEquals(-90.0, r.db, 0.001)
        assertFalse(r.voiceLike)
        val empty = SpeechFrameAnalyzer().analyze(ShortArray(0), 0)
        assertEquals(-90.0, empty.db, 0.001)
    }

    @Test
    fun `kurzes Klicken oder Husten loest nicht aus`() {
        val vad = BargeInVad()
        feed(vad, -34.0, 50)
        assertEquals(-1, feed(vad, -10.0, 6))           // 120 ms laut
        assertEquals(-1, feed(vad, -34.0, 50))
    }

    @Test
    fun `nur leise Sprache unter der Mindestschwelle loest nicht aus`() {
        val vad = BargeInVad()
        feed(vad, -80.0, 50)                            // fast Stille
        assertEquals(-1, feed(vad, -45.0, 100))         // leises Rauschen/Fluestern unter -38 dB
    }

    @Test
    fun `waehrend der Einschwingzeit wird nur gelernt`() {
        val vad = BargeInVad()
        // sehr laut gleich zu Beginn (Anfang von Donnas Ton) → wird Echo-Pegel, kein Ausloeser
        assertEquals(-1, feed(vad, -12.0, 14))
        assertEquals(-1, feed(vad, -12.0, 100))
    }

    @Test
    fun `loest genau einmal aus`() {
        val vad = BargeInVad()
        feed(vad, -34.0, 50)
        assertTrue(feed(vad, -16.0, 50) > 0)
        assertEquals(-1, feed(vad, -16.0, 50))
    }

    @Test
    fun `kurze Pausen in der Sprache werden verziehen`() {
        val vad = BargeInVad()
        feed(vad, -34.0, 50)
        var hit = -1
        // Sprache mit kurzen Luecken: 3 Frames laut, 1 leise
        for (i in 1..60) {
            val db = if (i % 4 == 0) -34.0 else -16.0
            if (vad.onFrame(db)) { hit = i; break }
        }
        assertTrue(hit > 0)
    }

    @Test
    fun `rmsDb liefert dBFS`() {
        val silent = ShortArray(320)
        assertEquals(-90.0, BargeInVad.rmsDb(silent, 320), 0.001)
        val full = ShortArray(320) { Short.MAX_VALUE }
        assertEquals(0.0, BargeInVad.rmsDb(full, 320), 0.01)
        val half = ShortArray(320) { (Short.MAX_VALUE / 2).toShort() }
        assertEquals(-6.0, BargeInVad.rmsDb(half, 320), 0.1)
        assertEquals(-90.0, BargeInVad.rmsDb(full, 0), 0.001)
    }

    // ── Echo-Abgleich mit Donnas gesprochenem Text ───────────────────────────

    private val spoken = "Ich habe das Licht im Wohnzimmer ausgemacht und die Rollläden geschlossen."

    @Test
    fun `Donnas eigener Text wird als Echo erkannt`() {
        assertTrue(SpokenTextMatcher.isEcho("ich habe das licht im wohnzimmer ausgemacht", spoken))
        assertTrue(SpokenTextMatcher.isEcho("Licht im Wohnzimmer ausgemacht und die Rollläden geschlossen", spoken))
    }

    @Test
    fun `eine echte neue Aeusserung ist kein Echo`() {
        assertFalse(SpokenTextMatcher.isEcho("mach bitte das Licht in der Küche an", spoken))
        assertFalse(SpokenTextMatcher.isEcho("wie wird das Wetter morgen", spoken))
    }

    @Test
    fun `ein einzelnes Wort ist nie Echo - ja und stopp sind echte Antworten`() {
        assertFalse(SpokenTextMatcher.isEcho("Licht", spoken))
        assertFalse(SpokenTextMatcher.isEcho("ja", "ja klar mache ich"))
    }

    @Test
    fun `leerer gesprochener Text bedeutet kein Echo`() {
        assertFalse(SpokenTextMatcher.isEcho("irgendwas ganz anderes hier", ""))
    }

    @Test
    fun `zwei Woerter muessen beide aus Donnas Text stammen`() {
        assertTrue(SpokenTextMatcher.isEcho("Licht ausgemacht", spoken))
        assertFalse(SpokenTextMatcher.isEcho("Licht anmachen", spoken))
    }

    // ── Echo-Strikes ─────────────────────────────────────────────────────────

    @Test
    fun `bei Erreichen der Strike-Schwelle wird pausiert, nicht mehr dauerhaft abgeschaltet`() {
        val e = EchoStrikes(maxStrikes = 2)
        assertFalse(e.record())
        assertTrue(e.record())        // Schwelle erreicht → Pause ausgeloest
        assertTrue(e.isPaused())
        e.reset()
        assertEquals(0, e.strikes)
        assertFalse(e.isPaused())
    }

    @Test
    fun `A3 - Echo-Pause ist temporaer und wird nach dem Cooldown automatisch wieder scharf`() {
        var now = 0L
        val e = EchoStrikes(maxStrikes = 4, cooldownMs = 90_000L, strikeDecayMs = 20_000L, clock = { now })
        // 3 Fehler in kurzer Folge → noch KEINE Pause (hoehere Toleranz)
        assertFalse(e.record()); now += 1_000
        assertFalse(e.record()); now += 1_000
        assertFalse(e.record()); now += 1_000
        assertFalse("vor der Schwelle nicht pausiert", e.isPaused())
        // 4. Fehler → Pause
        assertTrue(e.record())
        assertTrue("jetzt pausiert", e.isPaused())
        now += 89_000
        assertTrue("waehrend des Cooldowns pausiert", e.isPaused())
        // Nach Ablauf des Cooldowns: automatisch wieder scharf (kein Dauer-Aus mehr)
        now += 2_000
        assertFalse("nach Cooldown wieder aktiv", e.isPaused())
        assertEquals(0, e.strikes)
    }

    @Test
    fun `A3 - vereinzelte Fehlausloeser klingen ab und summieren sich nicht zur Pause`() {
        var now = 0L
        val e = EchoStrikes(maxStrikes = 4, strikeDecayMs = 20_000L, clock = { now })
        assertFalse(e.record())          // Strike 1
        now += 25_000                    // lange Ruhe > Decay
        assertFalse(e.record())          // zaehlt wegen Decay wieder ab 1
        assertEquals(1, e.strikes)
        now += 25_000
        assertFalse(e.record())
        assertFalse("vereinzelte Fehler loesen nie eine Pause aus", e.isPaused())
    }

    @Test
    fun `A3 - echte Sprache setzt Strikes und Pause zurueck`() {
        var now = 0L
        val e = EchoStrikes(maxStrikes = 2, clock = { now })
        assertFalse(e.record())
        assertTrue(e.record())           // pausiert
        assertTrue(e.isPaused())
        e.reset()                        // genuine speech
        assertFalse(e.isPaused())
        assertEquals(0, e.strikes)
    }

    @Test
    fun `A3 - Echo-Fehlausloeser schalten Barge-in im Overlay nicht mehr dauerhaft ab`() {
        val src = java.io.File("src/main/java/com/yourcompany/donna/VoiceInputActivity.kt").readText()
        // Dauer-Aus entfernt: nirgends wird disabledForProcess auf true gesetzt (whitespace-tolerant).
        assertFalse(
            "kein Dauer-Aus mehr (disabledForProcess=true entfernt)",
            Regex("disabledForProcess\\s*=\\s*true").containsMatchIn(src),
        )
        // voiceBargeInEnabled() gated TATSAECHLICH ueber die temporaere Echo-Pause (nicht nur im KDoc).
        assertTrue(
            "isPaused-Gate im voiceBargeInEnabled-Body",
            Regex("fun voiceBargeInEnabled\\(\\)[\\s\\S]{0,400}echoStrikes\\.isPaused\\(\\)").containsMatchIn(src),
        )
        // Manueller Aus-Schalter bleibt erhalten.
        assertTrue(src.contains("toggleVoiceBargeIn"))
    }

    @Test
    fun `A3 - Produktions-Defaults von EchoStrikes - Schwelle 3, selbst-erholende Pause`() {
        var now = 0L
        val e = EchoStrikes(clock = { now })   // sonst Produktions-Defaults (Schwelle 3, Decay 60s, Cooldown 90s)
        assertFalse(e.record()); now += 1_000   // Strike 1
        assertFalse(e.record()); now += 1_000   // Strike 2 — noch keine Pause
        assertFalse("vor der Default-Schwelle nicht pausiert", e.isPaused())
        assertTrue("Default-Schwelle = 3 → Pause", e.record())  // Strike 3
        assertTrue(e.isPaused())
    }

    // ── STT-Biasing ──────────────────────────────────────────────────────────

    @Test
    fun `Bias-Liste enthaelt alle Raeume und Geraete, eindeutig`() {
        val t = SttBias.TERMS
        for (room in listOf("Streamingzimmer", "Schlafzimmer", "Wohnzimmer", "Kinderzimmer", "Küche", "Flur", "Bad", "Balkon")) {
            assertTrue("fehlt: $room", room in t)
        }
        for (d in listOf(
            "Softboxen", "Regallicht", "Wandlampe", "Lüfter Steckdose", "Shishakohle", "Shishakohleanzünder",
            "Shisha Restzeit", "Kohle", "Yannik Projektor", "Ambilight", "Licht Streamen", "Regallicht Halloween",
        )) {
            assertTrue("fehlt: $d", d in t)
        }
        assertEquals(t.size, t.toSet().size)
    }

    @Test
    fun `EXTRA_BIASING_STRINGS erst ab API 33`() {
        assertFalse(SttBias.isSupported(32))
        assertTrue(SttBias.isSupported(33))
        assertTrue(SttBias.isSupported(35))
    }

    // ── Lernphase (stiller TTS-Vorlauf) ──────────────────────────────────────

    @Test
    fun `stiller TTS-Vorlauf vor dem Echo loest nicht aus`() {
        val vad = BargeInVad()
        assertEquals(-1, feed(vad, -90.0, 30))        // 600 ms Stille am Anfang der Audiodatei
        assertEquals(-1, feed(vad, -34.0, 100))       // dann beginnt Donnas Stimme (Echo-Rest) — 2 s
        assertFalse(vad.triggered)
    }

    @Test
    fun `Lernphase beginnt erst am ersten hoerbaren Frame`() {
        val vad = BargeInVad()
        feed(vad, -90.0, 30)                          // 600 ms Stille
        assertFalse("noch nicht gelernt", vad.isLearningDone)
        feed(vad, -34.0, 14)                          // 280 ms hoerbar < Einschwingzeit
        assertFalse(vad.isLearningDone)
        feed(vad, -34.0, 3)
        assertTrue(vad.isLearningDone)
    }

    @Test
    fun `komplett stiller Beginn - Lernphase endet am Hard-Cap mit konservativer Untergrenze`() {
        val vad = BargeInVad()
        feed(vad, -90.0, 80)                          // 1,6 s nichts Hoerbares
        assertTrue(vad.isLearningDone)
        assertTrue("konservativ: Schwelle mind. -32 dBFS", vad.thresholdDb >= -32.0)
        assertEquals(-1, feed(vad, -35.0, 100))       // leises Rauschen loest nicht aus
    }

    @Test
    fun `nach stiller Lernphase loest laute Sprache trotzdem aus`() {
        val vad = BargeInVad()
        feed(vad, -90.0, 80)
        assertTrue(feed(vad, -15.0, 50) > 0)
    }

    @Test
    fun `waehrend der Lernphase wird nie ausgeloest, auch bei Sprache`() {
        val vad = BargeInVad()
        assertEquals(-1, feed(vad, -10.0, 15))        // 300 ms laut direkt am Anfang
        assertFalse(vad.triggered)
    }

    // ── Echo-Abgleich: Modi ──────────────────────────────────────────────────

    private val question = "Soll ich das Licht im Wohnzimmer ausschalten?"

    @Test
    fun `Ja plus Wiederholung ist eine echte Antwort, kein Echo`() {
        assertFalse(SpokenTextMatcher.isEcho("Ja, das Licht im Wohnzimmer", question))
        assertFalse(SpokenTextMatcher.isEcho("Ja, das Licht im Wohnzimmer", question, bargeIn = true))
        assertFalse(SpokenTextMatcher.isEcho("Nein lass das Licht im Wohnzimmer an", question))
    }

    @Test
    fun `Auto-Relisten - bis sechs Woerter muessen alle aus Donnas Text stammen`() {
        assertFalse(SpokenTextMatcher.isEcho("das Licht im Wohnzimmer anmachen", question))   // 1 von 5 fremd
        assertTrue(SpokenTextMatcher.isEcho("soll ich das Licht im Wohnzimmer", question))    // 6 von 6
    }

    @Test
    fun `Barge-in - niedrigere Schwelle 0,5 und Einzelwort-Echo`() {
        assertTrue(SpokenTextMatcher.isEcho("das Licht im Wohnzimmer hmm", question, bargeIn = true))
        assertFalse(SpokenTextMatcher.isEcho("das Licht im Wohnzimmer hmm", question, bargeIn = false))
        assertTrue(SpokenTextMatcher.isEcho("Wohnzimmer", question, bargeIn = true))
        assertFalse(SpokenTextMatcher.isEcho("Wohnzimmer", question, bargeIn = false))
        assertFalse(SpokenTextMatcher.isEcho("Stopp", question, bargeIn = true))
    }

    @Test
    fun `Barge-in - frische Aeusserung ohne Ueberschneidung ist kein Echo`() {
        assertFalse(SpokenTextMatcher.isEcho("mach die Musik leiser bitte", question, bargeIn = true))
    }

    // ── Detektor-Vertrag ─────────────────────────────────────────────────────

    @Test
    fun `Detektor hat Hard-Cap und Backoff`() {
        assertEquals(90_000L, BargeInDetector.MAX_RUNTIME_MS)
        assertTrue(BargeInDetector.INIT_BACKOFF_MS >= 30_000L)
    }

    @Test
    fun `Voice-Barge-in ist standardmaessig AN (Opt-out)`() {
        val src = java.io.File("src/main/java/com/yourcompany/donna/VoiceInputActivity.kt").readText()
        assertTrue(src.contains("VOICE_BARGE_IN_DEFAULT = true"))
        assertFalse(src.contains("VOICE_BARGE_IN_DEFAULT = false"))
        // Opt-out: die gespeicherte Einstellung ueberstimmt den Default (lange auf den Gespraechs-Chip tippen)
        assertTrue(src.contains("PREF_VOICE_BARGE_IN, VOICE_BARGE_IN_DEFAULT"))
        assertTrue(src.contains("toggleVoiceBargeIn"))
    }

    @Test
    fun `Barge-in Schutznetze bleiben - Hintergrund-Abbruch und 90s-Cap`() {
        val src = java.io.File("src/main/java/com/yourcompany/donna/VoiceInputActivity.kt").readText()
        // onPause (gesperrt/Hintergrund) bricht ab → Zustand verlaesst SPEAKING → Detektor wird gestoppt
        assertTrue(Regex("override fun onPause\\(\\)[\\s\\S]{0,600}OverlayEvent\\.Aborted").containsMatchIn(src))
        assertTrue(src.contains("if (t.state != OverlayState.SPEAKING) stopBargeInDetector()"))
        assertTrue(Regex("override fun onDestroy\\(\\)[\\s\\S]{0,300}stopBargeInDetector\\(\\)").containsMatchIn(src))
        // Lauschen nur im Zustand SPEAKING
        assertTrue(src.contains("machine.state != OverlayState.SPEAKING"))
        assertEquals(90_000L, BargeInDetector.MAX_RUNTIME_MS)
    }
}
