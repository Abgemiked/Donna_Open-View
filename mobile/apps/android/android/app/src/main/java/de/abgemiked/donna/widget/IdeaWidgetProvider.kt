package com.yourcompany.donna.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.yourcompany.donna.R
import com.yourcompany.donna.idea.IdeaCaptureLogic

/**
 * #233/#243: Ideen-Widget → native Erfassung mit Vorschau; Speichern legt ein Issue in der donna-inbox an.
 * #252: In allen Größen zwei runde 48-dp-Symboltasten (Tastatur = Tippen, Mikro = Donnas Diktat), die nie mitwachsen;
 * links davon je nach Breite ([WidgetSizing.ideaHeader]) nichts, die Glühbirne oder Glühbirne + Titel.
 */
class IdeaWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) = render(context, manager, appWidgetIds)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        try { render(context, manager, intArrayOf(appWidgetId)) } catch (_: Exception) {}
    }

    companion object {
        fun render(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
            val text = DonnaEntries.pending(ctx, DonnaEntries.RC_IDEA_WIDGET, DonnaEntries.ideaCapture(ctx, IdeaCaptureLogic.KANAL_WIDGET))
            val voice = DonnaEntries.pending(
                ctx, DonnaEntries.RC_IDEA_VOICE, DonnaEntries.ideaCapture(ctx, IdeaCaptureLogic.KANAL_WIDGET, voice = true),
            )
            for (id in ids) {
                manager.updateAppWidget(id, DataWidgetProvider.sized(manager, id) { w, _ ->
                    val header = WidgetSizing.ideaHeader(w)
                    RemoteViews(ctx.packageName, R.layout.widget_idea).apply {
                        setViewVisibility(R.id.widget_idea_header, if (header == WidgetSizing.IdeaHeader.NONE) View.GONE else View.VISIBLE)
                        val showTitle = header == WidgetSizing.IdeaHeader.SHORT_TITLE || header == WidgetSizing.IdeaHeader.FULL_TITLE
                        setViewVisibility(R.id.widget_idea_title, if (showTitle) View.VISIBLE else View.GONE)
                        setTextViewText(
                            R.id.widget_idea_title,
                            ctx.getString(if (header == WidgetSizing.IdeaHeader.FULL_TITLE) R.string.widget_idea_title else R.string.widget_idea_label),
                        )
                        // Fläche außerhalb der Knöpfe = Tippen (größte, ungefährlichste Wirkung)
                        setOnClickPendingIntent(android.R.id.background, text)
                        setOnClickPendingIntent(R.id.widget_idea_text, text)
                        setOnClickPendingIntent(R.id.widget_idea_voice, voice)
                    }
                })
            }
        }
    }
}
