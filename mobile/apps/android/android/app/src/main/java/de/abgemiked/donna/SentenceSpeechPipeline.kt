package com.yourcompany.donna

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Merkt prozessweit, ob eine Sprach-Quelle (Server-TTS /tts, Edge) gerade unbrauchbar ist (Timeout, Netzfehler),
 * damit nicht jeder Satz und jeder Turn erneut auf einen toten Endpunkt wartet (Latenz!). Nach [cooldownMs]
 * wird sie wieder versucht.
 *
 * [failureThreshold]: so viele Fehler IN FOLGE braucht es, bis die Quelle als tot gilt (Standard 1 = sofort).
 * Mobiles Netz hat Aussetzer — fuer Edge-TTS gilt 2, damit ein einzelner Timeout nicht minutenlang auf die
 * Ersatz-Stimme zwingt. Ein Erfolg ([markSuccess]) setzt die Fehlerserie zurueck.
 */
class TtsHealth(
    private val cooldownMs: Long = 5 * 60_000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val failureThreshold: Int = 1,
) {
    @Volatile private var failedAt = 0L
    @Volatile private var streak = 0

    fun isDead(): Boolean = failedAt != 0L && clock() - failedAt < cooldownMs
    fun markFailed() {
        streak++
        if (streak >= failureThreshold) failedAt = clock()
    }
    /**
     * Ein Erfolg beendet die Fehlerserie („IN FOLGE“). Waehrend einer laufenden Pause (tot) heilt er nichts: ein
     * schon vorab angefragter Satz, der NACH dem Ausfall fertig wird, darf die Pause nicht vorzeitig aufheben.
     */
    fun markSuccess() {
        if (isDead()) return
        streak = 0
        failedAt = 0L
    }
    fun reset() { failedAt = 0L; streak = 0 }

    companion object {
        /** Fehler in Folge, bis eine Quelle als tot gilt (mobiles Netz hat Aussetzer — ein Timeout ist kein Ausfall). */
        const val CHAIN_FAILURE_THRESHOLD = 2
        /** Pause nach dem Ausfall, danach wird die Quelle wieder versucht. */
        const val CHAIN_COOLDOWN_MS = 45_000L

        /**
         * Zustand der GESAMTEN Engine-Kette (Edge und Piper gescheitert → System-TTS) für alle Overlay-Aufrufe im
         * Prozess. Bewusst NICHT mehr „1 Fehler / 5 min“: ein einzelner Edge-Timeout (z. B. waehrend des
         * 64-MB-Piper-Downloads, der die Leitung fuellt) darf nicht 5 Minuten auf System-TTS sperren.
         */
        val chain = TtsHealth(cooldownMs = CHAIN_COOLDOWN_MS, failureThreshold = CHAIN_FAILURE_THRESHOLD)

        /** Edge-TTS einzeln: gleiche Werte — Piper-lokal uebernimmt waehrend der Pause ohne Netz-Wartezeit. */
        val edge = TtsHealth(cooldownMs = CHAIN_COOLDOWN_MS, failureThreshold = CHAIN_FAILURE_THRESHOLD)
    }
}

/**
 * Satzweise Sprachausgabe (UX-03): Der erste Satz wird sofort gesprochen, der nächste wird schon
 * synthetisiert (vorab geladen), während der aktuelle läuft. [onFinished] feuert erst, wenn die
 * Wiedergabe WIRKLICH zu Ende ist (Completion-Callback von Player/System-TTS) und [finish] gerufen wurde —
 * das ist der Auslöser für das Auto-Relisten.
 *
 * Reine Koroutinen-Logik; Netzwerk und Audio kommen als Funktionen herein (im Test durch Fakes ersetzt).
 *
 * Alle Methoden vom Haupt-Thread aus rufen; [scope] sollte auf Dispatchers.Main laufen.
 */
class SentenceSpeechPipeline(
    private val scope: CoroutineScope,
    /** Satz → Audio-Bytes. null = bewusst kein Audio (Live-Guard, HTTP 204). Wirft bei Fehlern. */
    private val synthesize: suspend (String) -> ByteArray?,
    /** Spielt Audio ab und kehrt zurück, wenn die Wiedergabe beendet ist. Bei Abbruch (Cancel) stoppen. */
    private val play: suspend (ByteArray) -> Unit,
    /**
     * Offline-/Fehler-Fallback (System-TTS): kehrt zurück, wenn der Satz zu Ende gesprochen ist.
     * Ergebnis: true = es war Ton zu hören, false = nichts gesprochen (z. B. System-TTS nicht bereit).
     * Darf werfen — die Pipeline fängt das ab.
     */
    private val fallbackSpeak: suspend (String) -> Boolean,
    /** Alles gesprochen. [spokeAnything] = es war tatsächlich Ton (auch System-TTS) zu hören. */
    private val onFinished: (spokeAnything: Boolean) -> Unit,
    private val health: TtsHealth = TtsHealth.chain,
    /**
     * Obergrenze pro Satz für die Synthese (Engine-Kette: Edge-Versuch + Piper-lokal) — danach System-TTS
     * (Latenz-Schutz). Der Edge-Versuch hat eigene, kuerzere Socket-Timeouts; Piper braucht ~0,1-0,5 s/Satz.
     */
    private val synthTimeoutMs: Long = 8_000L,
) {
    private sealed class Synth {
        class Audio(val bytes: ByteArray) : Synth()
        object Silent : Synth()      // bewusst kein Audio (Live-Guard)
        object Failed : Synth()      // Server-TTS nicht nutzbar → Fallback
    }

    private class Item(val text: String, val synth: Deferred<Synth>?)

    private val sentences = Channel<String>(Channel.UNLIMITED)
    // Rendezvous: der Vorab-Lader startet höchstens EINEN Satz im Voraus
    private val prepared = Channel<Item>(Channel.RENDEZVOUS)

    private var loaderJob: Job? = null
    private var playerJob: Job? = null
    private var begun = false
    private var spokeAnything = false
    @Volatile private var stopped = false

    /** Satz zur Ausgabe vormerken. */
    fun enqueue(sentence: String) {
        if (stopped) return
        ensureStarted()
        sentences.trySend(sentence)
    }

    /** Keine weiteren Sätze mehr — nach der Ausgabe des letzten feuert [onFinished]. */
    fun finish() {
        if (stopped) return
        ensureStarted()
        sentences.close()
    }

    /** Sofort abbrechen (Stopp, Unterbrechen, onPause). [onFinished] feuert dann NICHT. */
    fun stop() {
        stopped = true
        sentences.close()
        loaderJob?.cancel()
        playerJob?.cancel()
        prepared.cancel()
    }

    private fun ensureStarted() {
        if (begun) return
        begun = true

        loaderJob = scope.launch {
            try {
                for (s in sentences) {
                    // Fehler der Synthese werden im Ergebnis transportiert (kein Absturz über die Eltern-Koroutine)
                    val synth = if (health.isDead()) null else async {
                        try {
                            withTimeout(synthTimeoutMs) { synthesize(s) }?.let { Synth.Audio(it) } ?: Synth.Silent
                        } catch (_: TimeoutCancellationException) {
                            Synth.Failed
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            Synth.Failed
                        }
                    }
                    prepared.send(Item(s, synth))
                }
            } finally {
                prepared.close()
            }
        }

        playerJob = scope.launch {
            for (item in prepared) {
                speakItem(item)
            }
            if (!stopped) onFinished(spokeAnything)
        }
    }

    private suspend fun speakItem(item: Item) {
        val result = item.synth?.await() ?: Synth.Failed
        when (result) {
            is Synth.Silent -> {
                // Der Server hat geantwortet (z. B. 204): Fehlerserie beenden — „2 Fehler IN FOLGE“ darf nicht
                // zu „2 Fehler seit Prozessstart“ verkommen. Uebersprungene Saetze (synth == null) zaehlen nicht.
                if (item.synth != null) health.markSuccess()
            }
            is Synth.Failed -> {
                // Server-TTS nicht erreichbar → ab jetzt (auch in späteren Turns) direkt System-TTS.
                // Nur ein ECHTER Fehlversuch zählt: übersprungene Sätze (synth == null, weil der Server schon
                // als tot gilt) dürfen den Cooldown nicht endlos verlängern, sonst wird /tts nie wieder versucht.
                if (item.synth != null) health.markFailed()
                speakFallback(item.text)
            }
            is Synth.Audio -> {
                spokeAnything = true
                // erfolgreich synthetisiert → Fehlerserie der Kette zuruecksetzen (siehe oben)
                health.markSuccess()
                try {
                    play(result.bytes)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Abspielen fehlgeschlagen → diesen Satz per System-TTS nachholen
                    speakFallback(item.text)
                }
            }
        }
    }

    private suspend fun speakFallback(text: String) {
        try {
            if (fallbackSpeak(text)) spokeAnything = true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // System-TTS defekt → Satz überspringen, Pipeline läuft weiter
        }
    }
}
