package com.yourcompany.donna

import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream

/**
 * Voice-Batch: A/B-Test zweier Sprachausgaben mit gegenseitigem Fallback.
 *  - [EDGE]  Edge-TTS ueber das Donna-Backend (`POST /tts` -> audio/mpeg), Cloud, Netz noetig
 *  - [PIPER] Piper on-device (sherpa-onnx, `de_DE-kerstin-low`, weiblich), offline
 *
 * Die Wahl liegt in den SharedPreferences [PREFS]/[PREF_ENGINE] (gleiche Datei wie die Overlay-Einstellungen)
 * und wird von JS ueber `PiperTTS.setTtsEngine()` geschrieben — Overlay und Haupt-Chat lesen dieselbe Quelle.
 * Reine Logik, JVM-testbar. Die jeweils andere Engine ist automatischer Fallback, danach System-TTS.
 */
object TtsEngines {
    const val EDGE = "edge"
    const val PIPER = "piper"
    /** Edge zuerst: beste Qualitaet; Piper springt bei Netzproblemen ein. */
    const val DEFAULT = EDGE

    /** Gleiche Datei wie die Overlay-Einstellungen (VoiceInputActivity.PREFS). */
    const val PREFS = "donna_overlay_prefs"
    const val PREF_ENGINE = "tts_engine"
    const val PREF_EDGE_VOICE = "tts_edge_voice"

    const val EDGE_VOICE_KATJA = "de-DE-KatjaNeural"
    const val EDGE_VOICE_SERAPHINA = "de-DE-SeraphinaMultilingualNeural"
    const val EDGE_VOICE_DEFAULT = EDGE_VOICE_KATJA
    val EDGE_VOICES: List<String> = listOf(EDGE_VOICE_KATJA, EDGE_VOICE_SERAPHINA)

    fun normalize(raw: String?): String = if (raw == PIPER) PIPER else EDGE

    fun other(engine: String): String = if (normalize(engine) == PIPER) EDGE else PIPER

    /** Reihenfolge der Versuche: gewaehlte Engine zuerst, dann die andere. */
    fun order(pref: String?): List<String> {
        val first = normalize(pref)
        return listOf(first, other(first))
    }

    fun normalizeEdgeVoice(raw: String?): String = if (raw != null && raw in EDGE_VOICES) raw else EDGE_VOICE_DEFAULT
}

/**
 * Synthese EINES Satzes ueber die Engine-Kette. Probiert die Engines der Reihe nach; der erste Erfolg gewinnt.
 * `null` vom Backend (HTTP 204 = bewusst kein Audio) ist ein Erfolg und wird durchgereicht.
 * Scheitern alle, wird die letzte Ausnahme geworfen — die [SentenceSpeechPipeline] weicht dann auf System-TTS aus.
 *
 * @param isUsable Engine ueberhaupt versuchen? (Piper: Modell bereit; Edge: nicht gerade als tot markiert)
 */
class SentenceSynthesizer(
    private val order: () -> List<String>,
    private val backends: Map<String, suspend (String) -> ByteArray?>,
    private val isUsable: (String) -> Boolean = { true },
    private val onSuccess: (String) -> Unit = {},
    private val onFailure: (String) -> Unit = {},
) {
    suspend fun synthesize(text: String): ByteArray? {
        var last: Exception? = null
        for (engine in order()) {
            val backend = backends[engine] ?: continue
            if (!isUsable(engine)) continue
            try {
                val audio = backend(text)
                onSuccess(engine)
                return audio
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onFailure(engine)
                last = e
            }
        }
        throw last ?: IllegalStateException("keine TTS-Engine verfuegbar")
    }
}

/** PCM-Float -> 16-bit-Mono-WAV (MediaPlayer-faehig). Piper liefert Float-Samples; der Overlay spielt Bytes. */
object WavEncoder {
    fun pcm16Wav(samples: FloatArray, sampleRate: Int): ByteArray {
        val dataLen = samples.size * 2
        val out = ByteArrayOutputStream(44 + dataLen)
        fun int32(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
        fun int16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        int32(36 + dataLen)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        int32(16)               // fmt-Chunk-Groesse
        int16(1)                // PCM
        int16(1)                // Mono
        int32(sampleRate)
        int32(sampleRate * 2)   // Byte-Rate (Mono, 16 bit)
        int16(2)                // Block-Align
        int16(16)               // Bits pro Sample
        out.write("data".toByteArray(Charsets.US_ASCII))
        int32(dataLen)
        for (s in samples) {
            val clipped = s.coerceIn(-1f, 1f)
            int16((clipped * 32767f).toInt())
        }
        return out.toByteArray()
    }

    /** Dateiendung fuer den Temp-File: WAV ("RIFF") vs. MP3 (Edge). MediaPlayer sniffed zwar, aber eindeutig ist sicherer. */
    fun extensionFor(bytes: ByteArray): String =
        if (bytes.size >= 4 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()) "wav" else "mp3"
}
