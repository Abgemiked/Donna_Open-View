package com.yourcompany.donna.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yourcompany.donna.reminder.ReminderLogic
import com.yourcompany.donna.reminder.ReminderReplies
import com.yourcompany.donna.reminder.Reply
import com.yourcompany.donna.reminder.ReplyKind

/**
 * #239: Knöpfe Erledigt / Später des Erinnerungen-Widgets. Nicht exportiert (nur der eigene PendingIntent).
 * Zeigt das Ergebnis sofort im Widget und sendet über [ReminderReplies] (gemerkt + Nachsenden bei Netzfehler,
 * gleiche Rückmeldung wie die Benachrichtigungs-Tasten). Danach holt der [WidgetRefresher] den Server-Stand.
 */
class ReminderWidgetActionReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_ID = "widget_reminder_id"
        const val EXTRA_KIND = "widget_reminder_kind"
        const val KIND_DONE = "done"
        const val KIND_SNOOZE = "snooze"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID)?.takeIf { it.toLongOrNull()?.let { n -> n > 0 } == true } ?: return
        val kind = ReplyKind.fromKey(intent.getStringExtra(EXTRA_KIND)) ?: return
        val app = context.applicationContext
        val minutes = if (kind == ReplyKind.SNOOZE) ReminderLogic.snoozeMinutes(null) else null
        val now = System.currentTimeMillis()
        if (kind == ReplyKind.SNOOZE) {
            // Schutz gegen veraltete Knöpfe: nie eine noch nicht fällige Erinnerung vorziehen
            val item = WidgetCache.reminders(app).firstOrNull { it.id == id } ?: return
            if (!ReminderWidgetLogic.canSnooze(item, now)) return
        }
        WidgetCache.updateReminders(app) {
            if (kind == ReplyKind.DONE) ReminderWidgetLogic.without(it, id) else ReminderWidgetLogic.snoozed(it, id, minutes!!, now)
        }
        WidgetRefresher.renderAll(app)

        val pending = goAsync()
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val finish = { if (finished.compareAndSet(false, true)) try { pending.finish() } catch (_: Exception) {} }
        try {
            ReminderReplies.submit(app, Reply(kind, id, minutes), ReminderLogic.notificationId(id)) {
                // Server-Stand holen (bei Fehlschlag zeigt das Widget wieder den echten Stand)
                try { WidgetRefresher.request(app, forceReminderFetch = true) { finish() } } catch (_: Exception) { finish() }
            }
        } catch (_: Exception) {
            finish()
        }
    }
}
