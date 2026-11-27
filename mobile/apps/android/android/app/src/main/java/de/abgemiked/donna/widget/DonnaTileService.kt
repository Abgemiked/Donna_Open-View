package com.yourcompany.donna.widget

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * #234: Schnelleinstellungs-Kachel „Donna“. Ein Tipp schliesst das Schnellmenue und oeffnet das Overlay ueber
 * [com.yourcompany.donna.LiveBubbleLauncherActivity] (gleicher Weg wie die Funktionstaste; gesperrt → Overlay mit
 * Biometrie). Die Kachel hat keinen An/Aus-Zustand.
 */
class DonnaTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = DonnaEntries.overlay(this)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Ab Android 14 nur noch per PendingIntent erlaubt (die Intent-Variante wirft dort).
                startActivityAndCollapse(DonnaEntries.pending(this, DonnaEntries.RC_TILE, intent))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (e: Exception) {
            android.util.Log.w("DonnaTile", "Overlay nicht gestartet: ${e.javaClass.simpleName}")
        }
    }
}
