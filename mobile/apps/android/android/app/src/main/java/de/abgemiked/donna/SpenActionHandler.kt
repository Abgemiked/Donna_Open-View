package com.yourcompany.donna

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.KeyEvent
import android.util.Log

/**
 * SpenActionHandler — DONNA-118: Samsung S Pen Air Action Integration
 *
 * Samsung S Pen sendet bei Air Actions KeyEvent.KEYCODE_MEDIA_RECORD (126).
 * Da das Samsung S Pen SDK nicht öffentlich über Maven verfügbar ist, nutzen wir
 * den Standard-Android-Weg via KeyEvent-Intercepting in MainActivity.
 *
 * Single Press → VoiceInputActivity öffnen (wie Wake-Word-Trigger)
 * Double Press → TODO: reserviert für künftige Funktionen
 *
 * Graceful Degradation: Auf Geräten ohne S Pen wird der Handler erstellt aber
 * nie aufgerufen (isSpenPresent() = false).
 */
object SpenActionHandler {

    private const val TAG = "SpenActionHandler"

    /**
     * Samsung S Pen Feature-Flag (com.samsung.feature.spen_usp).
     * Ist false auf jedem Gerät ohne S Pen — Handler degradiert gracefully.
     */
    fun isSpenPresent(context: Context): Boolean =
        context.packageManager.hasSystemFeature("com.samsung.feature.spen_usp")

    /**
     * Prüft ob der gegebene KeyCode ein S Pen Air Action ist.
     * KEYCODE_MEDIA_RECORD (126) wird vom S Pen Stift-Button auf Samsung-Geräten gesendet.
     */
    fun isSpenKeyCode(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_MEDIA_RECORD

    /**
     * S Pen Single Press Handler → öffnet VoiceInputActivity.
     * Entspricht dem Wake-Word-Trigger-Verhalten: Donna hört sofort zu.
     */
    fun onSinglePress(context: Context) {
        Log.d(TAG, "S Pen single press — öffne VoiceInputActivity")
        try {
            val intent = Intent(context, VoiceInputActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("source", "spen_air_action")
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Starten der VoiceInputActivity via S Pen", e)
        }
    }

    /**
     * S Pen Double Press Handler.
     * TODO DONNA-118: Funktion noch nicht definiert — reserviert für Phase 2.
     * Mögliche Verwendung: Kamera-Shortcut, Quick-Note, etc.
     */
    fun onDoublePress(context: Context) {
        Log.d(TAG, "S Pen double press — noch nicht implementiert (TODO Phase 2)")
        // TODO DONNA-118: Double-Press-Funktion nach User-Anforderung implementieren
    }
}
