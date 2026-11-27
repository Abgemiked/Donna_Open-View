package com.yourcompany.donna.device

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.yourcompany.donna.BuildConfig
import com.yourcompany.donna.SseEvent
import com.yourcompany.donna.TokenStore
import com.yourcompany.donna.TtsEngines
import java.net.HttpURLConnection
import java.net.URL

/**
 * #147 Paket D — Android-Anbindung des Offline-Wegs: erzeugt aus Mikes Äußerung die Ereignisse, die sonst der Server
 * liefern würde (Text + Geräte-Aktionen + Ende). Die Aktionen laufen danach durch denselben Weg wie sonst
 * (Gate: Schema, Schalter je Aktionsart, Budget -> Runner), nichts Eigenes. Logs ohne Nutzerinhalte.
 */
object OfflineFallback {
    private const val TAG = "OfflineFallback"

    /** Das Gerät hat gerade kein Netz mit Internet (dann sofort offline, ohne Wartezeit). */
    fun noInternet(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (_: Exception) {
        false // im Zweifel den normalen Weg versuchen
    }

    /** Ereignisse für den Offline-Weg. Nie leer: mindestens eine ehrliche Antwort und das Ende. */
    fun events(context: Context, prefs: SharedPreferences, message: String): List<SseEvent> {
        val enabled = DeviceActionKind.enabledSet(prefs) - DeviceActionKind.CHAT_ONLY
        val snapshot = OfflineRoutineStore.load(prefs)
        val outcome = try {
            OfflineCommands.interpret(message, enabled, snapshot)
        } catch (e: Exception) {
            Log.w(TAG, "Auswertung fehlgeschlagen: ${e.javaClass.simpleName}")
            OfflineCommands.Outcome.Say(OfflineCommands.NEEDS_SERVER)
        }
        return when (outcome) {
            is OfflineCommands.Outcome.Say -> listOf(SseEvent.Delta(outcome.speech), SseEvent.Done)
            is OfflineCommands.Outcome.Run ->
                listOf<SseEvent>(SseEvent.Delta(outcome.speech)) + outcome.actions.map { SseEvent.Action(it) } + SseEvent.Done
        }.also { Log.i(TAG, "offline: ${if (outcome is OfflineCommands.Outcome.Run) "actions=${outcome.actions.size}" else "say"}") }
    }
}

/** Lokale Ablage des zuletzt synchronisierten Routinen-Stands + Abgleich mit dem Server (best effort, gedrosselt). */
object OfflineRoutineStore {
    private const val TAG = "OfflineRoutines"
    const val PREF_KEY = "offline_routines_v1"
    private const val PREF_LAST_TRY = "offline_routines_last_try"
    private const val MIN_INTERVAL_MS = 30L * 60 * 1000
    private const val RETRY_AFTER_FAIL_MS = 5L * 60 * 1000
    private const val MAX_BODY_CHARS = 256 * 1024

    fun load(prefs: SharedPreferences): OfflineRoutineSnapshot? =
        try { prefs.getString(PREF_KEY, null)?.let { OfflineRoutineSnapshot.parseStored(it) } } catch (_: Exception) { null }

    /** Startet (höchstens alle 6 Stunden) einen Abgleich im Hintergrund; nie auf dem Aufrufer-Thread. */
    fun syncIfStale(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(TtsEngines.PREFS, Context.MODE_PRIVATE)
        val last = try { prefs.getLong(PREF_LAST_TRY, 0L) } catch (_: Exception) { 0L }
        if (nowMs - last < MIN_INTERVAL_MS) return
        prefs.edit().putLong(PREF_LAST_TRY, nowMs).apply()
        Thread({ sync(app, prefs, nowMs) }, "offline-routines-sync").apply { isDaemon = true }.start()
    }

    /** Nach einem Fehlschlag kurz warten (kein Dauerfeuer von Threads, solange der Server fehlt). */
    private fun failedTry(prefs: SharedPreferences, nowMs: Long) {
        prefs.edit().putLong(PREF_LAST_TRY, nowMs - MIN_INTERVAL_MS + RETRY_AFTER_FAIL_MS).apply()
    }

    private fun sync(app: Context, prefs: SharedPreferences, nowMs: Long) {
        val token = try { TokenStore.getToken(app) } catch (_: Exception) { null }
        if (token.isNullOrBlank()) return
        try {
            val conn = URL(BuildConfig.DONNA_API_URL + "/routines/offline").openConnection() as HttpURLConnection
            try {
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.connectTimeout = 5_000
                conn.readTimeout = 8_000
                val code = conn.responseCode
                if (code == 401 || code == 403) {
                    prefs.edit().remove(PREF_KEY).apply() // abgemeldet/entkoppelt: kein alter Stand mehr
                    return
                }
                if (code != 200) {
                    failedTry(prefs, nowMs)
                    return
                }
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                    val buf = CharArray(MAX_BODY_CHARS + 1)
                    val n = r.read(buf)
                    if (n < 0 || n > MAX_BODY_CHARS) return
                    String(buf, 0, n)
                }
                val snap = OfflineRoutineSnapshot.parseServer(body, nowMs) ?: return
                prefs.edit().putString(PREF_KEY, snap.toJson()).apply()
                Log.i(TAG, "synchronisiert: ${snap.routines.size}")
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Abgleich nicht möglich: ${e.javaClass.simpleName}")
            failedTry(prefs, nowMs)
        }
    }
}
