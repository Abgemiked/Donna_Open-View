package com.yourcompany.donna

import android.content.Context
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
 * PiperTTSModule — On-Device TTS via Piper ONNX (Kerstin Low, Deutsch, WEIBLICH).
 *
 * DONNA-192: Ersetzt Cloud-TTS durch lokale ONNX-Inferenz (~80-150ms Latenz).
 * Voice-Batch: Modell `de_DE-kerstin-low` (weiblich) (das frühere männliche Modell ist abgelöst).
 * Partner der Edge-TTS-Stimme im A/B-Test (siehe [TtsEngines]); die jeweils andere Engine ist Fallback.
 *
 * Bundle: vits-piper-de_DE-kerstin-low.tar.bz2 von k2-fsa/sherpa-onnx GitHub Releases (~64 MB).
 * Enthaelt: de_DE-kerstin-low.onnx + tokens.txt + espeak-ng-data/
 * EAGER-Download: [ensureModel] beim App-Start / im Onboarding (mit sichtbarem Fortschritt) — NICHT lazy beim
 * ersten speakPiper(). Ein Modell-Wechsel wird beim Start erkannt: das alte Bundle
 * liegt in einem anderen Unterordner, die Pruefung scheitert, modelDir wird bereinigt und neu geladen.
 *
 * JS-Interface:
 *   isPiperReady(): Promise<boolean>
 *   getPiperStatus(): Promise<string>   // "ready" | "downloading" | "not_downloaded"
 *   getPiperState(): Promise<{status, progress, error}>   // fuer Fortschritts-Anzeige
 *   speakPiper(text: string): Promise<string>  // "ok" | "fallback" | "empty"
 *   stopPiper(): void
 *   ensureModel(): Promise<string>  // "ready" | "downloading" | "started" — startet den Download, wartet nicht
 *   downloadModel(): Promise<void>  // expliziter Download, wartet auf das Ende
 *   getTtsEngine()/setTtsEngine(engine)  // A/B-Wahl "edge" | "piper" (SharedPreferences, auch fuer den Overlay)
 *   getEdgeVoice()/setEdgeVoice(voice)
 */
class PiperTTSModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val NAME = "PiperTTS"

        // Komplettes Modell-Bundle als tar.bz2 von k2-fsa GitHub Releases.
        // Enthält model.onnx + tokens.txt + espeak-ng-data/ (benötigt für espeak-ng Phonemizer).
        // dataDir="" crasht mit "Not a model using characters as modeling unit" → System.exit()
        // im nativen Code, nicht fangbar. Bundle-Ansatz ist die kanonische sherpa-onnx-Methode.
        const val BUNDLE_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-de_DE-kerstin-low.tar.bz2"
        const val MODEL_SUBDIR = "vits-piper-de_DE-kerstin-low"
        const val MODEL_FILENAME = "de_DE-kerstin-low.onnx"
        private const val TOKENS_FILENAME = "tokens.txt"
        private const val ESPEAK_DATA_DIRNAME = "espeak-ng-data"
        private const val MODEL_DIR_NAME = "piper_tts"
        private const val READY_MARKER = ".sherpa_ready"

        /** Sprechtempo: Kerstin-low ist von Haus aus zuegig — leicht gebremst klingt sie ruhiger. */
        private const val SPEAK_SPEED = 0.95f

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

        // ── Prozessweit geteilte Engine (Haupt-App-Modul UND Overlay nutzen dieselbe) ──────────────────
        private val engineLock = Any()
        @Volatile private var sharedEngine: OfflineTts? = null

        fun isEngineReady(): Boolean = sharedEngine != null

        private fun modelRoot(filesDir: File) = File(filesDir, MODEL_DIR_NAME)
        private fun extracted(filesDir: File) = File(modelRoot(filesDir), MODEL_SUBDIR)

        private fun bundleComplete(filesDir: File): Boolean {
            val dir = extracted(filesDir)
            return File(dir, MODEL_FILENAME).exists() && File(dir, TOKENS_FILENAME).exists() &&
                File(dir, ESPEAK_DATA_DIRNAME).exists()
        }

        /**
         * Laedt die Engine von der Platte, falls das Bundle vollstaendig ist und noch keine Engine laeuft.
         * Auch fuer den Overlay (Haupt-App-Modul evtl. nicht gestartet). Blockiert (ONNX-Laden ~0,5-1 s) —
         * nur von einem Hintergrund-Thread aufrufen.
         * @return true = Engine bereit
         */
        fun ensureLoadedFromDisk(filesDir: File): Boolean {
            if (sharedEngine != null) return true
            synchronized(engineLock) {
                if (sharedEngine != null) return true
                if (!File(modelRoot(filesDir), READY_MARKER).exists() || !bundleComplete(filesDir)) return false
                return buildEngine(filesDir)
            }
        }

        /**
         * Engine bauen und als [sharedEngine] ablegen. Aufrufer MUSS [engineLock] halten.
         * Idempotent: laeuft schon eine Engine, wird sie NIE ersetzt/freigegeben (ein parallel laufendes
         * `generate()` oder ein Overlay-Satz haette sonst eine freigegebene native Engine — Use-after-free).
         */
        private fun buildEngine(filesDir: File): Boolean {
            if (sharedEngine != null) return true
            val dir = extracted(filesDir)
            val modelFile = File(dir, MODEL_FILENAME)
            val tokensFile = File(dir, TOKENS_FILENAME)
            val espeakDir = File(dir, ESPEAK_DATA_DIRNAME)
            if (!modelFile.exists() || !tokensFile.exists() || !espeakDir.exists()) {
                android.util.Log.w(NAME,
                    "Piper: Dateien unvollständig — model=${modelFile.exists()}, " +
                    "tokens=${tokensFile.exists()}, espeak=${espeakDir.exists()}")
                return false
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

            // Crash-Protection: sherpa-onnx kann bei Config-Fehlern System.exit() aufrufen.
            // try/catch(Throwable) fängt Java-Exceptions — bei System.exit() ist es zu spät,
            // aber fehlerhafte Pfade oder Datei-Fehler werden als Exception gemeldet.
            val engine = try {
                OfflineTts(assetManager = null, config = ttsConfig)
            } catch (t: Throwable) {
                android.util.Log.e(NAME, "OfflineTts Init fatal: ${t.message}", t)
                extracted(filesDir).deleteRecursively()
                File(modelRoot(filesDir), READY_MARKER).delete()
                return false
            }
            sharedEngine = engine
            File(modelRoot(filesDir), READY_MARKER).createNewFile()
            android.util.Log.i(NAME, "Piper TTS Engine bereit — model=$MODEL_FILENAME, dataDir=${espeakDir.absolutePath}")
            return true
        }

        /**
         * Satz -> WAV-Bytes (16-bit Mono) fuer die [SentenceSpeechPipeline] des Overlays. null = Engine nicht bereit
         * oder nichts Sprechbares. Blockiert (CPU) — von einem Hintergrund-Thread aufrufen.
         */
        fun synthesizeWav(text: String): ByteArray? {
            val clean = cleanText(text)
            if (clean.isEmpty()) return null
            val audio = generateLocked(clean) ?: return null
            if (audio.samples.isEmpty()) return null
            return WavEncoder.pcm16Wav(audio.samples, audio.sampleRate)
        }

        /**
         * Synthese UNTER [engineLock]; [sharedEngine] wird nur hier (innerhalb der Sperre) gelesen — nie eine
         * ausserhalb gemerkte Referenz benutzen. null = Engine nicht bereit.
         */
        private fun generateLocked(clean: String): com.k2fsa.sherpa.onnx.GeneratedAudio? =
            synchronized(engineLock) {
                val engine = sharedEngine ?: return@synchronized null
                engine.generate(text = clean, sid = 0, speed = SPEAK_SPEED)
            }

        /** Modell laden (idempotent, unter [engineLock]) — Pfad nach Download/Extraktion. */
        private fun loadEngineLocked(filesDir: File): Boolean =
            synchronized(engineLock) { buildEngine(filesDir) }

        internal fun cleanText(text: String): String = text
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
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    // AtomicBoolean: compareAndSet() ist atomar — verhindert Race Condition beim Download-Start.
    private val isDownloading = AtomicBoolean(false)
    @Volatile private var downloadProgress = 0
    @Volatile private var lastError: String? = null
    private var speakJob: Job? = null
    private var audioTrack: AudioTrack? = null

    // Verzeichnis-Hierarchie: piper_tts/ → vits-piper-de_DE-kerstin-low/ → model + espeak-ng-data/
    private val filesDir: File get() = reactContext.filesDir
    private val modelDir: File get() = modelRoot(filesDir)
    private val extractedDir: File get() = extracted(filesDir)
    private val modelFile: File get() = File(extractedDir, MODEL_FILENAME)
    private val tokensFile: File get() = File(extractedDir, TOKENS_FILENAME)
    private val espeakDir: File get() = File(extractedDir, ESPEAK_DATA_DIRNAME)

    private val isModelReady: Boolean get() = sharedEngine != null

    private fun prefs() = reactContext.getSharedPreferences(TtsEngines.PREFS, Context.MODE_PRIVATE)

    // Start-Pruefung (Engine laden bzw. altes Modell bereinigen). Der Download wartet darauf (join), damit die
    // Bereinigung nie ein frisch begonnenes Bundle loescht.
    private val initJob: Job = scope.launch(Dispatchers.IO) {
        run {
            val readyMarker = File(modelDir, READY_MARKER)
            if (readyMarker.exists()) {
                // .sherpa_ready vorhanden: alle drei Komponenten prüfen (model + tokens + espeak-ng-data)
                if (modelFile.exists() && tokensFile.exists() && espeakDir.exists()) {
                    ensureLoadedFromDisk(filesDir)
                } else {
                    android.util.Log.w(NAME,
                        "Piper: Dateien fehlen trotz .sherpa_ready (Modellwechsel/Defekt) → modelDir gelöscht, Re-Download")
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

    // ── A/B-Wahl der Engine (SharedPreferences, vom Overlay mitgelesen) ───────

    @ReactMethod
    fun getTtsEngine(promise: Promise) {
        promise.resolve(TtsEngines.normalize(prefs().getString(TtsEngines.PREF_ENGINE, TtsEngines.DEFAULT)))
    }

    @ReactMethod
    fun setTtsEngine(engine: String, promise: Promise) {
        val normalized = TtsEngines.normalize(engine)
        prefs().edit().putString(TtsEngines.PREF_ENGINE, normalized).apply()
        promise.resolve(normalized)
    }

    @ReactMethod
    fun getEdgeVoice(promise: Promise) {
        promise.resolve(TtsEngines.normalizeEdgeVoice(prefs().getString(TtsEngines.PREF_EDGE_VOICE, null)))
    }

    @ReactMethod
    fun setEdgeVoice(voice: String, promise: Promise) {
        val normalized = TtsEngines.normalizeEdgeVoice(voice)
        prefs().edit().putString(TtsEngines.PREF_EDGE_VOICE, normalized).apply()
        promise.resolve(normalized)
    }

    // ── Status-Abfragen ──────────────────────────────────────────────────────

    @ReactMethod
    fun isPiperReady(promise: Promise) {
        promise.resolve(isModelReady)
    }

    @ReactMethod
    fun getPiperStatus(promise: Promise) {
        promise.resolve(currentStatus())
    }

    /** Status + Fortschritt + letzter Fehler — fuer die Anzeige in den Einstellungen. */
    @ReactMethod
    fun getPiperState(promise: Promise) {
        promise.resolve(Arguments.createMap().apply {
            putString("status", currentStatus())
            putInt("progress", if (isModelReady) 100 else downloadProgress)
            if (lastError != null) putString("error", lastError) else putNull("error")
        })
    }

    private fun currentStatus(): String = when {
        isModelReady -> "ready"
        isDownloading.get() -> "downloading"
        else -> "not_downloaded"
    }

    // ── TTS sprechen ─────────────────────────────────────────────────────────

    /**
     * Spricht Text via Piper ONNX. KEIN Lazy-Download (siehe [ensureModel]).
     * Promise: "ok" = Audio läuft, "fallback" = Modell noch nicht bereit / Fehler → JS nutzt die andere Engine.
     */
    @ReactMethod
    fun speakPiper(text: String, promise: Promise) {
        val clean = cleanText(text)
        if (clean.isEmpty()) { promise.resolve("empty"); return }

        if (!isModelReady) {
            promise.resolve("fallback")
            return
        }

        speakJob?.cancel()
        stopAudioTrack()

        speakJob = scope.launch {
            // Ein Promise genau einmal aufloesen (ok → spaeterer Cancel loest nicht noch einmal auf)
            var settled = false
            fun settle(value: String) { if (!settled) { settled = true; promise.resolve(value) } }
            try {
                val audio = withContext(Dispatchers.Default) { generateLocked(clean) }
                if (!isActive) { settle("cancelled"); return@launch }
                if (audio == null) { settle("fallback"); return@launch }
                settle("ok")
                withContext(Dispatchers.IO) {
                    playAudio(audio.samples, audio.sampleRate)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Unterbrochen (Stopp/neue Antwort): JS-Kette bricht ab statt ewig zu warten oder zu wechseln
                settle("cancelled")
                throw e
            } catch (e: Exception) {
                android.util.Log.w(NAME, "Piper TTS fehlgeschlagen: ${e.message}")
                stopAudioTrack()
                settle("fallback")
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
     * EAGER-Download-Trigger (App-Start, Onboarding, Einstellungen): startet den Download, wenn das Modell
     * fehlt, und kehrt SOFORT zurueck. Fortschritt per Events:
     *   PiperTTS.downloadProgress { progress: 0-100 }, PiperTTS.downloadComplete {}, PiperTTS.downloadError { message }
     * Idempotent: laeuft die Engine schon oder der Download, passiert nichts.
     * [force] = false (Auto-Start): NUR im ungemessenen Netz (WLAN) — bei Mobilfunk/ohne Netz startet nichts,
     * die Oberflaeche bietet „Jetzt laden“ an (force = true).
     * Promise: "ready" | "downloading" (laeuft schon) | "started" | "metered" (Auto-Start uebersprungen).
     */
    @ReactMethod
    fun ensureModel(force: Boolean, promise: Promise) {
        if (isModelReady) { promise.resolve("ready"); return }
        if (!force && isMeteredOrOffline()) { promise.resolve("metered"); return }
        if (!isDownloading.compareAndSet(false, true)) { promise.resolve("downloading"); return }
        lastError = null
        scope.launch {
            try { startModelDownload(alreadyLocked = true) }
            catch (e: Exception) {
                android.util.Log.e(NAME, "Eager-Download fehlgeschlagen: ${e.message}", e)
                lastError = e.message ?: "Unbekannter Fehler"
                isDownloading.set(false)
            }
        }
        promise.resolve("started")
    }

    /**
     * Expliziter Download (z.B. "Erneut versuchen" in den Einstellungen) — Promise erst nach dem Ende.
     * Emittet dieselben Events wie [ensureModel].
     */
    @ReactMethod
    fun downloadModel(promise: Promise) {
        if (isModelReady) { promise.resolve(null); return }
        if (!isDownloading.compareAndSet(false, true)) {
            promise.reject("ALREADY_DOWNLOADING", "Download läuft bereits")
            return
        }
        lastError = null
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

    /** Mobilfunk/Roaming/Hotspot oder kein Netz → kein stiller 64-MB-Download. */
    private fun isMeteredOrOffline(): Boolean = try {
        val cm = reactContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        cm.activeNetwork == null || cm.isActiveNetworkMetered
    } catch (_: Exception) {
        true   // im Zweifel nicht still laden
    }

    private fun reportProgress(pct: Int) {
        downloadProgress = pct
        emitEvent("PiperTTS.downloadProgress", Arguments.createMap().apply { putInt("progress", pct) })
    }

    private suspend fun startModelDownload(alreadyLocked: Boolean = false) {
        if (!alreadyLocked && !isDownloading.compareAndSet(false, true)) return

        try {
            initJob.join()   // erst die Start-Bereinigung abwarten (altes Modell), dann laden

            // Cold Start: die Start-Pruefung hat die Engine schon geladen → nichts zu tun (kein Fake-Fortschritt,
            // vor allem KEIN Neuaufbau: eine laufende Engine wird nie ersetzt/freigegeben).
            if (isModelReady) {
                emitEvent("PiperTTS.downloadComplete", Arguments.createMap())
                return
            }
            // Bundle liegt komplett auf der Platte, Engine aber noch nicht geladen → nur laden, kein Download
            if (bundleComplete(filesDir)) {
                if (!withContext(Dispatchers.Default) { loadEngineLocked(filesDir) })
                    throw IllegalStateException("Engine konnte nicht geladen werden")
                emitEvent("PiperTTS.downloadComplete", Arguments.createMap())
                return
            }

            reportProgress(0)
            withContext(Dispatchers.IO) {
                modelDir.mkdirs()

                // Unvollständige Extraktion bereinigen
                if (extractedDir.exists()) extractedDir.deleteRecursively()

                val bundleFile = File(modelDir, "bundle.tar.bz2")

                // Bundle herunterladen (~64MB: model.onnx + espeak-ng-data + rest)
                downloadFile(
                    url = BUNDLE_URL,
                    dest = bundleFile,
                    onProgress = { pct -> reportProgress((pct * 90f).toInt()) }
                )

                reportProgress(90)

                // tar.bz2 extrahieren → modelDir/vits-piper-de_DE-kerstin-low/ (Apache Commons Compress)
                extractTarBz2(bundleFile, modelDir)
                bundleFile.delete()

                reportProgress(95)

                // Vollständigkeits-Check: alle drei Komponenten müssen vorhanden sein
                if (!modelFile.exists() || modelFile.length() == 0L)
                    throw IllegalStateException("model.onnx fehlt oder leer nach Extraktion")
                if (!tokensFile.exists() || tokensFile.length() == 0L)
                    throw IllegalStateException("tokens.txt fehlt oder leer nach Extraktion")
                if (!espeakDir.exists())
                    throw IllegalStateException("espeak-ng-data fehlt nach Extraktion")
            }

            val loaded = withContext(Dispatchers.Default) { loadEngineLocked(filesDir) }
            if (!loaded) throw IllegalStateException("Engine konnte nicht geladen werden")
            reportProgress(100)
            emitEvent("PiperTTS.downloadComplete", Arguments.createMap())

        } catch (e: Exception) {
            android.util.Log.e(NAME, "Modell-Download fehlgeschlagen: ${e.message}", e)
            lastError = e.message ?: "Unbekannter Fehler"
            // Bei Fehler alles löschen — kein .sherpa_ready gesetzt → kein Crash-Loop
            modelDir.deleteRecursively()
            downloadProgress = 0
            emitEvent("PiperTTS.downloadError",
                Arguments.createMap().apply { putString("message", lastError) })
        } finally {
            isDownloading.set(false)
        }
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
        TtsEvents.emit(reactContext, "started")
        val chunkSize = 4096
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + chunkSize, samples.size)
            track.write(samples, offset, end - offset, AudioTrack.WRITE_BLOCKING)
            offset = end
        }
        // Wurde die Wiedergabe in der Zwischenzeit gestoppt/ersetzt, ist audioTrack nicht mehr dieser Track
        val naturalEnd = audioTrack === track
        track.stop()
        track.release()
        if (naturalEnd) {
            audioTrack = null
            TtsEvents.emit(reactContext, "ended")
        }
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
            readTimeout = 30_000  // Lese-Timeout pro read() (nicht Gesamtdauer): haengende Verbindung faellt nach 30 s auf
            instanceFollowRedirects = true
        }
        conn.connect()
        // Host-Allowlist (nach Redirects): das Bundle kommt nur von GitHub bzw. dessen Release-Asset-CDN
        // (TODO Follow-up: SHA-256-Pin, sobald das Bundle stabil gepinnt werden kann)
        val finalHost = conn.url.host.lowercase()
        if (conn.url.protocol != "https" || !(finalHost == "github.com" || finalHost.endsWith(".githubusercontent.com"))) {
            conn.disconnect()
            throw java.io.IOException("Unerwarteter Download-Host: $finalHost")
        }
        val totalBytes = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
        var downloaded = 0L
        var lastPct = -1
        conn.inputStream.use { input ->
            FileOutputStream(dest).use { output ->
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } >= 0) {
                    output.write(buf, 0, n)
                    downloaded += n
                    if (totalBytes > 0) {
                        val frac = downloaded.toFloat() / totalBytes
                        // nur bei ganzen Prozent melden (sonst Event-Flut Richtung JS)
                        val pct = (frac * 90f).toInt()
                        if (pct != lastPct) { lastPct = pct; onProgress(frac) }
                    }
                }
            }
        }
    }

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
        // Die Engine bleibt bewusst geladen: der Overlay (eigene Activity) nutzt sie weiter.
    }
}
