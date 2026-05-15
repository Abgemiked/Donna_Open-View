package com.yourcompany.donna

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * TrackingService — Foreground Service für GPS + App-Aktivitäts-Tracking.
 *
 * Sendet alle 5 Minuten:
 *  - Aktuellen GPS-Standort (via LocationManager)
 *  - App-Nutzungsstatistiken der letzten 30 Min (via UsageStatsManager)
 *  - Leichtgewichtigen Heartbeat mit screen_on + device-Tag (DONNA-95)
 * an den Donna-Backend-Endpunkt POST /tracking/push.
 *
 * Permissions benötigt (müssen vom User manuell genehmigt werden):
 *  - android.permission.ACCESS_FINE_LOCATION
 *  - android.permission.ACCESS_BACKGROUND_LOCATION (Android 10+)
 *  - android.permission.PACKAGE_USAGE_STATS (Special Permission, via Settings)
 */
class TrackingService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var locationManager: LocationManager? = null
    private var lastLocation: Location? = null
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
        }
        @Deprecated("Deprecated in API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    companion object {
        private const val CHANNEL_ID = "donna_tracking_channel"
        private const val NOTIF_ID = 1002
        private const val PUSH_INTERVAL_MS = 5 * 60 * 1000L // 5 Minuten

        fun start(ctx: Context) {
            val intent = Intent(ctx, TrackingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TrackingService::class.java))
        }
    }

    // ── Service Lifecycle ─────────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForegroundCompat()
        startLocationUpdates()
        startPushLoop()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (_: Exception) {}
    }

    // ── Foreground Notification ───────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Donna Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Standort- und Aktivitäts-Tracking für Donna"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Donna")
            .setContentText("Aktivitäts-Tracking aktiv")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    // ── Location ──────────────────────────────────────────────────────────

    private fun startLocationUpdates() {
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            // GPS-Provider bevorzugen, Fallback auf Network
            val provider = when {
                locationManager!!.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    -> LocationManager.GPS_PROVIDER
                locationManager!!.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                    -> LocationManager.NETWORK_PROVIDER
                else -> return
            }
            locationManager!!.requestLocationUpdates(
                provider,
                60_000L,  // min 1 Minute zwischen Updates
                50f,      // min 50 Meter Bewegung
                locationListener
            )
            // Letzten bekannten Standort sofort nutzen
            lastLocation = locationManager!!.getLastKnownLocation(provider)
        } catch (_: SecurityException) {
            // Permission nicht erteilt — kein Crash, nächster Versuch beim nächsten Start
        }
    }

    // ── Push Loop ─────────────────────────────────────────────────────────

    private fun startPushLoop() {
        scope.launch {
            while (isActive) {
                pushHeartbeat()   // DONNA-95: leichtgewichtiger Ping zuerst
                pushLocation()
                pushActivity()
                pushMediaState()  // DONNA-123: MediaSession-Status
                delay(PUSH_INTERVAL_MS)
            }
        }
    }

    // ── Heartbeat (DONNA-95) ──────────────────────────────────────────────

    /**
     * Leichtgewichtiger 5-Min-Ping ohne App-Usage-Overhead.
     * Sendet screen_on-Status + device-Tag "android" für PresenceService.
     */
    private fun pushHeartbeat() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val screenOn = powerManager.isInteractive
        val body = JSONObject().apply {
            put("type", "pc_heartbeat")  // gleicher Type wie PC — device-Feld unterscheidet
            put("heartbeat", JSONObject().apply {
                put("device", "android")
                put("screen_on", screenOn)
                put("idle_sec", 0)         // Android hat kein system-weites idle_sec
                put("active_app", JSONObject.NULL)
                put("donna_focused", false)
            })
        }
        postJson(body)
    }

    private fun pushLocation() {
        val loc = lastLocation ?: return
        val body = JSONObject().apply {
            put("type", "location")
            put("location", JSONObject().apply {
                put("lat", loc.latitude)
                put("lon", loc.longitude)
                put("accuracy", loc.accuracy)
                put("speed", loc.speed)
                put("altitude", loc.altitude)
            })
        }
        postJson(body)
    }

    private fun pushActivity() {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val windowMs = 30 * 60 * 1000L
        val rawStats: MutableList<UsageStats>? = try {
            usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                now - windowMs,
                now
            )
        } catch (_: Exception) {
            null
        }

        val statsList: List<UsageStats> = rawStats ?: return
        if (statsList.isEmpty()) return
        val appsArray = JSONArray()
        statsList
            .filter { stat -> stat.totalTimeInForeground > 0 }
            .sortedByDescending { stat -> stat.totalTimeInForeground }
            .take(20)
            .forEach { stat ->
                appsArray.put(JSONObject().apply {
                    put("package", stat.packageName)
                    put("usage_ms", stat.totalTimeInForeground)
                })
            }

        val body = JSONObject().apply {
            put("type", "activity")
            put("activity", JSONObject().apply {
                put("apps", appsArray)
                put("window_min", 30)
            })
        }
        postJson(body)
    }

    // ── MediaSession (DONNA-123) ──────────────────────────────────────────

    /**
     * Aktualisiert MediaSessionObserver und sendet aktuellen Media-State
     * als /tracking/push mit type="media_playing".
     * Sendet auch wenn null (kein Medium aktiv) damit Backend den Zustand kennt.
     */
    private fun pushMediaState() {
        // Refresh im Service-Thread (kein UI-Thread nötig)
        MediaSessionObserver.refresh(applicationContext)

        val mediaDict = MediaSessionObserver.toDict()
        val body = JSONObject().apply {
            put("type", "media_playing")
            if (mediaDict != null) {
                put("media_playing", JSONObject().apply {
                    put("app", mediaDict["app"] ?: "unknown")
                    put("title", mediaDict["title"] ?: JSONObject.NULL)
                    put("artist", mediaDict["artist"] ?: JSONObject.NULL)
                    put("playing", mediaDict["playing"] ?: false)
                })
            }
            // media_playing fehlt komplett wenn null → Backend speichert null
        }
        postJson(body)
    }

    // ── HTTP ──────────────────────────────────────────────────────────────

    private fun postJson(body: JSONObject) {
        try {
            val url = URL("${BuildConfig.DONNA_API_URL}/tracking/push")
            val conn = url.openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer ${TokenStore.getToken(applicationContext) ?: ""}")
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            conn.disconnect()
            if (code !in 200..299) {
                android.util.Log.w("DonnaTracking", "push failed: HTTP $code")
            }
        } catch (e: Exception) {
            android.util.Log.w("DonnaTracking", "push error: ${e.message}")
        }
    }
}
