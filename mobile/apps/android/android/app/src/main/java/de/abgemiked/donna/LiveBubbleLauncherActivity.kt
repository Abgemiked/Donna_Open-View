package com.yourcompany.donna

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

/**
 * LiveBubbleLauncherActivity — unsichtbares Trampolin für die Funktionstaste/ASSIST.
 *
 * Mike-Entscheidung 2026-11-01: Die Funktionstaste ruft ab jetzt die schwebende Live-Bubble auf
 * (on-demand, verschiebbar), NICHT mehr das alte Vollbild-Overlay. Diese Activity hat KEINE UI
 * (translucentes Theme + sofortiges finish()) und leitet nur weiter:
 *
 *  - Entsperrt + Overlay-Recht erteilt → startet [LiveBubbleService] (die Blase erscheint).
 *  - Gesperrt (oder Overlay-Recht fehlt) → die Blase (TYPE_APPLICATION_OVERLAY) kann nicht über dem
 *    sicheren Lockscreen zeichnen bzw. ohne Recht gar nicht → Rückfall auf das bestehende
 *    [VoiceInputActivity]-Overlay (showWhenLocked + Lockscreen-Biometrie bleiben dort erhalten —
 *    kein Security-Verlust).
 *
 * Der Dauer-Toggle (Einrichtung → „Live-Bubble") bleibt als Alternative; Haupt-Aufruf ist die Taste.
 */
class LiveBubbleLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        if (!locked && Settings.canDrawOverlays(this)) {
            LiveBubbleService.start(this)
        } else {
            try {
                startActivity(
                    Intent(this, VoiceInputActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Exception) {
            }
        }
        finish()
    }
}
