package com.yourcompany.donna

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HealthConnectManager — DONNA-120 / #182: Health Connect Integration
 *
 * Liest Gesundheitsdaten aus Health Connect (Entscheidungs-/Format-Logik: HealthSyncLogic):
 * - SleepSessionRecord (Hauptschlaf, 36 h) → sleep_start / sleep_end / sleep_hours
 * - StepsRecord (heute + gestern, tagesweise mit Datum) → steps_today/steps_date, steps_yesterday
 * - HeartRateRecord (Ruhepuls, nur wenn gemessen) → resting_hr
 * Fehlende Werte werden als JSON null gesendet (nie 0).
 *
 * Sendet an POST /health/push mit Bearer-Token aus TokenStore — nur an den eigenen Server.
 *
 * Requires: androidx.health.connect:connect-client
 */
object HealthConnectManager {

    private const val TAG = "HealthConnectManager"
    private const val PREFS_FILE = "donna_health_cache"
    private const val KEY_LAST_PUSH_DATE = "last_health_push_date"
    private const val KEY_LAST_SLEEP_KEY = "last_health_sleep_key"
    private const val KEY_DIAG_NOTE = "health_diag_note"
    private const val BACKEND_HEALTH_URL = "https://your-donna-instance.example.com/health/push"

    val REQUIRED_PERMISSIONS = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Verhindert parallele Läufe (MainActivity + Permission-Activity) → keine Doppel-Pushes. */
    private val running = AtomicBoolean(false)

    /**
     * Prüft ob Health Connect auf dem Gerät verfügbar ist.
     */
    fun isAvailable(context: Context): Boolean {
        val status = HealthConnectClient.getSdkStatus(context)
        return status == HealthConnectClient.SDK_AVAILABLE
    }

    /**
     * Ob alle REQUIRED_PERMISSIONS erteilt sind (für den Einrichtungs-Assistenten).
     * Suspend, da Health Connect die Abfrage asynchron beantwortet.
     * false bei nicht verfügbarem Health Connect oder Fehler — kein Crash.
     */
    suspend fun hasAllPermissions(context: Context): Boolean {
        if (!isAvailable(context)) return false
        return try {
            HealthConnectClient.getOrCreate(context)
                .permissionController.getGrantedPermissions()
                .containsAll(REQUIRED_PERMISSIONS)
        } catch (e: Exception) {
            Log.w(TAG, "Health-Connect-Permissions nicht lesbar: ${e.message}")
            false
        }
    }

    /**
     * Hinweistext für die Einstellungen, z. B. wenn Samsung Health keinen Schlaf liefert
     * (null = nichts zu melden / noch nie geprüft).
     */
    fun diagnosisNote(context: Context): String? =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .getString(KEY_DIAG_NOTE, null)?.takeIf { it.isNotBlank() }

    /**
     * Synchronisiert Gesundheitsdaten: höchstens 1× je Kalendertag und 1× je neuer Schlafsitzung.
     * Aufruf beim App-Start und nach erteilter Berechtigung; der Zustand wird erst nach
     * erfolgreicher Übertragung (HTTP 2xx) gespeichert.
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

        if (!running.compareAndSet(false, true)) {
            Log.d(TAG, "Health-Sync läuft bereits — übersprungen")
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

                val zone = ZoneId.systemDefault()
                val now = Instant.now()
                val todayDate = LocalDate.now(zone)
                val today = todayDate.toString()
                val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

                val sessions = readSleepSessions(client, now)
                // Ehrliche Meldung für die Einstellungen (leer = alles ok)
                prefs.edit().putString(KEY_DIAG_NOTE, HealthSyncLogic.diagnosis(sessions, now, zone) ?: "").apply()

                val sleep = HealthSyncLogic.pickMainSleep(sessions, now)
                val sleepKey = sleep?.let { HealthSyncLogic.sleepKey(it, zone) }
                val state = HealthSyncLogic.SyncState(
                    prefs.getString(KEY_LAST_PUSH_DATE, null),
                    prefs.getString(KEY_LAST_SLEEP_KEY, null),
                )
                if (!HealthSyncLogic.shouldPush(state, sleepKey, today)) {
                    Log.d(TAG, "Health-Sync: nichts Neues ($today) — übersprungen")
                    return@launch
                }

                val snapshot = HealthSyncLogic.Snapshot(
                    sleep = sleep,
                    stepsToday = runCatching { readSteps(client, todayDate) }.getOrNull(),
                    stepsYesterday = runCatching { readSteps(client, todayDate.minusDays(1)) }.getOrNull(),
                    restingHr = runCatching { readRestingHeartRate(client, now) }.getOrNull(),
                )
                // Gesundheitswerte nie in Release-Logs (Datenschutz)
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Health-Daten gelesen: $snapshot")
                }

                val body = HealthSyncLogic.buildPayload(snapshot, zone, today).toString()
                if (sendToBackend(token, body)) {
                    prefs.edit()
                        .putString(KEY_LAST_PUSH_DATE, today)
                        .putString(KEY_LAST_SLEEP_KEY, sleepKey ?: state.lastSleepKey)
                        .apply()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Health Connect Sync fehlgeschlagen", e)
            } finally {
                running.set(false)
            }
        }
    }

    /** Schlafsitzungen der letzten Tage (inkl. Wachphasen und Quell-App). */
    private suspend fun readSleepSessions(client: HealthConnectClient, now: Instant): List<HealthSyncLogic.Sleep> {
        val from = now.minus(Duration.ofDays(HealthSyncLogic.DIAG_WINDOW_DAYS))
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(from, now),
            )
        )
        return response.records.map { r ->
            val awakeSeconds = r.stages
                .filter {
                    it.stage == SleepSessionRecord.STAGE_TYPE_AWAKE ||
                        it.stage == SleepSessionRecord.STAGE_TYPE_OUT_OF_BED
                }
                .sumOf { Duration.between(it.startTime, it.endTime).seconds }
            HealthSyncLogic.Sleep(r.startTime, r.endTime, awakeSeconds, r.metadata.dataOrigin.packageName)
        }
    }

    /** Tagessumme (Health Connect dedupliziert Quellen); null = keine Schrittdaten. */
    private suspend fun readSteps(client: HealthConnectClient, day: LocalDate): Long? {
        val result = client.aggregate(
            AggregateRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(
                    day.atStartOfDay(),
                    day.plusDays(1).atStartOfDay(),
                ),
            )
        )
        return result[StepsRecord.COUNT_TOTAL]
    }

    /** Ruhepuls-Näherung = Minimum der letzten 24 h; null wenn nichts gemessen. */
    private suspend fun readRestingHeartRate(client: HealthConnectClient, now: Instant): Int? {
        val request = ReadRecordsRequest(
            recordType = HeartRateRecord::class,
            timeRangeFilter = TimeRangeFilter.between(now.minus(Duration.ofHours(24)), now),
        )
        val samples = client.readRecords(request).records.flatMap { it.samples }
        return samples.map { it.beatsPerMinute.toInt() }.filter { it > 0 }.minOrNull()
    }

    /** true bei HTTP 2xx. */
    private fun sendToBackend(token: String, body: String): Boolean {
        return try {
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
            conn.disconnect()
            if (code in 200..299) {
                Log.i(TAG, "Health-Daten erfolgreich gesendet (HTTP $code)")
                true
            } else {
                Log.w(TAG, "Backend antwortete mit HTTP $code für Health-Daten")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Senden der Health-Daten ans Backend", e)
            false
        }
    }
}
