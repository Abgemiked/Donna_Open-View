package com.yourcompany.donna

import android.util.Log
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod

/**
 * NpuBridge — DONNA-128: React Native Bridge für NPU-Status.
 *
 * Stellt JS-Schicht Zugriff auf NpuManager bereit:
 * - getNpuStatus(): Status-String (NOT_CAPABLE | MODEL_NOT_DOWNLOADED | READY)
 * - isNpuCapable(): Boolean-Check für Geräte-Kompatibilität
 *
 * Graceful degradation: alle Methoden resolven nie mit reject auf nicht-fähigen
 * Geräten — UI kann sicher Status abfragen ohne try/catch.
 */
class NpuBridge(private val reactContext: ReactApplicationContext)
    : ReactContextBaseJavaModule(reactContext) {

    companion object {
        private const val TAG = "NpuBridge"
    }

    private val npuManager by lazy { NpuManager(reactContext) }

    override fun getName(): String = "NpuBridge"

    /**
     * Gibt den aktuellen NPU-Status als String zurück.
     *
     * Mögliche Werte:
     * - "NOT_CAPABLE" — Gerät nicht kompatibel (kein Snapdragon 8 Elite oder zu wenig RAM)
     * - "MODEL_NOT_DOWNLOADED" — Gerät bereit, Modell fehlt
     * - "READY" — Modell geladen, lokale Inferenz möglich
     */
    @ReactMethod
    fun getNpuStatus(promise: Promise) {
        try {
            val status = npuManager.getStatus().toStatusString()
            Log.i(TAG, "getNpuStatus: $status")
            promise.resolve(status)
        } catch (e: Exception) {
            Log.e(TAG, "getNpuStatus Fehler: ${e.message}")
            promise.resolve("NOT_CAPABLE") // graceful degradation
        }
    }

    /**
     * Prüft ob dieses Gerät NPU-fähig ist (Snapdragon 8 Elite + >=3GB RAM).
     */
    @ReactMethod
    fun isNpuCapable(promise: Promise) {
        try {
            val capable = npuManager.isNpuCapable()
            Log.i(TAG, "isNpuCapable: $capable")
            promise.resolve(capable)
        } catch (e: Exception) {
            Log.e(TAG, "isNpuCapable Fehler: ${e.message}")
            promise.resolve(false) // graceful degradation
        }
    }

    /**
     * Prüft ob das NPU-Modell bereits heruntergeladen ist.
     */
    @ReactMethod
    fun isModelAvailable(promise: Promise) {
        try {
            val available = npuManager.isModelAvailable()
            Log.i(TAG, "isModelAvailable: $available")
            promise.resolve(available)
        } catch (e: Exception) {
            Log.e(TAG, "isModelAvailable Fehler: ${e.message}")
            promise.resolve(false) // graceful degradation
        }
    }
}
