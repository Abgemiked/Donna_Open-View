package com.yourcompany.donna

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * HealthConnectManager — DONNA-120: Health Connect Integration
 *
 * Liest täglich (gecacht via SharedPreferences) Gesundheitsdaten aus Health Connect:
 * - SleepSessionRecord (letzte 24h) → sleep_hours
 * - StepsRecord (heute) → steps_today
 * - HeartRateRecord (Ruhepuls, letzte 24h) → resting_hr
 *
 * Sendet Daten an POST /health/push mit Bearer-Token aus TokenStore.
 *
 * Requires: androidx.health.connect:connect-client:1.1.0-rc01
 */
object HealthConnectManager {

    private const val TAG = "HealthConnectManager"
    private const val PREFS_FILE = "donna_health_cache"
    private const val KEY_LAST_SYNC_DATE = "last_health_sync_date"
    private const val BACKEND_HEALTH_URL = "https://your-donna-instance.example.com/health/push"

    val REQUIRED_PERMISSIONS = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Prüft ob Health Connect auf dem Gerät verfügbar ist.
     */
    fun isAvailable(context: Context): Boolean {
        val status = HealthConnectClient.getSdkStatus(context)
        return status == HealthConnectClient.SDK_AVAILABLE
    }

    /**
     * Synchronisiert Gesundheitsdaten — nur einmal täglich (gecacht).
     * Wird beim App-Start von MainActivity aufgerufen.
     * Kein Crash bei fehlenden Permissions oder nicht verfügbarem Health Connect.
     */
    fun syncIfNeeded(context: Context) {
        if (!isAvailable(context)) {
            Log.d(TAG, "Health Connect nicht verfügbar auf diesem Gerät — übersprungen")
            return
        }

        val token = TokenStore.getToken(context) ?: run {
            Log.w(TAG, "Kein Token in TokenStore — Health Sync übersprungen")
            return
        }

        val today = LocalDate.now(ZoneId.systemDefault()).toString()
        val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        val lastSyncDate = prefs.getString(KEY_LAST_SYNC_DATE, null)

        if (lastSyncDate == today) {
            Log.d(TAG, "Health-Sync heute bereits durchgeführt ($today) — übersprungen")
            return
        }

        scope.launch {
            try {
                val client = HealthConnectClient.getOrCreate(context)
                val grantedPermissions = client.permissionController.getGrantedPermissions()

                if (!grantedPermissions.containsAll(REQUIRED_PERMISSIONS)) {
                    Log.w(TAG, "Health Connect Permissions nicht vollständig erteilt — Sync übersprungen")
                    return@launch
                }

                val sleepHours = readSleepHours(client)
                val stepsToday = readStepsToday(client)
                val restingHr = readRestingHeartRate(client)

                Log.i(TAG, "Health-Daten gelesen: sleep=${sleepHours}h, steps=$stepsToday, hr=$restingHr")

                sendToBackend(token, sleepHours, stepsToday, restingHr)

                // Cache-Datum setzen damit heute kein zweiter Sync erfolgt
                prefs.edit().putString(KEY_LAST_SYNC_DATE, today).apply()

            } catch (e: Exception) {
                Log.e(TAG, "Health Connect Sync fehlgeschlagen", e)
            }
        }
    }

    private suspend fun readSleepHours(client: HealthConnectClient): Float {
        val now = Instant.now()
        val yesterday = now.minusSeconds(TimeUnit.HOURS.toSeconds(24))

        val request = ReadRecordsRequest(
            recordType = SleepSessionRecord::class,
            timeRangeFilter = TimeRangeFilter.between(yesterday, now),
        )
        val response = client.readRecords(request)

        val totalSleepMs = response.records.sumOf { session ->
            session.endTime.toEpochMilli() - session.startTime.toEpochMilli()
        }
        return totalSleepMs.toFloat() / TimeUnit.HOURS.toMillis(1)
    }

    private suspend fun readStepsToday(client: HealthConnectClient): Int {
        val zoneId = ZoneId.systemDefault()
        val startOfDay = LocalDate.now(zoneId).atStartOfDay(zoneId).toInstant()
        val now = Instant.now()

        val request = ReadRecordsRequest(
            recordType = StepsRecord::class,
            timeRangeFilter = TimeRangeFilter.between(startOfDay, now),
        )
        val response = client.readRecords(request)
        return response.records.sumOf { it.count }.toInt()
    }

    private suspend fun readRestingHeartRate(client: HealthConnectClient): Int {
        val now = Instant.now()
        val yesterday = now.minusSeconds(TimeUnit.HOURS.toSeconds(24))

        val request = ReadRecordsRequest(
            recordType = HeartRateRecord::class,
            timeRangeFilter = TimeRangeFilter.between(yesterday, now),
        )
        val response = client.readRecords(request)

        if (response.records.isEmpty()) return 0

        // Ruhepuls = Minimum aller gemessenen Herzraten (Annäherung)
        val allSamples = response.records.flatMap { it.samples }
        if (allSamples.isEmpty()) return 0
        return allSamples.minOf { it.beatsPerMinute }.toInt()
    }

    private fun sendToBackend(token: String, sleepHours: Float, stepsToday: Int, restingHr: Int) {
        try {
            val body = JSONObject().apply {
                put("sleep_hours", sleepHours)
                put("steps_today", stepsToday)
                put("resting_hr", restingHr)
            }.toString()

            val conn = URL(BACKEND_HEALTH_URL).openConnection() as HttpURLConnection
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
                Log.i(TAG, "Health-Daten erfolgreich gesendet (HTTP $code)")
            } else {
                Log.w(TAG, "Backend antwortete mit HTTP $code für Health-Daten")
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Senden der Health-Daten ans Backend", e)
        }
    }
}
