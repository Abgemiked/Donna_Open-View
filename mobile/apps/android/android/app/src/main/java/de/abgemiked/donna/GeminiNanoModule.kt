package com.yourcompany.donna

import android.os.Build
import com.facebook.react.bridge.*
import com.facebook.react.module.annotations.ReactModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * DONNA-157: Gemini Nano via Android AICore
 * On-device LLM für ~40% der Donna-Anfragen (kurze, kontextfreie Fragen).
 * Unterstützte Geräte: Samsung Galaxy S24+ / S25 Ultra, Pixel 8+, Android 14+
 *
 * AICore lädt Gemini Nano XS (~1.5 GB) beim ersten Aufruf im Hintergrund.
 * isAvailable() gibt false zurück solange Download läuft oder Gerät nicht unterstützt.
 */
@ReactModule(name = GeminiNanoModule.NAME)
class GeminiNanoModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val NAME = "GeminiNano"
        private const val TAG = "GeminiNanoModule"
    }

    // SupervisorJob: einzelne fehlgeschlagene Coroutine killt nicht den gesamten Scope
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // GenerativeModel-Singleton — wird einmalig initialisiert und wiederverwendet
    // null solange AICore nicht verfügbar oder Android < 14
    @Volatile private var cachedModel: com.google.ai.edge.aicore.GenerativeModel? = null

    override fun getName() = NAME

    /**
     * Gibt das gecachte GenerativeModel zurück oder erstellt es beim ersten Aufruf.
     * Wirft Exception wenn AICore nicht unterstützt wird.
     */
    private fun getOrCreateModel(maxTokens: Int = 256): com.google.ai.edge.aicore.GenerativeModel {
        return cachedModel ?: synchronized(this) {
            cachedModel ?: run {
                val config = com.google.ai.edge.aicore.generationConfig {
                    context = reactContext
                    temperature = 0.7f
                    topK = 40
                    maxOutputTokens = maxTokens.coerceIn(64, 512)
                }
                com.google.ai.edge.aicore.GenerativeModel(generationConfig = config).also {
                    cachedModel = it
                    android.util.Log.i(TAG, "GenerativeModel erstellt (maxTokens=${maxTokens.coerceIn(64, 512)})")
                }
            }
        }
    }

    /**
     * Prüft ob Gemini Nano auf diesem Gerät verfügbar und bereit ist.
     * false wenn: Android < 14, Gerät nicht unterstützt, Modell noch lädt.
     *
     * Nutzt nur Model-Initialisierung als Check (kein Inferenz-Call) → schnell, kein Kaltstart.
     */
    @ReactMethod
    fun isAvailable(promise: Promise) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // API 34 = Android 14
            promise.resolve(false)
            return
        }
        scope.launch {
            try {
                getOrCreateModel()
                android.util.Log.i(TAG, "AICore verfügbar")
                promise.resolve(true)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "AICore nicht verfügbar: ${e.javaClass.simpleName}: ${e.message}")
                promise.resolve(false)
            }
        }
    }

    /**
     * Generiert eine Antwort on-device via Gemini Nano.
     * Nur für kurze, kontextfreie Anfragen (< 80 Zeichen, kein Tool-Use).
     */
    @ReactMethod
    fun generate(prompt: String, maxTokens: Int, promise: Promise) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            promise.reject("UNSUPPORTED", "Android 14+ erforderlich")
            return
        }
        scope.launch {
            try {
                val model = getOrCreateModel(maxTokens)
                val t0 = System.currentTimeMillis()
                val response = model.generateContent(prompt)
                val latency = System.currentTimeMillis() - t0
                val text = response.text ?: ""
                android.util.Log.i(TAG, "AICore generate: ${latency}ms, ${text.length} Zeichen")
                if (text.isBlank()) {
                    promise.reject("EMPTY_RESPONSE", "Gemini Nano lieferte leere Antwort")
                } else {
                    promise.resolve(text.trim())
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "AICore generate fehlgeschlagen: ${e.message}")
                promise.reject("GENERATE_FAILED", e.message ?: "Unbekannter Fehler", e)
            }
        }
    }

    override fun onCatalystInstanceDestroy() {
        scope.cancel()
        cachedModel = null
        super.onCatalystInstanceDestroy()
    }

    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}
}
