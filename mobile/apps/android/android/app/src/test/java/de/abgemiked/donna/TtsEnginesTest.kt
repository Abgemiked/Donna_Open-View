package com.yourcompany.donna

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * JVM-Unit-Tests fuer den A/B-Test Edge <-> Piper (Voice-Batch): Engine-Wahl, Fallback-Kette pro Satz,
 * Fehlertoleranz gegen Netz-Aussetzer, WAV-Kodierung. Der Ton selbst ist nur am Geraet pruefbar.
 */
class TtsEnginesTest {

    // ── Engine-Wahl ──────────────────────────────────────────────────────────

    @Test
    fun `unbekannte oder fehlende Wahl faellt auf Edge`() {
        assertEquals(TtsEngines.EDGE, TtsEngines.normalize(null))
        assertEquals(TtsEngines.EDGE, TtsEngines.normalize(""))
        assertEquals(TtsEngines.EDGE, TtsEngines.normalize("kokoro"))
        assertEquals(TtsEngines.PIPER, TtsEngines.normalize("piper"))
        assertEquals(TtsEngines.EDGE, TtsEngines.DEFAULT)
    }

    @Test
    fun `die jeweils andere Engine ist Fallback`() {
        assertEquals(listOf("edge", "piper"), TtsEngines.order("edge"))
        assertEquals(listOf("piper", "edge"), TtsEngines.order("piper"))
        assertEquals(listOf("edge", "piper"), TtsEngines.order(null))
        assertEquals("piper", TtsEngines.other("edge"))
        assertEquals("edge", TtsEngines.other("piper"))
    }

    @Test
    fun `Edge-Stimmen sind weiblich und unbekannte fallen auf Katja`() {
        assertEquals("de-DE-KatjaNeural", TtsEngines.EDGE_VOICE_DEFAULT)
        assertEquals("de-DE-SeraphinaMultilingualNeural", TtsEngines.normalizeEdgeVoice("de-DE-SeraphinaMultilingualNeural"))
        assertEquals("de-DE-KatjaNeural", TtsEngines.normalizeEdgeVoice("de-DE-ConradNeural"))
        assertEquals("de-DE-KatjaNeural", TtsEngines.normalizeEdgeVoice(null))
    }

    @Test
    fun `Engine-Wahl nutzt dieselbe Prefs-Datei wie der Overlay`() {
        val overlay = java.io.File("src/main/java/com/yourcompany/donna/VoiceInputActivity.kt").readText()
        assertTrue(overlay.contains("PREFS = \"${TtsEngines.PREFS}\""))
    }

    // ── Piper-Modell: weiblich ───────────────────────────────────────────────

    @Test
    fun `Piper nutzt das weibliche Modell kerstin-low und laedt eager`() {
        val src = java.io.File("src/main/java/com/yourcompany/donna/PiperTTSModule.kt").readText()
        assertTrue(src.contains("vits-piper-de_DE-kerstin-low.tar.bz2"))
        assertTrue(src.contains("de_DE-kerstin-low.onnx"))
        assertFalse("maennliches Modell darf nicht mehr referenziert werden", src.contains("thorsten"))
        // eager: speakPiper startet KEINEN Download mehr, ensureModel ist der Einstieg
        assertTrue(src.contains("fun ensureModel"))
        val speak = src.substringAfter("fun speakPiper").substringBefore("fun stopPiper")
        assertFalse("speakPiper darf nicht lazy herunterladen", speak.contains("startModelDownload"))
        assertFalse(speak.contains("isDownloading"))
    }

    // ── Kette pro Satz ───────────────────────────────────────────────────────

    private fun chain(
        pref: String,
        edge: suspend (String) -> ByteArray?,
        piper: suspend (String) -> ByteArray?,
        usable: (String) -> Boolean = { true },
        ok: MutableList<String> = mutableListOf(),
        bad: MutableList<String> = mutableListOf(),
    ): SentenceSynthesizer {
        val backends = HashMap<String, suspend (String) -> ByteArray?>()
        backends[TtsEngines.EDGE] = edge
        backends[TtsEngines.PIPER] = piper
        return SentenceSynthesizer(
            order = { TtsEngines.order(pref) },
            backends = backends,
            isUsable = usable,
            onSuccess = { ok += it },
            onFailure = { bad += it },
        )
    }

    @Test
    fun `gewaehlte Engine gewinnt - die andere wird nicht gefragt`() = runBlocking {
        val calls = mutableListOf<String>()
        val s = chain("edge",
            edge = { calls += "edge"; "E".toByteArray() },
            piper = { calls += "piper"; "P".toByteArray() })
        assertEquals("E", String(s.synthesize("Hallo.")!!))
        assertEquals(listOf("edge"), calls)
        val s2 = chain("piper",
            edge = { calls += "edge"; "E".toByteArray() },
            piper = { calls += "piper"; "P".toByteArray() })
        assertEquals("P", String(s2.synthesize("Hallo.")!!))
        assertEquals(listOf("edge", "piper"), calls)
    }

    @Test
    fun `Edge faellt aus - Piper springt pro Satz automatisch ein`() = runBlocking {
        val bad = mutableListOf<String>()
        val ok = mutableListOf<String>()
        val s = chain("edge",
            edge = { throw IOException("HTTP 503") },
            piper = { "P:$it".toByteArray() }, ok = ok, bad = bad)
        assertEquals("P:Eins.", String(s.synthesize("Eins.")!!))
        assertEquals(listOf("edge"), bad)
        assertEquals(listOf("piper"), ok)
    }

    @Test
    fun `Piper nicht bereit - Edge springt ein`() = runBlocking {
        val s = chain("piper",
            edge = { "E".toByteArray() },
            piper = { throw IOException("Piper-Modell nicht bereit") })
        assertEquals("E", String(s.synthesize("Eins.")!!))
    }

    @Test
    fun `beide scheitern - letzte Ausnahme wird geworfen (Pipeline nimmt System-TTS)`() = runBlocking {
        val s = chain("edge",
            edge = { throw IOException("edge down") },
            piper = { throw IOException("piper down") })
        try {
            s.synthesize("Eins.")
            fail("erwartet: Ausnahme")
        } catch (e: IOException) {
            assertEquals("piper down", e.message)
        }
    }

    @Test
    fun `204 (null) zaehlt als Erfolg und wird nicht durch die andere Engine ersetzt`() = runBlocking {
        var piperCalls = 0
        val s = chain("edge", edge = { null }, piper = { piperCalls++; "P".toByteArray() })
        assertNull(s.synthesize("**"))
        assertEquals(0, piperCalls)
    }

    @Test
    fun `nicht nutzbare Engine wird uebersprungen (Edge als tot markiert)`() = runBlocking {
        var edgeCalls = 0
        val s = chain("edge",
            edge = { edgeCalls++; "E".toByteArray() },
            piper = { "P".toByteArray() },
            usable = { it != TtsEngines.EDGE })
        assertEquals("P", String(s.synthesize("Eins.")!!))
        assertEquals(0, edgeCalls)
    }

    @Test
    fun `Abbruch (Cancel) wird nicht als Fehler verschluckt`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val bad = mutableListOf<String>()
        val s = chain("edge",
            edge = { started.complete(Unit); CompletableDeferred<ByteArray?>().await() },
            piper = { "P".toByteArray() }, bad = bad)
        val job = async { s.synthesize("Eins.") }
        withTimeout(2_000) { started.await() }
        job.cancel()
        try { job.await(); fail("erwartet: Cancel") } catch (_: kotlinx.coroutines.CancellationException) {}
        assertTrue("Cancel ist kein Engine-Fehler", bad.isEmpty())
    }

    // ── Robustheit gegen Netz-Aussetzer (Edge-Health) ────────────────────────

    @Test
    fun `ein einzelner Edge-Fehler markiert Edge nicht als tot - zwei in Folge schon`() {
        var now = 1_000L
        val h = TtsHealth(cooldownMs = 45_000, clock = { now }, failureThreshold = 2)
        h.markFailed()
        assertFalse("ein Timeout ist kein Ausfall", h.isDead())
        h.markFailed()
        assertTrue(h.isDead())
        now += 44_999
        assertTrue(h.isDead())
        now += 2
        assertFalse("nach der Abkuehlzeit wieder versuchen", h.isDead())
    }

    @Test
    fun `ein Erfolg setzt die Fehlerserie zurueck`() {
        val h = TtsHealth(failureThreshold = 2)
        h.markFailed()
        h.markSuccess()
        h.markFailed()
        assertFalse("Fehler muessen in FOLGE auftreten", h.isDead())
        h.markFailed()
        assertTrue(h.isDead())
        h.markSuccess()
        assertTrue("waehrend der Pause heilt ein (verspaeteter) Erfolg nichts", h.isDead())
        h.reset()
        assertFalse(h.isDead())
    }

    @Test
    fun `Standard-Health markiert wie bisher sofort (Pipeline-Ebene)`() {
        val h = TtsHealth()
        h.markFailed()
        assertTrue(h.isDead())
    }

    @Test
    fun `Produktivwerte der Kette - 2 Fehler in Folge, 45 s Pause (nicht mehr 1 Fehler, 5 min)`() {
        assertEquals(2, TtsHealth.CHAIN_FAILURE_THRESHOLD)
        assertEquals(45_000L, TtsHealth.CHAIN_COOLDOWN_MS)
        // exakt wie in TtsHealth.chain / TtsHealth.edge gebaut, aber mit steuerbarer Uhr
        var now = 10_000L
        val h = TtsHealth(TtsHealth.CHAIN_COOLDOWN_MS, { now }, TtsHealth.CHAIN_FAILURE_THRESHOLD)
        h.markFailed()
        assertFalse("ein einzelner Timeout (z. B. waehrend des 64-MB-Downloads) sperrt nicht", h.isDead())
        h.markFailed()
        assertTrue(h.isDead())
        now += 44_999
        assertTrue(h.isDead())
        now += 2
        assertFalse("nach 45 s wird wieder versucht (nicht erst nach 5 min)", h.isDead())
    }

    @Test
    fun `chain und edge sind getrennte Instanzen, frisch nicht tot`() {
        TtsHealth.chain.reset(); TtsHealth.edge.reset()
        assertFalse(TtsHealth.chain.isDead())
        assertFalse(TtsHealth.edge.isDead())
        TtsHealth.edge.markFailed(); TtsHealth.edge.markFailed()
        assertTrue(TtsHealth.edge.isDead())
        assertFalse("Edge-Ausfall sperrt nicht die ganze Kette", TtsHealth.chain.isDead())
        TtsHealth.edge.reset()
    }

    @Test
    fun `Pipeline nutzt standardmaessig die Ketten-Health, nicht mehr shared`() {
        val src = java.io.File("src/main/java/com/yourcompany/donna/SentenceSpeechPipeline.kt").readText()
        assertTrue(src.contains("health: TtsHealth = TtsHealth.chain"))
        assertFalse(src.contains("TtsHealth.shared"))
    }

    // ── Review B2/M5/M6: Piper-Modul ─────────────────────────────────────────

    @Test
    fun `Piper - eine laufende Engine wird nie ersetzt oder freigegeben, sharedEngine nur unter der Sperre gelesen`() {
        val src = java.io.File("src/main/java/com/yourcompany/donna/PiperTTSModule.kt").readText()
        val build = src.substringAfter("private fun buildEngine").substringBefore("fun synthesizeWav")
        assertTrue("buildEngine ist idempotent", build.contains("if (sharedEngine != null) return true"))
        assertFalse("kein release() einer laufenden Engine", build.contains("sharedEngine?.let { try { it.release()"))
        // generate() nur ueber generateLocked (Lesen + Nutzen unter engineLock)
        assertTrue(src.contains("private fun generateLocked"))
        assertEquals("generate() genau einmal, in generateLocked", 1, Regex("\\.generate\\(").findAll(src).count())
        // nach initJob.join() zuerst auf isModelReady pruefen — kein Fake-Fortschritt, kein Neuaufbau
        val dl = src.substringAfter("private suspend fun startModelDownload")
        assertTrue(dl.indexOf("initJob.join()") < dl.indexOf("if (isModelReady)"))
        assertTrue(dl.indexOf("if (isModelReady)") < dl.indexOf("reportProgress(0)"))
    }

    @Test
    fun `Piper - Auto-Start nur im ungemessenen Netz, Lese-Timeout 30 s`() {
        val src = java.io.File("src/main/java/com/yourcompany/donna/PiperTTSModule.kt").readText()
        assertTrue(src.contains("fun ensureModel(force: Boolean, promise: Promise)"))
        assertTrue(src.contains("if (!force && isMeteredOrOffline())"))
        assertTrue(src.contains("isActiveNetworkMetered"))
        assertTrue(src.contains("readTimeout = 30_000"))
        assertFalse(src.contains("readTimeout = 300_000"))
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.ACCESS_NETWORK_STATE"))
    }

    @Test
    fun `Abbruch loest die Promises mit cancelled auf (Piper und Server-TTS)`() {
        val piper = java.io.File("src/main/java/com/yourcompany/donna/PiperTTSModule.kt").readText()
        val tts = java.io.File("src/main/java/com/yourcompany/donna/TTSModule.kt").readText()
        assertTrue(piper.contains("settle(\"cancelled\")"))
        assertEquals(2, Regex("promise\\.resolve\\(\"cancelled\"\\)").findAll(tts).count())
    }

    @Test
    fun `Server-TTS gibt Player und Temp-Datei bei einem Wurf frei`() {
        val tts = java.io.File("src/main/java/com/yourcompany/donna/TTSModule.kt").readText()
        val play = tts.substringAfter("private suspend fun playServerAudio").substringBefore("fun speakViaServer")
        assertTrue(play.contains("player.release()"))
        assertTrue(play.contains("tmpFile.delete()"))
        assertTrue(play.contains("throw e"))
    }

    // ── Pipeline + Kette: Satz-Streaming mit Netz-Roundtrip ──────────────────

    @Test
    fun `Pipeline - Edge-Aussetzer bei Satz 2 wird per Piper ueberbrueckt, Satz 3 geht wieder ueber Edge`() = runBlocking {
        val played = mutableListOf<String>()
        val finished = CompletableDeferred<Boolean>()
        var edgeCalls = 0
        val health = TtsHealth(failureThreshold = 2)
        val synth = SentenceSynthesizer(
            order = { TtsEngines.order("edge") },
            backends = HashMap<String, suspend (String) -> ByteArray?>().also {
                it[TtsEngines.EDGE] = { s -> edgeCalls++; if (s == "Zwei.") throw IOException("timeout") else "E:$s".toByteArray() }
                it[TtsEngines.PIPER] = { s -> "P:$s".toByteArray() }
            },
            isUsable = { e -> e != TtsEngines.EDGE || !health.isDead() },
            onSuccess = { e -> if (e == TtsEngines.EDGE) health.markSuccess() },
            onFailure = { e -> if (e == TtsEngines.EDGE) health.markFailed() },
        )
        val p = SentenceSpeechPipeline(
            scope = this, health = TtsHealth(),
            synthesize = { synth.synthesize(it) },
            play = { played += String(it) },
            fallbackSpeak = { false },
            onFinished = { finished.complete(it) },
        )
        for (s in listOf("Eins.", "Zwei.", "Drei.")) p.enqueue(s)
        p.finish()
        assertTrue(withTimeout(2_000) { finished.await() })
        assertEquals(listOf("E:Eins.", "P:Zwei.", "E:Drei."), played)
        assertEquals(3, edgeCalls)
    }

    // ── WAV ──────────────────────────────────────────────────────────────────

    @Test
    fun `WAV-Header und Samples stimmen`() {
        val wav = WavEncoder.pcm16Wav(floatArrayOf(0f, 1f, -1f, 2f, -2f, 0.5f), 22_050)
        assertEquals(44 + 6 * 2, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("fmt ", String(wav, 12, 4))
        assertEquals("data", String(wav, 36, 4))
        fun i32(o: Int) = (wav[o].toInt() and 0xFF) or ((wav[o + 1].toInt() and 0xFF) shl 8) or
            ((wav[o + 2].toInt() and 0xFF) shl 16) or ((wav[o + 3].toInt() and 0xFF) shl 24)
        fun i16(o: Int) = (wav[o].toInt() and 0xFF) or (wav[o + 1].toInt() shl 8)   // vorzeichenbehaftet
        assertEquals(36 + 12, i32(4))
        assertEquals(22_050, i32(24))
        assertEquals(22_050 * 2, i32(28))
        assertEquals(12, i32(40))
        assertEquals(0, i16(44))
        assertEquals(32767, i16(46))
        assertEquals(-32767, i16(48))
        assertEquals(32767, i16(50))    // 2.0 wird auf 1.0 begrenzt
        assertEquals(-32767, i16(52))
        assertEquals(16383, i16(54))
    }

    @Test
    fun `Dateiendung wird am Inhalt erkannt`() {
        val wav = WavEncoder.pcm16Wav(floatArrayOf(0f), 16_000)
        assertEquals("wav", WavEncoder.extensionFor(wav))
        assertEquals("mp3", WavEncoder.extensionFor(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)))
        assertEquals("mp3", WavEncoder.extensionFor(ByteArray(0)))
        assertArrayEquals("RIFF".toByteArray(), wav.copyOfRange(0, 4))
    }

    // ── Overlay-Verdrahtung ──────────────────────────────────────────────────

    @Test
    fun `Overlay nutzt die Engine-Kette und kennt MP3 und WAV`() {
        val src = java.io.File("src/main/java/com/yourcompany/donna/VoiceInputActivity.kt").readText()
        assertTrue(src.contains("sentenceSynth.synthesize("))
        assertTrue(src.contains("PREF_ENGINE"))
        assertTrue(src.contains("/tts?voice="))
        assertFalse("alte Piper-Voice-IDs duerfen nicht mehr an /tts gehen", src.contains("de_DE-mls-medium"))
        assertTrue(src.contains("TtsHealth.edge"))
    }
}
