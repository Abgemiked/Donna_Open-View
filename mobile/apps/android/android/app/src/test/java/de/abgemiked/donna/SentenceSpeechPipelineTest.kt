package com.yourcompany.donna

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-Unit-Tests für die satzweise Sprach-Pipeline (UX-03/UX-04): Reihenfolge, Vorab-Laden,
 * echtes Ende (Auslöser fürs Auto-Relisten), Abbruch, Fallback. Der Ton selbst ist nur am Gerät prüfbar.
 */
class SentenceSpeechPipelineTest {

    private fun bytesOf(s: String) = s.toByteArray()
    private fun textOf(b: ByteArray) = String(b)

    private suspend fun settle() = delay(30)

    @Test
    fun `Saetze werden der Reihe nach gespielt und onFinished feuert erst nach dem letzten`() = runBlocking {
        val played = mutableListOf<String>()
        val finished = CompletableDeferred<Boolean>()
        val p = SentenceSpeechPipeline(
            scope = this, health = TtsHealth(),
            synthesize = { bytesOf(it) },
            play = { played += textOf(it) },
            fallbackSpeak = { false },
            onFinished = { finished.complete(it) },
        )
        p.enqueue("Eins.")
        p.enqueue("Zwei.")
        p.enqueue("Drei.")
        p.finish()
        assertTrue(withTimeout(2_000) { finished.await() })
        assertEquals(listOf("Eins.", "Zwei.", "Drei."), played)
    }

    @Test
    fun `naechster Satz wird vorab geladen, waehrend der aktuelle spricht - aber nur einer`() = runBlocking {
        val synthStarted = mutableListOf<String>()
        val playStarted = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Boolean>()
        var firstPlay = true
        val p = SentenceSpeechPipeline(
            scope = this, health = TtsHealth(),
            synthesize = { synthStarted += it; bytesOf(it) },
            play = {
                if (firstPlay) { firstPlay = false; playStarted.complete(Unit); gate.await() }
            },
            fallbackSpeak = { false },
            onFinished = { finished.complete(it) },
        )
        p.enqueue("Eins.")
        p.enqueue("Zwei.")
        p.enqueue("Drei.")
        withTimeout(2_000) { playStarted.await() }
        settle()
        // Satz 1 spricht noch (Gate zu): Satz 2 ist schon synthetisiert, Satz 3 noch nicht angefragt
        assertEquals(listOf("Eins.", "Zwei."), synthStarted)

        p.finish()
        gate.complete(Unit)
        withTimeout(2_000) { finished.await() }
        assertEquals(listOf("Eins.", "Zwei.", "Drei."), synthStarted)
    }

    @Test
    fun `onFinished feuert nicht solange die Wiedergabe noch laeuft`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var finishedCalls = 0
        val finished = CompletableDeferred<Boolean>()
        val p = SentenceSpeechPipeline(
            scope = this, health = TtsHealth(),
            synthesize = { bytesOf(it) },
            play = { gate.await() },
            fallbackSpeak = { false },
            onFinished = { finishedCalls++; finished.complete(it) },
        )
        p.enqueue("Eins.")
        p.finish()            // Stream ist zu Ende, aber der Ton laeuft noch
        settle()
        assertEquals("Auto-Relisten darf nicht vor dem echten TTS-Ende starten", 0, finishedCalls)
        gate.complete(Unit)
        assertTrue(withTimeout(2_000) { finished.await() })
        assertEquals(1, finishedCalls)
    }

    @Test
    fun `stop bricht die Wiedergabe ab und onFinished feuert nicht`() = runBlocking {
        val playStarted = CompletableDeferred<Unit>()
        var playCancelled = false
        var finishedCalls = 0
        val p = SentenceSpeechPipeline(
            scope = this, health = TtsHealth(),
            synthesize = { bytesOf(it) },
            play = {
                playStarted.complete(Unit)
                try { CompletableDeferred<Unit>().await() } finally { playCancelled = true }
            },
            fallbackSpeak = { false },
            onFinished = { finishedCalls++ },
        )
        p.enqueue("Eins.")
        p.enqueue("Zwei.")
        p.finish()
        withTimeout(2_000) { playStarted.await() }
        p.stop()
        settle()
        assertTrue(playCancelled)
        assertEquals(0, finishedCalls)
        // nach stop nimmt die Pipeline nichts mehr an
        p.enqueue("Drei.")
        settle()
        assertEquals(0, finishedCalls)
    }

    @Test
    fun `Server-TTS-Fehler - System-TTS springt ein und weitere Saetze fragen den Server nicht mehr`() = runBlocking {
        val synthCalls = mutableListOf<String>()
        val played = mutableListOf<String>()
        val fallback = mutableListOf<String>()
        val finished = CompletableDeferred<Boolean>()
        val p = SentenceSpeechPipeline(
            scope = this, health = TtsHealth(),
            synthesize = {
                synthCalls += it
                if (it == "Eins.") throw java.io.IOException("offline") else bytesOf(it)
            },
            play = { played += textOf(it) },
            fallbackSpeak = { fallback += it; true },
            onFinished = { finished.complete(it) },
        )
        for (s in listOf("Eins.", "Zwei.", "Drei.", "Vier.")) p.enqueue(s)
        p.finish()
        assertTrue(withTimeout(2_000) { finished.await() })
        assertEquals("Eins.", fallback.first())
        // „Zwei.“ war schon vorab angefragt; ab „Drei.“ geht es ohne neuen Serverversuch direkt per System-TTS
        assertEquals(listOf("Eins.", "Zwei."), synthCalls)
        assertEquals(listOf("Eins.", "Drei.", "Vier."), fallback)
        assertEquals(listOf("Zwei."), played)
    }

    @Test
    fun `Live-Guard (kein Audio) bleibt still und meldet spokeAnything=false`() = runBlocking {
        var plays = 0
        var fallbacks = 0
        val finished = CompletableDeferred<Boolean>()
        val p = SentenceSpeechPipeline(
            scope = this, health = TtsHealth(),
            synthesize = { null },
            play = { plays++ },
            fallbackSpeak = { fallbacks++; true },
            onFinished = { finished.complete(it) },
        )
        p.enqueue("Eins.")
        p.finish()
        assertFalse(withTimeout(2_000) { finished.await() })
        assertEquals(0, plays)
        assertEquals(0, fallbacks)
    }

    @Test
    fun `nicht bereites System-TTS zaehlt nicht als gesprochen`() = runBlocking {
        val finished = CompletableDeferred<Boolean>()
        val p = SentenceSpeechPipeline(
            scope = this, health = TtsHealth(),
            synthesize = { throw java.io.IOException("offline") },
            play = { },
            fallbackSpeak = { false },
            onFinished = { finished.complete(it) },
        )
        p.enqueue("Eins.")
        p.finish()
        assertFalse(withTimeout(2_000) { finished.await() })
    }

    @Test
    fun `Abspielfehler wird per System-TTS nachgeholt`() = runBlocking {
        val fallback = mutableListOf<String>()
        val finished = CompletableDeferred<Boolean>()
        val p = SentenceSpeechPipeline(
            scope = this, health = TtsHealth(),
            synthesize = { bytesOf(it) },
            play = { throw java.io.IOException("MediaPlayer-Fehler") },
            fallbackSpeak = { fallback += it; true },
            onFinished = { finished.complete(it) },
        )
        p.enqueue("Eins.")
        p.finish()
        assertTrue(withTimeout(2_000) { finished.await() })
        assertEquals(listOf("Eins."), fallback)
    }

    @Test
    fun `ohne Saetze endet die Pipeline sofort mit spokeAnything=false`() = runBlocking {
        val finished = CompletableDeferred<Boolean>()
        val p = SentenceSpeechPipeline(
            scope = this, health = TtsHealth(),
            synthesize = { bytesOf(it) },
            play = { },
            fallbackSpeak = { true },
            onFinished = { finished.complete(it) },
        )
        p.finish()
        assertFalse(withTimeout(2_000) { finished.await() })
    }

    @Test
    fun `werfender System-TTS-Fallback bringt die Pipeline nicht zum Absturz`() = runBlocking {
        val finished = CompletableDeferred<Boolean>()
        val p = SentenceSpeechPipeline(
            scope = this, health = TtsHealth(),
            synthesize = { throw java.io.IOException("offline") },
            play = { },
            fallbackSpeak = { throw IllegalStateException("TTS defekt") },
            onFinished = { finished.complete(it) },
        )
        p.enqueue("Eins.")
        p.enqueue("Zwei.")
        p.finish()
        assertFalse(withTimeout(2_000) { finished.await() })
    }

    @Test
    fun `haengender Server-TTS wird nach dem Timeout per System-TTS ersetzt`() = runBlocking {
        val fallback = mutableListOf<String>()
        val finished = CompletableDeferred<Boolean>()
        val p = SentenceSpeechPipeline(
            scope = this, health = TtsHealth(),
            synthesize = { delay(10_000); bytesOf(it) },
            play = { },
            fallbackSpeak = { fallback += it; true },
            onFinished = { finished.complete(it) },
            synthTimeoutMs = 80,
        )
        p.enqueue("Eins.")
        p.finish()
        assertTrue(withTimeout(3_000) { finished.await() })
        assertEquals(listOf("Eins."), fallback)
    }

    @Test
    fun `toter Server-TTS wird auch fuer spaetere Turns uebersprungen`() = runBlocking {
        val health = TtsHealth()
        var synthCalls = 0
        suspend fun turn(): List<String> {
            val fallback = mutableListOf<String>()
            val finished = CompletableDeferred<Boolean>()
            val p = SentenceSpeechPipeline(
                scope = this@runBlocking, health = health,
                synthesize = { synthCalls++; throw java.io.IOException("HTTP 501") },
                play = { },
                fallbackSpeak = { fallback += it; true },
                onFinished = { finished.complete(it) },
            )
            p.enqueue("Eins.")
            p.finish()
            withTimeout(2_000) { finished.await() }
            return fallback
        }
        assertEquals(listOf("Eins."), turn())
        assertEquals(1, synthCalls)
        assertTrue(health.isDead())
        // zweiter Turn: kein /tts-Roundtrip mehr, direkt System-TTS
        assertEquals(listOf("Eins."), turn())
        assertEquals(1, synthCalls)
    }

    @Test
    fun `verstreute Aussetzer sperren die Kette nicht - Erfolg zwischen zwei Fehlern setzt die Serie zurueck`() = runBlocking {
        // Produktivwerte: 2 Fehler IN FOLGE. Fehler, Erfolg, Fehler, Erfolg ... darf nie „tot“ werden (sonst dauerhaft Roboterstimme)
        val health = TtsHealth(
            cooldownMs = TtsHealth.CHAIN_COOLDOWN_MS, failureThreshold = TtsHealth.CHAIN_FAILURE_THRESHOLD,
        )
        val played = mutableListOf<String>()
        val fallback = mutableListOf<String>()
        val finished = CompletableDeferred<Boolean>()
        val p = SentenceSpeechPipeline(
            scope = this, health = health,
            synthesize = { if (it == "Eins." || it == "Drei." || it == "Fuenf.") throw java.io.IOException("Aussetzer") else bytesOf(it) },
            play = { played += textOf(it) },
            fallbackSpeak = { fallback += it; true },
            onFinished = { finished.complete(it) },
        )
        for (s in listOf("Eins.", "Zwei.", "Drei.", "Vier.", "Fuenf.", "Sechs.")) p.enqueue(s)
        p.finish()
        assertTrue(withTimeout(2_000) { finished.await() })
        assertEquals(listOf("Eins.", "Drei.", "Fuenf."), fallback)
        assertEquals(listOf("Zwei.", "Vier.", "Sechs."), played)
        assertFalse("Fehler waren nie 2 in Folge", health.isDead())
    }

    @Test
    fun `zwei Fehler in Folge machen die Kette tot, ein spaeterer Erfolg nach der Pause heilt sie`() = runBlocking {
        var now = 1_000L
        val health = TtsHealth(
            cooldownMs = TtsHealth.CHAIN_COOLDOWN_MS, clock = { now }, failureThreshold = TtsHealth.CHAIN_FAILURE_THRESHOLD,
        )
        suspend fun turn(fail: Boolean) {
            val finished = CompletableDeferred<Boolean>()
            val p = SentenceSpeechPipeline(
                scope = this@runBlocking, health = health,
                synthesize = { if (fail) throw java.io.IOException("down") else bytesOf(it) },
                play = { }, fallbackSpeak = { true }, onFinished = { finished.complete(it) },
            )
            p.enqueue("Eins.")
            p.finish()
            withTimeout(2_000) { finished.await() }
        }
        turn(fail = true)
        assertFalse(health.isDead())
        turn(fail = true)
        assertTrue(health.isDead())
        now += TtsHealth.CHAIN_COOLDOWN_MS + 1
        assertFalse(health.isDead())
        turn(fail = false)                 // Erfolg setzt die Serie zurueck ...
        turn(fail = true)                  // ... also ist EIN neuer Fehler wieder kein Ausfall
        assertFalse(health.isDead())
    }

    @Test
    fun `TtsHealth versucht den Server nach der Abkuehlzeit wieder`() {
        var now = 1_000L
        val h = TtsHealth(cooldownMs = 5_000, clock = { now })
        assertFalse(h.isDead())
        h.markFailed()
        assertTrue(h.isDead())
        now += 4_999
        assertTrue(h.isDead())
        now += 2
        assertFalse(h.isDead())
        h.markFailed(); h.reset()
        assertFalse(h.isDead())
    }

    @Test
    fun `uebersprungene Saetze verlaengern den Cooldown des toten Server-TTS nicht`() = runBlocking {
        var now = 1_000L
        val health = TtsHealth(cooldownMs = 5_000, clock = { now })
        health.markFailed()                 // echter Ausfall bei t=1000
        now = 5_000                         // noch tot → Pipeline ueberspringt den Server
        var synthCalls = 0
        val finished = CompletableDeferred<Boolean>()
        val p = SentenceSpeechPipeline(
            scope = this, health = health,
            synthesize = { synthCalls++; bytesOf(it) },
            play = { },
            fallbackSpeak = { true },
            onFinished = { finished.complete(it) },
        )
        p.enqueue("Eins.")
        p.enqueue("Zwei.")
        p.finish()
        assertTrue(withTimeout(2_000) { finished.await() })
        assertEquals("kein /tts-Roundtrip waehrend der Abkuehlzeit", 0, synthCalls)
        now = 6_001                         // 5 s nach dem ECHTEN Ausfall → Server wird wieder versucht
        assertFalse("Cooldown wurde faelschlich verlaengert", health.isDead())
    }
}
