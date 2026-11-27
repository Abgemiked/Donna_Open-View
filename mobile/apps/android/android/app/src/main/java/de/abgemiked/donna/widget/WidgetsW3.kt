package com.yourcompany.donna.widget

import android.content.Context
import android.util.Log

/**
 * Welle 3 (#237): EIN Anknüpfungspunkt im [WidgetRefresher]-Lauf (Hintergrund-Thread), damit die W2-Dateien nur
 * eine Zeile ändern: Briefing laden (gedrosselt) und die lokalen W3-Widgets aus Cache/Systemzustand neu zeichnen.
 */
object WidgetsW3 {
    fun onRefresh(app: Context, now: Long) {
        try { BriefingWidgetProvider.loadIfDue(app, now) } catch (e: Exception) { Log.w("WidgetsW3", "Briefing: ${e.javaClass.simpleName}") }
        try { BriefingWidgetProvider.renderAll(app) } catch (_: Exception) {}
        try { FocusWidgetProvider.renderAll(app) } catch (_: Exception) {}
        try { TimerWidgetProvider.renderAll(app) } catch (_: Exception) {}
    }
}
