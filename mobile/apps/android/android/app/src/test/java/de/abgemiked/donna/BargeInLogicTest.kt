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

    /** Geräte-Kalibrierung (Gerätetest v3 2026-11-05), wie sie der BargeInDetector übergibt: divisor 4 (unverändert
     *  ggü. v2), Lernphase mit Onset-Skip + Perzentil über die BargeInVad-Defaults. */
    private fun calibratedVad() = BargeInVad(
        marginDb = 7.0, ambientMarginDb = 7.0, sustainMs = 260,
        voiceRecencyMs = 200, sustainDecayDivisor = 4,
    )

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
    fun `kalibriert - lange Stille-Pause senkt die Schwelle NICHT auf den Floor (kein Selbst-Trigger danach)`() {
        // v2: Die Echo-Spitze friert nach der Lernphase ein → eine Stille-Pause kann sie nicht auf den Floor ziehen,
        // und Donnas leiser Echo-Rest danach löst nicht fälschlich aus.
        val vad = calibratedVad()
        feed(vad, -25.0, 30)    // Lernphase: hörbares Echo ~-25 dB → learningDone, echoPeak ~-25
        feed(vad, -90.0, 400)   // 8 s echte Stille — darf die Schwelle nicht kollabieren lassen
        // Donnas leiser Echo-Rest ~-28 dB (voiceLike) muss unter der Schwelle (~-18 dB) bleiben → kein Auslösen
        assertEquals(-1, feed(vad, -28.0, 100))
        assertFalse(vad.triggered)
    }

    @Test
    fun `kalibriert v2 - echoPeak friert ein - choppy Sub-Schwellen-Reden laeuft der Schwelle NICHT davon`() {
        // Gerätetest-v2-REGRESSION (bewacht den HAUPTHEBEL echoPeak-Freeze): choppy Frames knapp UNTER der Schwelle
        // (-18 dB) mit Wortpausen (-70 dB, halten den noiseFloor unten) dürfen die eingefrorene Echo-Spitze NICHT
        // anheben. OHNE den Freeze (alter Rate-Limit-Anstieg) kletterte echoPeak via die -18-Frames auf ~-18 →
        // thr ~-11 → Mikes -15-Frames lägen darunter und würden NIE auslösen.
        val vad = calibratedVad()
        assertEquals(-1, feed(vad, -24.0, 30))   // Eva-Echo, Lernphase → echoPeak -24, thr ~-17
        for (i in 1..300) vad.onFrame(if (i % 2 == 0) -18.0 else -70.0, voiceLike = true)  // 6 s choppy, unter Schwelle
        assertTrue("Schwelle darf nicht weggelaufen sein (thr=${vad.thresholdDb})", vad.thresholdDb <= -16.0)
        val hit = feed(vad, -15.0, 40)            // Mike muss danach weiterhin durchschlagen
        assertTrue("Mike -15 muss nach dem choppy Vorlauf noch auslösen (hit=$hit)", hit > 0)
        assertTrue(vad.triggered)
    }

    @Test
    fun `kalibriert - lautes Reinreden mit flackerndem voice-Flag feuert (voiceRecency zaehlt voice=false-Peaks)`() {
        // Gerätetest v2: Mikes lauteste Barge-in-Frames sind oft voice=false (AEC-Restbursts). Bei abwechselnd
        // voiceLike/nicht (jeder 2. Frame false) muss es zügig auslösen — die voice=false-Frames zählen via
        // voiceRecency (kurz zuvor war ein lauter voiceLike-Frame) statt nur zu halten.
        val vad = calibratedVad()
        assertEquals(-1, feed(vad, -24.0, 30))   // Eva-Echo, Lernphase
        var hit = -1
        for (i in 1..40) {                        // 0,8 s — ein typisches Barge-in-Fenster
            val voice = i % 2 == 0                // nur jeder 2. Frame als Stimme erkannt
            if (vad.onFrame(-13.0, voiceLike = voice)) { hit = i; break }
        }
        assertTrue("flackerndes voice-Flag muss trotzdem auslösen (hit=$hit)", hit > 0)
        assertTrue(vad.triggered)
    }

    @Test
    fun `kalibriert - Eva spricht, Mike still → feuert NICHT (Selbst-Trigger-Schutz)`() {
        // Der eingefrorene Echo-Pegel + voiceRecency dürfen NICHT dazu führen, dass Donnas eigener Echo-Rest auslöst.
        val vad = calibratedVad()
        feed(vad, -22.0, 30)                 // Lernphase: Eva-Echo ~-22 dB
        assertEquals(-1, feed(vad, -22.0, 300))  // 6 s weiter Eva-Echo (voiceLike), unter Schwelle → kein Auslösen
        assertFalse(vad.triggered)
    }

    @Test
    fun `kalibriert v2 - choppy Dauerreden mit voice=false-Peaks und Wortpausen feuert (Integration)`() {
        // Geräteszenario Logcat (Dauerreden 15 s, f11ad86 feuerte nie). Integrationstest aller drei v2-Fixes:
        // echoPeak-Freeze (thr läuft nicht weg) + voiceRecency (laute voice=false-Peaks zählen) + Leaky-Sustain
        // (Wortpausen überbrücken) → das choppy Muster muss auslösen.
        val vad = calibratedVad()
        assertEquals(-1, feed(vad, -24.0, 30))   // Eva-Echo, Lernphase
        var hit = -1
        // Muster je „Runde": 4 laute voiceLike (-15), 1 lauter voice=false-Peak (-11), 2 Wortpausen (-65 dB).
        outer@ for (rep in 1..20) {
            for (k in 1..4) if (vad.onFrame(-15.0, voiceLike = true)) { hit = rep; break@outer }
            if (vad.onFrame(-11.0, voiceLike = false)) { hit = rep; break@outer }
            for (k in 1..2) if (vad.onFrame(-65.0, voiceLike = false)) { hit = rep; break@outer }
        }
        assertTrue("choppy Dauerreden muss auslösen (hit=$hit)", hit > 0)
        assertTrue(vad.triggered)
    }

    @Test
    fun `kalibriert v2 - dynamisches Eva-Echo loest nicht selbst aus (Freeze haelt die gelernte Spitze)`() {
        // Selbst-Trigger-Schutz bei SCHWANKENDEM Echo (der Grund, warum ein fester Clamp NICHT genommen wurde):
        // Die Lernphase fängt Evas Spitze (-18). Weil echoPeak danach EINFRIERT (nicht zum Tal -30 absinkt), bleibt
        // thr ~-11 und die wiederkehrenden -18-Peaks sind NIE laut. Mit einem Clamp -20 (oder absinkendem echoPeak)
        // hätten die -18-Peaks ausgelöst.
        val vad = calibratedVad()
        feed(vad, -18.0, 30)                      // Lernphase fängt die Spitze -18 → echoPeak -18, thr ~-11
        var hit = -1
        for (i in 1..400) {                       // 8 s wechselndes Echo -30/-18 (voiceLike)
            val db = if ((i / 5) % 2 == 0) -30.0 else -18.0
            if (vad.onFrame(db, voiceLike = true)) { hit = i; break }
        }
        assertEquals("dynamisches Echo darf nicht auslösen", -1, hit)
        assertFalse(vad.triggered)
    }

    @Test
    fun `kalibriert v2 - ein lautes Wort plus Dauer-Laerm feuert NICHT (voiceRecency verfaellt nach 200ms)`() {
        // voiceRecency (200ms) darf NICHT so weit tragen, dass EIN lautes voiceLike-Wort + anhaltendes lautes
        // voice=false-Dröhnen auslöst: nach 200ms verfällt die Frische → höchstens ~220ms kommen zusammen (< 260).
        val vad = calibratedVad()
        assertEquals(-1, feed(vad, -24.0, 30))        // Lernphase, thr ~-17
        assertEquals(-1, feed(vad, -14.0, 1))         // EIN lautes voiceLike-Fragment
        var hit = -1
        for (i in 1..200) if (vad.onFrame(-14.0, voiceLike = false)) { hit = i; break }  // 4 s lautes Dröhnen
        assertEquals("ein Wort + Dröhnen darf nicht auslösen", -1, hit)
        assertFalse(vad.triggered)
    }

    @Test
    fun `kalibriert v2 - voiceRecency laesst Mikes laute voice=false-Peaks zaehlen`() {
        // Echter Recency-Wächter (Untergrenze): Muster je Runde 1 lauter voiceLike + 3 laute voice=false
        // (AEC-Transienten). MIT voiceRecency zählen alle 4 Frames (+20 je) → feuert in ~13 Frames; OHNE (nur
        // voiceLike zählt, Rest HÄLT) nur +20 je 4 Frames → bräuchte ~52 Frames, also mehr als dieses Fenster.
        val vad = calibratedVad()
        assertEquals(-1, feed(vad, -24.0, 30))   // Lernphase, thr ~-17
        var hit = -1
        outer@ for (rep in 1..10) {              // 40 Frames = 0,8 s
            if (vad.onFrame(-14.0, voiceLike = true)) { hit = rep; break@outer }
            for (k in 1..3) if (vad.onFrame(-14.0, voiceLike = false)) { hit = rep; break@outer }
        }
        assertTrue("voice=false-Peaks müssen via Recency zählen (hit=$hit)", hit > 0)
        assertTrue(vad.triggered)
    }

    @Test
    fun `kalibriert v2 - Leaky-Sustain ueberbrueckt eine Wortpause`() {
        // Mikes echo-gecancelte Wortpausen (-65 dB) dürfen die schon aufgebaute Dauer nicht wegreißen (sanfter
        // Abbau frameMs/4 statt hart). Mit hartem Reset hätte es nach der Pause nicht mehr gereicht.
        val vad = calibratedVad()
        assertEquals(-1, feed(vad, -24.0, 30))   // Lernphase
        assertEquals(-1, feed(vad, -15.0, 10))   // 200 ms laute Stimme → voicedMs ~200
        assertEquals(-1, feed(vad, -65.0, 12))   // 240 ms Wortpause — Leaky baut nur ~60 ms ab (divisor 4)
        val hit = feed(vad, -15.0, 8)            // weitere Stimme → überschreitet sustain 260
        assertTrue("nach der Pause muss es auslösen (hit=$hit)", hit > 0)
        assertTrue(vad.triggered)
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

    // ── Geraete-Kalibrierung (Gerätetest v2 2026-11-02: marginDb=7, sustainMs=260, echoPeak-Freeze, ─────
    //     voiceRecency=200, Leaky /4) — der BargeInDetector konstruiert BargeInVad so; die DEFAULTS bleiben unberührt.

    @Test
    fun `kalibriert - lauter voiceLike-Bargein loest nach 260ms aus`() {
        val vad = calibratedVad()
        assertEquals(-1, feed(vad, -34.0, 50))           // Echo-Rest + Lernphase
        val hit = feed(vad, -10.5, 40)                   // Mike redet laut rein (Geräte-Messwert -10,5 dB)
        assertTrue("sollte ausloesen (hit=$hit)", hit > 0)
        assertTrue("sustain 260 ms = 13 Frames: Frame $hit", hit * frameMs >= 260)
        assertTrue(vad.triggered)
    }

    @Test
    fun `kalibriert - laute Nicht-Sprache und zu kurzer Laut loesen nicht aus`() {
        val vad = calibratedVad()
        feed(vad, -34.0, 50)                             // Lernphase
        var hit = -1
        for (i in 1..200) if (vad.onFrame(-10.0, voiceLike = false)) { hit = i; break }   // 4 s lautes Rauschen
        assertEquals(-1, hit)
        assertEquals(-1, feed(vad, -10.0, 10))           // 200 ms laute Sprache < 260 ms sustain → nicht
        assertFalse(vad.triggered)
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

    // ── Lernphase v3 (Onset-Skip + Perzentil) — Bubble-Pfad-Fix 2026-11-05 ────
    // Kern des Fixes: der laute TTS-Einschwing-Transient (im SpeechDriver-/Bubble-Pfad lauter als im Overlay)
    // darf die gelernte Echo-Spitze nicht hochreißen; gelernt wird das typische Residual (Perzentil nach Onset-Skip).

    @Test
    fun `v3 - lauter TTS-Onset plus leises Residual - Schwelle landet tief, Mike feuert`() {
        val vad = calibratedVad()
        // Lernphase (16 hörbare Frames = warmup 300 ms): 6 laute Onset-Frames (-18, erste 120 ms) + 10 ruhige
        // Residual-Frames (-29). OHNE den Fix hätte max() -18 gelernt → thr ~-11 → Mikes -17 läge drunter.
        repeat(6) { vad.onFrame(-18.0, voiceLike = true) }   // TTS-Einschwing-Transient → wird übersprungen
        repeat(10) { vad.onFrame(-29.0, voiceLike = true) }  // Evas typisches Residual
        assertTrue("Lernphase fertig", vad.isLearningDone)
        assertTrue("gelernte Spitze = Residual, nicht der -18-Onset: ${vad.learnedEchoDb}", vad.learnedEchoDb <= -27.0)
        assertTrue("Onset übersprungen → thr ≲ -16: ${vad.thresholdDb}", vad.thresholdDb <= -16.0)
        assertTrue("Onset-Frames aus dem Perzentil raus (retained=${vad.retainedLearnFrames})", vad.retainedLearnFrames in 8..12)
        // Mikes Normalstimme -17 (Gerätewert -15..-20) muss jetzt zuverlässig auslösen
        val hit = feed(vad, -17.0, 40)
        assertTrue("Mike -17 muss feuern (hit=$hit)", hit > 0)
        assertTrue(vad.triggered)
    }

    @Test
    fun `v3 - wiederkehrende Satz-Onset-Spikes bei stillem Mike feuern NICHT (Review-Rat-Auflage)`() {
        // Opus-Audit + Reviewer 2/6: Der Onset-Skip wirkt nur EINMAL (Lernphase). Streaming-TTS erzeugt an JEDEM
        // Satzanfang einen neuen lauten Transienten (-18, voiceLike), der nach dem Freeze ÜBER der Schwelle (~-22)
        // liegt. Bei realistischer Satzkadenz (Onset-Burst ~alle 0,6 s) darf die aufgebaute Dauer zwischen den
        // Bursts wieder abbluten (Leaky /4) und NICHT über sustain 260ms akkumulieren → kein Selbst-Trigger.
        val vad = calibratedVad()
        repeat(6) { vad.onFrame(-18.0, voiceLike = true) }   // Lernphase: Onset übersprungen
        repeat(10) { vad.onFrame(-29.0, voiceLike = true) }  // + Residual → thr ~-22
        assertTrue(vad.isLearningDone)
        var fired = false
        for (satz in 1..25) {                                 // 25 Sätze ≈ 15 s Dauerrede, Mike still
            repeat(6) { if (vad.onFrame(-18.0, voiceLike = true)) fired = true }   // Satz-Onset-Burst (-18, über thr)
            repeat(24) { if (vad.onFrame(-29.0, voiceLike = true)) fired = true }  // Rest des Satzes (Residual, unter thr)
        }
        assertFalse("wiederkehrende Satz-Onsets bei stillem Mike dürfen nicht selbst auslösen", fired)
        assertFalse(vad.triggered)
    }

    @Test
    fun `v3 - Eva Onset plus Residual, Mike STILL - feuert NICHT (Selbst-Trigger-Schutz)`() {
        // Gegenprobe zum Fix: trotz des tieferen gelernten Peaks darf Evas weiterlaufendes Residual nicht auslösen.
        val vad = calibratedVad()
        repeat(6) { vad.onFrame(-18.0, voiceLike = true) }
        repeat(10) { vad.onFrame(-29.0, voiceLike = true) }
        assertTrue(vad.isLearningDone)
        var hit = -1
        for (i in 1..500) {                                   // 10 s Eva-Residual, schwankend -31..-27 (voiceLike)
            val db = if ((i / 5) % 2 == 0) -31.0 else -27.0
            if (vad.onFrame(db, voiceLike = true)) { hit = i; break }
        }
        assertEquals("Evas Residual darf nach dem Onset-Skip nicht selbst auslösen", -1, hit)
        assertFalse(vad.triggered)
    }

    @Test
    fun `v3 - percentile - Interpolation und Grenzfaelle`() {
        assertEquals(-90.0, BargeInVad.percentile(emptyList(), 0.70), 0.001)    // leer → Stille
        assertEquals(-5.0, BargeInVad.percentile(listOf(-5.0), 0.70), 0.001)    // 1 Wert
        val ramp = (0..10).map { it.toDouble() }                                // 11 Werte 0..10
        assertEquals(7.0, BargeInVad.percentile(ramp, 0.70), 0.001)
        assertEquals(0.0, BargeInVad.percentile(ramp, 0.0), 0.001)
        assertEquals(10.0, BargeInVad.percentile(ramp, 1.0), 0.001)
        assertEquals(5.0, BargeInVad.percentile(listOf(0.0, 10.0), 0.5), 0.001)  // lineare Interpolation
        // 70. Perzentil ignoriert einen einzelnen lauten Ausreißer (Onset-Leftover): 9×-29 + 1×-10
        assertTrue(BargeInVad.percentile(List(9) { -29.0 } + listOf(-10.0), 0.70) <= -25.0)
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
