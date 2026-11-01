package com.yourcompany.donna

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * NtfyRestartReceiver — DONNA-13: Empfängt den AlarmManager-Broadcast nach onTaskRemoved().
 *
 * Samsung One UI killt den NtfyService wenn der User die App aus dem Recents-Menü wischt,
 * auch wenn START_STICKY gesetzt ist. NtfyService.onTaskRemoved() plant per AlarmManager
 * einen PendingIntent 5s nach dem Entfernen — dieser Receiver empfängt diesen Intent und
 * startet den Service neu.
 *
 * Flow: onTaskRemoved() → AlarmManager → NtfyRestartReceiver.onReceive() → NtfyService.start()
 */
class NtfyRestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "AlarmManager-Restart-Intent empfangen — starte NtfyService neu")
        NtfyService.start(context)
    }

    companion object {
        private const val TAG = "DonnaNtfyRestart"
    }
}
