package com.yourcompany.donna

import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.facebook.react.bridge.WritableMap
import com.facebook.react.bridge.Arguments

/**
 * SideButtonModule — Native React Native Module
 *
 * Leitet Samsung Side-Key-Events (KeyEvent.KEYCODE_STEM_PRIMARY)
 * an den JavaScript-Layer weiter.
 *
 * Verwendung in JS:
 *   import { NativeModules, NativeEventEmitter } from 'react-native';
 *   const emitter = new NativeEventEmitter(NativeModules.SideButtonModule);
 *   emitter.addListener('onSideButtonPress', handler);
 */
class SideButtonModule(
    private val reactContext: ReactApplicationContext
) : ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val NAME = "SideButtonModule"
        const val EVENT_PRESS = "onSideButtonPress"
        const val EVENT_DOUBLE_PRESS = "onSideButtonDoublePress"

        // Samsung STEM keycodes
        const val KEYCODE_STEM_PRIMARY = 283
        const val KEYCODE_STEM_1 = 220
    }

    override fun getName(): String = NAME

    /**
     * Wird von MainActivity aufgerufen wenn Side-Key gedrückt wird.
     * Sendet Event an JavaScript-Layer.
     */
    fun emitSideButtonPress() {
        val params: WritableMap = Arguments.createMap()
        params.putString("action", "press")
        sendEvent(EVENT_PRESS, params)
    }

    fun emitSideButtonDoublePress() {
        val params: WritableMap = Arguments.createMap()
        params.putString("action", "double_press")
        sendEvent(EVENT_DOUBLE_PRESS, params)
    }

    private fun sendEvent(eventName: String, params: WritableMap) {
        reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(eventName, params)
    }

    @ReactMethod
    fun addListener(eventName: String) {
        // Required for React Native EventEmitter — no-op
    }

    @ReactMethod
    fun removeListeners(count: Int) {
        // Required for React Native EventEmitter — no-op
    }
}
