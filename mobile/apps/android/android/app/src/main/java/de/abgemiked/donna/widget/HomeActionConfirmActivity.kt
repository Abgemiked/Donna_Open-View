package com.yourcompany.donna.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.yourcompany.donna.BuildConfig
import com.yourcompany.donna.R
import com.yourcompany.donna.ThemeChoice
import com.yourcompany.donna.TokenStore
import com.yourcompany.donna.TtsEngines
import com.yourcompany.donna.device.DeviceActionKind
import com.yourcompany.donna.device.DeviceActionRunner
import com.yourcompany.donna.device.OfflineCommands
import com.yourcompany.donna.device.OfflineRoutineStore
import com.yourcompany.donna.device.TurnBudget
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * #244: kleines Bestätigungsfenster des Szenen/Routinen-Widgets („Szene X starten?“ Ja/Nein). Nur „Ja“ löst aus;
 * „Nein“, Zurück oder Tipp daneben schließen ohne Wirkung. Nicht exportiert (nur der eigene Widget-PendingIntent).
 * Der Knopf wird NICHT aus dem Intent übernommen, sondern über seinen Schlüssel aus der gespeicherten Auswahl
 * dieses Widgets gelesen (kein Auslösen beliebiger Kennungen).
 *
 * Szene: `POST /smarthome/scene/{id}`. Routine: `POST /routines/{id}/run` (Server führt Smart-Home-Schritte mit
 * seinen Sperren aus, gibt Geräte-Schritte zurück) → Geräte-Schritte laufen hier durch dieselbe Prüfung wie im
 * Chat ([DeviceActionRunner.runGated]: Positivliste, Schalter je Art, höchstens 3 je Lauf). Server nicht
 * erreichbar → Routine aus dem lokalen Stand (nur Geräte-Schritte, [OfflineCommands.runStoredRoutine]).
 * Rückmeldung per Toast, ehrlich („angestoßen“, nie „erledigt“ ohne Rückmeldung).
 */
class HomeActionConfirmActivity : AppCompatActivity() {
    private var button: HomeButton? = null
    private val started = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences(TtsEngines.PREFS, MODE_PRIVATE)
        delegate.localNightMode = when (ThemeChoice.nightOverride(ThemeChoice.read(prefs))) {
            true -> AppCompatDelegate.MODE_NIGHT_YES
            false -> AppCompatDelegate.MODE_NIGHT_NO
            null -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home_action_confirm)
        if (!bind(intent)) return
        findViewById<android.view.View>(R.id.home_confirm_root).setOnClickListener { finish() }
        findViewById<Button>(R.id.home_confirm_no).setOnClickListener { finish() }
        findViewById<Button>(R.id.home_confirm_yes).setOnClickListener { v ->
            val b = button ?: return@setOnClickListener
            if (!started.compareAndSet(false, true)) return@setOnClickListener
            v.isEnabled = false
            findViewById<Button>(R.id.home_confirm_no).isEnabled = false
            findViewById<TextView>(R.id.home_confirm_status).setText(R.string.widget_home_running)
            Thread({ execute(b) }, "widget-home-action").apply { isDaemon = true }.start()
        }
    }

    /** Knopf aus der gespeicherten Auswahl lesen (nie aus dem Intent) und die Frage zeigen; unbekannt -> schließen. */
    private fun bind(i: Intent?): Boolean {
        val widgetId = i?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val b = HomeActionsStore.load(this, widgetId).firstOrNull { HomeActionsLogic.key(it) == i?.getStringExtra(EXTRA_KEY) }
        if (b == null) {
            Toast.makeText(this, R.string.widget_home_unknown, Toast.LENGTH_SHORT).show()
            finish()
            return false
        }
        button = b
        findViewById<TextView>(R.id.home_confirm_question).text = HomeActionsLogic.question(b)
        return true
    }

    /**
     * singleTask: ein zweiter Knopf-Tipp bei noch offenem Fenster zeigt DESSEN Frage (nicht die alte). Läuft schon
     * ein Auslösen, bleibt es dabei (kein Wechsel mitten im Schalten).
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (started.get()) return
        setIntent(intent)
        bind(intent)
    }

    private fun execute(b: HomeButton) {
        val msg = try {
            if (b.type == HomeActionsLogic.TYPE_SCENE) runScene(b) else runRoutine(b)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Auslösen fehlgeschlagen: ${e.javaClass.simpleName}")
            getString(R.string.widget_home_failed)
        }
        runOnUiThread {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
            if (!isFinishing) finish()
        }
    }

    private fun token(): String? = try { TokenStore.getToken(this) } catch (_: Exception) { null }

    private fun runScene(b: HomeButton): String {
        val token = token() ?: return getString(R.string.widget_home_no_token)
        val r = WidgetHttp.postJson("${BuildConfig.DONNA_API_URL}/smarthome/scene/${b.id}", token, "{}")
        return when {
            r.ok -> getString(R.string.widget_home_scene_ok, b.name)
            else -> errorText(r)
        }
    }

    private fun runRoutine(b: HomeButton): String {
        val prefs = getSharedPreferences(TtsEngines.PREFS, MODE_PRIVATE)
        val enabled = DeviceActionKind.enabledSet(prefs)
        val token = token() ?: return getString(R.string.widget_home_no_token)
        val body = JSONObject()
            .put("confirmed", true)
            .put("device_actions", JSONArray(DeviceActionKind.enabledKeysNoCards(prefs)))
            .toString()
        val r = WidgetHttp.postJson("${BuildConfig.DONNA_API_URL}/routines/${b.id}/run", token, body)
        // nur wenn die Anfrage gar nicht ankam lokal weiter (nach gesendeter Anfrage könnte der Server schon geschaltet haben)
        if (r.unreachable) return runOffline(b, enabled)
        if (!r.ok) return errorText(r)
        val res = HomeActionsLogic.parseRunResult(r.body) ?: return getString(R.string.widget_home_failed)
        val failed = runDevice(res.device, enabled)
        val text = res.text.ifBlank { getString(R.string.widget_home_routine_ok, b.name) }
        return if (failed > 0) text + " " + getString(R.string.widget_home_device_failed, failed) else text
    }

    /** Ohne Server: nur Geräte-Schritte aus dem zuletzt synchronisierten Stand (gleiche Regeln wie offline per Sprache). */
    private fun runOffline(b: HomeButton, enabled: Set<DeviceActionKind>): String {
        val snap = OfflineRoutineStore.load(getSharedPreferences(TtsEngines.PREFS, MODE_PRIVATE))
        return when (val out = OfflineCommands.runStoredRoutine(b.name, enabled, snap)) {
            null -> getString(R.string.widget_home_offline_unknown)
            is OfflineCommands.Outcome.Say -> getString(R.string.widget_home_offline_prefix) + " " + out.speech
            is OfflineCommands.Outcome.Run -> {
                val failed = runDevice(out.actions, enabled)
                val text = getString(R.string.widget_home_offline_prefix) + " " + out.speech
                if (failed > 0) text + " " + getString(R.string.widget_home_device_failed, failed) else text
            }
        }
    }

    /** Geräte-Schritte mit zweiter Prüfung (Positivliste, Schalter, Budget); liefert die Zahl der nicht ausgeführten. */
    private fun runDevice(actions: List<JSONObject>, enabled: Set<DeviceActionKind>): Int {
        if (actions.isEmpty()) return 0
        val runner = DeviceActionRunner(this)
        val budget = TurnBudget()
        var failed = 0
        for (a in actions) {
            val res = try { runner.runGated(a, enabled, budget) } catch (_: Exception) { null }
            if (res == null || !res.ok) failed++
        }
        return failed
    }

    private fun errorText(r: WidgetResponse): String = getString(
        when (HomeActionsLogic.errorKind(r.code)) {
            HomeActionsLogic.ErrorKind.UNREACHABLE -> R.string.widget_home_unreachable
            HomeActionsLogic.ErrorKind.UNKNOWN -> R.string.widget_home_outcome_unknown
            HomeActionsLogic.ErrorKind.NO_TOKEN -> R.string.widget_home_no_token
            HomeActionsLogic.ErrorKind.RATE_LIMITED -> R.string.widget_home_rate_limited
            HomeActionsLogic.ErrorKind.LIVE_LOCKED -> R.string.widget_home_live_locked
            HomeActionsLogic.ErrorKind.NOT_CONFIGURED -> R.string.widget_home_not_configured
            HomeActionsLogic.ErrorKind.GONE -> R.string.widget_home_unknown
            HomeActionsLogic.ErrorKind.FAILED -> R.string.widget_home_failed
        },
    )

    companion object {
        private const val TAG = "HomeActionConfirm"
        const val EXTRA_KEY = "home_button_key"
    }
}
