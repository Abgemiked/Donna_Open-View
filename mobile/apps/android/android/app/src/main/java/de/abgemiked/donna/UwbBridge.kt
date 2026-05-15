package com.yourcompany.donna

import android.util.Log
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod

/**
 * UwbBridge — DONNA-129: React Native Bridge für UWB Indoor-Positionierung.
 *
 * Stellt JS-Schicht Zugriff auf UwbPositioningManager bereit:
 * - getUwbStatus(): Lesbarer Status-String
 * - getCurrentRoom(): Aktueller Raum (null wenn nicht erkennbar)
 * - isUwbAvailable(): Boolean-Check für Hardware-Verfügbarkeit
 *
 * Graceful degradation: alle Methoden resolven auch auf Geräten ohne UWB.
 */
class UwbBridge(private val reactContext: ReactApplicationContext)
    : ReactContextBaseJavaModule(reactContext) {

    companion object {
        private const val TAG = "UwbBridge"
    }

    private val uwbManager by lazy { UwbPositioningManager(reactContext) }

    override fun getName(): String = "UwbBridge"

    /**
     * Gibt den aktuellen UWB-Status als lesbaren String zurück.
     *
     * Beispiele:
     * - "UWB-Hardware nicht verfügbar"
     * - "Keine Räume konfiguriert — bitte in Einstellungen einrichten"
     * - "Bereit (3 Räume konfiguriert, Ranging ausstehend)"
     */
    @ReactMethod
    fun getUwbStatus(promise: Promise) {
        try {
            val status = uwbManager.getStatus()
            Log.i(TAG, "getUwbStatus: $status")
            promise.resolve(status)
        } catch (e: Exception) {
            Log.e(TAG, "getUwbStatus Fehler: ${e.message}")
            promise.resolve("UWB-Hardware nicht verfügbar") // graceful degradation
        }
    }

    /**
     * Gibt den aktuellen Raum zurück oder null wenn nicht erkennbar.
     *
     * Gibt null zurück wenn:
     * - UWB nicht verfügbar
     * - Keine Räume konfiguriert
     * - Ranging noch nicht aktiv (Phase 2)
     */
    @ReactMethod
    fun getCurrentRoom(promise: Promise) {
        try {
            val room = uwbManager.getCurrentRoom()
            Log.i(TAG, "getCurrentRoom: $room")
            promise.resolve(room) // null ist valider Wert für React Native
        } catch (e: Exception) {
            Log.e(TAG, "getCurrentRoom Fehler: ${e.message}")
            promise.resolve(null) // graceful degradation
        }
    }

    /**
     * Prüft ob UWB-Hardware auf diesem Gerät verfügbar ist.
     */
    @ReactMethod
    fun isUwbAvailable(promise: Promise) {
        try {
            val available = uwbManager.isUwbAvailable()
            Log.i(TAG, "isUwbAvailable: $available")
            promise.resolve(available)
        } catch (e: Exception) {
            Log.e(TAG, "isUwbAvailable Fehler: ${e.message}")
            promise.resolve(false) // graceful degradation
        }
    }
}
