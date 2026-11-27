package com.yourcompany.donna.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.view.View
import android.widget.RemoteViews
import com.yourcompany.donna.R
import java.time.ZoneId

/**
 * #240/#243: Kalender-Widget. Nächste Termine heute/morgen aus dem Handy-Kalender (Uhrzeit + Titel, Mike-Entscheidung),
 * Zeilenzahl nach Höhe (bis [WidgetSizing.MAX_LIST_ROWS]); bei einer Zeile Höhe kompakt nur der nächste Termin.
 * Tipp auf einen Termin öffnet ihn im Kalender, Tipp auf die Kopfzeile den Kalender bei heute. Ohne Leserecht: Hinweis,
 * Tipp öffnet Donna (dort das Kalenderrecht erteilen).
 */
class CalendarWidgetProvider : DataWidgetProvider() {
    override fun draw(context: Context, manager: AppWidgetManager, ids: IntArray) = render(context, manager, ids)

    private class Snapshot(val now: Long, val zone: ZoneId, val permitted: Boolean, val state: String?, val events: List<WidgetEvent>)

    companion object {
        private const val RC_HEADER = 2401
        private const val RC_ROW_BASE = 24010 // + Zeile (bis 8 Zeilen)

        fun render(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
            val permitted = WidgetRefresher.canReadCalendar(ctx)
            val s = Snapshot(
                now = System.currentTimeMillis(),
                zone = WidgetRefresher.zone,
                permitted = permitted,
                state = WidgetCache.str(ctx, WidgetCache.CALENDAR_STATE),
                events = if (permitted) CalendarWidgetLogic.fromCache(WidgetCache.str(ctx, WidgetCache.CALENDAR)) else emptyList(),
            )
            for (id in ids) {
                manager.updateAppWidget(id, sized(manager, id) { _, h -> build(ctx, s, h) })
            }
        }

        private fun emptyText(ctx: Context, s: Snapshot): String = when {
            !s.permitted -> ctx.getString(R.string.widget_calendar_no_permission)
            s.state == null -> ctx.getString(R.string.widget_loading)
            else -> ctx.getString(R.string.widget_calendar_empty)
        }

        private fun build(ctx: Context, s: Snapshot, heightDp: Int): RemoteViews {
            if (WidgetSizing.isCompactList(heightDp)) return buildCompact(ctx, s)
            val shown = CalendarWidgetLogic.select(s.events, s.now, s.zone, WidgetSizing.listRows(heightDp))
            val v = RemoteViews(ctx.packageName, R.layout.widget_list)
            v.setTextViewText(R.id.widget_list_title, ctx.getString(R.string.widget_calendar_title))
            v.setImageViewResource(R.id.widget_list_icon, R.drawable.ic_donna_calendar)
            val header = if (s.permitted) openCalendar(ctx, s.now) else openApp(ctx, RC_HEADER)
            header?.let { v.setOnClickPendingIntent(R.id.widget_list_header, it) }
            v.removeAllViews(R.id.widget_list_rows)
            shown.forEachIndexed { i, r ->
                val row = RemoteViews(ctx.packageName, R.layout.widget_row_event)
                row.setTextViewText(R.id.widget_row_time, r.label)
                row.setTextViewText(R.id.widget_row_text, r.title)
                row.setTextViewText(R.id.widget_row_tag, r.event.tag)
                row.setViewVisibility(R.id.widget_row_tag, if (r.event.tag.isNotEmpty()) View.VISIBLE else View.GONE)
                row.setOnClickPendingIntent(R.id.widget_row_root, openEvent(ctx, i, r.event))
                v.addView(R.id.widget_list_rows, row)
            }
            val empty = if (shown.isEmpty()) emptyText(ctx, s) else null
            v.setTextViewText(R.id.widget_list_empty, empty ?: "")
            v.setViewVisibility(R.id.widget_list_empty, if (empty != null) View.VISIBLE else View.GONE)
            if (!s.permitted) openApp(ctx, RC_HEADER)?.let { v.setOnClickPendingIntent(R.id.widget_list_empty, it) }
            v.setViewVisibility(R.id.widget_list_stale, View.GONE)
            return v
        }

        /** Eine Zeile Höhe: nur der nächste Termin (Tipp öffnet ihn; sonst Kalender bzw. Donna). */
        private fun buildCompact(ctx: Context, s: Snapshot): RemoteViews {
            val next = CalendarWidgetLogic.select(s.events, s.now, s.zone, 1).firstOrNull()
            val v = RemoteViews(ctx.packageName, R.layout.widget_single)
            v.setImageViewResource(R.id.widget_single_icon, R.drawable.ic_donna_calendar)
            // #243: rechts neben der Überschrift der Personen-Tag (Kalender hat keinen „Stand“-Hinweis)
            val tag = next?.event?.tag.orEmpty()
            v.setTextViewText(R.id.widget_single_stale, tag)
            v.setViewVisibility(R.id.widget_single_stale, if (tag.isNotEmpty()) View.VISIBLE else View.GONE)
            val click = if (next != null) {
                v.setTextViewText(R.id.widget_single_title, ctx.getString(R.string.widget_next_event, next.label))
                v.setTextViewText(R.id.widget_single_detail, next.title)
                openEvent(ctx, 0, next.event)
            } else {
                v.setTextViewText(R.id.widget_single_title, ctx.getString(R.string.widget_calendar_title))
                v.setTextViewText(R.id.widget_single_detail, emptyText(ctx, s))
                if (s.permitted) openCalendar(ctx, s.now) else openApp(ctx, RC_HEADER)
            }
            click?.let { v.setOnClickPendingIntent(android.R.id.background, it) }
            return v
        }

        private fun openEvent(ctx: Context, row: Int, e: WidgetEvent): PendingIntent {
            val intent = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.eventId))
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.beginMs)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.endMs)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return DonnaEntries.pending(ctx, RC_ROW_BASE + row, intent)
        }

        private fun openCalendar(ctx: Context, now: Long): PendingIntent {
            val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").also { ContentUris.appendId(it, now) }.build()
            val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return DonnaEntries.pending(ctx, RC_HEADER, intent)
        }
    }
}
