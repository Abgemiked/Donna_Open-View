package com.yourcompany.donna.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import com.yourcompany.donna.R
import java.time.ZonedDateTime

/**
 * #241/#243: Stream-Widget. Klein (eine Zeile Höhe): am Streamtag Uhrzeit + Programm, sonst „Nächster Stream: …“.
 * Größer: Liste der kommenden Programmpunkte der Woche (laufender zuerst, Ausfall-Tage als eigene Zeile).
 * Quelle ist die öffentliche Seite your-donna-instance.example.com/api/schedule (Cache; bei Fehler bleibt der letzte Stand). Die Anzeige
 * wird beim Zeichnen aus dem Cache für die aktuelle Uhrzeit berechnet. Tipp öffnet Donna.
 */
class StreamWidgetProvider : DataWidgetProvider() {
    override fun draw(context: Context, manager: AppWidgetManager, ids: IntArray) = render(context, manager, ids)

    companion object {
        private const val RC_OPEN = 2411
        private const val STALE_MS = 24 * 60 * 60_000L

        fun render(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
            val now = System.currentTimeMillis()
            val plan = StreamWidgetLogic.parse(WidgetCache.str(ctx, WidgetCache.STREAM))
            val at = ZonedDateTime.now(StreamWidgetLogic.ZONE)
            val loadedAt = WidgetCache.long(ctx, WidgetCache.STREAM_AT)
            // älter als ein Tag → Stand anzeigen, damit ein veralteter Plan als solcher erkennbar ist
            val stale = plan != null && WidgetFormat.isStale(loadedAt, now, STALE_MS)
            val staleText = if (stale) ctx.getString(R.string.widget_stand, WidgetFormat.whenLabel(loadedAt, now, WidgetRefresher.zone)) else ""
            for (id in ids) {
                manager.updateAppWidget(id, sized(manager, id) { _, h ->
                    if (WidgetSizing.isCompactList(h)) buildCompact(ctx, plan, at, staleText)
                    else buildList(ctx, plan, at, staleText, WidgetSizing.listRows(h))
                })
            }
        }

        private fun buildCompact(ctx: Context, plan: StreamPlan?, at: ZonedDateTime, staleText: String): RemoteViews {
            val view = StreamWidgetLogic.view(plan, at)
            return RemoteViews(ctx.packageName, R.layout.widget_single).apply {
                setImageViewResource(R.id.widget_single_icon, R.drawable.ic_donna_wave)
                setTextViewText(R.id.widget_single_title, view.title)
                setTextViewText(R.id.widget_single_detail, view.detail)
                setTextViewText(R.id.widget_single_stale, staleText)
                setViewVisibility(R.id.widget_single_stale, if (staleText.isNotEmpty()) View.VISIBLE else View.GONE)
                openApp(ctx, RC_OPEN)?.let { setOnClickPendingIntent(android.R.id.background, it) }
            }
        }

        private fun buildList(ctx: Context, plan: StreamPlan?, at: ZonedDateTime, staleText: String, rows: Int): RemoteViews {
            val items = StreamWidgetLogic.upcoming(plan, at, rows)
            val v = RemoteViews(ctx.packageName, R.layout.widget_list)
            v.setTextViewText(R.id.widget_list_title, ctx.getString(R.string.widget_stream_title))
            v.setImageViewResource(R.id.widget_list_icon, R.drawable.ic_donna_wave)
            v.removeAllViews(R.id.widget_list_rows)
            for (r in items) {
                val row = RemoteViews(ctx.packageName, R.layout.widget_row_event)
                row.setTextViewText(R.id.widget_row_time, r.label)
                row.setTextViewText(R.id.widget_row_text, r.text)
                v.addView(R.id.widget_list_rows, row)
            }
            // keine Zeilen (Plan fehlt/veraltet, Woche vorbei) → dieselbe Aussage wie die Kompaktansicht
            val empty = if (items.isEmpty()) StreamWidgetLogic.view(plan, at).detail else null
            v.setTextViewText(R.id.widget_list_empty, empty ?: "")
            v.setViewVisibility(R.id.widget_list_empty, if (empty != null) View.VISIBLE else View.GONE)
            v.setTextViewText(R.id.widget_list_stale, staleText)
            v.setViewVisibility(R.id.widget_list_stale, if (staleText.isNotEmpty()) View.VISIBLE else View.GONE)
            openApp(ctx, RC_OPEN)?.let { v.setOnClickPendingIntent(android.R.id.background, it) }
            return v
        }
    }
}
