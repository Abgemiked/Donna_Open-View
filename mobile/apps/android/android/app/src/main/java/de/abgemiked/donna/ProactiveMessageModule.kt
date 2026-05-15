package com.yourcompany.donna

import android.content.Context
import android.content.SharedPreferences
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod

/**
 * ProactiveMessageModule — Native Module für SharedPreferences-basiertes Proaktiv-Nachrichten-Handling.
 *
 * DONNA-198 v2: Löst die Race-Condition beim Kalt-Start:
 * DeviceEventEmitter kann feuern BEVOR App.tsx den Listener registriert hat.
 * Lösung: proaktive Nachricht wird in SharedPreferences gespeichert (NtfyService.kt),
 * App.tsx/ChatScreen.tsx liest beim Mounten über dieses Native Module.
 *
 * API:
 *   ProactiveMessageModule.getAndClear() → Promise<string | null>
 *   Gibt JSON {"message":"...","session_id":"..."} zurück und löscht den Eintrag.
 *   Gibt null zurück wenn keine proaktive Nachricht ausstehend.
 */
class ProactiveMessageModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = "ProactiveMessageModule"

    private fun prefs(): SharedPreferences =
        reactApplicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Liest und löscht die ausstehende proaktive Nachricht.
     * commit() statt apply() — synchron, garantiert Atomarität zwischen read und delete.
     * Verhindert Race-Condition bei doppeltem Aufruf (z.B. DeviceEventEmitter + AppState-Resume).
     * Gibt null zurück wenn kein Eintrag vorhanden.
     */
    @ReactMethod
    fun getAndClear(promise: Promise) {
        try {
            val prefs = prefs()
            val payload = prefs.getString(KEY_PROACTIVE_PAYLOAD, null)
            if (payload != null) {
                prefs.edit().remove(KEY_PROACTIVE_PAYLOAD).commit() // commit() statt apply() für Synchronität
                android.util.Log.i(TAG, "getAndClear: proaktive Nachricht gelesen + gelöscht: ${payload.take(80)}")
                promise.resolve(payload)
            } else {
                promise.resolve(null)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "getAndClear fehlgeschlagen: ${e.message}")
            promise.resolve(null)  // Fehler ≠ Crash — null zurückgeben
        }
    }

    /**
     * Löscht eine evtl. ausstehende Nachricht ohne sie zu lesen.
     * Wird von MainActivity aufgerufen nachdem DeviceEventEmitter erfolgreich emittiert hat.
     */
    @ReactMethod
    fun clear(promise: Promise) {
        try {
            prefs().edit().remove(KEY_PROACTIVE_PAYLOAD).apply()
            promise.resolve(null)
        } catch (e: Exception) {
            promise.resolve(null)
        }
    }

    companion object {
        const val TAG = "ProactiveMessageModule"
        const val PREFS_NAME = "donna_proactive"
        const val KEY_PROACTIVE_PAYLOAD = "pending_payload"

        /** Speichert einen JSON-Payload in SharedPreferences. Wird von NtfyService aufgerufen.
         *  commit() statt apply() — synchroner Disk-Write garantiert, dass getAndClear()
         *  den Payload sofort findet wenn die App aus dem Hintergrund resumt. */
        fun save(context: Context, jsonPayload: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PROACTIVE_PAYLOAD, jsonPayload)
                .commit() // commit() statt apply() — Disk-Write muss abgeschlossen sein bevor App resumt
            android.util.Log.i(TAG, "save: proaktive Nachricht in SharedPreferences gespeichert (commit): ${jsonPayload.take(80)}")
        }

        /** Liest (ohne Löschen) — für MainActivity.scheduleProactiveChatEmit. */
        fun peek(context: Context): String? =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_PROACTIVE_PAYLOAD, null)

        /** Löscht — nach erfolgreichem DeviceEventEmitter-Emit. */
        fun clear(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(KEY_PROACTIVE_PAYLOAD).apply()
        }
    }
}
