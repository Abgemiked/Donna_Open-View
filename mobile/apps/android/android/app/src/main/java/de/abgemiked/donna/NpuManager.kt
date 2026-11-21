package com.yourcompany.donna

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * NpuManager — DONNA-128: On-Device NPU Framework.
 *
 * Framework-Vorbereitung für lokales LLM via MediaPipe LLM Inference API.
 * Das eigentliche Modell (donna_local_phi3mini_int4.task, ~1GB) wird in
 * Phase 2 heruntergeladen. Diese Klasse prüft Gerät-Kompatibilität und
 * Modell-Verfügbarkeit (graceful degradation auf nicht-fähigen Geräten).
 *
 * Datenschutz: Lokale Inferenz — keine Daten verlassen das Gerät.
 * MediaPipe-Dependency wird erst bei Modell-Download eingebunden (Phase 2).
 */
class NpuManager(private val context: Context) {

    companion object {
        private const val TAG = "NpuManager"
        private const val MODEL_FILENAME = "donna_local_phi3mini_int4.task"
        private const val MIN_RAM_MB = 3000L
        // Snapdragon 8 Elite — verbaut im Samsung Galaxy S25 Ultra
        private const val SOC_SNAPDRAGON_8_ELITE = "SM8750"
    }

    // ── Capability Checks ────────────────────────────────────────────────────

    /**
     * Prüft ob das Gerät NPU-fähig ist.
     *
     * Kriterien:
     * - Snapdragon 8 Elite SoC (SM8750) — Samsung S25 Ultra
     * - Mindestens 3 GB RAM
     *
     * Graceful degradation: gibt false zurück auf nicht-fähigen Geräten.
     */
    fun isNpuCapable(): Boolean {
        val hasSoc = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            Build.SOC_MODEL?.contains(SOC_SNAPDRAGON_8_ELITE, ignoreCase = true) == true

        val memInfo = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .getMemoryInfo(memInfo)
        val totalRamMb = memInfo.totalMem / 1024L / 1024L

        val capable = hasSoc && totalRamMb >= MIN_RAM_MB
        Log.d(TAG, "isNpuCapable: soc=${Build.SOC_MODEL} ram=${totalRamMb}MB capable=$capable")
        return capable
    }

    /**
     * Prüft ob das Modell im internen Speicher verfügbar ist.
     * Modell wird in Phase 2 via DownloadManager heruntergeladen.
     */
    fun isModelAvailable(): Boolean {
        val modelFile = File(context.filesDir, MODEL_FILENAME)
        val exists = modelFile.exists() && modelFile.length() > 0
        Log.d(TAG, "isModelAvailable: path=${modelFile.absolutePath} exists=$exists size=${modelFile.length()}")
        return exists
    }

    /**
     * Gibt den aktuellen NPU-Status zurück.
     */
    fun getStatus(): NpuStatus = when {
        !isNpuCapable() -> NpuStatus.NOT_CAPABLE
        !isModelAvailable() -> NpuStatus.MODEL_NOT_DOWNLOADED
        else -> NpuStatus.READY
    }.also { Log.i(TAG, "NPU Status: $it") }

    // ── Inference (Phase 2 Placeholder) ─────────────────────────────────────

    /**
     * Lokale Inferenz via MediaPipe LLM Inference API.
     *
     * Gibt null zurück bis:
     * - Gerät NPU-fähig ist
     * - Modell heruntergeladen ist
     * - MediaPipe-Dependency eingebunden ist (Phase 2)
     *
     * TODO DONNA-128 Phase 2: MediaPipe LLM Inference API hier einbinden:
     *   val options = LlmInference.LlmInferenceOptions.builder()
     *       .setModelPath(File(context.filesDir, MODEL_FILENAME).absolutePath)
     *       .setMaxTokens(1024)
     *       .build()
     *   val llm = LlmInference.createFromOptions(context, options)
     *   return llm.generateResponse(prompt)
     */
    suspend fun inferLocal(prompt: String): String? {
        if (getStatus() != NpuStatus.READY) {
            Log.d(TAG, "inferLocal: nicht verfügbar (Status=${getStatus()})")
            return null
        }
        Log.w(TAG, "inferLocal: Modell vorhanden aber MediaPipe noch nicht eingebunden (Phase 2)")
        return null
    }
}

/**
 * NPU-Status-Enum für Donna-UI und Bridge.
 */
enum class NpuStatus {
    /** Gerät hat keinen kompatiblen SoC oder zu wenig RAM. */
    NOT_CAPABLE,
    /** Gerät ist fähig aber Modell noch nicht heruntergeladen. */
    MODEL_NOT_DOWNLOADED,
    /** Modell geladen und bereit für lokale Inferenz. */
    READY;

    fun toStatusString(): String = when (this) {
        NOT_CAPABLE -> "NOT_CAPABLE"
        MODEL_NOT_DOWNLOADED -> "MODEL_NOT_DOWNLOADED"
        READY -> "READY"
    }
}
