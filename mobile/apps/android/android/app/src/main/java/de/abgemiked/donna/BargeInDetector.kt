package com.yourcompany.donna

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * Voice-Barge-in: lauscht WÄHREND Donna spricht mit offenem Mikrofon (Quelle VOICE_COMMUNICATION + aktiver
 * [AcousticEchoCanceler]) auf Mikes Stimme. Erkennt [BargeInVad] anhaltend lauteres als Donnas Echo, wird das
 * Mikrofon sofort freigegeben und [onBargeIn] auf dem Haupt-Thread gerufen — der Aufrufer stoppt die Ausgabe und
 * startet den normalen Spracherkenner.
 *
 * **Standardmäßig AN (Opt-out)** — Voice-Batch: Mike kann es per langem Tippen auf den Gesprächs-Chip abschalten.
 * Dafür ist der Detektor hart gegen Selbst-Auslösung: AEC Pflicht, Lernphase für Donnas Echo, Sprachband-Pegel +
 * Stimm-Ähnlichkeit + Umgebungsgeräusch-Boden ([BargeInVad], [SpeechFrameAnalyzer]), Echo-Strikes (nach mehreren
 * Fehlauslösern TEMPORÄRE, selbst-erholende Pause — kein Dauer-Aus mehr), 90-s-Hard-Cap, keine Auslösung im
 * Hintergrund/bei gesperrtem Gerät (Aufrufer).
 *
 * Sicherheitsnetze:
 *  - [start] liefert NUR true, wenn alles wirklich läuft (AEC aktiv UND Aufnahme im Zustand RECORDING); sonst
 *    sauberer Fallback (false → Mikrofon erst nach dem TTS-Ende) und ein Backoff, damit dieser Turn nicht erneut probiert.
 *  - Hard-Cap [MAX_RUNTIME_MS]: der Detektor stoppt sich selbst (kein Leck, wenn ein Aufrufer das Stoppen vergisst).
 *  - Start/Stop sind synchronisiert und tragen eine Lauf-ID: ein verspäteter Auslöser eines alten Laufs meldet nichts.
 */
class BargeInDetector(
    private val context: Context,
    private val onBargeIn: () -> Unit,
) {
    companion object {
        private const val TAG = "BargeInDetector"
        private const val SAMPLE_RATE = 16_000
        private const val FRAME_MS = 20
        private const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000

        /** Längste Laufzeit am Stück (länger spricht keine Antwort; Schutz gegen vergessenes Stoppen). */
        const val MAX_RUNTIME_MS = 90_000L

        /** Nach einem Init-Fehler so lange nicht erneut versuchen. */
        const val INIT_BACKOFF_MS = 30_000L

        /** MANUELLER Aus-Schalter (Chip-Langdruck / RN-Bridge). NICHT mehr durch Echo-Strikes gesetzt. */
        @Volatile var disabledForProcess: Boolean = false

        @Volatile private var initFailedUntil: Long = 0L

        /**
         * Echo-Selbstunterbrechungen: löst bei zu vielen Fehlauslösern eine TEMPORÄRE Pause aus (selbst-erholend).
         * Monotone Uhr (SystemClock.elapsedRealtime) — unempfindlich gegen Wanduhr-Sprünge (NTP/Zeitstellen).
         */
        val echoStrikes = EchoStrikes(clock = { android.os.SystemClock.elapsedRealtime() })
    }

    /** Alles, was zu EINEM Lauf gehört. */
    private class Run(
        val id: Long,
        val record: AudioRecord,
        val aec: AcousticEchoCanceler,
        val ns: NoiseSuppressor?,
    ) {
        @Volatile var active = true
        var thread: Thread? = null
    }

    private val main = Handler(Looper.getMainLooper())
    // Eine Sperre nur für den Zustand (kurz gehalten); Warten auf den Thread passiert NIE unter der Sperre
    private val lock = Any()
    private var run: Run? = null
    private var nextRunId = 0L

    val isRunning: Boolean get() = synchronized(lock) { run?.active == true }

    /** Voraussetzungen: Berechtigung + Echo-Unterdrücker + nicht abgeschaltet + kein Backoff. */
    fun isSupported(): Boolean =
        !disabledForProcess &&
            SystemClock.elapsedRealtime() >= initFailedUntil &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            AcousticEchoCanceler.isAvailable()

    /**
     * @return true NUR wenn der Detektor wirklich läuft (AEC aktiviert, Aufnahme gestartet);
     *   false → Fallback (Mikrofon erst nach dem TTS-Ende), kein Barge-in per Stimme.
     */
    fun start(): Boolean = synchronized(lock) {
        if (run?.active == true) return true
        val supported = isSupported()
        android.util.Log.i(TAG, "start() aufgerufen, supported=$supported")   // DIAGNOSE Gerätetest
        if (!supported) return false
        val r = try { init() } catch (e: Exception) {
            android.util.Log.w(TAG, "Init fehlgeschlagen: ${e.message}")
            null
        }
        if (r == null) {
            initFailedUntil = SystemClock.elapsedRealtime() + INIT_BACKOFF_MS   // diesen Turn nicht erneut probieren
            return false
        }
        android.util.Log.i(TAG, "Detektor gestartet, recordingState=${r.record.recordingState} aecEnabled=${r.aec.enabled}")
        run = r
        r.thread = Thread({ loop(r) }, "DonnaBargeIn").also { it.isDaemon = true; it.start() }
        true
    }

    /** Stoppt und gibt das Mikrofon frei (wartet kurz, damit der Spracherkenner es sofort übernehmen kann). */
    fun stop() {
        val r: Run
        synchronized(lock) {
            r = run ?: return
            run = null
            r.active = false
        }
        val t = r.thread
        if (t != null && t !== Thread.currentThread()) {
            try { t.join(250) } catch (_: InterruptedException) {}
        }
        release(r)   // idempotent — falls die Schleife noch in read() hing
    }

    private fun init(): Run? {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, FRAME_SAMPLES * 2 * 4),
        )
        var aec: AcousticEchoCanceler? = null
        var ns: NoiseSuppressor? = null
        try {
            if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); return null }
            aec = AcousticEchoCanceler.create(record.audioSessionId)
            // Ohne WIRKLICH aktive Echo-Unterdrückung kein Voice-Barge-in
            if (aec == null || aec.setEnabled(true) != AudioEffect.SUCCESS || !aec.enabled) {
                aec?.release(); record.release(); return null
            }
            if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(record.audioSessionId)?.also { it.enabled = true }
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                try { record.stop() } catch (_: Exception) {}
                aec.release(); ns?.release(); record.release()
                return null
            }
            return Run(++nextRunId, record, aec, ns)
        } catch (e: Exception) {
            try { aec?.release() } catch (_: Exception) {}
            try { ns?.release() } catch (_: Exception) {}
            try { record.release() } catch (_: Exception) {}
            throw e
        }
    }

    private fun release(r: Run) {
        try { r.record.stop() } catch (_: Exception) {}
        try { r.aec.release() } catch (_: Exception) {}
        try { r.ns?.release() } catch (_: Exception) {}
        try { r.record.release() } catch (_: Exception) {}
    }

    private fun loop(r: Run) {
        var fired = false
        var maxDb = Double.NEGATIVE_INFINITY   // DIAGNOSE: liest der Detektor Stille (Concurrent-Capture) oder Pegel?
        var sawVoiceLike = false
        var frames = 0
        var maxThr = Double.NEGATIVE_INFINITY  // höchste Auslöse-Schwelle über den Lauf (Echo/Umgebung)
        var loudVoiceFrames = 0                // Frames mit db>thr && voiceLike (Auslöse-Kandidaten)
        var maxVoicedMs = 0                    // längste anhaltend aufgebaute Sprach-Dauer (vor sustain)
        var lastFrameLogAt = 0L
        var learnedEcho = Double.NEGATIVE_INFINITY  // v3: gelernte Echo-Spitze (für die End-Summary)
        var retainedFrames = 0                 // v3: Lern-Frames nach Onset-Skip / gesamt (Diagnose)
        var totalLearnFrames = 0
        val startedAt = SystemClock.elapsedRealtime()
        try {
            // Gerätetest-v2-Kalibrierung (Logcat 2026-11-02, Dauerreden-Analyse + Stufe-3-Review): Über 15 s
            // Dauerreden lief die Schwelle Mikes lauter werdendem Input davon (thr -16 → -7), bis nichts mehr
            // durchschlug; choppy delivery + voice=false-Peaks verhinderten zusätzlich, dass voicedMs 260ms erreichte.
            // Drei datengetriebene Fixes:
            //  • HAUPTHEBEL: echoPeak wird in der Lernphase gelernt und friert danach KONSTANT ein (weder nach oben
            //    noch nach unten) — Mikes Stimme hebt die gelernte Eva-Spitze NICHT → Schwelle läuft nicht weg, und
            //    bei schwankendem Echo sinkt sie nicht zum Tal (kein Selbst-Trigger). (Ein fester Schwellen-Clamp
            //    wäre fragil gewesen: er ließe dynamisches Eva-Echo über der Clamp-Grenze auslösen — Review-BLOCK.)
            //  • voiceRecencyMs = 200: Mikes laute voice=false-Peaks (AEC-Transienten) zählen, wenn in den letzten
            //    200 ms ein LAUTER voiceLike-Frame war — nur aus LAUTEN Stimm-Frames gesetzt (kein Bridge aus Echo).
            //  • sustainDecayDivisor = 4: Leaky-Sustain überbrückt Mikes echo-gecancelte Wortpausen (-60-Frames).
            // margin/ambientMargin 7, sustain 260ms bleiben. BargeInVad-Defaults unverändert.
            // Gerätetest v3 (2026-11-05, Bubble-Pfad): Die Lernphase lernt die Echo-Spitze jetzt als 70.-Perzentil
            // der Lern-Frames NACH dem Überspringen der lautesten Onset-Frames (erste 120ms) — nicht mehr als max()
            // eines einzelnen lauten TTS-Einschwing-Transienten (der im SpeechDriver-Pfad lauter ist → Schwelle
            // riss zu hoch, thr fror bei -10 ein, Barge-in feuerte nur beim Schreien). So landet thr tief genug.
            // sustainDecayDivisor BEWUSST bei 4 (NICHT auf 6 erhöht): Der Threshold-Fix behebt die Ursache der
            // maxVoicedMs-Aushungerung (zu hohe Schwelle → zu wenige laute Frames) bereits; ein langsamerer Abbau
            // würde nur das Selbst-Trigger-Fenster für WIEDERKEHRENDE Satz-Onset-Spikes vergrößern (Review-Rat v3).
            // GESUND im Log = maxThr ≲ -16 (= gelernter Peak ≲ -23, da thr = peak + 7) UND loudVoiceFrames > 0.
            // loudVoiceFrames == 0 trotz Reinredens → Peak noch zu hoch (learnedEcho/retained im End-Log prüfen).
            // GERÄTETEST-WACHPUNKT: Eva spricht mehrere Sätze, Mike STILL → darf NICHT feuern; learnedEcho/
            // loudVoiceFrames/maxVoicedMs im End-Log beobachten (wiederkehrende -18-Onsets sind das Restrisiko).
            val vad = BargeInVad(
                frameMs = FRAME_MS, marginDb = 7.0, ambientMarginDb = 7.0, sustainMs = 260,
                voiceRecencyMs = 200, sustainDecayDivisor = 4,
            )
            var learnLogged = false   // v3: gelernte Spitze genau einmal beim Lernphasen-Ende loggen
            // Sprachband-Pegel (Hochpass) + Stimm-Ähnlichkeit: Brummen/Rauschen/Lärm lösen nichts aus
            val analyzer = SpeechFrameAnalyzer(SAMPLE_RATE)
            val frame = ShortArray(FRAME_SAMPLES)
            while (r.active) {
                // Hard-Cap: nie länger als MAX_RUNTIME_MS offen halten
                if (SystemClock.elapsedRealtime() - startedAt > MAX_RUNTIME_MS) break
                val n = r.record.read(frame, 0, FRAME_SAMPLES)
                if (n <= 0) break
                frames++
                val a = analyzer.analyze(frame, n)
                val db = a.db.toDouble()
                if (db > maxDb) maxDb = db
                if (a.voiceLike) sawVoiceLike = true
                val thr = vad.thresholdDb
                if (thr > maxThr) maxThr = thr
                if (vad.isLearningDone && db > thr && a.voiceLike) loudVoiceFrames++
                // Pro-Frame-Diagnose, gedrosselt (max ~1 Zeile/250 ms, nur „interessante" Frames) — zeigt beim
                // nächsten Logcat die exakte Gate-Stelle (Schwelle vs. Pegel vs. aufgebaute Dauer).
                if (a.voiceLike || db > -20.0) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastFrameLogAt >= 250L) {
                        lastFrameLogAt = now
                        android.util.Log.i(
                            TAG,
                            "frame db=${"%.1f".format(db)} voice=${a.voiceLike} thr=${"%.1f".format(thr)} " +
                                "voicedMs=${vad.currentVoicedMs} learned=${vad.isLearningDone}",
                        )
                    }
                }
                val triggeredNow = vad.onFrame(a.db, a.voiceLike)
                if (vad.currentVoicedMs > maxVoicedMs) maxVoicedMs = vad.currentVoicedMs
                // v3: Sobald die Lernphase fertig ist, die gelernte Echo-Spitze + die Schwelle genau einmal loggen —
                // der Gerätetest sieht sofort, ob der v3-Fix griff (learnedEcho ≲ -23 → thr ≲ -16).
                if (vad.isLearningDone && !learnLogged) {
                    learnLogged = true
                    learnedEcho = vad.learnedEchoDb
                    retainedFrames = vad.retainedLearnFrames
                    totalLearnFrames = vad.totalLearnFrames
                    android.util.Log.i(
                        TAG,
                        "Lernphase fertig learnedEcho=${"%.1f".format(learnedEcho)} thr=${"%.1f".format(vad.thresholdDb)} " +
                            "retained=$retainedFrames/$totalLearnFrames",
                    )
                }
                if (triggeredNow) {
                    fired = true
                    android.util.Log.i(TAG, "VAD fired db=${"%.1f".format(db)} voiceLike=${a.voiceLike} thr=${"%.1f".format(thr)}")
                    break
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Detektor beendet: ${e.message}")
        } finally {
            android.util.Log.i(
                TAG,
                "Detektor beendet fired=$fired maxDb=${"%.1f".format(maxDb)} sawVoiceLike=$sawVoiceLike frames=$frames " +
                    "maxThr=${"%.1f".format(maxThr)} loudVoiceFrames=$loudVoiceFrames maxVoicedMs=$maxVoicedMs " +
                    "learnedEcho=${"%.1f".format(learnedEcho)} retained=$retainedFrames/$totalLearnFrames active=${r.active}",
            )
            val stillCurrent: Boolean
            synchronized(lock) {
                stillCurrent = run === r && r.active
                if (run === r) run = null
                r.active = false
            }
            release(r)
            // Erst NACH der Freigabe des Mikrofons melden, damit der Erkenner es sofort bekommt;
            // nur wenn dies noch der aktuelle Lauf ist (ein gestoppter/ersetzter Lauf meldet nichts)
            if (fired && stillCurrent) main.post { onBargeIn() }
        }
    }
}
