package com.yourcompany.donna.reminder

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.yourcompany.donna.NotificationIcons
import com.yourcompany.donna.R
import com.yourcompany.donna.TtsEngines

/**
 * #153: Zeigt eine Erinnerung als Benachrichtigung im eigenen Kanal (hohe Wichtigkeit) mit den Tasten "Erledigt" und
 * "Spaeter". Je nach [ReminderLogic.presentation] kommt dazu eine Vollbild-Absicht auf [ReminderPopupActivity]
 * (nur bei gesperrtem Geraet und vorhandener Sonderberechtigung). Die Benachrichtigung bleibt stehen, bis sie beantwortet ist.
 */
object ReminderNotifier {
    const val EXTRA_ID = "reminder_id"
    const val EXTRA_TEXT = "reminder_text"
    const val EXTRA_NOTIF_ID = "reminder_notif_id"
    const val EXTRA_KIND = "reminder_kind"
    const val EXTRA_MINUTES = "reminder_minutes"
    const val ACTION_REPLY = "com.yourcompany.donna.REMINDER_REPLY"

    private val anonymousCounter = java.util.concurrent.atomic.AtomicInteger(0)

    private fun nm(ctx: Context) = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun dndBypassEnabled(ctx: Context): Boolean =
        try {
            ctx.getSharedPreferences(TtsEngines.PREFS, Context.MODE_PRIVATE)
                .getBoolean(ReminderLogic.PREF_DND_BYPASS, ReminderLogic.DND_BYPASS_DEFAULT)
        } catch (_: Exception) {
            ReminderLogic.DND_BYPASS_DEFAULT
        }

    /** Der Kanal "Erinnerungen" immer; der zweite Kanal (durchbricht Nicht stoeren) nur, wenn der Schalter an ist. */
    fun ensureChannels(ctx: Context) {
        val m = nm(ctx)
        m.createNotificationChannel(
            NotificationChannel(ReminderLogic.CHANNEL_ID, "Erinnerungen", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Erinnerungen von Donna mit Erledigt und Später"
                // bewusst oeffentlich (siehe show())
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            },
        )
        if (dndBypassEnabled(ctx)) {
            m.createNotificationChannel(
                NotificationChannel(
                    ReminderLogic.CHANNEL_DND_ID,
                    "Erinnerungen (auch bei Nicht stören)",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Erinnerungen, die „Nicht stören“ durchbrechen dürfen, sobald du es in den Systemeinstellungen erlaubst"
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    setBypassDnd(true)
                },
            )
        }
    }

    /** Schalter aus: den Zusatzkanal wieder entfernen (er bliebe sonst in den Systemeinstellungen stehen). */
    fun removeDndChannel(ctx: Context) {
        try { nm(ctx).deleteNotificationChannel(ReminderLogic.CHANNEL_DND_ID) } catch (_: Exception) {}
    }

    /** Android 14+: USE_FULL_SCREEN_INTENT ist eine Sonderberechtigung, die die Nutzerin erteilen muss. */
    fun canUseFullScreen(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) nm(ctx).canUseFullScreenIntent() else true

    private fun headsUpAllowed(ctx: Context, channelId: String): Boolean {
        val m = nm(ctx)
        if (!m.areNotificationsEnabled()) return false
        val ch = m.getNotificationChannel(channelId) ?: return true
        return ch.importance >= NotificationManager.IMPORTANCE_HIGH
    }

    fun cancel(ctx: Context, notifId: Int) {
        try { nm(ctx).cancel(notifId) } catch (_: Exception) {}
    }

    private fun replyIntent(ctx: Context, id: String, notifId: Int, kind: ReplyKind, minutes: Int?): PendingIntent {
        val i = Intent(ctx, ReminderActionReceiver::class.java).apply {
            action = ACTION_REPLY
            // eigene Data-Adresse je Erinnerung und Taste, damit sich die PendingIntents nicht ueberschreiben
            data = Uri.parse("donna-reminder://$id/${kind.key}")
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_NOTIF_ID, notifId)
            putExtra(EXTRA_KIND, kind.key)
            if (minutes != null) putExtra(EXTRA_MINUTES, minutes)
        }
        return PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun popupIntent(ctx: Context, r: Reminder, notifId: Int): PendingIntent {
        val i = Intent(ctx, ReminderPopupActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data = Uri.parse("donna-reminder://${r.id ?: "none"}/popup")
            putExtra(EXTRA_ID, r.id)
            putExtra(EXTRA_TEXT, r.text)
            putExtra(EXTRA_NOTIF_ID, notifId)
        }
        return PendingIntent.getActivity(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun show(ctx: Context, r: Reminder) {
        val app = ctx.applicationContext
        // Doppelte Zustellung: eine bereits erledigte Erinnerung nicht erneut zeigen
        if (!ReminderLogic.shouldShow(r, ReminderReplies.answeredRaw(app))) return
        ensureChannels(app)
        val dnd = dndBypassEnabled(app)
        val channelId = ReminderLogic.channelId(dnd)
        val notifId = if (r.id != null) ReminderLogic.notificationId(r.id) else ReminderLogic.notificationIdAnonymous(anonymousCounter.incrementAndGet())
        // Wiederholung derselben noch offenen Erinnerung: kein zweites Heads-up/Geraeusch
        val alreadyShown = try { nm(app).activeNotifications.any { it.id == notifId } } catch (_: Exception) { false }
        val locked = (app.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true
        val presentation = ReminderLogic.presentation(locked, canUseFullScreen(app), headsUpAllowed(app, channelId))

        val openApp = app.packageManager.getLaunchIntentForPackage(app.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val b = NotificationCompat.Builder(app, channelId)
            .setContentTitle("Erinnerung")
            .setContentText(r.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(r.text))
            .setSmallIcon(R.drawable.ic_stat_donna)
            .setColor(androidx.core.content.ContextCompat.getColor(app, R.color.donna_accent_solid))
            .setLargeIcon(NotificationIcons.largeOrb(app))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            // Bewusst oeffentlich: der Text soll auch bei gesperrtem Handy lesbar sein (Mike will Erinnerungen nicht
            // uebersehen; das Popup zeigt ihn ebenfalls im Klartext). Android blendet ihn nur aus, wenn die Nutzerin
            // systemweit "Sensible Inhalte ausblenden" waehlt.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(alreadyShown)
            .setOngoing(r.id != null)
            .setAutoCancel(r.id == null)
        if (openApp != null) {
            b.setContentIntent(PendingIntent.getActivity(app, 0, openApp, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        if (r.id != null) {
            b.addAction(0, "Erledigt", replyIntent(app, r.id, notifId, ReplyKind.DONE, null))
            b.addAction(0, "Später", replyIntent(app, r.id, notifId, ReplyKind.SNOOZE, ReminderLogic.SNOOZE_DEFAULT))
        }
        if (presentation == Presentation.FULLSCREEN_POPUP) {
            b.setFullScreenIntent(popupIntent(app, r, notifId), true)
        }
        nm(app).notify(notifId, b.build())
    }
}
