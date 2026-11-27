package com.yourcompany.donna.idea

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.yourcompany.donna.ContinuationDecision
import com.yourcompany.donna.SilenceProfile
import com.yourcompany.donna.SpeechCollector
import com.yourcompany.donna.SpeechContinuation
import com.yourcompany.donna.SttBias
import com.yourcompany.donna.TtsEngines

/**
 * #243: Diktat der Ideen-Erfassung mit Donnas eigener Aufnahme statt des Google-Dialogs. Gleiche Bausteine wie das
 * Overlay ([com.yourcompany.donna.VoiceInputActivity]): `SpeechRecognizer` mit Teilergebnissen, Sprechpausen aus dem
 * [SilenceProfile] (App-Einstellung), Bevorzugung von Raum-/Gerätenamen ([SttBias]) und das Nachhören nach Denkpausen
 * aus #226 ([SpeechCollector]/[SpeechContinuation]). Kein Zustandsautomat des Overlays: hier gibt es nur „hört zu“
 * und „fertig“. Läuft komplett auf dem Hauptthread. Nie Inhalte loggen.
 *
 * Ablauf: [start] → Teilergebnisse an [Listener.onPartial] → Ergebnis; je nach Text kurz weiterhören (Nachhören),
 * bei echter Stille gesamten Text an [Listener.onFinal]. [stop] = „Fertig“ (Gehörtes wird übernommen),
 * [cancel] = verwerfen (Activity verlässt den Vordergrund).
 */
class IdeaDictation(private val context: Context, private val listener: Listener) {
    interface Listener {
        fun onListening(active: Boolean)
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
    }

    private val ui = Handler(Looper.getMainLooper())
    private val collector = SpeechCollector()
    private val prefs = context.getSharedPreferences(TtsEngines.PREFS, Context.MODE_PRIVATE)
    private var recognizer: SpeechRecognizer? = null
    /** Erhöht bei jedem Neustart/Ende: Rückrufe eines alten Erkenners verfallen. */
    private var generation = 0
    private var heardSpeech = false
    private var lastPartial = ""
    private var pendingGraceMs = 0L
    private var triedOffline = false
    private var userStop = false

    var active = false
        private set

    private val graceRunnable = Runnable { finishWith(collector.take()) }
    private val guardRunnable = Runnable { finishWith(collector.takeWith(lastPartial)) }
    private val noSpeechRunnable = Runnable {
        if (active && !heardSpeech && !collector.hasPending) {
            end()
            listener.onError(IdeaDictationLogic.TEXT_NOTHING)
        }
    }
    private val maxRunnable = Runnable { stop() }

    private fun profile(): SilenceProfile = SilenceProfile.read(prefs)

    /** Startet das Diktat (Mikrofonrecht muss vorher erteilt sein). */
    fun start() {
        if (active) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            listener.onError(IdeaDictationLogic.TEXT_UNAVAILABLE)
            return
        }
        active = true
        userStop = false
        triedOffline = false
        collector.reset()
        listener.onListening(true)
        begin(continuation = false, preferOffline = false)
    }

    /** „Fertig“: im Nachlauf-Fenster sofort übernehmen, sonst den Erkenner das Ergebnis liefern lassen. */
    fun stop() {
        if (!active) return
        userStop = true
        if (collector.hasPending && lastPartial.isBlank()) {
            finishWith(collector.take())
            return
        }
        try { recognizer?.stopListening() } catch (_: Exception) {}
        ui.removeCallbacks(guardRunnable)
        ui.postDelayed(guardRunnable, SpeechContinuation.RESULT_GUARD_MS)
    }

    /** Verwerfen ohne Ergebnis (Activity pausiert/geschlossen). */
    fun cancel() {
        if (!active) return
        collector.reset()
        end()
    }

    private fun clearTimers() {
        ui.removeCallbacks(graceRunnable)
        ui.removeCallbacks(guardRunnable)
        ui.removeCallbacks(noSpeechRunnable)
        ui.removeCallbacks(maxRunnable)
    }

    private fun end() {
        active = false
        generation++
        clearTimers()
        pendingGraceMs = 0L
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        listener.onListening(false)
    }

    private fun finishWith(text: String) {
        if (!active) return
        val stoppedByUser = userStop
        end()
        val t = IdeaDictationLogic.salvage(text)
        when {
            t != null -> listener.onFinal(t)
            !stoppedByUser -> listener.onError(IdeaDictationLogic.TEXT_NOTHING)
        }
    }

    private fun begin(continuation: Boolean, preferOffline: Boolean) {
        try { recognizer?.destroy() } catch (_: Exception) {}
        generation++
        lastPartial = ""
        ui.removeCallbacks(guardRunnable)
        ui.removeCallbacks(noSpeechRunnable)
        ui.removeCallbacks(maxRunnable)
        if (!continuation) {
            heardSpeech = false
            ui.postDelayed(noSpeechRunnable, IdeaDictationLogic.NO_SPEECH_MS)
        }
        val maxMs = if (continuation) minOf(IdeaDictationLogic.MAX_SEGMENT_MS, collector.remainingMs(SystemClock.uptimeMillis()))
        else IdeaDictationLogic.MAX_SEGMENT_MS
        ui.postDelayed(maxRunnable, maxMs)
        val silence = profile()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silence.completeMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silence.possiblyCompleteMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, silence.minimumMs)
            if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        SttBias.applyTo(intent)
        try {
            // erst zuweisen, dann starten: wirft startListening, räumt end()/begin() den Erkenner trotzdem ab
            val r = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = r
            r.setRecognitionListener(recognitionListener(generation))
            r.startListening(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Erkenner nicht gestartet: ${e.javaClass.simpleName}")
            try { recognizer?.destroy() } catch (_: Exception) {}
            recognizer = null
            val salvaged = collector.take()
            if (salvaged.isNotBlank()) finishWith(salvaged) else {
                end()
                listener.onError(IdeaDictationLogic.TEXT_HICCUP)
            }
        }
    }

    private fun recognitionListener(gen: Int) = object : RecognitionListener {
        fun stale() = gen != generation || !active

        override fun onReadyForSpeech(params: Bundle?) {
            if (stale()) return
            // #226: Nachlauf-Fenster zählt ab „bereit“
            if (pendingGraceMs > 0L && collector.hasPending) {
                ui.removeCallbacks(graceRunnable)
                ui.postDelayed(graceRunnable, pendingGraceMs)
                pendingGraceMs = 0L
            }
        }

        override fun onBeginningOfSpeech() {
            if (stale()) return
            heardSpeech = true
            ui.removeCallbacks(noSpeechRunnable)
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            if (stale()) return
            // Sicherung, falls kein Ergebnis mehr kommt: Gehörtes übernehmen
            ui.removeCallbacks(guardRunnable)
            ui.postDelayed(guardRunnable, SpeechContinuation.RESULT_GUARD_MS)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (stale()) return
            val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
            if (partial.isNotBlank()) {
                heardSpeech = true
                lastPartial = partial
                ui.removeCallbacks(noSpeechRunnable)
                // Mike spricht im Nachlauf-Fenster weiter → Fenster stoppen, Ergebnis dieses Abschnitts abwarten
                ui.removeCallbacks(graceRunnable)
                pendingGraceMs = 0L
            }
            listener.onPartial(collector.display(partial))
        }

        override fun onResults(results: Bundle?) {
            if (stale()) return
            generation++ // Ergebnis verbraucht
            clearTimers()
            // leeres Endergebnis trotz Teilergebnis: das zuletzt Gehörte nicht verwerfen
            val raw = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?.takeIf { it.isNotBlank() } ?: lastPartial
            val decision = collector.offer(raw, SystemClock.uptimeMillis(), profile(), allowContinue = !userStop)
            if (decision is ContinuationDecision.Continue) {
                pendingGraceMs = decision.graceMs
                ui.postDelayed(graceRunnable, decision.graceMs + SpeechContinuation.READY_ALLOWANCE_MS)
                listener.onPartial(collector.pending)
                ui.post { if (active && collector.hasPending) begin(continuation = true, preferOffline = triedOffline) }
                return
            }
            finishWith((decision as ContinuationDecision.Finish).text)
        }

        override fun onError(error: Int) {
            if (stale()) return
            generation++
            clearTimers()
            // Fehler/Stille im Nachhören = fertig → Gesammeltes übernehmen (keine Meldung)
            val collected = collector.takeWith(lastPartial)
            if (collected.isNotBlank()) {
                finishWith(collected)
                return
            }
            if (!userStop && IdeaDictationLogic.shouldRetryOffline(error, triedOffline)) {
                triedOffline = true
                begin(continuation = false, preferOffline = true)
                return
            }
            val stoppedByUser = userStop
            end()
            if (!stoppedByUser || error == IdeaDictationLogic.ERROR_INSUFFICIENT_PERMISSIONS) {
                listener.onError(IdeaDictationLogic.errorText(error))
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    companion object {
        private const val TAG = "IdeaDictation"
    }
}
