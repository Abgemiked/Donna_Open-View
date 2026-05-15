package com.yourcompany.donna

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * DonnaNotificationListener — DONNA-122: Notification Access
 *
 * Lauscht auf alle Notifications des Geräts und puffert relevante
 * in NotificationStore (Ring-Buffer, max 20 Einträge, nur RAM).
 *
 * Filterregeln:
 *   1. Donna-eigene Notifications ignorieren (packageName == eigenes Package)
 *   2. System-Notifications ignorieren (packageName-Blacklist)
 *   3. Passwortfelder ignorieren: text.length < 4 ODER enthält "•••"
 *
 * Benötigt: android.permission.BIND_NOTIFICATION_LISTENER_SERVICE
 * User muss in Einstellungen → Benachrichtigungszugriff aktivieren.
 * Check in MainActivity via NotificationManagerCompat.getEnabledListenerPackages().
 */
class DonnaNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "DonnaNotifListener"

        /**
         * System-Packages die wir gezielt ignorieren.
         * Passwort-Manager, OTP-Apps, eigene Notifications, System-Core.
         */
        private val PACKAGE_BLACKLIST = setOf(
            "com.yourcompany.donna",                        // eigene App
            "android",                                    // Android-System
            "com.android.systemui",                      // System UI
            "com.android.settings",                      // Einstellungen
            "com.android.phone",                         // Telefon-System
            "com.google.android.gms",                    // Google Play Services
            "com.google.android.gsf",                    // Google Services Framework
            "com.samsung.android.knox.containeragent",   // Samsung Knox
            "com.samsung.android.incallui",              // Samsung Anruf-UI
            "com.android.nfc",                           // NFC
            "com.android.bluetooth",                     // Bluetooth
            "com.android.wifi",                          // WLAN
            "com.keepassdx",                             // KeePass
            "org.keepassdroid",                          // KeePassDroid
            "com.lastpass.lpandroid",                    // LastPass
            "com.onepassword.android",                   // 1Password
            "com.agilebits.onepassword",                 // 1Password alt
            "com.dashlane",                              // Dashlane
            "com.bitwarden.mobile",                      // Bitwarden
            "com.google.android.apps.authenticator2",    // Google Authenticator
            "org.shadowice.flocke.andotp",               // andOTP
            "com.authy.authy",                           // Authy
            "com.microsoft.authenticator",               // MS Authenticator
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        val pkg = sbn.packageName ?: return

        // Filter 1: Blacklist
        if (pkg in PACKAGE_BLACKLIST) {
            return
        }

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

        // Filter 2: Passwortfelder — zu kurz oder enthält Maskierungszeichen
        val filteredText = if (text != null && (text.length < 4 || text.contains("•••"))) {
            null  // Passwort-artige Inhalte nicht speichern
        } else {
            text
        }

        // App-Label aus PackageManager auflesen (graceful: falls nicht möglich → packageName nutzen)
        val appLabel = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
            ).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }

        val entry = NotificationEntry(
            timestamp = sbn.postTime,
            packageName = pkg,
            appLabel = appLabel,
            title = title,
            text = filteredText,
        )

        NotificationStore.add(entry)
        Log.d(TAG, "Notification gepuffert: $appLabel → ${title?.take(50)}")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Keine Aktion beim Entfernen — Ring-Buffer managed sich selbst
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "NotificationListenerService verbunden")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "NotificationListenerService getrennt")
    }
}
