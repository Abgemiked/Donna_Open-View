package com.yourcompany.donna.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Welle 3 (#245/#247): Knopf-/Wecker-Empfänger der W3-Widgets. Nicht exportiert — erreichbar nur über die eigenen
 * PendingIntents (Nicht-stören-Tipp, Timer-Ablauf). Die Widget-Provider selbst sind für das System exportiert und
 * nehmen deshalb keine Schalt-Broadcasts an.
 */
class WidgetW3ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_FOCUS_TOGGLE -> {
                val pending = goAsync()
                FocusWidgetProvider.toggle(context) { try { pending.finish() } catch (_: Exception) {} }
            }
            ACTION_TIMER_EXPIRED -> try { TimerWidgetProvider.renderAll(context) } catch (_: Exception) {}
        }
    }

    companion object {
        const val ACTION_FOCUS_TOGGLE = "com.yourcompany.donna.widget.FOCUS_TOGGLE"
        const val ACTION_TIMER_EXPIRED = "com.yourcompany.donna.widget.TIMER_EXPIRED"
    }
}
