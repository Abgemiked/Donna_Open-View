package com.yourcompany.donna

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.modules.core.DeviceEventManagerModule

/**
 * TtsEvents — meldet der JS-Schicht den ECHTEN Zustand der Sprachausgabe (statt Dauer-Schätzung):
 * Event "DonnaTTSState" {state: "started" | "ended"}.
 *  - started: Ton läuft
 *  - ended:   Wiedergabe natürlich zu Ende (oder Fehler/Live-Guard ohne Ton) — NICHT bei bewusstem Stopp/Ersetzen
 */
object TtsEvents {
    const val EVENT = "DonnaTTSState"

    fun emit(ctx: ReactApplicationContext?, state: String) {
        try {
            if (ctx == null || !ctx.hasActiveReactInstance()) return
            ctx.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(EVENT, Arguments.createMap().apply { putString("state", state) })
        } catch (e: Exception) {
            android.util.Log.w("TtsEvents", "emit($state) fehlgeschlagen: ${e.message}")
        }
    }
}

/**
 * BargeInModule — Voice-Barge-in für den RN-Haupt-Chat (siehe [BargeInDetector]).
 *
 * JS: start(): Promise<boolean>   // true = Detektor läuft; false = Fallback (Mikrofon erst nach TTS-Ende)
 *     stop(): void
 *     setDisabled(disabled: boolean): void   // nach Echo-Selbstunterbrechungen
 * Event "DonnaBargeIn": Mikes Stimme wurde über Donnas Ausgabe erkannt.
 */
@ReactModule(name = BargeInModule.NAME)
class BargeInModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object { const val NAME = "BargeInModule" }

    private val detector = BargeInDetector(reactContext) {
        try {
            if (reactContext.hasActiveReactInstance()) {
                reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                    .emit("DonnaBargeIn", Arguments.createMap())
            }
        } catch (e: Exception) {
            android.util.Log.w(NAME, "Event fehlgeschlagen: ${e.message}")
        }
    }

    override fun getName() = NAME

    @ReactMethod
    fun start(promise: Promise) {
        promise.resolve(try { detector.start() } catch (e: Exception) { false })
    }

    @ReactMethod
    fun stop() {
        try { detector.stop() } catch (_: Exception) {}
    }

    @ReactMethod
    fun setDisabled(disabled: Boolean) {
        BargeInDetector.disabledForProcess = disabled
        if (disabled) stop()
    }

    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}

    override fun invalidate() {
        stop()
        super.invalidate()
    }
}
