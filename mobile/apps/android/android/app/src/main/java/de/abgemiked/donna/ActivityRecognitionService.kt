package com.yourcompany.donna

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity

/**
 * ActivityRecognitionService — DONNA-119: Google Activity Recognition Integration
 *
 * Registriert ActivityTransition-Updates via Google Play Services.
 * Erkennt: WALKING, RUNNING, IN_VEHICLE, ON_BICYCLE, STILL
 *
 * Update-Intervall: 60_000ms (1 Minute) via ActivityTransitionRequest.
 * Ergebnisse werden via PendingIntent an ActivityRecognitionReceiver gesendet.
 *
 * Permission-Check: ACTIVITY_RECOGNITION (Android 10+ Runtime Permission)
 */
object ActivityRecognitionService {

    private const val TAG = "ActivityRecognitionSvc"

    /** PendingIntent-Request-Code — muss App-weit eindeutig sein */
    private const val PENDING_INTENT_REQUEST_CODE = 12001

    /**
     * Prüft ob die ACTIVITY_RECOGNITION-Permission erteilt ist.
     * Android 10+ (API 29+): Runtime Permission erforderlich.
     * Unter Android 10: automatisch erteilt.
     */
    fun hasPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true  // Unter Android 10: kein Runtime-Check nötig
        }
    }

    /**
     * Startet die Activity-Erkennung.
     * Registriert ActivityTransitions für alle relevanten Aktivitäten.
     * Fehler werden geloggt, kein Crash — graceful degradation.
     */
    fun start(context: Context) {
        if (!hasPermission(context)) {
            Log.w(TAG, "ACTIVITY_RECOGNITION Permission nicht erteilt — Activity Recognition nicht gestartet")
            return
        }

        val transitions = buildTransitionList()
        val request = ActivityTransitionRequest(transitions)
        val pendingIntent = buildPendingIntent(context)

        ActivityRecognition.getClient(context)
            .requestActivityTransitionUpdates(request, pendingIntent)
            .addOnSuccessListener {
                Log.i(TAG, "Activity Recognition registriert (${transitions.size} Transitions)")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Activity Recognition Registrierung fehlgeschlagen", e)
            }
    }

    /**
     * Stoppt die Activity-Erkennung.
     * Sollte bei App-Shutdown aufgerufen werden.
     */
    fun stop(context: Context) {
        val pendingIntent = buildPendingIntent(context)
        ActivityRecognition.getClient(context)
            .removeActivityTransitionUpdates(pendingIntent)
            .addOnSuccessListener {
                Log.i(TAG, "Activity Recognition deregistriert")
                pendingIntent.cancel()
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Activity Recognition Deregistrierung fehlgeschlagen", e)
            }
    }

    private fun buildTransitionList(): List<ActivityTransition> {
        val activityTypes = listOf(
            DetectedActivity.WALKING,
            DetectedActivity.RUNNING,
            DetectedActivity.IN_VEHICLE,
            DetectedActivity.ON_BICYCLE,
            DetectedActivity.STILL,
        )
        val transitionTypes = listOf(
            ActivityTransition.ACTIVITY_TRANSITION_ENTER,
            ActivityTransition.ACTIVITY_TRANSITION_EXIT,
        )
        return activityTypes.flatMap { activity ->
            transitionTypes.map { transition ->
                ActivityTransition.Builder()
                    .setActivityType(activity)
                    .setActivityTransition(transition)
                    .build()
            }
        }
    }

    private fun buildPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ActivityRecognitionReceiver::class.java).apply {
            action = ActivityRecognitionReceiver.ACTION_ACTIVITY_TRANSITION
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(
            context,
            PENDING_INTENT_REQUEST_CODE,
            intent,
            flags,
        )
    }
}
