package com.yourcompany.donna

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

    // #226: Nachhören nach dem Erkenner-Ende (Logik: SpeechContinuation/SpeechCollector, JVM-getestet). Der
    // JS-Chat bekommt weiterhin genau EIN onResult je Äußerung — nur eben erst nach echter Stille.
    private val ui = Handler(Looper.getMainLooper())
    private val collector = SpeechCollector()
    private var locale = "de-DE"
    /** „Fertig“ getippt: kein Nachhören mehr, das nächste Ergebnis wird gesendet. */
    private var stopRequested = false
    /** Jeder Erkenner gehört zu einer Generation; Callbacks eines verworfenen Erkenners verfallen. */
    private var generation = 0
    private val graceRunnable = Runnable { onGraceElapsed() }
    /** Nachlauf-Fenster, das ab „Erkenner bereit“ zählt; 0 = schon gesetzt. */
    private var pendingGraceMs = 0L
    /** Letztes nicht-leeres Teilergebnis des laufenden Abschnitts. */
    private var lastPartial = ""

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
                ui.removeCallbacks(graceRunnable)
                collector.reset()
                stopRequested = false
                this.locale = locale
                val err = startRecognizer()
                if (err == null) promise.resolve(true) else promise.reject(err.first, err.second.message, err.second)
            }
        } catch (e: Exception) {
            promise.reject("STT_START_FAILED", e.message, e)
        }
    }

    /**
     * Erzeugt und startet einen Erkenner (UI-Thread). null = gestartet; sonst (Fehlercode, Ausnahme) — dann ist
     * bereits aufgeräumt. Die Erzeugung ist ABGESICHERT: eine Ausnahme auf dem UI-Thread wäre sonst ein App-Absturz.
     */
    private fun startRecognizer(): Pair<String, Throwable>? {
        val ctx = reactApplicationContext
        generation++
        lastPartial = ""
        try {
            recognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
            } else {
                SpeechRecognizer.createSpeechRecognizer(ctx)
            }
            recognizer?.setRecognitionListener(buildListener(generation))
        } catch (e: Throwable) {
            stopInternal()
            return "STT_CREATE_FAILED" to e
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        // Großzügige Sprechpause (Denkpausen), wie im Overlay — #226: dasselbe Profil (Kurz/Normal/Lang)
        putExtraSilence(intent)
        // Raum-/Gerätenamen bevorzugen (API 33+), gemeinsame Liste mit dem Overlay
        SttBias.applyTo(intent)
        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            // Start gescheitert: aufräumen, damit kein halb gestarteter Erkenner das Mikrofon hält
            stopInternal()
            return "STT_START_FAILED" to e
        }
        return null
    }

    /**
     * „Fertig“: Aufnahme beenden, das Ergebnis kommt noch (onResults) — der Erkenner wird erst danach freigegeben.
     * #226: Im Nachhören beendet „Fertig“ das Nachlauf-Fenster; der gesammelte Text wird gesendet.
     */
    @ReactMethod
    fun stopListening(promise: Promise) {
        UiThreadUtil.runOnUiThread {
            stopRequested = true
            ui.removeCallbacks(graceRunnable)
            pendingGraceMs = 0L
            if (collector.hasPending && (recognizer == null || lastPartial.isBlank())) {
                // Im Nachhören ohne neue Sprache: nicht auf den frischen Erkenner warten, Gesammeltes sofort senden
                generation++
                stopInternal()
                emitResult(collector.take())
            } else {
                try { recognizer?.stopListening() } catch (_: Exception) {
                    generation++
                    stopInternal()
                    if (collector.hasPending) emitResult(collector.takeWith(lastPartial))
                }
            }
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
            ui.removeCallbacks(graceRunnable)
            collector.reset()
            pendingGraceMs = 0L
            generation++
            stopInternal()
            promise.resolve(true)
        }
    }

    private fun profile(): SilenceProfile =
        SilenceProfile.read(reactApplicationContext.getSharedPreferences(TtsEngines.PREFS, Context.MODE_PRIVATE))

    /** #226: Sprechpause aus dem eingestellten Profil (früher hart 1800/1500 ms, unabhängig von der Einstellung). */
    private fun putExtraSilence(intent: Intent) {
        val silence = profile()
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silence.completeMs)
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silence.possiblyCompleteMs)
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, silence.minimumMs)
    }

    /** #226: Nachlauf-Fenster ohne neue Sprache abgelaufen → Nachhör-Erkenner verwerfen, gesammelten Text senden. */
    private fun onGraceElapsed() {
        if (!collector.hasPending) return
        generation++
        stopInternal()
        emitResult(collector.take())
    }

    private fun emitResult(text: String) {
        emit("OfflineSTT.onResult", Arguments.createMap().apply { putString("text", text) })
    }

    private fun stopInternal() {
        val r = recognizer
        recognizer = null
        try { r?.cancel() } catch (_: Exception) {}
        try { r?.destroy() } catch (_: Exception) {}
    }

    private fun buildListener(gen: Int) = object : RecognitionListener {
        // Callbacks laufen auf dem Haupt-Thread (Erkenner dort erzeugt) → Zustand ohne Sperren
        fun stale() = gen != generation
        override fun onResults(results: Bundle?) {
            if (stale()) return
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
            ui.removeCallbacks(graceRunnable)
            // Erkenner ist fertig → freigeben (Mikrofon nicht länger halten)
            stopInternal()
            // #226: Abschnitt sammeln; bei längerem/unvollständigem Satz nahtlos weiterhören statt sofort zu melden
            val d = collector.offer(text, SystemClock.uptimeMillis(), profile(), allowContinue = !stopRequested)
            if (d is ContinuationDecision.Continue) {
                emit("OfflineSTT.onPartial", Arguments.createMap().apply { putString("text", collector.pending) })
                // Fenster zählt ab „Erkenner bereit“ (onReadyForSpeech); Sicherung, falls er nie bereit wird
                pendingGraceMs = d.graceMs
                ui.postDelayed(graceRunnable, d.graceMs + SpeechContinuation.READY_ALLOWANCE_MS)
                // Neustart misslungen → nicht hängen bleiben: Gesammeltes sofort senden
                if (startRecognizer() != null) {
                    ui.removeCallbacks(graceRunnable)
                    onGraceElapsed()
                }
            } else {
                emitResult((d as ContinuationDecision.Finish).text)
            }
        }
        override fun onPartialResults(partialResults: Bundle?) {
            if (stale()) return
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
            // #226: Mike spricht im Nachlauf-Fenster weiter → Fenster stoppen, Ergebnis dieses Abschnitts abwarten
            if (text.isNotBlank()) {
                lastPartial = text
                ui.removeCallbacks(graceRunnable)
                pendingGraceMs = 0L
            }
            emit("OfflineSTT.onPartial", Arguments.createMap().apply { putString("text", collector.display(text)) })
        }
        override fun onError(error: Int) {
            if (stale()) return
            ui.removeCallbacks(graceRunnable)
            stopInternal()
            // #226: Fehler/Stille im Nachhören = Mike ist fertig → gesammelten Text senden (keine Fehlermeldung)
            if (collector.hasPending) {
                emitResult(collector.takeWith(lastPartial))
                return
            }
            emit("OfflineSTT.onError", Arguments.createMap().apply { putInt("code", error) })
        }
        override fun onReadyForSpeech(p: Bundle?) {
            if (stale() || pendingGraceMs <= 0L || !collector.hasPending) return
            // #226: Nachhör-Erkenner bereit → jetzt beginnt das eigentliche Nachlauf-Fenster
            ui.removeCallbacks(graceRunnable)
            ui.postDelayed(graceRunnable, pendingGraceMs)
            pendingGraceMs = 0L
        }
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
