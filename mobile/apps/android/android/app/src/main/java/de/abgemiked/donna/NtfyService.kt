package com.yourcompany.donna

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * NtfyService — Foreground Service für ntfy Push-Notifications im Background.
 *
 * DONNA-13: Empfängt Push-Nachrichten via ntfy HTTP-SSE-Stream (JSON-Format)
 * auch wenn die App vollständig gekillt wurde. Der Service läuft eigenständig
 * im nativen Android-Layer ohne Abhängigkeit vom JS-Thread.
 *
 * ntfy-Endpoint: GET {DONNA_API_URL}/donna/json?poll=0
 * Format: newline-delimited JSON, event-Feld = "open"|"keepalive"|"message"
 *
 * Design-Entscheidungen:
 *  - START_STICKY: Android startet den Service nach OOM-Kill automatisch neu
 *  - readTimeout=90s: ntfy sendet alle ~55s Keepalives → Timeout bei stiller Verbindung
 *  - sseJob-Guard: verhindert mehrfache SSE-Loops bei wiederholtem onStartCommand
 *  - AtomicInteger für Notification-IDs: thread-safe (IO-Dispatcher, mehrere Nachrichten)
 *
 * Unterschied zu useNtfyNotifications.ts:
 *   JS-Hook = Foreground/Background-suspended (App lebt)
 *   NtfyService = Background + App-Kill (Service überlebt App-Kill via START_STICKY)
 */
class NtfyService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // DONNA-13: Guard gegen mehrfache SSE-Loops bei wiederholtem onStartCommand
    private var sseJob: Job? = null
    // PARTIAL_WAKE_LOCK: verhindert CPU-Sleep im Doze-Mode — Display muss nicht an bleiben
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val TAG = "DonnaNtfy"

        // Foreground-Notification (persistente Status-Bar-Benachrichtigung)
        private const val CHANNEL_FOREGROUND_ID = "donna_ntfy_service"
        private const val NOTIF_SERVICE_ID = 1003

        // User-sichtbare Donna-Notifications
        private const val CHANNEL_PUSH_ID = "donna_push"
        // AtomicInteger — thread-safe bei gleichzeitigen Push-Events (IO-Dispatcher)
        private val pushNotifCounter = AtomicInteger(0)

        // ntfy sendet alle ~55s Keepalives — 90s Timeout triggert Reconnect bei stiller Verbindung
        private const val READ_TIMEOUT_MS = 90_000
        // Reconnect: Exponentieller Backoff — 2s, 4s, 8s … max 60s
        private const val RECONNECT_INITIAL_MS = 2_000L
        private const val RECONNECT_MAX_MS = 60_000L

        fun start(ctx: Context) {
            val intent = Intent(ctx, NtfyService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, NtfyService::class.java))
        }

        /**
         * DONNA-13: Plant einen AlarmManager-Restart in 5 Sekunden.
         * Wird von onTaskRemoved() aufgerufen wenn die App aus dem Recents-Menü
         * entfernt wird (Samsung One UI killt den Service in diesem Fall aggressiv).
         *
         * Strategie: ELAPSED_REALTIME_WAKEUP + setExact() — weckt das Gerät auch
         * aus dem Doze-Mode. AlarmManager-PendingIntent → NtfyRestartReceiver →
         * startForegroundService(NtfyService). So überlebt der Service auch bei
         * Samsung One UI die "App aus Recents entfernen"-Aktion.
         */
        fun scheduleRestart(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val restartIntent = Intent(ctx, NtfyRestartReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                ctx, 0, restartIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val triggerAt = SystemClock.elapsedRealtime() + RESTART_DELAY_MS
            // setExact: Samsung-kompatibel — setAndAllowWhileIdle wäre nicht exakt genug.
            // API 31+: canScheduleExactAlarms() prüfen; fallback auf setWindow() wenn denied.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                // Kein SCHEDULE_EXACT_ALARM Grant → setWindow mit 5s Fenster
                am.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, 5_000L, pi)
            } else {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
            Log.i(TAG, "NtfyService-Restart geplant in ${RESTART_DELAY_MS}ms")
        }

        // Restart-Delay: 5s — kurz genug um Notification-Lücke minimal zu halten
        private const val RESTART_DELAY_MS = 5_000L
    }

    // ── Service Lifecycle ─────────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannels()
        startForegroundCompat()
        // WakeLock acquiren — CPU bleibt aktiv auch im Doze-Mode
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "DonnaNtfy:NtfyService"
            ).also { it.acquire() }
            Log.d(TAG, "WakeLock acquired")
        }
        // Kein Doppelstart — wenn sseJob schon läuft, nichts tun
        if (sseJob?.isActive == true) {
            Log.d(TAG, "SSE-Loop läuft bereits — onStartCommand ignoriert")
            return START_STICKY
        }
        sseJob = startSseLoop()
        return START_STICKY  // Android startet Service nach Kill automatisch neu
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * DONNA-13: Wird aufgerufen wenn der User die App aus dem Recents-Menü wischt.
     * Samsung One UI killt den Foreground-Service in diesem Fall trotz START_STICKY.
     * Lösung: AlarmManager-PendingIntent 5s nach Entfernen → NtfyRestartReceiver →
     * Service wird neu gestartet. START_STICKY allein reicht auf Samsung nicht aus.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.w(TAG, "onTaskRemoved — Samsung-Restart via AlarmManager geplant")
        scheduleRestart(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        // WakeLock freigeben — isHeld-Check verhindert IllegalReleaseException
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        Log.d(TAG, "WakeLock released")
    }

    // ── Foreground Notification ───────────────────────────────────────────

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            // Kanal für den persistenten Service-Status (unauffällig)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_FOREGROUND_ID,
                    "Donna Push-Dienst",
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = "Hintergrundprozess für Push-Nachrichten"
                    setShowBadge(false)
                }
            )

            // Kanal für die eigentlichen Push-Nachrichten (sichtbar)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_PUSH_ID,
                    "Donna Benachrichtigungen",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Push-Nachrichten von Donna"
                }
            )
        }
    }

    private fun startForegroundCompat() {
        val launchIntent = packageManager
            .getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_FOREGROUND_ID)
            .setContentTitle("Donna")
            .setContentText("Push-Dienst aktiv")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: FOREGROUND_SERVICE_TYPE_DATA_SYNC erforderlich
            startForeground(
                NOTIF_SERVICE_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_SERVICE_ID, notification)
        }
    }

    // ── SSE Stream Loop ───────────────────────────────────────────────────

    private fun startSseLoop(): Job = scope.launch {
        var reconnectDelayMs = RECONNECT_INITIAL_MS
        while (isActive) {
            try {
                connectAndStream()
                // Saubere Verbindungstrennung (EOF oder SocketTimeout) → sofort reconnecten
                reconnectDelayMs = RECONNECT_INITIAL_MS
            } catch (e: Exception) {
                if (!isActive) break
                Log.w(TAG, "SSE-Verbindung fehlgeschlagen: ${e.javaClass.simpleName}: ${e.message}. Retry in ${reconnectDelayMs}ms")
                delay(reconnectDelayMs)
                reconnectDelayMs = minOf(reconnectDelayMs * 2, RECONNECT_MAX_MS)
            }
        }
    }

    /**
     * Öffnet den ntfy HTTP-SSE-Stream und verarbeitet eingehende JSON-Zeilen.
     * Blockiert bis zur Verbindungstrennung, SocketTimeoutException oder Exception.
     *
     * readTimeout=90s: ntfy sendet alle ~55s Keepalives — bei stiller Verbindung
     * (NAT-Drop, stiller Server-Crash) wird nach spätestens 90s reconnectet.
     */
    private fun connectAndStream() {
        // DONNA-13: Nutzt DONNA_NTFY_URL (separater ntfy-Container, nicht die FastAPI)
        val streamUrl = "${BuildConfig.DONNA_NTFY_URL}/donna/json?poll=0"
        Log.d(TAG, "Verbinde zu ntfy-Stream: $streamUrl")

        val conn = (URL(streamUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/x-ndjson")
            setRequestProperty("Authorization", "Bearer ${TokenStore.getToken(applicationContext) ?: ""}")
            connectTimeout = 15_000
            readTimeout = READ_TIMEOUT_MS  // SocketTimeoutException nach 90s Stille → Reconnect
        }

        try {
            conn.connect()
            val responseCode = conn.responseCode
            if (responseCode !in 200..299) {
                throw RuntimeException("HTTP $responseCode")
            }

            Log.i(TAG, "ntfy-Stream verbunden (HTTP $responseCode)")

            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (!scope.isActive) break
                    line?.takeIf { it.isNotBlank() }?.let { processLine(it) }
                }
            }

            Log.i(TAG, "ntfy-Stream sauber beendet (EOF)")
        } finally {
            // Sicherstellen dass Socket geschlossen wird — auch bei Coroutine-Cancel
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    // ── Message Processing ────────────────────────────────────────────────

    private fun processLine(line: String) {
        val json: JSONObject = try {
            JSONObject(line)
        } catch (e: Exception) {
            Log.w(TAG, "Ungültiges JSON ignoriert: ${line.take(80)}")
            return
        }

        val event = json.optString("event", "")
        when (event) {
            "open"      -> Log.d(TAG, "ntfy stream geöffnet")
            "keepalive" -> Log.d(TAG, "ntfy keepalive")
            "message"   -> showPushNotification(json)
            else        -> Log.d(TAG, "Unbekanntes ntfy-Event: $event")
        }
    }

    private fun showPushNotification(json: JSONObject) {
        val title = json.optString("title", "Donna").ifBlank { "Donna" }
        val body  = json.optString("message", "").ifBlank {
            json.optString("body", "")
        }

        if (body.isBlank()) {
            Log.w(TAG, "Push-Nachricht ohne Body — ignoriert")
            return
        }

        Log.i(TAG, "Push empfangen: $title — ${body.take(60)}")

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifId = 2000 + pushNotifCounter.incrementAndGet()  // AtomicInteger — thread-safe

        // DONNA-198 v2: Proaktive Nachricht in SharedPreferences speichern BEVOR Notification gezeigt wird.
        // Race-Condition-Fix: DeviceEventEmitter kann feuern BEVOR App.tsx seinen Listener registriert.
        // SharedPreferences ist die zuverlässige Fallback-Quelle — ChatScreen liest beim Mount.
        val sessionId = json.optString("session_id", "").ifBlank { null }
        val proactivePayload = JSONObject().apply {
            put("message", body)
            if (sessionId != null) put("session_id", sessionId)
        }.toString()
        ProactiveMessageModule.save(applicationContext, proactivePayload)

        // BUG-1 Fix: Intent mit Extras — JS-Schicht erkennt Notification-Tap
        // DONNA-135: Proaktive Nachricht + Flag zum Öffnen eines neuen Chats mitgeben
        // DONNA-147: edge-cases verified — FLAG_ACTIVITY_NEW_TASK + CLEAR_TOP + SINGLE_TOP
        // aktiviert bestehende Activity statt neue Task zu erstellen (Android 12+ korrekt)
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("from_notification", true)
            putExtra("source", "ntfy_notification")
            // Session-ID aus ntfy-JSON mitgeben falls vorhanden
            if (sessionId != null) putExtra("session_id", sessionId)
            // DONNA-135: Proaktive Nachricht und New-Chat-Flag für JS-Schicht
            putExtra("donna_proactive_message", body)
            putExtra("open_new_chat", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            notifId,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_PUSH_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        nm.notify(notifId, notification)
    }
}
