package com.yourcompany.donna

import android.provider.Settings
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.module.annotations.ReactModule

/**
 * LiveBubbleModule — RN-Bridge zum [LiveBubbleService] (A2 Live-Bubble, Increment 1).
 *
 * JS:
 *   canDrawOverlay(): Promise<boolean>   // Overlay-Recht erteilt? (sonst erst im Onboarding erteilen)
 *   show(): Promise<boolean>             // startet die schwebende Blase; false = Overlay-Recht fehlt
 *   hide(): void                         // beendet die Blase
 *   isRunning(): Promise<boolean>
 */
@ReactModule(name = LiveBubbleModule.NAME)
class LiveBubbleModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object { const val NAME = "LiveBubbleModule" }

    override fun getName() = NAME

    @ReactMethod
    fun canDrawOverlay(promise: Promise) {
        promise.resolve(Settings.canDrawOverlays(reactContext))
    }

    @ReactMethod
    fun show(promise: Promise) {
        if (!Settings.canDrawOverlays(reactContext)) {
            promise.resolve(false)
            return
        }
        try {
            LiveBubbleService.start(reactContext)
            promise.resolve(true)
        } catch (e: Exception) {
            android.util.Log.w(NAME, "show fehlgeschlagen: ${e.message}")
            promise.resolve(false)
        }
    }

    @ReactMethod
    fun hide() {
        try { LiveBubbleService.stop(reactContext) } catch (_: Exception) {}
    }

    @ReactMethod
    fun isRunning(promise: Promise) {
        promise.resolve(LiveBubbleService.running)
    }
}
