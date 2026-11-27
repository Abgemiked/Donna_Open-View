package com.yourcompany.donna.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.yourcompany.donna.BuildConfig
import com.yourcompany.donna.R
import com.yourcompany.donna.ThemeChoice
import com.yourcompany.donna.TokenStore
import com.yourcompany.donna.TtsEngines

/**
 * #244: Auswahl der Knöpfe des Szenen/Routinen-Widgets (beim Platzieren, über „Widget einstellen“ ab Android 12
 * oder das Zahnrad im Widget). Lädt `GET /smarthome/scenes` und `GET /routines`; höchstens 6 Häkchen.
 * Hier wird NICHTS ausgelöst. Exportiert nur, weil der Launcher die Konfiguration startet — Kennungen, die nicht
 * zu diesem Widget-Typ gehören, beenden sofort.
 */
class HomeActionsConfigActivity : AppCompatActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var save: Button
    private val boxes = ArrayList<Pair<CheckBox, HomeButton>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences(TtsEngines.PREFS, MODE_PRIVATE)
        delegate.localNightMode = when (ThemeChoice.nightOverride(ThemeChoice.read(prefs))) {
            true -> AppCompatDelegate.MODE_NIGHT_YES
            false -> AppCompatDelegate.MODE_NIGHT_NO
            null -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val info = try { AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId) } catch (_: Exception) { null }
        if (info?.provider != ComponentName(this, HomeActionsWidgetProvider::class.java)) {
            finish()
            return
        }
        setContentView(R.layout.activity_home_actions_config)
        list = findViewById(R.id.home_config_list)
        status = findViewById(R.id.home_config_status)
        save = findViewById(R.id.home_config_save)
        findViewById<Button>(R.id.home_config_cancel).setOnClickListener { finish() }
        save.setOnClickListener { saveAndClose() }
        save.isEnabled = false
        status.setText(R.string.widget_loading)
        Thread({ load() }, "widget-home-config").apply { isDaemon = true }.start()
    }

    private fun load() {
        val token = try { TokenStore.getToken(this) } catch (_: Exception) { null }
        if (token.isNullOrEmpty()) {
            runOnUiThread { status.setText(R.string.widget_home_no_token) }
            return
        }
        val scenes = WidgetHttp.get("${BuildConfig.DONNA_API_URL}/smarthome/scenes", token)
        val routines = WidgetHttp.get("${BuildConfig.DONNA_API_URL}/routines", token)
        val items = (if (scenes.ok) HomeActionsLogic.parseScenes(scenes.body) else emptyList()) +
            (if (routines.ok) HomeActionsLogic.parseRoutines(routines.body) else emptyList())
        val unreachable = scenes.unreachable && routines.unreachable
        runOnUiThread { show(items, unreachable) }
    }

    private fun show(items: List<HomeButton>, unreachable: Boolean) {
        if (isFinishing || isDestroyed) return
        val chosen = HomeActionsStore.load(this, widgetId).map { HomeActionsLogic.key(it) }.toSet()
        list.removeAllViews()
        boxes.clear()
        for (b in items) {
            val box = CheckBox(this).apply {
                text = (if (b.type == HomeActionsLogic.TYPE_SCENE) "Szene · " else "Routine · ") + b.name
                isChecked = HomeActionsLogic.key(b) in chosen
                setTextColor(getColor(R.color.donna_text))
                minHeight = (48 * resources.displayMetrics.density).toInt()
                setOnCheckedChangeListener { v, checked ->
                    if (checked && boxes.count { it.first.isChecked } > HomeActionsLogic.MAX_BUTTONS) {
                        v.isChecked = false
                        status.text = getString(R.string.widget_home_max, HomeActionsLogic.MAX_BUTTONS)
                    }
                }
            }
            boxes += box to b
            list.addView(box)
        }
        status.text = when {
            items.isNotEmpty() -> getString(R.string.widget_home_pick, HomeActionsLogic.MAX_BUTTONS)
            unreachable -> getString(R.string.widget_home_unreachable)
            else -> getString(R.string.widget_home_none)
        }
        status.visibility = View.VISIBLE
        save.isEnabled = items.isNotEmpty()
    }

    private fun saveAndClose() {
        val picked = boxes.filter { it.first.isChecked }.map { it.second }.take(HomeActionsLogic.MAX_BUTTONS)
        HomeActionsStore.save(this, widgetId, picked)
        HomeActionsWidgetProvider.render(this, AppWidgetManager.getInstance(this), widgetId)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}
