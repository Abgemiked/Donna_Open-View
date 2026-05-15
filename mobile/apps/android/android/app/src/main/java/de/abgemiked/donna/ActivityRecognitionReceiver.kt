package com.yourcompany.donna

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * ActivityRecognitionReceiver — DONNA-119: Empfängt ActivityTransitionResults
 *
 * Wird von Google Play Services aufgerufen wenn sich die Aktivität ändert.
 * Mapped den Google-ActivityType auf Donna-Strings und sendet via HTTP POST
 * an /tracking/push mit Bearer-Token aus TokenStore.
 */
class ActivityRecognitionReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "ActivityRecognitionRcv"
        const val ACTION_ACTIVITY_TRANSITION = "com.yourcompany.donna.ACTIVITY_TRANSITION"

        /** Maps Google DetectedActivity-Typ auf Donna-Aktivitäts-String */
        fun mapActivityType(type: Int): String = when (type) {
            DetectedActivity.WALKING    -> "WALKING"
            DetectedActivity.RUNNING    -> "RUNNING"
            DetectedActivity.IN_VEHICLE -> "IN_VEHICLE"
            DetectedActivity.ON_BICYCLE -> "ON_BICYCLE"
            DetectedActivity.STILL      -> "STILL"
            DetectedActivity.TILTING    -> "UNKNOWN"
            DetectedActivity.UNKNOWN    -> "UNKNOWN"
            else                        -> "UNKNOWN"
        }
    }

    // SupervisorJob: BroadcastReceiver-Lebensdauer ist kurz — eigener Scope verhindert Leak
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) {
            Log.w(TAG, "Intent hat kein ActivityTransitionResult — ignoriert")
            return
        }

        val result = ActivityTransitionResult.extractResult(intent) ?: run {
            Log.w(TAG, "ActivityTransitionResult konnte nicht extrahiert werden")
            return
        }

        // Letztes ENTER-Event nehmen — das ist die aktuelle Aktivität
        val latestEnter = result.transitionEvents
            .filter { it.transitionType == com.google.android.gms.location.ActivityTransition.ACTIVITY_TRANSITION_ENTER }
            .lastOrNull()

        if (latestEnter == null) {
            Log.d(TAG, "Nur EXIT-Events — kein ENTER, nichts zu senden")
            return
        }

        val activityType = mapActivityType(latestEnter.activityType)
        Log.i(TAG, "Aktivität erkannt: $activityType")

        val token = TokenStore.getToken(context)
        if (token == null) {
            Log.w(TAG, "Kein Token in TokenStore — Activity nicht gesendet")
            return
        }

        scope.launch {
            sendActivityToBackend(token, activityType)
        }
    }

    private fun sendActivityToBackend(token: String, activityType: String) {
        // BuildConfig ist nur in Activity/Application zugänglich — URL hardcoded (wie TrackingService)
        val url = "https://your-donna-instance.example.com/tracking/push"

        try {
            val body = JSONObject().apply {
                put("type", "activity_recognition")
                put("activity_type", activityType)
            }.toString()

            val conn = URL(url).openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
            }

            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code in 200..299) {
                Log.i(TAG, "Activity '$activityType' erfolgreich gesendet (HTTP $code)")
            } else {
                Log.w(TAG, "Backend antwortete mit HTTP $code für Activity '$activityType'")
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Senden der Activity '$activityType' ans Backend", e)
        }
    }
}
