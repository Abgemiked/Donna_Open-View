package com.yourcompany.donna

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod

/**
 * TeslaNavModule — React-Native-Brücke zu [TeslaNav].
 *
 * JS: NativeModules.TeslaNavModule.sendToTesla(text): Promise<boolean>
 * Reject-Codes: TESLA_NOT_INSTALLED, EMPTY_TEXT, SEND_FAILED (gemappt in src/native/TeslaNav.ts)
 */
class TeslaNavModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName() = "TeslaNavModule"

    @ReactMethod
    fun sendToTesla(text: String?, promise: Promise) {
        when (TeslaNav.send(reactApplicationContext, text)) {
            TeslaNav.Result.SENT -> promise.resolve(true)
            TeslaNav.Result.NOT_INSTALLED ->
                promise.reject("TESLA_NOT_INSTALLED", "Tesla-App nicht installiert")
            TeslaNav.Result.EMPTY ->
                promise.reject("EMPTY_TEXT", "Kein Ziel angegeben")
            TeslaNav.Result.FAILED ->
                promise.reject("SEND_FAILED", "Senden an Tesla fehlgeschlagen")
        }
    }
}
