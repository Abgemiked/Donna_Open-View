package com.yourcompany.donna

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.facebook.react.ReactPackage
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.uimanager.ViewManager
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.yourcompany.donna.device.ScreenshotPreview
import com.yourcompany.donna.device.DeviceActionKind
import com.yourcompany.donna.device.DeviceActionOrder
import com.yourcompany.donna.device.DeviceActionResult
import com.yourcompany.donna.device.DeviceActionRunner
import com.yourcompany.donna.device.ResultKind
import com.yourcompany.donna.device.SystemPermissions
import com.yourcompany.donna.device.TurnBudget
import org.json.JSONArray

/**
 * #147 Paket A — Brücke für den Chat-Weg und die Einstellungen. EINE Quelle der Wahrheit für die Schalter ist die
 * native Pref (je Aktionsart, Standard aus); die Ausführung läuft über dasselbe [DeviceActionRunner] wie im Overlay.
 */
class DeviceActionsModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName() = "DeviceActions"

    private fun prefs() = reactContext.getSharedPreferences(TtsEngines.PREFS, Context.MODE_PRIVATE)

    private val runner = DeviceActionRunner(reactContext)

    init {
        // Taschenlampen-Zustand früh über den Haupt-Looper beobachten (Vorzustand für Rückgängig)
        runner.warmUp()
        // #219: URI des neuen Screenshots an den Chat (Vorschau); nur die URI, nie Bilddaten
        runner.onScreenshotSaved = { uri ->
            try {
                reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                    .emit(EVENT_SCREENSHOT_SAVED, uri)
            } catch (_: Exception) {
                // JS nicht bereit: keine Vorschau, der Screenshot liegt trotzdem in der Galerie
            }
        }
    }

    /** #219: Tipp auf die Vorschau -> genau dieses Bild in der Galerie (nur MediaStore-Bild-URIs, sonst false). */
    @ReactMethod
    fun openScreenshot(uri: String?, promise: Promise) {
        promise.resolve(ScreenshotPreview.openInGallery(reactContext, uri))
    }

    // Pflicht für NativeEventEmitter (RN 0.76)
    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Double) {}

    companion object {
        const val EVENT_SCREENSHOT_SAVED = "DeviceScreenshotSaved"
    }

    @ReactMethod
    fun getEnabled(promise: Promise) {
        val arr = Arguments.createArray()
        DeviceActionKind.enabledKeys(prefs()).forEach { arr.pushString(it) }
        promise.resolve(arr)
    }

    @ReactMethod
    fun setEnabled(key: String, enabled: Boolean, promise: Promise) {
        val kind = DeviceActionKind.fromKey(key)
        if (kind == null) {
            promise.resolve(false)
            return
        }
        DeviceActionKind.write(prefs(), kind, enabled)
        promise.resolve(DeviceActionKind.read(prefs(), kind))
    }

    /**
     * Geführter Hinweis in den Einstellungen (#147 Paket F): "granted" | "missing" | "n/a" je Aktionsart. Nur lesen,
     * nichts erteilen.
     */
    @ReactMethod
    fun getPermissionState(key: String, promise: Promise) {
        val kind = DeviceActionKind.fromKey(key)
        promise.resolve(
            if (kind == null) "n/a" else SystemPermissions.stateOf(kind, DeviceActionRunner.permissionFacts(reactContext)),
        )
    }

    /** Öffnet die passende Systemseite — nur auf Tipp von Mike (nie von selbst). Liefert, ob die Seite aufging. */
    @ReactMethod
    fun openPermissionSettings(key: String, promise: Promise) {
        val need = DeviceActionKind.fromKey(key)?.let { SystemPermissions.needOf(it) }
        if (need == null) {
            promise.resolve(false)
            return
        }
        try {
            val intent = Intent(need.settingsAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (need.needsPackageUri) intent.data = Uri.fromParts("package", reactContext.packageName, null)
            reactContext.startActivity(intent)
            promise.resolve(true)
        } catch (_: Exception) {
            promise.resolve(false)
        }
    }

    /**
     * Führt die Geräte-Aktionen EINER Antwort aus (JSON-Array). Jede läuft durch dieselbe Prüfung wie im Overlay:
     * Positivliste, Aktionsart eingeschaltet, höchstens 3, keine Wiederholung. Ergebnis: [{kind, message}].
     */
    @ReactMethod
    fun run(actionsJson: String, promise: Promise) {
        Thread {
            try {
                val input = JSONArray(actionsJson)
                val enabled = DeviceActionKind.enabledSet(prefs())
                val budget = TurnBudget()
                // Sperren/Screenshot zuletzt (stabil); die Ergebnisse bleiben an der Eingabeposition
                val order = DeviceActionOrder.executionOrder(
                    (0 until input.length()).map { input.optJSONObject(it)?.optString("action") },
                )
                val results = arrayOfNulls<DeviceActionResult>(input.length())
                for (i in order) {
                    val obj = input.optJSONObject(i)
                    results[i] =
                        if (obj == null) {
                            DeviceActionResult(ResultKind.REJECTED, "Das kann ich so nicht ausführen.")
                        } else {
                            runner.runGated(obj, enabled, budget)
                        }
                }
                val out = JSONArray()
                results.forEach { out.put(it?.toJson()) }
                promise.resolve(out.toString())
            } catch (e: Exception) {
                promise.reject("DEVICE_ACTION_ERROR", "Fehler")
            }
        }.start()
    }

    /** Nimmt die Aktion mit dieser Kennung zurück (und nur diese); das Gerät prüft Freigabe, Zeit, Reihenfolge. */
    @ReactMethod
    fun undo(undoId: Double, promise: Promise) {
        val id = undoId.toLong()
        Thread { promise.resolve(runner.undo(id, DeviceActionKind.enabledSet(prefs())).toJson().toString()) }.start()
    }
}

class DeviceActionsPackage : ReactPackage {
    override fun createNativeModules(ctx: ReactApplicationContext) = listOf(DeviceActionsModule(ctx))
    override fun createViewManagers(ctx: ReactApplicationContext): List<ViewManager<*, *>> = emptyList()
}
