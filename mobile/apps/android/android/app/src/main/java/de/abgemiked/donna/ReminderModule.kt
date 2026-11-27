package com.yourcompany.donna

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.facebook.react.ReactPackage
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.ViewManager
import com.yourcompany.donna.reminder.ReminderLogic
import com.yourcompany.donna.reminder.ReminderNotifier

/**
 * #153 Bruecke fuer die Einstellungen "Erinnerungen". EINE Quelle der Wahrheit fuer den Schalter "Darf Nicht stoeren
 * durchbrechen" ist die native Pref (Standard aus). Der Schalter legt nur den zweiten Kanal an; ob Android ihn
 * durchlaesst, entscheidet die Systemfreigabe der Nutzerin (`dndGranted`).
 */
class ReminderModule(private val reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    override fun getName() = "Reminders"

    private fun prefs() = reactContext.getSharedPreferences(TtsEngines.PREFS, Context.MODE_PRIVATE)

    private fun state(): WritableMap {
        val nm = reactContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val enabled = ReminderNotifier.dndBypassEnabled(reactContext)
        val granted = enabled && nm.getNotificationChannel(ReminderLogic.CHANNEL_DND_ID)?.canBypassDnd() == true
        return Arguments.createMap().apply {
            putBoolean("dndBypass", enabled)
            putBoolean("dndGranted", granted)
            putBoolean("fullScreenAllowed", ReminderNotifier.canUseFullScreen(reactContext))
        }
    }

    @ReactMethod
    fun getState(promise: Promise) {
        promise.resolve(try { state() } catch (_: Exception) { Arguments.createMap() })
    }

    @ReactMethod
    fun setDndBypass(enabled: Boolean, promise: Promise) {
        try {
            prefs().edit().putBoolean(ReminderLogic.PREF_DND_BYPASS, enabled).apply()
            if (enabled) ReminderNotifier.ensureChannels(reactContext) else ReminderNotifier.removeDndChannel(reactContext)
            promise.resolve(state())
        } catch (e: Exception) {
            promise.reject("REMINDER_ERROR", "Fehler")
        }
    }

    private fun launch(intent: Intent): Boolean = try {
        reactContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        false
    }

    /** Android 14+: Seite "Vollbild-Benachrichtigungen" der App; davor die allgemeinen Benachrichtigungs-Einstellungen. */
    @ReactMethod
    fun openFullScreenSettings(promise: Promise) {
        val i = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${reactContext.packageName}"))
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, reactContext.packageName)
        }
        promise.resolve(launch(i))
    }

    /** Systemseite des Kanals "Erinnerungen (auch bei Nicht stören)", dort erlaubt die Nutzerin das Durchbrechen. */
    @ReactMethod
    fun openChannelSettings(promise: Promise) {
        ReminderNotifier.ensureChannels(reactContext)
        val i = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, reactContext.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, ReminderLogic.CHANNEL_DND_ID)
        promise.resolve(launch(i))
    }
}

class ReminderPackage : ReactPackage {
    override fun createNativeModules(ctx: ReactApplicationContext) = listOf(ReminderModule(ctx))
    override fun createViewManagers(ctx: ReactApplicationContext): List<ViewManager<*, *>> = emptyList()
}
