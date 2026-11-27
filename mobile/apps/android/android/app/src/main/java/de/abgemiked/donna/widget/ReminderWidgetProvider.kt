package com.yourcompany.donna.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.yourcompany.donna.R
import com.yourcompany.donna.reminder.ReminderLogic
import com.yourcompany.donna.reminder.ReminderReplies
import java.time.ZoneId

/**
 * #239/#243: Erinnerungen-Widget. Zeigt die nächsten offenen Erinnerungen aus dem Cache, Zeilenzahl nach Höhe
 * (bis [WidgetSizing.MAX_LIST_ROWS]); bei einer Zeile Höhe kompakt nur die nächste Erinnerung. Je Listenzeile
 * Erledigt und — nur bei fälligen — Später (10 Min). Die Knöpfe gehen an [ReminderWidgetActionReceiver] →
 * [ReminderReplies] (mit Offline-Warteschlange).
 */
class ReminderWidgetProvider : DataWidgetProvider() {
    override fun draw(context: Context, manager: AppWidgetManager, ids: IntArray) = render(context, manager, ids)

    /** Einmal je Zeichnen gelesener Stand (für alle Größen-Varianten gleich). */
    private class Snapshot(
        val now: Long,
        val zone: ZoneId,
        val state: String?,
        val loadedAt: Long,
        val items: List<WidgetReminder>,
        val answered: List<String>,
    )

    companion object {
        private const val RC_HEADER = 2391
        private const val RC_ACTION_BASE = 23900 // + Zeile * 2 + Art (bis 8 Zeilen → 23900..23915)

        fun render(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
            val s = Snapshot(
                now = System.currentTimeMillis(),
                zone = WidgetRefresher.zone,
                state = WidgetCache.str(ctx, WidgetCache.REMINDERS_STATE),
                loadedAt = WidgetCache.long(ctx, WidgetCache.REMINDERS_AT),
                items = WidgetCache.reminders(ctx),
                // Erledigt-Merkliste von ReminderReplies (gemeinsame Wahrheit mit Benachrichtigung/Popup)
                answered = ReminderLogic.answeredParse(ReminderReplies.answeredRaw(ctx)),
            )
            for (id in ids) {
                manager.updateAppWidget(id, sized(manager, id) { _, h -> build(ctx, s, h) })
            }
        }

        private fun emptyText(ctx: Context, s: Snapshot): String = when {
            s.state == WidgetCache.STATE_NO_TOKEN -> ctx.getString(R.string.widget_reminders_no_token)
            s.state == null -> ctx.getString(R.string.widget_loading)
            s.state == WidgetCache.STATE_ERROR && s.loadedAt == 0L -> ctx.getString(R.string.widget_reminders_unreachable)
            else -> ctx.getString(R.string.widget_reminders_empty)
        }

        private fun isStale(s: Snapshot) =
            s.state == WidgetCache.STATE_ERROR && WidgetFormat.isStale(s.loadedAt, s.now, 60 * 60_000L)

        private fun build(ctx: Context, s: Snapshot, heightDp: Int): RemoteViews {
            if (WidgetSizing.isCompactList(heightDp)) return buildCompact(ctx, s)
            val shown = ReminderWidgetLogic.visible(s.items, s.answered, WidgetSizing.listRows(heightDp))
            val v = RemoteViews(ctx.packageName, R.layout.widget_list)
            v.setTextViewText(R.id.widget_list_title, ctx.getString(R.string.widget_reminders_title))
            // #243: Glocke — das Warn-Dreieck sah nach Fehler aus, auch im normalen leeren Zustand
            v.setImageViewResource(R.id.widget_list_icon, R.drawable.ic_donna_bell)
            openApp(ctx, RC_HEADER)?.let { v.setOnClickPendingIntent(R.id.widget_list_header, it) }
            v.removeAllViews(R.id.widget_list_rows)
            shown.forEachIndexed { i, r ->
                val row = RemoteViews(ctx.packageName, R.layout.widget_row_reminder)
                row.setTextViewText(R.id.widget_row_time, WidgetFormat.whenLabel(r.dueMs, s.now, s.zone))
                row.setTextViewText(R.id.widget_row_text, r.text)
                row.setOnClickPendingIntent(R.id.widget_row_done, action(ctx, i, r.id, ReminderWidgetActionReceiver.KIND_DONE))
                if (ReminderWidgetLogic.canSnooze(r, s.now)) {
                    row.setViewVisibility(R.id.widget_row_snooze, View.VISIBLE)
                    row.setOnClickPendingIntent(R.id.widget_row_snooze, action(ctx, i, r.id, ReminderWidgetActionReceiver.KIND_SNOOZE))
                } else {
                    row.setViewVisibility(R.id.widget_row_snooze, View.GONE)
                }
                v.addView(R.id.widget_list_rows, row)
            }
            val empty = if (shown.isEmpty()) emptyText(ctx, s) else null
            v.setTextViewText(R.id.widget_list_empty, empty ?: "")
            v.setViewVisibility(R.id.widget_list_empty, if (empty != null) View.VISIBLE else View.GONE)
            openApp(ctx, RC_HEADER)?.let { v.setOnClickPendingIntent(R.id.widget_list_empty, it) }
            setStale(ctx, v, isStale(s), s.loadedAt, s.zone)
            return v
        }

        /** Eine Zeile Höhe: nur die nächste Erinnerung (Tipp öffnet Donna). */
        private fun buildCompact(ctx: Context, s: Snapshot): RemoteViews {
            val next = ReminderWidgetLogic.visible(s.items, s.answered, 1).firstOrNull()
            val v = RemoteViews(ctx.packageName, R.layout.widget_single)
            v.setImageViewResource(R.id.widget_single_icon, R.drawable.ic_donna_bell)
            if (next != null) {
                v.setTextViewText(R.id.widget_single_title, ctx.getString(R.string.widget_next_reminder, WidgetFormat.whenLabel(next.dueMs, s.now, s.zone)))
                v.setTextViewText(R.id.widget_single_detail, next.text)
            } else {
                v.setTextViewText(R.id.widget_single_title, ctx.getString(R.string.widget_reminders_title))
                v.setTextViewText(R.id.widget_single_detail, emptyText(ctx, s))
            }
            setSingleStale(ctx, v, isStale(s), s.loadedAt, s.zone)
            openApp(ctx, RC_HEADER)?.let { v.setOnClickPendingIntent(android.R.id.background, it) }
            return v
        }

        private fun setStale(ctx: Context, v: RemoteViews, stale: Boolean, loadedAt: Long, zone: ZoneId) {
            v.setTextViewText(R.id.widget_list_stale, staleText(ctx, stale, loadedAt, zone))
            v.setViewVisibility(R.id.widget_list_stale, if (stale) View.VISIBLE else View.GONE)
        }

        private fun setSingleStale(ctx: Context, v: RemoteViews, stale: Boolean, loadedAt: Long, zone: ZoneId) {
            v.setTextViewText(R.id.widget_single_stale, staleText(ctx, stale, loadedAt, zone))
            v.setViewVisibility(R.id.widget_single_stale, if (stale) View.VISIBLE else View.GONE)
        }

        private fun staleText(ctx: Context, stale: Boolean, loadedAt: Long, zone: ZoneId): String =
            if (!stale) "" else if (loadedAt > 0) ctx.getString(R.string.widget_stand, WidgetFormat.hm(loadedAt, zone))
            else ctx.getString(R.string.widget_offline)

        private fun action(ctx: Context, row: Int, id: String, kind: String): PendingIntent {
            val intent = Intent(ctx, ReminderWidgetActionReceiver::class.java)
                .setAction("com.yourcompany.donna.widget.REMINDER_$kind")
                .putExtra(ReminderWidgetActionReceiver.EXTRA_ID, id)
                .putExtra(ReminderWidgetActionReceiver.EXTRA_KIND, kind)
            val rc = RC_ACTION_BASE + row * 2 + if (kind == ReminderWidgetActionReceiver.KIND_DONE) 0 else 1
            return PendingIntent.getBroadcast(ctx, rc, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
