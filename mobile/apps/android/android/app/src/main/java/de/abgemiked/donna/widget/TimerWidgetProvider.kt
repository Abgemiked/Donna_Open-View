package com.yourcompany.donna.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.AlarmClock
import android.view.View
import android.widget.RemoteViews
import com.yourcompany.donna.R

/**
 * #247: Timer-Widget (3x1, bis 4x2). Zeigt die Timer, die Donna gestellt hat, als Countdown
 * ([RemoteViews.setChronometerCountDown]); der Launcher zählt selbst, ohne Neuzeichnen je Sekunde. Zum Ablauf
 * des nächsten Timers zeichnet ein AlarmManager-Wecker neu und blendet ihn aus (ohne Exakt-Recht kann das
 * einige Minuten später sein; bis dahin zeigt die Uhr kurz negative Zeit).
 * GRENZE (siehe [TimerWidgetLogic]): Abbruch/Änderung in der Samsung-Uhr wird NICHT erkannt.
 * Tipp öffnet die Timer der Uhr-App.
 */
class TimerWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        try { renderAll(context) } catch (_: Exception) {}
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        try { renderAll(context) } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "TimerWidget"
        private const val RC_OPEN = 2471
        private const val RC_EXPIRED = 2472
        private const val CACHE = "w3_timers"
        private val lock = Any()
        private val ROWS = intArrayOf(R.id.timer_row0, R.id.timer_row1, R.id.timer_row2)
        private val LABELS = intArrayOf(R.id.timer_label0, R.id.timer_label1, R.id.timer_label2)
        private val CLOCKS = intArrayOf(R.id.timer_clock0, R.id.timer_clock1, R.id.timer_clock2)

        /** Aufruf, sobald Donna einen Timer an die Uhr-App übergeben hat (Geräte-Aktion oder RN-Modul). */
        fun onTimerSet(ctx: Context, minutes: Int, label: String) {
            try {
                val app = ctx.applicationContext
                val now = System.currentTimeMillis()
                synchronized(lock) {
                    val next = TimerWidgetLogic.add(TimerWidgetLogic.fromJson(WidgetCache.str(app, CACHE)), now, minutes, label)
                    WidgetCache.put(app, CACHE to TimerWidgetLogic.toJson(next))
                }
                renderAll(app)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Timer nicht gemerkt: ${e.javaClass.simpleName}")
            }
        }

        private fun ids(ctx: Context): IntArray = try {
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, TimerWidgetProvider::class.java))
        } catch (_: Exception) { IntArray(0) }

        fun renderAll(ctx: Context) {
            val app = ctx.applicationContext
            val now = System.currentTimeMillis()
            val active = synchronized(lock) {
                val all = TimerWidgetLogic.fromJson(WidgetCache.str(app, CACHE))
                TimerWidgetLogic.active(all, now).also { if (it.size != all.size) WidgetCache.put(app, CACHE to TimerWidgetLogic.toJson(it)) }
            }
            scheduleNext(app, TimerWidgetLogic.next(active, now))
            val ids = ids(app)
            if (ids.isEmpty()) return
            val m = AppWidgetManager.getInstance(app)
            // #243-Muster: Zeilenzahl folgt der Höhe, je Widget
            for (id in ids) {
                m.updateAppWidget(id, DataWidgetProvider.sized(m, id) { _, h -> build(app, active, now, TimerWidgetLogic.rowsFor(h)) })
            }
        }

        private fun build(ctx: Context, active: List<WidgetTimer>, now: Long, rows: Int): RemoteViews =
            RemoteViews(ctx.packageName, R.layout.widget_timer).apply {
                setViewVisibility(R.id.timer_empty, if (active.isEmpty()) View.VISIBLE else View.GONE)
                val elapsedNow = SystemClock.elapsedRealtime()
                for (i in ROWS.indices) {
                    val t = active.getOrNull(i)
                    if (t == null || i >= rows) {
                        setViewVisibility(ROWS[i], View.GONE)
                        continue
                    }
                    setViewVisibility(ROWS[i], View.VISIBLE)
                    setTextViewText(LABELS[i], t.label.ifEmpty { ctx.getString(R.string.widget_timer_unnamed) })
                    // Basis in elapsedRealtime: Countdown endet genau zur Ablaufzeit
                    setChronometer(CLOCKS[i], elapsedNow + (t.endsAtMs - now), null, true)
                    setChronometerCountDown(CLOCKS[i], true)
                }
                val open = Intent(AlarmClock.ACTION_SHOW_TIMERS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setOnClickPendingIntent(android.R.id.background, DonnaEntries.pending(ctx, RC_OPEN, open))
            }

        /** Neuzeichnen zum nächsten Ablauf (+1 s); ohne laufenden Timer wird der Wecker entfernt. */
        private fun scheduleNext(app: Context, atMs: Long?) {
            val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = PendingIntent.getBroadcast(
                app, RC_EXPIRED, Intent(app, WidgetW3ActionReceiver::class.java).setAction(WidgetW3ActionReceiver.ACTION_TIMER_EXPIRED),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            try {
                if (atMs == null || ids(app).isEmpty()) {
                    am.cancel(pi)
                    return
                }
                val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
                if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC, atMs + 1_000L, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC, atMs + 1_000L, pi)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Neuzeichnen nicht geplant: ${e.javaClass.simpleName}")
            }
        }
    }
}
