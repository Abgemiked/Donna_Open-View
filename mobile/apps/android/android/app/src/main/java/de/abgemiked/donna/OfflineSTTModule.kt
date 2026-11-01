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
            // isOnDeviceRecognitionAvailable gibt es erst ab API 33 (nicht 31): auf älteren Geräten wäre der
            // Aufruf ein NoSuchMethodError, den catch (Exception) nicht fängt
            val hasOnDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
                // Erzeugung ABGESICHERT: eine Ausnahme auf dem UI-Thread wäre sonst ein App-Absturz statt Promise-reject
                try {
                    recognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) {
                        SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
                    } else {
                        SpeechRecognizer.createSpeechRecognizer(ctx)
                    }
                    recognizer?.setRecognitionListener(buildListener())
                } catch (e: Throwable) {
                    stopInternal()
                    promise.reject("STT_CREATE_FAILED", e.message, e)
                    return@runOnUiThread
                }
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
                // Großzügige Sprechpause (Denkpausen), wie im Overlay
                putExtraSilence(intent)
                // Raum-/Gerätenamen bevorzugen (API 33+), gemeinsame Liste mit dem Overlay
                SttBias.applyTo(intent)
                try {
                    recognizer?.startListening(intent)
                } catch (e: Exception) {
                    // Start gescheitert: aufräumen, damit kein halb gestarteter Erkenner das Mikrofon hält
                    stopInternal()
                    promise.reject("STT_START_FAILED", e.message, e)
                    return@runOnUiThread
                }
                promise.resolve(true)
            }
        } catch (e: Exception) {
            promise.reject("STT_START_FAILED", e.message, e)
        }
    }

    /**
     * „Fertig“: Aufnahme beenden, das Ergebnis kommt noch (onResults) — der Erkenner wird erst danach freigegeben.
     */
    @ReactMethod
    fun stopListening(promise: Promise) {
        UiThreadUtil.runOnUiThread {
            try { recognizer?.stopListening() } catch (_: Exception) { stopInternal() }
            promise.resolve(true)
        }
    }

    /**
     * Abbruch OHNE Ergebnis (Hintergrund, Unterbrechen, Tippen): Erkenner sofort zerstören. Danach kommt kein
     * onResults mehr — wichtig, damit im Hintergrund kein weiterer Chat-Aufruf entsteht.
     */
    @ReactMethod
    fun cancelListening(promise: Promise) {
        UiThreadUtil.runOnUiThread {
            stopInternal()
            promise.resolve(true)
        }
    }

    private fun putExtraSilence(intent: Intent) {
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
    }

    private fun stopInternal() {
        val r = recognizer
        recognizer = null
        try { r?.cancel() } catch (_: Exception) {}
        try { r?.destroy() } catch (_: Exception) {}
    }

    private fun buildListener() = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
            // Erkenner ist fertig → freigeben (Mikrofon nicht länger halten), dann melden
            UiThreadUtil.runOnUiThread { stopInternal() }
            emit("OfflineSTT.onResult", Arguments.createMap().apply { putString("text", text) })
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
            emit("OfflineSTT.onPartial", Arguments.createMap().apply { putString("text", text) })
        }
        override fun onError(error: Int) {
            UiThreadUtil.runOnUiThread { stopInternal() }
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
        try {
            if (!reactApplicationContext.hasActiveReactInstance()) return
            reactApplicationContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(event, params)
        } catch (e: Exception) {
            android.util.Log.w(NAME, "emit($event) fehlgeschlagen: ${e.message}")
        }
    }

    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}
}
