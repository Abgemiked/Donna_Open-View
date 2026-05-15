package com.yourcompany.donna

import android.content.Intent
import android.os.Build
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Bundle
import com.facebook.react.bridge.*
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.modules.core.DeviceEventManagerModule

@ReactModule(name = OfflineSTTModule.NAME)
class OfflineSTTModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object { const val NAME = "OfflineSTT" }

    private var recognizer: SpeechRecognizer? = null

    override fun getName() = NAME

    @ReactMethod
    fun isAvailable(promise: Promise) {
        try {
            val ctx = reactApplicationContext
            val hasRecognition = SpeechRecognizer.isRecognitionAvailable(ctx)
            val hasOnDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
            } else false
            promise.resolve(hasRecognition && hasOnDevice)
        } catch (e: Exception) {
            promise.resolve(false)
        }
    }

    @ReactMethod
    fun startListening(locale: String, promise: Promise) {
        try {
            UiThreadUtil.runOnUiThread {
                stopInternal()
                val ctx = reactApplicationContext
                recognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(ctx)
                }
                recognizer?.setRecognitionListener(buildListener())
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
                recognizer?.startListening(intent)
                promise.resolve(true)
            }
        } catch (e: Exception) {
            promise.reject("STT_START_FAILED", e.message, e)
        }
    }

    @ReactMethod
    fun stopListening(promise: Promise) {
        UiThreadUtil.runOnUiThread {
            stopInternal()
            promise.resolve(true)
        }
    }

    private fun stopInternal() {
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
    }

    private fun buildListener() = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
            emit("OfflineSTT.onResult", Arguments.createMap().apply { putString("text", text) })
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
            emit("OfflineSTT.onPartial", Arguments.createMap().apply { putString("text", text) })
        }
        override fun onError(error: Int) {
            emit("OfflineSTT.onError", Arguments.createMap().apply { putInt("code", error) })
        }
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(e: Int, p: Bundle?) {}
    }

    private fun emit(event: String, params: WritableMap) {
        reactApplicationContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(event, params)
    }

    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}
}
