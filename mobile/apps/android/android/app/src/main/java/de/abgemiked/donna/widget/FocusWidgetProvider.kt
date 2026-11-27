package com.yourcompany.donna.widget

import android.app.NotificationManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import com.yourcompany.donna.R
import com.yourcompany.donna.device.DeviceAction
import com.yourcompany.donna.device.DeviceActionRunner
import com.yourcompany.donna.device.DndLogic
import com.yourcompany.donna.device.ResultKind
import java.util.concurrent.atomic.AtomicBoolean

/** #245: reiner Zustand des Schalters (JVM-testbar). */
enum class FocusState { ON, OFF, NO_PERMISSION }

object FocusWidgetLogic {
    /** Jeder Filter außer „alles durchlassen“ gilt als an (Priorität, Wecker, nichts). */
    fun state(policyAccess: Boolean, filter: Int): FocusState = when {
        !policyAccess -> FocusState.NO_PERMISSION
        filter > DndLogic.FILTER_ALL -> FocusState.ON
        else -> FocusState.OFF
    }

    /** Schmal (unter 100 dp, bekannt) = nur das Symbol. */
    fun compact(widthDp: Int): Boolean = widthDp in 1 until 100
}

/**
 * #245: Nicht-stören/Stream-Fokus-Schalter (2x1), rein lokal. Zeigt den aktuellen Zustand; ein Tipp schaltet um
 * (an = Priorität, aus = alles) über DIESELBE DND-Logik wie Donnas Geräte-Aktion ([DeviceActionRunner], inkl.
 * Rücklesen: übernimmt das System den Filter nicht, gibt es eine ehrliche Meldung). Der Widget-Tipp ist Mikes
 * eigene Bedienung wie die Schnelleinstellung, daher gilt der Donna-Schalter je Aktionsart hier NICHT.
 * Fehlt „Nicht stören“-Zugriff (ACCESS_NOTIFICATION_POLICY), zeigt das Widget das und öffnet beim Tipp die
 * passende Systemseite — es wird nie still etwas erteilt.
 * Grenze: Wird „Nicht stören“ außerhalb umgeschaltet, sieht das Widget das beim nächsten Neuzeichnen (Tipp,
 * App-Start, spätestens nach 30 min) — ein Dauer-Empfänger dafür ist nur zur Laufzeit möglich.
 */
class FocusWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        try { render(context, manager, appWidgetIds) } catch (_: Exception) {}
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: android.os.Bundle) {
        try { render(context, manager, intArrayOf(appWidgetId)) } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "FocusWidget"
        private const val RC_TOGGLE = 2451
        private const val RC_SETTINGS = 2452
        private val busy = AtomicBoolean(false)

        /**
         * Umschalten (aus [WidgetW3ActionReceiver], nicht exportiert — der Provider selbst ist für das System
         * exportiert und darf daher KEINE Schalt-Broadcasts annehmen). Worker-Thread: das Rücklesen (bis ~800 ms)
         * darf schlafen. [onDone] kommt immer (goAsync).
         */
        fun toggle(ctx: Context, onDone: () -> Unit) {
            val app = ctx.applicationContext
            if (!busy.compareAndSet(false, true)) {
                onDone()
                return
            }
            Thread({
                try {
                    val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    val st = FocusWidgetLogic.state(policyAccess(app), nm?.currentInterruptionFilter ?: DndLogic.FILTER_UNKNOWN)
                    if (st != FocusState.NO_PERMISSION) {
                        val res = DeviceActionRunner(app).run(DeviceAction.Dnd(on = st == FocusState.OFF))
                        if (!res.ok) toast(app, if (res.kind == ResultKind.NEEDS_PERMISSION) R.string.widget_focus_hint_permission else R.string.widget_focus_failed)
                    }
                    renderAll(app)
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Umschalten fehlgeschlagen: ${e.javaClass.simpleName}")
                    toast(app, R.string.widget_focus_failed)
                } finally {
                    busy.set(false)
                    try { onDone() } catch (_: Exception) {}
                }
            }, "widget-focus").apply { isDaemon = true }.start()
        }

        private fun policyAccess(ctx: Context): Boolean =
            DeviceActionRunner.permissionFacts(ctx).policyAccess

        private fun toast(ctx: Context, res: Int) {
            Handler(Looper.getMainLooper()).post { Toast.makeText(ctx, res, Toast.LENGTH_LONG).show() }
        }

        fun renderAll(ctx: Context) {
            val m = AppWidgetManager.getInstance(ctx)
            val ids = try { m.getAppWidgetIds(ComponentName(ctx, FocusWidgetProvider::class.java)) } catch (_: Exception) { IntArray(0) }
            if (ids.isNotEmpty()) render(ctx, m, ids)
        }

        fun render(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val state = FocusWidgetLogic.state(policyAccess(ctx), nm?.currentInterruptionFilter ?: DndLogic.FILTER_UNKNOWN)
            // #243-Muster: schmal (unter 100 dp) nur das Symbol
            for (id in ids) {
                manager.updateAppWidget(id, DataWidgetProvider.sized(manager, id) { w, _ ->
                    build(ctx, state, compact = FocusWidgetLogic.compact(w))
                })
            }
        }

        private fun build(ctx: Context, state: FocusState, compact: Boolean): RemoteViews =
            RemoteViews(ctx.packageName, R.layout.widget_focus).apply {
                setViewVisibility(R.id.widget_focus_texts, if (compact) View.GONE else View.VISIBLE)
                setInt(R.id.widget_focus_icon, "setBackgroundResource",
                    if (state == FocusState.ON) R.drawable.widget_mic_bg else R.drawable.widget_btn_bg)
                setInt(R.id.widget_focus_icon, "setColorFilter", ctx.getColor(
                    if (state == FocusState.ON) R.color.donna_on_accent_solid else R.color.donna_on_accent_container))
                setTextViewText(R.id.widget_focus_state, ctx.getString(when (state) {
                    FocusState.ON -> R.string.widget_focus_on
                    FocusState.OFF -> R.string.widget_focus_off
                    FocusState.NO_PERMISSION -> R.string.widget_focus_no_permission
                }))
                setContentDescription(android.R.id.background, ctx.getString(R.string.widget_focus_label) + ": " +
                    ctx.getString(when (state) {
                        FocusState.ON -> R.string.widget_focus_on
                        FocusState.OFF -> R.string.widget_focus_off
                        FocusState.NO_PERMISSION -> R.string.widget_focus_no_permission
                    }))
                val click = if (state == FocusState.NO_PERMISSION) {
                    DonnaEntries.pending(ctx, RC_SETTINGS,
                        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    android.app.PendingIntent.getBroadcast(
                        ctx, RC_TOGGLE,
                        Intent(ctx, WidgetW3ActionReceiver::class.java).setAction(WidgetW3ActionReceiver.ACTION_FOCUS_TOGGLE),
                        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                }
                setOnClickPendingIntent(android.R.id.background, click)
            }
    }
}
