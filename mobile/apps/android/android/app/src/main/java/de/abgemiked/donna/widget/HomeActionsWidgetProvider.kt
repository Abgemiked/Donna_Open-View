package com.yourcompany.donna.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.yourcompany.donna.R

/**
 * #244: Szenen/Routinen-Widget. Bis zu 6 Knöpfe (Auswahl beim Platzieren in [HomeActionsConfigActivity], ab
 * Android 12 auch per „Widget einstellen“, sonst über das Zahnrad). Ein Tipp schaltet NIE direkt: er öffnet das
 * Bestätigungsfenster [HomeActionConfirmActivity] („X starten?“ Ja/Nein); erst „Ja“ löst aus.
 * Größen: [DataWidgetProvider.sized] (#243) — schmal 2 Spalten, sonst 3; niedrig 1 Zeile, sonst 2.
 */
class HomeActionsWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) try { render(context, manager, id) } catch (_: Exception) {}
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        try { render(context, manager, appWidgetId) } catch (_: Exception) {}
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) HomeActionsStore.clear(context, id)
    }

    companion object {
        private const val RC_BASE = 2_440_000
        private val SLOTS = intArrayOf(
            R.id.home_b0, R.id.home_b1, R.id.home_b2, R.id.home_b3, R.id.home_b4, R.id.home_b5,
        )

        fun renderAll(ctx: Context) {
            val m = AppWidgetManager.getInstance(ctx)
            val ids = try { m.getAppWidgetIds(ComponentName(ctx, HomeActionsWidgetProvider::class.java)) } catch (_: Exception) { IntArray(0) }
            for (id in ids) try { render(ctx, m, id) } catch (_: Exception) {}
        }

        fun render(ctx: Context, manager: AppWidgetManager, widgetId: Int) {
            val buttons = HomeActionsStore.load(ctx, widgetId)
            // #243-Muster: eine Variante je gemeldeter Größe (Android 12+), sonst aktuelle Größe
            manager.updateAppWidget(widgetId, DataWidgetProvider.sized(manager, widgetId) { w, h ->
                build(ctx, widgetId, buttons, HomeActionsLogic.columnsFor(w), HomeActionsLogic.rowsFor(h))
            })
        }

        private fun build(ctx: Context, widgetId: Int, buttons: List<HomeButton>, columns: Int, rows: Int): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_home_actions)
            v.setOnClickPendingIntent(R.id.home_edit, configIntent(ctx, widgetId))
            if (buttons.isEmpty()) {
                v.setViewVisibility(R.id.home_row1, View.GONE)
                v.setViewVisibility(R.id.home_row2, View.GONE)
                v.setViewVisibility(R.id.home_empty, View.VISIBLE)
                v.setOnClickPendingIntent(R.id.home_empty, configIntent(ctx, widgetId))
                return v
            }
            v.setViewVisibility(R.id.home_empty, View.GONE)
            val visible = minOf(buttons.size, columns * rows)
            v.setViewVisibility(R.id.home_row1, View.VISIBLE)
            v.setViewVisibility(R.id.home_row2, if (visible > columns) View.VISIBLE else View.GONE)
            // Zeile 1 = Plätze 0..2, Zeile 2 = 3..5; je Zeile [columns] Spalten (Knopf-Index = Zeile * Spalten + Spalte)
            for ((slot, viewId) in SLOTS.withIndex()) {
                val col = slot % 3
                val index = (slot / 3) * columns + col
                when {
                    col >= columns -> v.setViewVisibility(viewId, View.GONE)
                    // freier Platz: unsichtbar, damit die Knopfbreite gleich bleibt
                    index >= visible -> v.setViewVisibility(viewId, View.INVISIBLE)
                    else -> {
                        val b = buttons[index]
                        v.setViewVisibility(viewId, View.VISIBLE)
                        v.setTextViewText(viewId, b.name)
                        v.setContentDescription(viewId, HomeActionsLogic.question(b))
                        v.setOnClickPendingIntent(viewId, confirmIntent(ctx, widgetId, index, b))
                    }
                }
            }
            return v
        }

        private fun configIntent(ctx: Context, widgetId: Int) = DonnaEntries.pending(
            ctx, RC_BASE + widgetId % 10_000 * 10 + 9,
            Intent(ctx, HomeActionsConfigActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .setData(Uri.parse("donna-widget://home/$widgetId/config"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )

        private fun confirmIntent(ctx: Context, widgetId: Int, slot: Int, b: HomeButton) = DonnaEntries.pending(
            ctx, RC_BASE + widgetId % 10_000 * 10 + slot,
            Intent(ctx, HomeActionConfirmActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .putExtra(HomeActionConfirmActivity.EXTRA_KEY, HomeActionsLogic.key(b))
                // eigene Daten-URI je Knopf, damit sich die PendingIntents nicht überschreiben
                .setData(Uri.parse("donna-widget://home/$widgetId/$slot"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** #244: Auswahl je Widget (app-private Prefs). */
object HomeActionsStore {
    private const val PREFS = "donna_widgets_w3"
    private fun key(widgetId: Int) = "home_buttons_$widgetId"

    fun load(ctx: Context, widgetId: Int): List<HomeButton> =
        HomeActionsLogic.fromJson(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(widgetId), null))

    fun save(ctx: Context, widgetId: Int, list: List<HomeButton>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key(widgetId), HomeActionsLogic.toJson(list)).apply()
    }

    fun clear(ctx: Context, widgetId: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(key(widgetId)).apply()
    }
}
