package com.yourcompany.donna.widget

import android.content.Context

/**
 * Welle 2 (#236): lokaler Cache der Daten-Widgets (app-private SharedPreferences). Die Widgets lesen NUR hier;
 * geschrieben wird vom [WidgetRefresher] und von den Widget-Knöpfen (sofortige Anzeige). Keine Gesundheitsdaten.
 */
object WidgetCache {
    private const val PREFS = "donna_widgets"
    const val REMINDERS = "reminders"
    const val REMINDERS_AT = "reminders_at"
    const val REMINDERS_TRIED = "reminders_tried"
    const val REMINDERS_STATE = "reminders_state"
    const val REMINDERS_DELIVERED = "reminders_delivered"
    const val CALENDAR = "calendar"
    const val CALENDAR_STATE = "calendar_state"
    const val STREAM = "stream"
    const val STREAM_AT = "stream_at"
    const val STREAM_TRIED = "stream_tried"

    const val STATE_OK = "ok"
    const val STATE_NO_TOKEN = "no_token"
    const val STATE_ERROR = "error"
    const val STATE_NO_PERMISSION = "no_permission"

    private val lock = Any()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun str(ctx: Context, key: String): String? = prefs(ctx).getString(key, null)
    fun long(ctx: Context, key: String): Long = prefs(ctx).getLong(key, 0L)

    fun put(ctx: Context, vararg pairs: Pair<String, Any?>) {
        synchronized(lock) {
            val e = prefs(ctx).edit()
            for ((k, v) in pairs) {
                when (v) {
                    null -> e.remove(k)
                    is Long -> e.putLong(k, v)
                    is String -> e.putString(k, v)
                    else -> e.putString(k, v.toString())
                }
            }
            e.apply()
        }
    }

    fun reminders(ctx: Context): List<WidgetReminder> = ReminderWidgetLogic.fromCache(str(ctx, REMINDERS))

    /** Liest-ändert-schreibt die Erinnerungsliste unter Sperre (Knopf und Abruf können gleichzeitig laufen). */
    fun updateReminders(ctx: Context, change: (List<WidgetReminder>) -> List<WidgetReminder>) {
        synchronized(lock) {
            val p = prefs(ctx)
            val next = change(ReminderWidgetLogic.fromCache(p.getString(REMINDERS, null)))
            p.edit().putString(REMINDERS, ReminderWidgetLogic.toCache(next)).apply()
        }
    }
}
