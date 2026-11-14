package com.yourcompany.donna

import ai.onnxruntime.genai.Generator
import ai.onnxruntime.genai.GeneratorParams
import ai.onnxruntime.genai.Model
import ai.onnxruntime.genai.Tokenizer
import ai.onnxruntime.genai.TokenizerStream
import android.content.Context
import android.util.Log
import com.facebook.react.bridge.*
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.modules.core.DeviceEventManagerModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * DONNA-189: Phi-3 Mini INT4 via ONNX Runtime GenAI
 *
 * Stufe 2 im dreistufigen On-Device-Router:
 *   1. Gemini Nano     — sehr kurz, kontextfrei, ~200-400ms
 *   2. Phi-3 Mini      — mittlere Komplexität, kein Internet, ~1-3s   ← diese Klasse
 *   3. Cloud Gemini    — komplex, Memory/Search nötig
 *
 * Modell: microsoft/Phi-3-mini-4k-instruct-onnx
 *   Variante: cpu_and_mobile/cpu-int4-rtn-block-32-acc-level-4
 *   Größe: ~2.3 GB, Download on first use
 *   Speicherort: context.filesDir/phi3mini/ (internes Storage, kein externen SD)
 *
 * Execution Provider Strategie (Phase 2):
 *   - Primary:   QNN EP (Qualcomm Hexagon NPU, Snapdragon 8 Elite → ~70 tok/s)
 *   - Fallback:  NNAPI EP (GPU/DSP, Android 9+)
 *   - Default:   CPU EP  (Arm KleidiAI INT4-optimiert, ~6-9 tok/s auf S25 Ultra)
 *
 * Phase 1 (diese Session): Stub — isAvailable(), getModelStatus(), generate() als
 *   Platzhalter + vollständiger Download-Manager. Echte ORT-GenAI-Inferenz in Phase 2
 *   sobald das onnxruntime-genai AAR (Maven) eingebunden ist.
 *
 * Datenschutz: Lokale Inferenz — keine Daten verlassen das Gerät.
 */
@ReactModule(name = PhiModule.NAME)
class PhiModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val NAME = "PhiModule"
        private const val TAG = "PhiModule"

        // Modell-Verzeichnis im internen App-Speicher
        private const val MODEL_DIR = "phi3mini"
        // Haupt-Gewichtsdatei — nach Download-Abschluss muss diese existieren
        // Echter Dateiname aus HF-Repo: phi3-mini-4k-instruct-cpu-int4-rtn-block-32-acc-level-4.onnx.data
        private const val MODEL_WEIGHTS_FILE = "phi3-mini-4k-instruct-cpu-int4-rtn-block-32-acc-level-4.onnx.data"
        // Config-Dateien die ORT GenAI benötigt
        private const val MODEL_CONFIG_FILE = "genai_config.json"
        // HuggingFace-Download-URL für das mobile-optimierte INT4-Modell
        // Direkt-Link zum Microsoft-offiziellen ONNX-HuggingFace-Repo
        private const val HF_BASE =
            "https://huggingface.co/microsoft/Phi-3-mini-4k-instruct-onnx/resolve/main/cpu_and_mobile/cpu-int4-rtn-block-32-acc-level-4"

        // OkHttp-Client: einmal erstellt, für alle Datei-Downloads geteilt (~2.3 GB gesamt)
        private val httpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .build()

        // Dateien die heruntergeladen werden müssen (aus dem HF-Repo)
        // Korrekte Dateinamen aus microsoft/Phi-3-mini-4k-instruct-onnx
        // Variante: cpu_and_mobile/cpu-int4-rtn-block-32-acc-level-4
        // Verifiziert via HF API + Tree-Page (kein "model.onnx" — nur der lange Dateiname existiert)
        val MODEL_FILES = listOf(
            "phi3-mini-4k-instruct-cpu-int4-rtn-block-32-acc-level-4.onnx",
            "phi3-mini-4k-instruct-cpu-int4-rtn-block-32-acc-level-4.onnx.data",
            "genai_config.json",
            "tokenizer.json",
            "tokenizer_config.json",
            "tokenizer.model",
            "added_tokens.json",
            "special_tokens_map.json",
        )
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // DONNA-189 Phase 2: ORT GenAI Model-Instanz (lazy-init beim ersten generate()-Aufruf)
    @Volatile private var ortModel: Model? = null
    private val modelLock = Any()

    // Guard gegen Doppelstart: nur ein Download-Job gleichzeitig (Review-Rat Punkt 4)
    @Volatile private var downloadJob: Job? = null

    // ── Modell-Pfade ──────────────────────────────────────────────────────────

    private fun modelDir(): File = File(reactContext.filesDir, MODEL_DIR)

    private fun isModelComplete(): Boolean {
        val dir = modelDir()
        if (!dir.exists()) return false
        // Prüfe ob alle benötigten Dateien vorhanden und nicht leer sind
        return MODEL_FILES.all { filename ->
            val f = File(dir, filename)
            f.exists() && f.length() > 0
        }
    }

    // ── React Native API ──────────────────────────────────────────────────────

    /**
     * Gibt true zurück wenn Phi-3 Mini bereit für Inferenz ist.
     * Bedingungen:
     *   - Android 9+ (NNAPI benötigt API 28+, für CPU reicht jedes Android)
     *   - Modell vollständig heruntergeladen
     *   - [Phase 2] ORT GenAI Bibliothek geladen
     *
     * Diese Methode ist synchron-freundlich (kein Netzwerk, nur Datei-Check).
     */
    @ReactMethod
    fun isAvailable(promise: Promise) {
        scope.launch {
            try {
                // Phase 2: isModelComplete() prüft alle Dateien; ORT GenAI AAR ist fest eingebunden
                val modelReady = isModelComplete()
                Log.i(TAG, "isAvailable: modelReady=$modelReady")
                promise.resolve(modelReady)
            } catch (e: Exception) {
                Log.w(TAG, "isAvailable Fehler: ${e.message}")
                promise.resolve(false)
            }
        }
    }

    /**
     * Gibt den aktuellen Modell-Status zurück.
     *
     * Mögliche Werte:
     *   "NOT_DOWNLOADED" — Modell fehlt, Download nötig
     *   "DOWNLOADING"    — Download läuft gerade
     *   "READY"          — Modell vollständig, bereit für Inferenz
     *   "ERROR"          — Fehler beim Download oder Initialisierung
     */
    @ReactMethod
    fun getModelStatus(promise: Promise) {
        scope.launch {
            try {
                val status = when {
                    isModelComplete() -> "READY"
                    downloadJob?.isActive == true -> "DOWNLOADING"
                    else -> "NOT_DOWNLOADED"
                }
                Log.i(TAG, "getModelStatus: $status")
                promise.resolve(status)
            } catch (e: Exception) {
                Log.e(TAG, "getModelStatus Fehler: ${e.message}")
                promise.resolve("ERROR")
            }
        }
    }

    /**
     * Gibt den Download-Fortschritt zurück (0-100), -1 wenn kein Download läuft.
     */
    @ReactMethod
    fun getDownloadProgress(promise: Promise) {
        scope.launch {
            try {
                val running = downloadJob?.isActive == true
                promise.resolve(if (running) 0 else -1)
            } catch (e: Exception) {
                promise.resolve(-1)
            }
        }
    }

    /**
     * Startet den Modell-Download im Hintergrund via OkHttp direkt in context.filesDir.
     * DownloadManager wird NICHT verwendet — er unterstützt kein privates App-Storage.
     * Benötigt WRITE_EXTERNAL_STORAGE nicht — internes Storage (context.filesDir).
     *
     * Gibt zurück: true bei Erfolg, reject bei Fehler.
     * Progress-Events: "PhiDownloadProgress" {percent, bytesDownloaded, bytesTotal}
     * Abschluss-Event: "PhiDownloadComplete"
     * Fehler-Event: "PhiDownloadError" {error}
     */
    @ReactMethod
    fun startModelDownload(promise: Promise) {
        if (isModelComplete()) {
            promise.resolve(true)
            return
        }

        // Doppelstart-Schutz (Review-Rat Punkt 4)
        if (downloadJob?.isActive == true) {
            promise.reject("DOWNLOAD_RUNNING", "Download läuft bereits")
            return
        }

        downloadJob = scope.launch {
            val modelDir = File(reactContext.filesDir, MODEL_DIR)
            modelDir.mkdirs()

            // Nur fehlende Dateien herunterladen (Resume über .tmp-Pattern)
            val missing = MODEL_FILES.filter { filename ->
                val f = File(modelDir, filename)
                !f.exists() || f.length() == 0L
            }

            Log.i(TAG, "startModelDownload: ${missing.size} Dateien fehlen: $missing")

            var totalBytesAllFiles = 0L
            var downloadedBytesAllFiles = 0L

            try {
                missing.forEachIndexed { _, filename ->
                    val url = "$HF_BASE/$filename"
                    // Temp-Datei-Pattern: erst in .download schreiben, dann umbenennen (Review-Rat Punkt 3)
                    val tmpFile = File(modelDir, "$filename.download")
                    val destFile = File(modelDir, filename)

                    downloadFile(url, tmpFile) { deltaBytes, fileTotal ->
                        // deltaBytes = Bytes dieses einzelnen Chunks (nicht kumulativ) — Review-Rat Punkt 1
                        downloadedBytesAllFiles += deltaBytes
                        totalBytesAllFiles = fileTotal * missing.size.toLong()
                        val percent = if (totalBytesAllFiles > 0)
                            (downloadedBytesAllFiles * 100 / totalBytesAllFiles).toInt().coerceIn(0, 99)
                        else 0
                        sendEvent("PhiDownloadProgress", Arguments.createMap().apply {
                            putInt("percent", percent)
                            putDouble("bytesDownloaded", downloadedBytesAllFiles.toDouble())
                            putDouble("bytesTotal", totalBytesAllFiles.toDouble())
                        })
                    }

                    // Atomisches Umbenennen nach erfolgreichem Download
                    tmpFile.renameTo(destFile)
                    Log.i(TAG, "Datei heruntergeladen und verschoben: $filename")
                }

                // Abschluss-Event mit 100%
                sendEvent("PhiDownloadProgress", Arguments.createMap().apply {
                    putInt("percent", 100)
                    putDouble("bytesDownloaded", downloadedBytesAllFiles.toDouble())
                    putDouble("bytesTotal", totalBytesAllFiles.toDouble())
                })
                sendEvent("PhiDownloadComplete", Arguments.createMap())
                promise.resolve(true)

            } catch (e: Exception) {
                Log.e(TAG, "Download fehlgeschlagen: ${e.message}", e)
                // Unvollständige .download-Dateien aufräumen
                missing.forEach { filename ->
                    File(modelDir, "$filename.download").delete()
                }
                sendEvent("PhiDownloadError", Arguments.createMap().apply {
                    putString("error", e.message)
                })
                promise.reject("DOWNLOAD_FAILED", e.message ?: "Unbekannter Fehler", e)
            }
        }
    }

    /**
     * Lädt eine Datei via OkHttp herunter und schreibt sie nach destFile.
     * onProgress wird mit (deltaBytes: Long, fileTotal: Long) aufgerufen — deltaBytes ist das
     * Delta dieses Chunks (nicht kumulativ), fileTotal ist die Gesamtgröße dieser Datei.
     */
    private fun downloadFile(url: String, destFile: File, onProgress: (Long, Long) -> Unit) {
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) throw IOException("Download fehlgeschlagen: ${response.code} für $url")
        val body = response.body ?: throw IOException("Leerer Response-Body für $url")
        val fileTotal = body.contentLength()

        destFile.parentFile?.mkdirs()
        FileOutputStream(destFile).use { out ->
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    out.write(buffer, 0, bytesRead)
                    // deltaBytes = bytesRead (dieses Chunks), nicht kumulativ (Review-Rat Punkt 1)
                    onProgress(bytesRead.toLong(), fileTotal)
                }
            }
        }
    }

    /**
     * Bricht den laufenden Download ab und löscht unvollständige Dateien.
     * Cancelt nur den downloadJob, NICHT den gesamten scope (Review-Rat Punkte 3+5).
     */
    @ReactMethod
    fun cancelDownload(promise: Promise) {
        scope.launch {
            try {
                // Nur den Download-Job canceln — scope bleibt aktiv für spätere Downloads
                downloadJob?.cancel()
                downloadJob = null

                // Alle .download-Temp-Dateien und leere Zieldateien löschen (Review-Rat Punkt 3)
                val dir = modelDir()
                MODEL_FILES.forEach { filename ->
                    File(dir, "$filename.download").delete()
                    val f = File(dir, filename)
                    if (f.exists() && f.length() == 0L) f.delete()
                }
                Log.i(TAG, "cancelDownload: Abgebrochen und aufgeräumt")
                promise.resolve(true)
            } catch (e: Exception) {
                Log.e(TAG, "cancelDownload Fehler: ${e.message}")
                promise.resolve(false)
            }
        }
    }

    /**
     * Generiert eine Antwort on-device via Phi-3 Mini INT4 (ONNX Runtime GenAI).
     * Nur für mittlere Komplexität (80-500 Zeichen, kein Tool-Use, kein Memory).
     *
     * EP-Strategie:
     *   1. QNN EP (Snapdragon 8 Elite Hexagon NPU, ~70 tok/s) — probiert, Fallback auf CPU
     *   2. CPU EP (ARM KleidiAI INT4, ~6-9 tok/s auf S25 Ultra) — immer verfügbar
     *
     * Streaming: Jeder Token wird via "PhiToken"-Event gesendet (Early-TTS-kompatibel).
     * generate() gibt zusätzlich den vollständigen Text zurück.
     *
     * @param prompt     Der vollständige Prompt (bereits formatiert)
     * @param maxTokens  Maximale Ausgabe-Token (default 512)
     */
    @ReactMethod
    fun generate(prompt: String, maxTokens: Int, promise: Promise) {
        if (!isModelComplete()) {
            promise.reject("MODEL_NOT_READY", "Phi-3 Mini Modell nicht heruntergeladen. Bitte zuerst startModelDownload() aufrufen.")
            return
        }

        scope.launch {
            try {
                val t0 = System.currentTimeMillis()

                // ── Lazy Model-Init (einmalig, thread-safe) ──────────────────
                val model = synchronized(modelLock) {
                    if (ortModel == null) {
                        Log.i(TAG, "Initialisiere ORT GenAI Model aus: ${modelDir().absolutePath}")
                        val m = Model(modelDir().absolutePath)
                        // QNN EP: Model(String) nutzt automatisch verfügbare EPs (CPU immer, QNN wenn vorhanden)
                        // appendProvider() existiert nicht in der Android-API — EP-Auswahl via genai_config.json
                        ortModel = m
                        Log.i(TAG, "ORT GenAI Model geladen in ${System.currentTimeMillis() - t0}ms")
                    }
                    ortModel!!
                }

                // ── Tokenizer + GeneratorParams ──────────────────────────────
                val tokenizer = Tokenizer(model)
                val sequences = tokenizer.encode(prompt)

                // GeneratorParams: nur Search-Options setzen — Input via appendTokenSequences am Generator
                val params = GeneratorParams(model).apply {
                    setSearchOption("max_length", maxTokens.toDouble())
                    setSearchOption("temperature", 0.7)
                }

                // ── Token-Streaming + Volltext-Ausgabe ───────────────────────
                val output = StringBuilder()
                // TokenizerStream für inkrementelles Streaming — einmalig vor dem Loop erstellen
                val tokenizerStream: TokenizerStream = tokenizer.createStream()

                Generator(model, params).use { gen ->
                    // Input-Sequenz in den Generator laden (entspricht setInput("input_ids") in der C++-API)
                    gen.appendTokenSequences(sequences)
                    while (!gen.isDone()) {
                        // generateNextToken() führt intern computeLogits + sampling durch
                        gen.generateNextToken()
                        // Streaming-API: letztes Token inkrementell dekodieren
                        val newToken = tokenizerStream.decode(gen.getLastTokenInSequence(0).toInt())
                        output.append(newToken)

                        // Early TTS: Token-Event für Streaming-TTS (DONNA-153)
                        sendEvent("PhiToken", Arguments.createMap().apply {
                            putString("token", newToken)
                            putString("text", output.toString())
                        })
                    }
                }

                // Volltext aus dem inkrementell aufgebauten StringBuilder (generator ist hier bereits closed)
                val text = output.toString().trim()
                val latencyMs = System.currentTimeMillis() - t0
                Log.i(TAG, "generate() abgeschlossen: ${text.length} Zeichen, ${latencyMs}ms, prompt.length=${prompt.length}")

                promise.resolve(text)

            } catch (e: Exception) {
                Log.e(TAG, "generate() Fehler: ${e.message}", e)
                // Model zurücksetzen damit nächster Aufruf neu initialisiert
                synchronized(modelLock) { ortModel = null }
                promise.reject("GENERATE_FAILED", e.message ?: "Unbekannter Fehler bei Phi-3 Inferenz", e)
            }
        }
    }

    // ── Event-Hilfsmethode ────────────────────────────────────────────────────

    private fun sendEvent(eventName: String, params: WritableMap) {
        reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(eventName, params)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun getName() = NAME

    override fun onCatalystInstanceDestroy() {
        downloadJob?.cancel()
        scope.cancel()
        synchronized(modelLock) {
            try {
                ortModel?.close()
            } catch (e: Exception) {
                Log.w(TAG, "ortModel.close() Fehler beim Destroy: ${e.message}")
            } finally {
                ortModel = null
            }
        }
        super.onCatalystInstanceDestroy()
    }

    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}
}
