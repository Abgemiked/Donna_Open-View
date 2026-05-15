package com.yourcompany.donna

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * UwbPositioningManager — DONNA-129: UWB Indoor-Positionierung Framework.
 *
 * UWB (Ultra-Wideband) ermöglicht präzise Raum-Erkennung via Ranging zu Anchor-Geräten.
 * Das Samsung Galaxy S25 Ultra besitzt einen UWB-Chip.
 *
 * Aktueller Status:
 * - Hardware-Verfügbarkeit: geprüft (S25 Ultra: verfügbar)
 * - Anchor-Geräte: fehlen noch — daher kein aktives Ranging
 * - Graceful degradation: gibt null zurück wenn keine Anchors konfiguriert
 *
 * TODO DONNA-129 Phase 2: UwbManager.createRangingSession() wenn Anchor-Hardware vorhanden:
 *   val uwbManager = context.getSystemService(UwbManager::class.java)
 *   val session = uwbManager.createRangingSession(...)
 *   session.start(...)
 */
class UwbPositioningManager(private val context: Context) {

    companion object {
        private const val TAG = "UwbPositioningManager"
        private const val PREFS_NAME = "uwb_rooms"
        private const val PREFS_KEY_ROOMS = "rooms"
    }

    // ── Data Models ──────────────────────────────────────────────────────────

    /**
     * Raum-Konfiguration: Name + UWB-Anchor-Adresse.
     * Gespeichert in SharedPreferences als JSON.
     */
    data class Room(
        val name: String,
        val anchorAddress: String,
    )

    // ── Hardware Check ───────────────────────────────────────────────────────

    /**
     * Prüft ob UWB-Hardware auf diesem Gerät vorhanden ist.
     * Anforderung: Android 12+ (API 31) + android.hardware.uwb Feature.
     *
     * Samsung S25 Ultra: UWB-Chip vorhanden → true.
     * Graceful degradation: gibt false zurück auf Geräten ohne UWB.
     */
    fun isUwbAvailable(): Boolean {
        val available = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_UWB)
        } else {
            false
        }
        Log.d(TAG, "isUwbAvailable: sdk=${Build.VERSION.SDK_INT} available=$available")
        return available
    }

    // ── Room Configuration ───────────────────────────────────────────────────

    /**
     * Liest gespeicherte Raum-Konfigurationen aus SharedPreferences.
     * Format: JSON-Array von {name, anchorAddress} Objekten.
     */
    fun getSavedRooms(): List<Room> {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(PREFS_KEY_ROOMS, "[]") ?: "[]"
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                try {
                    val obj = arr.getJSONObject(i)
                    Room(
                        name = obj.getString("name"),
                        anchorAddress = obj.getString("anchorAddress"),
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Raum $i konnte nicht gelesen werden: ${e.message}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getSavedRooms Fehler: ${e.message}")
            emptyList()
        }
    }

    /**
     * Speichert Raum-Konfiguration in SharedPreferences.
     * Wird von der Settings-UI aufgerufen wenn User Räume konfiguriert.
     */
    fun saveRoom(room: Room) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existing = getSavedRooms().toMutableList()
            // Überschreibe falls Raum mit gleichem Namen existiert
            existing.removeAll { it.name == room.name }
            existing.add(room)

            val arr = JSONArray()
            existing.forEach { r ->
                arr.put(JSONObject().apply {
                    put("name", r.name)
                    put("anchorAddress", r.anchorAddress)
                })
            }
            prefs.edit().putString(PREFS_KEY_ROOMS, arr.toString()).apply()
            Log.i(TAG, "Raum gespeichert: ${room.name} → ${room.anchorAddress}")
        } catch (e: Exception) {
            Log.e(TAG, "saveRoom Fehler: ${e.message}")
        }
    }

    // ── Positioning ──────────────────────────────────────────────────────────

    /**
     * Gibt den aktuellen Raum zurück basierend auf UWB-Ranging.
     *
     * Gibt null zurück wenn:
     * - UWB-Hardware nicht verfügbar
     * - Keine Räume konfiguriert
     * - Ranging schlägt fehl
     *
     * TODO DONNA-129 Phase 2: Echtes UWB-Ranging implementieren wenn Anchors vorhanden.
     */
    fun getCurrentRoom(): String? {
        if (!isUwbAvailable()) {
            Log.d(TAG, "getCurrentRoom: UWB nicht verfügbar")
            return null
        }
        val rooms = getSavedRooms()
        if (rooms.isEmpty()) {
            Log.d(TAG, "getCurrentRoom: Keine Räume konfiguriert")
            return null
        }
        // TODO DONNA-129 Phase 2: UwbManager.createRangingSession() für aktives Ranging
        // Gibt null zurück bis Anchor-Hardware konfiguriert ist
        Log.d(TAG, "getCurrentRoom: ${rooms.size} Räume konfiguriert aber noch kein Ranging (Phase 2)")
        return null
    }

    /**
     * Gibt einen lesbaren Status-String zurück (für UI + Bridge).
     */
    fun getStatus(): String = when {
        !isUwbAvailable() -> "UWB-Hardware nicht verfügbar"
        getSavedRooms().isEmpty() ->
            "Keine Räume konfiguriert — bitte in Einstellungen einrichten"
        else -> "Bereit (${getSavedRooms().size} Räume konfiguriert, Ranging ausstehend)"
    }.also { Log.i(TAG, "UWB Status: $it") }
}
