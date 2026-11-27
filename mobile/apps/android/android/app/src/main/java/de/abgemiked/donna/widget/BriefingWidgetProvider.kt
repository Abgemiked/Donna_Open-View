package com.yourcompany.donna.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import com.yourcompany.donna.BuildConfig
import com.yourcompany.donna.R
import com.yourcompany.donna.TokenStore

/**
 * #246: Tagesbriefing-Kachel (3x1, bis 4x2). Daten aus `GET /briefing` über den gemeinsamen [WidgetCache]; der
 * Abruf läuft im [WidgetRefresher]-Lauf (Anstoß über [WidgetsW3.onRefresh]). Tipp öffnet Donna mit der
 * Briefing-Frage ([DonnaEntries.briefing]). Größen über [DataWidgetProvider.sized] (#243): schmal nur die Hauptzeile.
 */
class BriefingWidgetProvider : DataWidgetProvider() {
    override fun draw(context: Context, manager: AppWidgetManager, ids: IntArray) = render(context, manager, ids)

    companion object {
        private const val RC_OPEN = 2461
        const val CACHE = "w3_briefing"
        const val CACHE_AT = "w3_briefing_at"
        const val CACHE_TRIED = "w3_briefing_tried"
        const val CACHE_STATE = "w3_briefing_state"

        fun ids(ctx: Context): IntArray = try {
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, BriefingWidgetProvider::class.java))
        } catch (_: Exception) { IntArray(0) }

        /** Im Hintergrund-Thread des Refreshers: nur wenn eine Kachel liegt und der Abruf fällig ist. */
        fun loadIfDue(app: Context, now: Long) {
            if (ids(app).isEmpty()) return
            if (!BriefingWidgetLogic.due(now, WidgetCache.long(app, CACHE_AT), WidgetCache.long(app, CACHE_TRIED))) return
            WidgetCache.put(app, CACHE_TRIED to now)
            val token = try { TokenStore.getToken(app) } catch (_: Exception) { null }
            if (token.isNullOrEmpty()) {
                WidgetCache.put(app, CACHE_STATE to WidgetCache.STATE_NO_TOKEN, CACHE to null)
                return
            }
            val r = WidgetHttp.get("${BuildConfig.DONNA_API_URL}/briefing", token)
            val view = if (r.ok) BriefingWidgetLogic.parse(r.body) else null
            if (view == null) {
                WidgetCache.put(app, CACHE_STATE to WidgetCache.STATE_ERROR) // letzter Stand bleibt stehen
                return
            }
            WidgetCache.put(app, CACHE to BriefingWidgetLogic.toCache(view), CACHE_AT to now, CACHE_STATE to WidgetCache.STATE_OK)
        }

        fun renderAll(ctx: Context) {
            val ids = ids(ctx)
            if (ids.isNotEmpty()) render(ctx, AppWidgetManager.getInstance(ctx), ids)
        }

        fun render(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
            val now = System.currentTimeMillis()
            val view = BriefingWidgetLogic.fromCache(WidgetCache.str(ctx, CACHE))
            val state = WidgetCache.str(ctx, CACHE_STATE)
            val loadedAt = WidgetCache.long(ctx, CACHE_AT)
            val headline = when {
                state == WidgetCache.STATE_NO_TOKEN -> ctx.getString(R.string.widget_briefing_no_token)
                view != null -> view.headline
                else -> ctx.getString(R.string.widget_loading)
            }
            val detail = if (state == WidgetCache.STATE_NO_TOKEN) "" else view?.detail.orEmpty()
            // älter als 6 Stunden → Stand zeigen
            val stale = view != null && WidgetFormat.isStale(loadedAt, now, 6 * 60 * 60_000L)
            val staleText = if (stale) ctx.getString(R.string.widget_stand, WidgetFormat.whenLabel(loadedAt, now, WidgetRefresher.zone)) else ""
            fun build(compact: Boolean) = RemoteViews(ctx.packageName, R.layout.widget_briefing).apply {
                setTextViewText(R.id.widget_briefing_headline, headline)
                setTextViewText(R.id.widget_briefing_detail, detail)
                setViewVisibility(R.id.widget_briefing_detail, if (compact || detail.isEmpty()) View.GONE else View.VISIBLE)
                setTextViewText(R.id.widget_briefing_stale, staleText)
                setViewVisibility(R.id.widget_briefing_stale, if (stale && !compact) View.VISIBLE else View.GONE)
                setOnClickPendingIntent(android.R.id.background, DonnaEntries.pending(ctx, RC_OPEN, DonnaEntries.briefing(ctx)))
            }
            // #243-Muster: schmal nur die Hauptzeile
            for (id in ids) manager.updateAppWidget(id, sized(manager, id) { w, _ -> build(BriefingWidgetLogic.compact(w)) })
        }
    }
}
