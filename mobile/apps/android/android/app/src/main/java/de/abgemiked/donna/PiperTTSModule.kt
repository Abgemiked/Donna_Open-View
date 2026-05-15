package com.yourcompany.donna

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PiperTTSModule — On-Device TTS via Piper ONNX (Thorsten Emotional, Deutsch).
 *
 * DONNA-192: Ersetzt Cloud-TTS durch lokale ONNX-Inferenz (~80-150ms Latenz).
 * DONNA-195: Thorsten Emotional Medium (8 Emotionen) statt Kerstin Low (monoton).
 *
 * Bundle: vits-piper-de_DE-thorsten_emotional-medium.tar.bz2 von k2-fsa/sherpa-onnx GitHub Releases.
 * Enthält: de_DE-thorsten_emotional-medium.onnx + tokens.txt + espeak-ng-data/
 * Lazy-Download beim ersten speakPiper()-Aufruf.
 * Fallback auf Android System TTS (TTSModule) wenn Modell noch nicht geladen.
 *
 * JS-Interface:
 *   isPiperReady(): Promise<boolean>
 *   getPiperStatus(): Promise<string>   // "ready" | "downloading" | "not_downloaded"
 *   speakPiper(text: string): Promise<string>  // "ok" | "fallback"
 *   stopPiper(): void
 *   downloadModel(): Promise<void>  // expliziter Download-Trigger
 */
class PiperTTSModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val NAME = "PiperTTS"

        // DONNA-192 Final Fix: Komplettes Modell-Bundle als tar.bz2 von k2-fsa GitHub Releases.
        // Enthält model.onnx + tokens.txt + espeak-ng-data/ (benötigt für espeak-ng Phonemizer).
        // dataDir="" crasht mit "Not a model using characters as modeling unit" → System.exit()
        // im nativen Code, nicht fangbar. Bundle-Ansatz ist die kanonische sherpa-onnx-Methode.
        private const val BUNDLE_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-de_DE-thorsten_emotional-medium.tar.bz2"
        private const val MODEL_SUBDIR = "vits-piper-de_DE-thorsten_emotional-medium"
        private const val MODEL_FILENAME = "de_DE-thorsten_emotional-medium.onnx"
        private const val TOKENS_FILENAME = "tokens.txt"
        private const val ESPEAK_DATA_DIRNAME = "espeak-ng-data"
        private const val MODEL_DIR_NAME = "piper_tts"

        // USAGE_ASSISTANT-Routing (Samsung One UI 8 "KI-Stimme"-Lautstärkeregler)
        private val ASSISTANT_AUDIO_ATTRS: AudioAttributes =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            } else {
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    @Volatile private var ttsEngine: OfflineTts? = null
    @Volatile private var isModelReady = false
    // AtomicBoolean: compareAndSet() ist atomar — verhindert Race Condition beim Download-Start.
    private val isDownloading = AtomicBoolean(false)
    private var speakJob: Job? = null
    private var audioTrack: AudioTrack? = null

    // Verzeichnis-Hierarchie: piper_tts/ → vits-piper-de_DE-kerstin-low/ → model + espeak-ng-data/
    private val modelDir: File get() = File(reactContext.filesDir, MODEL_DIR_NAME)
    private val extractedDir: File get() = File(modelDir, MODEL_SUBDIR)
    private val modelFile: File get() = File(extractedDir, MODEL_FILENAME)
    private val tokensFile: File get() = File(extractedDir, TOKENS_FILENAME)
    private val espeakDir: File get() = File(extractedDir, ESPEAK_DATA_DIRNAME)

    init {
        scope.launch(Dispatchers.IO) {
            val readyMarker = File(modelDir, ".sherpa_ready")
            if (readyMarker.exists()) {
                // .sherpa_ready vorhanden: alle drei Komponenten prüfen (model + tokens + espeak-ng-data)
                if (modelFile.exists() && tokensFile.exists() && espeakDir.exists()) {
                    initEngine()
                } else {
                    android.util.Log.w(NAME,
                        "Piper: Dateien fehlen trotz .sherpa_ready → modelDir gelöscht (Re-Download nötig)")
                    modelDir.deleteRecursively()
                }
            } else if (modelDir.exists()) {
                // Kein .sherpa_ready aber modelDir vorhanden: altes/inkompatibles Modell → bereinigen
                android.util.Log.w(NAME,
                    "Piper: kein .sherpa_ready → altes/inkompatibles Modell gelöscht")
                modelDir.deleteRecursively()
            }
        }
    }

    override fun getName() = NAME

    // ── Status-Abfragen ──────────────────────────────────────────────────────

    @ReactMethod
    fun isPiperReady(promise: Promise) {
        promise.resolve(isModelReady && ttsEngine != null)
    }

    @ReactMethod
    fun getPiperStatus(promise: Promise) {
        val status = when {
            isModelReady && ttsEngine != null -> "ready"
            isDownloading.get() -> "downloading"
            else -> "not_downloaded"
        }
        promise.resolve(status)
    }

    // ── TTS sprechen ─────────────────────────────────────────────────────────

    /**
     * Spricht Text via Piper ONNX. Startet Lazy-Download falls nötig.
     * Promise: "ok" = Audio läuft, "fallback" = Modell noch nicht bereit.
     */
    @ReactMethod
    fun speakPiper(text: String, promise: Promise) {
        val clean = cleanText(text)
        if (clean.isEmpty()) { promise.resolve("empty"); return }

        if (!isModelReady || ttsEngine == null) {
            // Modell noch nicht bereit → Auto-Download starten (einmalig, race-condition-sicher)
            if (isDownloading.compareAndSet(false, true)) {
                scope.launch {
                    try { startModelDownload(alreadyLocked = true) }
                    catch (e: Exception) {
                        android.util.Log.e(NAME, "Auto-Download fehlgeschlagen: ${e.message}", e)
                        isDownloading.set(false)
                    }
                }
            }
            promise.resolve("fallback")
            return
        }

        speakJob?.cancel()
        stopAudioTrack()

        speakJob = scope.launch {
            try {
                val audio = withContext(Dispatchers.Default) {
                    val engine = ttsEngine ?: throw IllegalStateException("TTS engine null")
                    engine.generate(text = clean, sid = 0, speed = 0.85f)
                }
                if (!isActive) return@launch
                promise.resolve("ok")
                withContext(Dispatchers.IO) {
                    playAudio(audio.samples, audio.sampleRate)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w(NAME, "Piper TTS fehlgeschlagen: ${e.message}")
                promise.resolve("fallback")
            }
        }
    }

    @ReactMethod
    fun stopPiper() {
        speakJob?.cancel()
        speakJob = null
        stopAudioTrack()
    }

    // ── Modell-Download ──────────────────────────────────────────────────────

    /**
     * Expliziter Download-Trigger (z.B. aus Settings-Screen).
     * Emittet Events: PiperTTS.downloadProgress { progress: 0-100 }
     *                 PiperTTS.downloadComplete {}
     *                 PiperTTS.downloadError { message: string }
     */
    @ReactMethod
    fun downloadModel(promise: Promise) {
        if (isModelReady) { promise.resolve(null); return }
        if (!isDownloading.compareAndSet(false, true)) {
            promise.reject("ALREADY_DOWNLOADING", "Download läuft bereits")
            return
        }
        scope.launch {
            try {
                startModelDownload(alreadyLocked = true)
                promise.resolve(null)
            } catch (e: Exception) {
                isDownloading.set(false)
                promise.reject("DOWNLOAD_FAILED", e.message, e)
            }
        }
    }

    // ── Interne Logik ────────────────────────────────────────────────────────

    private suspend fun startModelDownload(alreadyLocked: Boolean = false) {
        if (!alreadyLocked && !isDownloading.compareAndSet(false, true)) return
        emitEvent("PiperTTS.downloadProgress", Arguments.createMap().apply { putInt("progress", 0) })

        try {
            withContext(Dispatchers.IO) {
                modelDir.mkdirs()

                // Bereits vollständig extrahiert?
                if (modelFile.exists() && tokensFile.exists() && espeakDir.exists()) {
                    android.util.Log.i(NAME, "Piper: Bundle bereits vorhanden, überspringe Download")
                    return@withContext
                }

                // Unvollständige Extraktion bereinigen
                if (extractedDir.exists()) extractedDir.deleteRecursively()

                val bundleFile = File(modelDir, "bundle.tar.bz2")

                // Bundle herunterladen (~82MB: model.onnx 63MB + espeak-ng-data 18MB + rest)
                downloadFile(
                    url = BUNDLE_URL,
                    dest = bundleFile,
                    onProgress = { pct ->
                        emitEvent("PiperTTS.downloadProgress",
                            Arguments.createMap().apply { putInt("progress", (pct * 90f).toInt()) })
                    }
                )

                emitEvent("PiperTTS.downloadProgress",
                    Arguments.createMap().apply { putInt("progress", 90) })

                // tar.bz2 extrahieren → modelDir/vits-piper-de_DE-kerstin-low/ (Apache Commons Compress)
                extractTarBz2(bundleFile, modelDir)
                bundleFile.delete()

                emitEvent("PiperTTS.downloadProgress",
                    Arguments.createMap().apply { putInt("progress", 95) })

                // Vollständigkeits-Check: alle drei Komponenten müssen vorhanden sein
                if (!modelFile.exists() || modelFile.length() == 0L)
                    throw IllegalStateException("model.onnx fehlt oder leer nach Extraktion")
                if (!tokensFile.exists() || tokensFile.length() == 0L)
                    throw IllegalStateException("tokens.txt fehlt oder leer nach Extraktion")
                if (!espeakDir.exists())
                    throw IllegalStateException("espeak-ng-data fehlt nach Extraktion")
            }

            emitEvent("PiperTTS.downloadProgress", Arguments.createMap().apply { putInt("progress", 100) })
            initEngine()
            emitEvent("PiperTTS.downloadComplete", Arguments.createMap())

        } catch (e: Exception) {
            android.util.Log.e(NAME, "Modell-Download fehlgeschlagen: ${e.message}", e)
            // Bei Fehler alles löschen — kein .sherpa_ready gesetzt → kein Crash-Loop
            modelDir.deleteRecursively()
            emitEvent("PiperTTS.downloadError",
                Arguments.createMap().apply { putString("message", e.message ?: "Unbekannter Fehler") })
        } finally {
            isDownloading.set(false)
        }
    }

    private fun initEngine() {
        if (!modelFile.exists() || !tokensFile.exists() || !espeakDir.exists()) {
            android.util.Log.w(NAME,
                "Piper: Dateien unvollständig — model=${modelFile.exists()}, " +
                "tokens=${tokensFile.exists()}, espeak=${espeakDir.exists()}")
            isModelReady = false
            return
        }

        val vitsConfig = OfflineTtsVitsModelConfig(
            model = modelFile.absolutePath,
            lexicon = "",
            tokens = tokensFile.absolutePath,
            // DONNA-192 Fix: espeak-ng-data Verzeichnis — erforderlich für espeak-ng Phonemizer.
            // Ohne diesen Pfad ruft sherpa-onnx System.exit() auf → unkontrollierbarer App-Crash.
            dataDir = espeakDir.absolutePath,
            noiseScale = 0.667f,
            noiseScaleW = 0.65f,
            lengthScale = 1.0f,
        )
        val modelConfig = OfflineTtsModelConfig(
            vits = vitsConfig,
            numThreads = 2,
            debug = false,
            provider = "cpu",
        )
        val ttsConfig = OfflineTtsConfig(model = modelConfig)

        ttsEngine?.let { try { it.release() } catch (_: Exception) {} }

        // Crash-Protection: sherpa-onnx kann bei Config-Fehlern System.exit() aufrufen.
        // try/catch(Throwable) fängt Java-Exceptions — bei System.exit() ist es zu spät,
        // aber fehlerhafte Pfade oder Datei-Fehler werden als Exception gemeldet.
        ttsEngine = try {
            OfflineTts(assetManager = null, config = ttsConfig)
        } catch (t: Throwable) {
            android.util.Log.e(NAME, "OfflineTts Init fatal: ${t.message}", t)
            isModelReady = false
            extractedDir.deleteRecursively()
            File(modelDir, ".sherpa_ready").delete()
            return
        }

        isModelReady = true
        File(modelDir, ".sherpa_ready").createNewFile()
        android.util.Log.i(NAME,
            "Piper TTS Engine bereit — model=${modelFile.name}, dataDir=${espeakDir.absolutePath}")
    }

    private fun extractTarBz2(archiveFile: File, destDir: File) {
        val destCanonical = destDir.canonicalPath
        BZip2CompressorInputStream(archiveFile.inputStream().buffered()).use { bzip2 ->
            TarArchiveInputStream(bzip2).use { tar ->
                var entry = tar.nextTarEntry
                while (entry != null) {
                    val outFile = File(destDir, entry.name)
                    // Path-Traversal-Schutz: verhindert ../../../-Einträge in bösartigen Archiven
                    val canonical = outFile.canonicalPath
                    if (!canonical.startsWith(destCanonical + File.separator) &&
                        canonical != destCanonical) {
                        android.util.Log.w(NAME, "Path Traversal blockiert: ${entry.name}")
                        entry = tar.nextTarEntry
                        continue
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out -> tar.copyTo(out) }
                    }
                    entry = tar.nextTarEntry
                }
            }
        }
    }

    private fun playAudio(samples: FloatArray, sampleRate: Int) {
        stopAudioTrack()
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(ASSISTANT_AUDIO_ATTRS)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack = track
        track.play()
        val chunkSize = 4096
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + chunkSize, samples.size)
            track.write(samples, offset, end - offset, AudioTrack.WRITE_BLOCKING)
            offset = end
        }
        track.stop()
        track.release()
        audioTrack = null
    }

    private fun stopAudioTrack() {
        audioTrack?.let {
            try { if (it.state == AudioTrack.STATE_INITIALIZED) it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        audioTrack = null
    }

    private fun downloadFile(url: String, dest: File, onProgress: (Float) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 300_000  // 5min für ~82MB Bundle
            instanceFollowRedirects = true
        }
        conn.connect()
        val totalBytes = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
        var downloaded = 0L
        conn.inputStream.use { input ->
            FileOutputStream(dest).use { output ->
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } >= 0) {
                    output.write(buf, 0, n)
                    downloaded += n
                    if (totalBytes > 0) onProgress(downloaded.toFloat() / totalBytes)
                }
            }
        }
    }

    private fun cleanText(text: String): String = text
        .replace(Regex("(\\d+)\\s*°C"), "$1 Grad")
        .replace(Regex("(\\d+)\\s*°"), "$1 Grad")
        .replace(Regex("(\\d+)\\s*%"), "$1 Prozent")
        .replace("km/h", "Kilometer pro Stunde")
        .replace("m/s", "Meter pro Sekunde")
        .replace(Regex("\\*+"), "")
        .replace(Regex("#{1,6}\\s"), "")
        .replace(Regex("https?://\\S+"), "")
        .replace(Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)"), "$1")
        .replace("•", "")
        .trim()

    private fun emitEvent(event: String, params: com.facebook.react.bridge.WritableMap) {
        try {
            reactApplicationContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(event, params)
        } catch (_: Exception) {}
    }

    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}

    fun shutdown() {
        speakJob?.cancel()
        scope.cancel()
        stopAudioTrack()
        ttsEngine?.let { try { it.release() } catch (_: Exception) {} }
        ttsEngine = null
        isModelReady = false
    }
}
