package com.yourcompany.donna.widget

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.util.Log
import com.yourcompany.donna.BuildConfig
import com.yourcompany.donna.TokenStore
import com.yourcompany.donna.calendaredit.AndroidCalendarGateway
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Welle 2 (#236): gemeinsames Fundament der Daten-Widgets. Holt Daten NUR für Widgets, die auf dem Homescreen liegen,
 * schreibt sie in den [WidgetCache] und zeichnet die Widgets neu. Angestoßen von:
 *  - NtfyService (jede Push-Nachricht, Dienststart), MainActivity.onResume (App-Start/-Vordergrund),
 *  - updatePeriodMillis 30 min der Widgets (Sicherheitsnetz), Kalender-ContentObserver, Widget-Knöpfen.
 * Drosseln schützen vor Dauerabrufen (Erinnerungen 20 s, Streamplan 30 min nach Erfolg / 5 min nach Fehlversuch).
 * Ein Lauf zur Zeit; ein Anstoß während eines Laufs löst genau einen weiteren Lauf aus. Nie Inhalte loggen.
 */
object WidgetRefresher {
    private const val TAG = "WidgetRefresher"
    const val SCHEDULE_URL = "https://your-donna-instance.example.com/api/schedule"
    private const val REMINDER_MIN_GAP_MS = 20_000L
    private const val STREAM_OK_GAP_MS = 30 * 60_000L
    private const val STREAM_TRY_GAP_MS = 5 * 60_000L
    private const val TIMEOUT_MS = 6_000
    private const val BODY_LIMIT = 64 * 1024

    private val running = AtomicBoolean(false)
    private val again = AtomicBoolean(false)
    private val forceReminders = AtomicBoolean(false)
    private val observerRegistered = AtomicBoolean(false)

    val zone: ZoneId get() = ZoneId.systemDefault()

    private fun ids(ctx: Context, cls: Class<*>): IntArray =
        try { AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, cls)) } catch (_: Exception) { IntArray(0) }

    fun hasReminderWidgets(ctx: Context) = ids(ctx, ReminderWidgetProvider::class.java).isNotEmpty()
    fun hasCalendarWidgets(ctx: Context) = ids(ctx, CalendarWidgetProvider::class.java).isNotEmpty()
    fun hasStreamWidgets(ctx: Context) = ids(ctx, StreamWidgetProvider::class.java).isNotEmpty()

    fun canReadCalendar(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /**
     * Daten neu holen und alle Daten-Widgets neu zeichnen (im Hintergrund). [forceReminderFetch] umgeht die
     * 20-s-Drossel (nach einem Widget-Knopf). [onDone] kommt immer (für goAsync der Widget-Broadcasts).
     */
    fun request(ctx: Context, forceReminderFetch: Boolean = false, onDone: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        if (forceReminderFetch) forceReminders.set(true)
        if (!running.compareAndSet(false, true)) {
            again.set(true)
            onDone?.invoke()
            return
        }
        Thread {
            try {
                do {
                    again.set(false)
                    refreshOnce(app)
                } while (again.get())
            } catch (e: Exception) {
                Log.w(TAG, "Aktualisierung fehlgeschlagen: ${e.javaClass.simpleName}")
            } finally {
                running.set(false)
                try { onDone?.invoke() } catch (_: Exception) {}
                if (again.getAndSet(false)) request(app)
            }
        }.apply { isDaemon = true; name = "widget-refresh" }.start()
    }

    private fun refreshOnce(app: Context) {
        ensureCalendarObserver(app)
        val now = System.currentTimeMillis()
        if (hasReminderWidgets(app)) {
            val force = forceReminders.getAndSet(false)
            if (force || now - WidgetCache.long(app, WidgetCache.REMINDERS_TRIED) > REMINDER_MIN_GAP_MS) loadReminders(app, now)
        }
        if (hasCalendarWidgets(app)) loadCalendar(app, now)
        if (hasStreamWidgets(app)) {
            val okAge = now - WidgetCache.long(app, WidgetCache.STREAM_AT)
            val tryAge = now - WidgetCache.long(app, WidgetCache.STREAM_TRIED)
            if (okAge > STREAM_OK_GAP_MS && tryAge > STREAM_TRY_GAP_MS) loadStream(app, now)
        }
        renderAll(app)
        WidgetsW3.onRefresh(app, now) // Welle 3 (#237): Briefing laden, Fokus/Timer neu zeichnen
    }

    /** Nur aus dem Cache neu zeichnen (z. B. sofort nach einem Knopfdruck). */
    fun renderAll(ctx: Context) {
        val app = ctx.applicationContext
        val m = AppWidgetManager.getInstance(app)
        try {
            ids(app, ReminderWidgetProvider::class.java).takeIf { it.isNotEmpty() }?.let { ReminderWidgetProvider.render(app, m, it) }
            ids(app, CalendarWidgetProvider::class.java).takeIf { it.isNotEmpty() }?.let { CalendarWidgetProvider.render(app, m, it) }
            ids(app, StreamWidgetProvider::class.java).takeIf { it.isNotEmpty() }?.let { StreamWidgetProvider.render(app, m, it) }
        } catch (e: Exception) {
            Log.w(TAG, "Zeichnen fehlgeschlagen: ${e.javaClass.simpleName}")
        }
    }

    /** Push einer Erinnerung ist angekommen → sie ist zugestellt und steht nicht mehr „bevor“. */
    fun onReminderDelivered(ctx: Context, id: String?) {
        if (id.isNullOrEmpty()) return
        val app = ctx.applicationContext
        if (!hasReminderWidgets(app)) return
        val now = System.currentTimeMillis()
        WidgetCache.put(
            app,
            WidgetCache.REMINDERS_DELIVERED to
                ReminderWidgetLogic.deliveredAdd(WidgetCache.str(app, WidgetCache.REMINDERS_DELIVERED), id, now),
        )
        WidgetCache.updateReminders(app) { ReminderWidgetLogic.without(it, id) }
        renderAll(app)
    }

    // ── Quellen ────────────────────────────────────────────────────────────────

    /** GET mit Grenzen; liefert (Code, Text) oder null bei Netzfehler. [bearer] nur für den eigenen Server. */
    private fun get(url: String, bearer: String?): Pair<Int, String?>? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
            val code = conn.responseCode
            val body = if (code in 200..299) {
                conn.inputStream.use { s ->
                    val bytes = s.readNBytesCompat(BODY_LIMIT + 1)
                    if (bytes.size > BODY_LIMIT) null else String(bytes, Charsets.UTF_8)
                }
            } else null
            code to body
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        Log.w(TAG, "Abruf fehlgeschlagen: ${e.javaClass.simpleName}")
        null
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (out.size() < limit) {
            val n = read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun loadReminders(app: Context, now: Long) {
        WidgetCache.put(app, WidgetCache.REMINDERS_TRIED to now)
        val token = try { TokenStore.getToken(app) } catch (_: Exception) { null }
        if (token.isNullOrEmpty()) {
            // nicht (mehr) verbunden → alte Texte nicht weiter auf dem Homescreen zeigen
            WidgetCache.put(app, WidgetCache.REMINDERS_STATE to WidgetCache.STATE_NO_TOKEN, WidgetCache.REMINDERS to null)
            return
        }
        val res = get("${BuildConfig.DONNA_API_URL}/reminders", token)
        val items = if (res != null && res.first == 200) ReminderWidgetLogic.parse(res.second) else null
        if (items == null) {
            // Fehler → letzter Cache bleibt stehen
            WidgetCache.put(app, WidgetCache.REMINDERS_STATE to WidgetCache.STATE_ERROR)
            return
        }
        // gerade per Push zugestellte meldet der Server kurz noch als offen („sending“) → herausfiltern
        val delivered = ReminderWidgetLogic.deliveredActive(WidgetCache.str(app, WidgetCache.REMINDERS_DELIVERED), now)
        WidgetCache.updateReminders(app) { items.filter { it.id !in delivered } }
        WidgetCache.put(app, WidgetCache.REMINDERS_AT to now, WidgetCache.REMINDERS_STATE to WidgetCache.STATE_OK)
    }

    private fun loadCalendar(app: Context, now: Long) {
        if (!canReadCalendar(app)) {
            WidgetCache.put(app, WidgetCache.CALENDAR_STATE to WidgetCache.STATE_NO_PERMISSION, WidgetCache.CALENDAR to null)
            return
        }
        try {
            val (from, to) = CalendarWidgetLogic.window(now, zone)
            val raw = AndroidCalendarGateway(app).instances(from, to)
            // #243: wer hat erstellt / wer ist beteiligt — nur der fertige Namens-Tag landet im Cache
            val tags = CalendarPeopleReader.tags(app, raw)
            val events = raw.map {
                WidgetEvent(it.eventId, it.beginMs, it.endMs, it.title, it.allDay, tags[it.eventId].orEmpty())
            }
            WidgetCache.put(
                app,
                WidgetCache.CALENDAR to CalendarWidgetLogic.toCache(events),
                WidgetCache.CALENDAR_STATE to WidgetCache.STATE_OK,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Kalender nicht gelesen: ${e.javaClass.simpleName}")
        }
    }

    private fun loadStream(app: Context, now: Long) {
        WidgetCache.put(app, WidgetCache.STREAM_TRIED to now)
        // Öffentliche Seite: bewusst OHNE Token (Mike-Entscheidung, kein Server-Umbau)
        val res = get(SCHEDULE_URL, null) ?: return
        if (res.first != 200) return
        val body = res.second ?: return
        if (StreamWidgetLogic.parse(body) == null) return // kaputt → letzter Cache bleibt
        WidgetCache.put(app, WidgetCache.STREAM to body, WidgetCache.STREAM_AT to now)
    }

    // ── Kalender-Beobachter ─────────────────────────────────────────────────────

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var appRef: Context? = null
    private val calendarChanged = Runnable { appRef?.let { request(it) } }

    /**
     * Beobachtet Kalenderänderungen, solange der Prozess lebt (der Push-Dienst hält ihn meist am Leben). Nur wenn ein
     * Kalender-Widget liegt und das Leserecht da ist. Änderungen werden 3 s gebündelt.
     */
    fun ensureCalendarObserver(ctx: Context) {
        val app = ctx.applicationContext
        if (observerRegistered.get() || !hasCalendarWidgets(app) || !canReadCalendar(app)) return
        if (!observerRegistered.compareAndSet(false, true)) return
        appRef = app
        try {
            app.contentResolver.registerContentObserver(
                CalendarContract.CONTENT_URI, true,
                object : ContentObserver(mainHandler) {
                    override fun onChange(selfChange: Boolean) {
                        mainHandler.removeCallbacks(calendarChanged)
                        mainHandler.postDelayed(calendarChanged, 3_000L)
                    }
                },
            )
        } catch (e: Exception) {
            observerRegistered.set(false)
            Log.w(TAG, "Kalender-Beobachter nicht registriert: ${e.javaClass.simpleName}")
        }
    }
}
