package com.yourcompany.donna.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.yourcompany.donna.R

/**
 * #232: Donna-Suchleiste (4x1) auf dem Homescreen. Klassisches AppWidget (RemoteViews), kein Hintergrund-Update.
 *  - Tipp aufs Feld → Donna-Overlay im Textmodus (Tastatur offen, Mikro aus).
 *  - Mikro-Symbol → Overlay/Live-Bubble wie die Funktionstaste (Antwort im Overlay).
 */
class SearchBarWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_search_bar).apply {
            val field = DonnaEntries.pending(context, DonnaEntries.RC_SEARCH_FIELD, DonnaEntries.textInput(context))
            setOnClickPendingIntent(R.id.widget_search_field, field)
            setOnClickPendingIntent(R.id.widget_search_logo, field)
            // Rand der Pille (Innenabstand) ebenfalls tippbar; das Mikro behaelt seinen eigenen Einstieg
            setOnClickPendingIntent(android.R.id.background, field)
            setOnClickPendingIntent(
                R.id.widget_search_mic,
                DonnaEntries.pending(context, DonnaEntries.RC_SEARCH_MIC, DonnaEntries.overlay(context)),
            )
        }
        for (id in appWidgetIds) manager.updateAppWidget(id, views)
    }
}
