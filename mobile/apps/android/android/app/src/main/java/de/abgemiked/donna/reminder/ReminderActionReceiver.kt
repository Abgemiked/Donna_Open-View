package com.yourcompany.donna.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * #153: Tasten "Erledigt" / "Spaeter" der Erinnerungs-Benachrichtigung. Nicht exportiert (nur der eigene
 * PendingIntent erreicht ihn). Der Versand laeuft ueber [ReminderReplies] (bei Fehler: Benachrichtigung bleibt stehen).
 */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(ReminderNotifier.EXTRA_ID) ?: return
        val kind = ReplyKind.fromKey(intent.getStringExtra(ReminderNotifier.EXTRA_KIND)) ?: return
        val notifId = intent.getIntExtra(ReminderNotifier.EXTRA_NOTIF_ID, ReminderLogic.notificationId(id))
        val minutes = if (kind == ReplyKind.SNOOZE) {
            ReminderLogic.snoozeMinutes(
                if (intent.hasExtra(ReminderNotifier.EXTRA_MINUTES)) intent.getIntExtra(ReminderNotifier.EXTRA_MINUTES, 0) else null,
            )
        } else null
        val pending = goAsync()
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val finish = { if (finished.compareAndSet(false, true)) try { pending.finish() } catch (_: Exception) {} }
        try {
            // Netzzeit (je 4 s Verbindung/Lesen) liegt unter der Broadcast-Grenze; die Antwort ist vorher gemerkt
            ReminderReplies.submit(context, Reply(kind, id, minutes), notifId) { finish() }
        } catch (_: Exception) {
            finish()
        }
    }
}
