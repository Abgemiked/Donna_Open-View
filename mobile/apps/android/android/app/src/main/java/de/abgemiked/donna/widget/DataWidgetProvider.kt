package com.yourcompany.donna.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews

/**
 * Welle 2 (#236): Basis der Daten-Widgets. Zeichnet sofort aus dem Cache und stößt dann den [WidgetRefresher] an
 * (goAsync: der Broadcast lebt bis der Abruf fertig ist). Größenwechsel zeichnet neu (Zeilenzahl folgt der Höhe).
 */
abstract class DataWidgetProvider : AppWidgetProvider() {
    abstract fun draw(context: Context, manager: AppWidgetManager, ids: IntArray)

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        try { draw(context, manager, appWidgetIds) } catch (_: Exception) {}
        val pending = goAsync()
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            WidgetRefresher.request(context) { if (finished.compareAndSet(false, true)) try { pending.finish() } catch (_: Exception) {} }
        } catch (_: Exception) {
            if (finished.compareAndSet(false, true)) try { pending.finish() } catch (_: Exception) {}
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        try { draw(context, manager, intArrayOf(appWidgetId)) } catch (_: Exception) {}
    }

    companion object {
        /** Höchstzahl der Größen-Varianten (Grenze von `RemoteViews(Map)`). */
        private const val MAX_SIZES = 16

        /**
         * #243: Responsive Darstellung. Android 12+: eine Variante je vom Launcher gemeldeter Größe
         * (`RemoteViews(Map<SizeF, RemoteViews>)`, das System wählt beim Verkleinern/Vergrößern selbst).
         * Älter bzw. ohne Größenliste: eine Variante für die aktuelle Größe (Hochformat: min. Breite, max. Höhe);
         * `onAppWidgetOptionsChanged` zeichnet bei Größenwechsel neu. [build] bekommt Breite und Höhe in dp (0 = unbekannt).
         */
        fun sized(manager: AppWidgetManager, id: Int, build: (widthDp: Int, heightDp: Int) -> RemoteViews): RemoteViews {
            val options = try { manager.getAppWidgetOptions(id) } catch (_: Exception) { null }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && options != null) {
                val sizes = try {
                    @Suppress("DEPRECATION")
                    options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                } catch (_: Exception) { null }
                val distinct = sizes.orEmpty().filter { it.width > 0f && it.height > 0f }.distinct().take(MAX_SIZES)
                if (distinct.isNotEmpty()) {
                    return RemoteViews(distinct.associateWith { build(it.width.toInt(), it.height.toInt()) })
                }
            }
            val w = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
            val h = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) ?: 0
            return build(w, h)
        }

        /** Tipp auf die Kopfzeile: Donna-App öffnen. */
        fun openApp(ctx: Context, requestCode: Int): PendingIntent? {
            val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return null
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return DonnaEntries.pending(ctx, requestCode, launch)
        }
    }
}
