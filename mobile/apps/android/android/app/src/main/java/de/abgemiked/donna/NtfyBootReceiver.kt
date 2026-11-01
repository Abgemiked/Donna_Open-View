package com.yourcompany.donna

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * NtfyBootReceiver — Startet NtfyService nach Geräte-Neustart automatisch.
 *
 * DONNA-13: Empfängt BOOT_COMPLETED + QUICKBOOT_POWERON (Samsung) und
 * startet den ntfy-Foreground-Service. Erfordert RECEIVE_BOOT_COMPLETED-
 * Permission in AndroidManifest.xml.
 */
class NtfyBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.i("DonnaNtfy", "Boot erkannt ($action) — starte NtfyService")
            NtfyService.start(context)
        }
    }
}
