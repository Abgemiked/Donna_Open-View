package com.yourcompany.donna

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * GeofenceBroadcastReceiver — DONNA-121: Empfängt Geofence-Transitions
 *
 * Mapped GeofenceTransition → "home" | "work" | "transit"
 * und sendet den location_context via POST /tracking/push an das Backend.
 *
 * Bei mehreren gleichzeitigen Transitions (z.B. Enter + Dwell) wird nur
 * die semantisch relevanteste gesendet (ENTER/DWELL → zone, EXIT → "transit").
 */
class GeofenceBroadcastReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_GEOFENCE_TRANSITION = "com.yourcompany.donna.GEOFENCE_TRANSITION"
        private const val TAG = "GeofenceReceiver"
    }

    // Coroutine-Scope für HTTP-Post im BroadcastReceiver
    // SupervisorJob: verhindert dass ein fehlerhafter Job den Scope beendet
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent) ?: return

        if (geofencingEvent.hasError()) {
            val errorCode = geofencingEvent.errorCode
            Log.e(TAG, "Geofencing-Fehler: ${GeofenceStatusCodes.getStatusCodeString(errorCode)} ($errorCode)")
            return
        }

        val transition = geofencingEvent.geofenceTransition
        val triggeringGeofences = geofencingEvent.triggeringGeofences ?: emptyList()

        if (triggeringGeofences.isEmpty()) {
            Log.w(TAG, "Geofence-Transition ohne Geofences empfangen")
            return
        }

        // Ersten triggernden Geofence als Kontext verwenden
        val geofenceId = triggeringGeofences.first().requestId

        val locationContext = mapTransitionToContext(transition, geofenceId)
        Log.i(TAG, "Geofence-Transition: $geofenceId → $locationContext (transition=$transition)")

        scope.launch {
            pushLocationContext(context, locationContext)
        }
    }

    /**
     * Mapped Geofence-Transition + Zone-ID auf semantischen Kontext-String.
     *
     * Regeln:
     * - ENTER oder DWELL in bekannter Zone → Zone-ID ("home" / "work")
     * - EXIT aus bekannter Zone → "transit"
     * - Unbekannte Zone → "transit" (safe default)
     */
    private fun mapTransitionToContext(transition: Int, geofenceId: String): String {
        return when (transition) {
            Geofence.GEOFENCE_TRANSITION_ENTER,
            Geofence.GEOFENCE_TRANSITION_DWELL -> when (geofenceId) {
                "home" -> "home"
                "work" -> "work"
                else   -> "transit"
            }
            Geofence.GEOFENCE_TRANSITION_EXIT -> "transit"
            else -> "transit"
        }
    }

    /**
     * POST /tracking/push mit location_context-Feld.
     * Nutzt bestehendes TrackingPush-Schema — location_context ist optional/neu.
     */
    private fun pushLocationContext(context: Context, locationContext: String) {
        val token = TokenStore.getToken(context) ?: run {
            Log.w(TAG, "Kein Token im TokenStore — Geofence-Push übersprungen")
            return
        }

        val body = JSONObject().apply {
            put("type", "location_context")
            put("location_context", locationContext)
        }

        try {
            val url = URL("${BuildConfig.DONNA_API_URL}/tracking/push")
            val conn = url.openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            conn.disconnect()
            if (code !in 200..299) {
                Log.w(TAG, "Geofence-Push fehlgeschlagen: HTTP $code")
            } else {
                Log.i(TAG, "Geofence-Push erfolgreich: location_context=$locationContext")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Geofence-Push Fehler: ${e.message}")
        }
    }
}
