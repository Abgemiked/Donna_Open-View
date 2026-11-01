package com.yourcompany.donna

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

/**
 * GeofenceManager — DONNA-121: Geofencing-Integration
 *
 * Definiert HOME und WORK Geofence-Zonen und registriert sie beim System.
 * Ergebnis-Events werden via PendingIntent an GeofenceBroadcastReceiver übermittelt.
 *
 * Koordinaten: Hardcoded als TODO — in einer späteren Phase via App-Settings konfigurierbar.
 *
 * Permissions:
 *   - ACCESS_FINE_LOCATION  (bereits vorhanden)
 *   - ACCESS_BACKGROUND_LOCATION (bereits vorhanden)
 */
object GeofenceManager {

    private const val TAG = "GeofenceManager"

    /** PendingIntent-Request-Code — muss App-weit eindeutig sein */
    private const val PENDING_INTENT_REQUEST_CODE = 13001

    // TODO: Koordinaten via App-Settings konfigurieren
    private val ZONES = listOf(
        GeofenceZone(
            id = "home",
            lat = 52.5200,   // TODO: Mit echten Home-Koordinaten ersetzen
            lon = 13.4050,
            radiusMeters = 150f,
        ),
        GeofenceZone(
            id = "work",
            lat = 52.5100,   // TODO: Mit echten Work-Koordinaten ersetzen
            lon = 13.4000,
            radiusMeters = 200f,
        ),
    )

    /** Dwell-Zeit in Millisekunden bevor DWELL-Transition gefeuert wird (15 Min) */
    private const val DWELL_DELAY_MS = 15 * 60 * 1000

    private var geofencingClient: GeofencingClient? = null

    data class GeofenceZone(
        val id: String,
        val lat: Double,
        val lon: Double,
        val radiusMeters: Float,
    )

    /**
     * Prüft ob die nötigen Location-Permissions erteilt sind.
     * Geofencing benötigt ACCESS_FINE_LOCATION + ACCESS_BACKGROUND_LOCATION.
     */
    fun hasPermissions(context: Context): Boolean {
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasBg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return hasFine && hasBg
    }

    /**
     * Startet Geofencing für alle definierten Zonen.
     * Graceful degradation: bei fehlenden Permissions → kein Crash, nur Log.
     */
    fun start(context: Context) {
        if (!hasPermissions(context)) {
            Log.w(TAG, "Geofencing nicht gestartet — Permissions fehlen (ACCESS_FINE_LOCATION + ACCESS_BACKGROUND_LOCATION)")
            return
        }

        geofencingClient = LocationServices.getGeofencingClient(context)

        val geofences = ZONES.map { zone ->
            Geofence.Builder()
                .setRequestId(zone.id)
                .setCircularRegion(zone.lat, zone.lon, zone.radiusMeters)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(
                    Geofence.GEOFENCE_TRANSITION_ENTER or
                    Geofence.GEOFENCE_TRANSITION_EXIT or
                    Geofence.GEOFENCE_TRANSITION_DWELL
                )
                .setLoiteringDelay(DWELL_DELAY_MS)
                .build()
        }

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(
                GeofencingRequest.INITIAL_TRIGGER_ENTER or
                GeofencingRequest.INITIAL_TRIGGER_DWELL
            )
            .addGeofences(geofences)
            .build()

        val pendingIntent = buildPendingIntent(context)

        try {
            geofencingClient!!.addGeofences(request, pendingIntent)
                .addOnSuccessListener {
                    Log.i(TAG, "Geofencing registriert: ${geofences.size} Zonen (${ZONES.map { it.id }})")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Geofencing Registrierung fehlgeschlagen", e)
                }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException beim Geofencing-Start — Permission fehlt", e)
        }
    }

    /**
     * Stoppt Geofencing und entfernt alle registrierten Zonen.
     */
    fun stop(context: Context) {
        val client = LocationServices.getGeofencingClient(context)
        val pendingIntent = buildPendingIntent(context)
        client.removeGeofences(pendingIntent)
            .addOnSuccessListener {
                Log.i(TAG, "Geofencing deregistriert")
                pendingIntent.cancel()
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Geofencing Deregistrierung fehlgeschlagen", e)
            }
    }

    private fun buildPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java).apply {
            action = GeofenceBroadcastReceiver.ACTION_GEOFENCE_TRANSITION
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
