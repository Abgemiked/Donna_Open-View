package com.yourcompany.donna

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * SpeechDriver (A2 Increment 3b) — service-seitige Sprach-AUSGABE für die Live-Bubble.
 *
 * Hostet Donnas TTS-Ausgabe IM [LiveBubbleService] (ohne die Vollbild-`VoiceInputActivity`), komponiert aus
 * den bereits REINEN Bausteinen [SentenceSpeechPipeline] + [SentenceSynthesizer] + [TtsEngines]/[TtsHealth] und
 * der aus `VoiceInputActivity` ÜBERTRAGENEN (nicht verschobenen) TTS/MediaPlayer-Verdrahtung
 * (onInit/applyTtsSettings, utteranceListener, synthesizeViaServer/Piper, playAudio, speakSystem).
 *
 * NEU ggü. dem Overlay-Pfad (Review/Audit-Auflage 3b): **Audio-Fokus** (AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK —
 * duckt fremde Medien statt sie zu übersprechen; wird nach dem Sprechen wieder freigegeben) und
 * **ASSISTANT-AudioAttributes** (USAGE_ASSISTANT/CONTENT_TYPE_SPEECH) auf TTS- und MediaPlayer-Ausgabe.
 *
 * NICHT enthalten (spätere Increments): SSE `/chat` (3c), STT + Barge-in (3d). Reine Ausgabe.
 *
 * Alle öffentlichen Methoden vom Main-Thread rufen; [scope] läuft auf Dispatchers.Main.
 */
class SpeechDriver(private val context: Context) : TextToSpeech.OnInitListener {

    private val ui = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val prefs by lazy { context.getSharedPreferences(TtsEngines.PREFS, Context.MODE_PRIVATE) }
    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private val ttsWaiters = HashMap<String, CancellableContinuation<Boolean>>()
    private var utteranceSeq = 0
    private var mediaPlayer: android.media.MediaPlayer? = null
    private var pipeline: SentenceSpeechPipeline? = null

    private var focusRequest: AudioFocusRequest? = null
    @Volatile private var focusHeld = false
    /** Nach release() keine späten onInit-/speak-Pfade mehr (sonst TTS-Leak / nie freigegebener Fokus). */
    @Volatile private var released = false

    /** Assistenten-Routing (Samsung „KI-Stimme" / korrektes Ducking). */
    private val audioAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /**
     * Increment 3d: wird aufgerufen, sobald tatsächlich Audio läuft (MediaPlayer gestartet / System-TTS
     * onStart) — Hook für den [ConversationController], um den Barge-in-Detektor genau dann zu starten.
     */
    var onAudioStarted: (() -> Unit)? = null

    init {
        // System-TTS asynchron initialisieren; onInit setzt ttsReady. Früh starten, damit die erste
        // Ausgabe nicht wartet. Die Engine-Kette (Edge/Piper) läuft auch ohne System-TTS.
        tts = TextToSpeech(context, this)
    }

    // ── TTS-Init ─────────────────────────────────────────────────────────────
    override fun onInit(status: Int) {
        if (released) return // Init-Callback kam nach release() — keine neue Engine mehr anlegen (Leak-Schutz).
        if (status == TextToSpeech.SUCCESS) {
            applyTtsSettings()
        } else {
            tts?.shutdown()
            tts = TextToSpeech(context, { s2 -> if (!released && s2 == TextToSpeech.SUCCESS) applyTtsSettings() }, "com.google.android.tts")
        }
    }

    private fun applyTtsSettings() {
        val engine = tts ?: return
        engine.language = Locale.GERMAN
        val voices = engine.voices
        val best = voices?.firstOrNull { it.name == "de-de-x-nfh-network" }
            ?: voices?.firstOrNull { it.name == "de-de-x-nfh-local" }
            ?: voices?.firstOrNull { it.name == "de-de-x-deb-network" }
            ?: voices?.firstOrNull { it.locale.language == "de" }
        best?.let { engine.voice = it }
        engine.setPitch(0.92f)
        engine.setSpeechRate(0.93f)
        try { engine.setAudioAttributes(audioAttrs) } catch (_: Exception) {}
        engine.setOnUtteranceProgressListener(utteranceListener)
        ttsReady = true
    }

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) { ui.post { onAudioStarted?.invoke() } }
        override fun onDone(utteranceId: String?) = completeUtterance(utteranceId, true)
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = completeUtterance(utteranceId, false)
        override fun onError(utteranceId: String?, errorCode: Int) = completeUtterance(utteranceId, false)
        override fun onStop(utteranceId: String?, interrupted: Boolean) = completeUtterance(utteranceId, !interrupted)
    }

    private fun completeUtterance(id: String?, spoke: Boolean) {
        if (id == null) return
        ui.post {
            val cont = ttsWaiters.remove(id)
            if (cont != null && cont.isActive) cont.resume(spoke)
        }
    }

    // ── Öffentliche API ───────────────────────────────────────────────────────
    /** Spricht [text] als einen Satz laut (Engine-Kette Edge/Piper → System). Bricht laufende Ausgabe ab. */
    fun speak(text: String) {
        if (released) return
        // Wie im Overlay-Pfad: VOR enqueue bereinigen (Markdown/Emoji) — sonst bekämen Edge/Piper Rohtext.
        val clean = SpeechText.cleanForSpeech(text)
        if (clean.isEmpty()) return
        stopPlayback() // laufende Ausgabe beenden, Fokus HALTEN (kein Ducking-Flattern beim Neustart)
        requestFocus()
        val p = SentenceSpeechPipeline(
            scope = scope,
            synthesize = { sentenceSynth.synthesize(it) },
            play = { playAudio(it) },
            fallbackSpeak = { speakSystem(it) },
            onFinished = { _ -> abandonFocus() },
        )
        pipeline = p
        p.enqueue(clean)
        p.finish()
    }

    /** Ausgabe stoppen, Audio-Fokus ABER halten (für nahtlosen Neustart in [speak]). */
    private fun stopPlayback() {
        pipeline?.stop()
        pipeline = null
        try { tts?.stop() } catch (_: Exception) {}
        try { mediaPlayer?.stop() } catch (_: Exception) {}
    }

    /** Laufende Ausgabe sofort stoppen + Audio-Fokus freigeben (Panel zu, Interim-Loop, Service-Stopp). */
    fun stop() {
        stopPlayback()
        abandonFocus()
    }

    /** Endgültig freigeben (Service onDestroy). */
    fun release() {
        released = true
        stop()
        try { scope.cancel() } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
        ttsReady = false
    }

    // ── Engine-Zugänge für den ConversationController (Increment 3c/3d) ─────────
    // Der Controller baut eine eigene SentenceSpeechPipeline pro Antwort-Turn und treibt sie inkrementell
    // (enqueue pro SSE-Delta), nutzt dafür aber die hier gekapselte Synthese/Wiedergabe + Audio-Fokus.
    internal fun acquireFocus() = requestFocus()
    internal fun releaseFocus() = abandonFocus()
    /** Laufende Wiedergabe stoppen, Audio-Fokus HALTEN (Fokus-Freigabe steuert der Controller am Turn-Ende). */
    internal fun stopPlaybackKeepFocus() = stopPlayback()
    internal suspend fun synthesizeSentence(text: String): ByteArray? = sentenceSynth.synthesize(text)
    internal suspend fun playSentence(bytes: ByteArray) = playAudio(bytes)
    internal suspend fun speakSystemSentence(text: String): Boolean = speakSystem(text)

    // ── Audio-Fokus (transient, duckt fremde Medien statt sie zu übersprechen) ──
    private fun requestFocus() {
        if (focusHeld) return
        focusHeld = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(audioAttrs)
                    .build()
                focusRequest = req
                audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) ==
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun abandonFocus() {
        if (!focusHeld) return
        focusHeld = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION") audioManager.abandonAudioFocus(null)
            }
        } catch (_: Exception) {}
        focusRequest = null
    }

    // ── Synthese + Wiedergabe (aus VoiceInputActivity übertragen) ───────────────
    private val sentenceSynth by lazy {
        val backends = HashMap<String, suspend (String) -> ByteArray?>()
        backends[TtsEngines.EDGE] = { s -> synthesizeViaServer(s) }
        backends[TtsEngines.PIPER] = { s -> synthesizeViaPiper(s) }
        SentenceSynthesizer(
            order = { TtsEngines.order(prefs.getString(TtsEngines.PREF_ENGINE, TtsEngines.DEFAULT)) },
            backends = backends,
            isUsable = { engine -> if (engine == TtsEngines.EDGE) !TtsHealth.edge.isDead() else true },
            onSuccess = { engine -> if (engine == TtsEngines.EDGE) TtsHealth.edge.markSuccess() },
            onFailure = { engine -> if (engine == TtsEngines.EDGE) TtsHealth.edge.markFailed() },
        )
    }

    /** Edge-TTS (Backend) für EINEN Satz — POST /tts?voice=… → MP3. Kurze Timeouts, wirft bei Fehler. */
    private suspend fun synthesizeViaServer(text: String): ByteArray? = withContext(Dispatchers.IO) {
        val voice = TtsEngines.normalizeEdgeVoice(prefs.getString(TtsEngines.PREF_EDGE_VOICE, null))
        val url = URL(BuildConfig.DONNA_API_URL + "/tts?voice=" + voice)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer ${TokenStore.getToken(context) ?: ""}")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "audio/mpeg")
            connectTimeout = EDGE_CONNECT_TIMEOUT_MS
            readTimeout = EDGE_READ_TIMEOUT_MS
            doOutput = true
        }
        try {
            val body = """{"text":${JSONObject.quote(text)},"was_voice_input":true}"""
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code == 204) return@withContext null
            if (code != 200) throw IOException("HTTP $code")
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    /** Piper-lokal für EINEN Satz → WAV-Bytes. Wirft, wenn das Modell nicht bereit ist. */
    private suspend fun synthesizeViaPiper(text: String): ByteArray? = withContext(Dispatchers.Default) {
        if (!PiperTTSModule.isEngineReady()) PiperTTSModule.ensureLoadedFromDisk(context.applicationContext.filesDir)
        if (!PiperTTSModule.isEngineReady()) throw IOException("Piper-Modell nicht bereit")
        PiperTTSModule.synthesizeWav(text)
    }

    /** Spielt einen Satz (MP3 Edge / WAV Piper) ab; kehrt zurück, wenn er WIRKLICH zu Ende ist. */
    private suspend fun playAudio(bytes: ByteArray) {
        val tmpFile = File(context.cacheDir, "donna_bubble_tts_${System.nanoTime()}.${WavEncoder.extensionFor(bytes)}")
        var player: android.media.MediaPlayer? = null
        try {
            withContext(Dispatchers.IO) { FileOutputStream(tmpFile).use { it.write(bytes) } }
            val mp = android.media.MediaPlayer()
            player = mp
            mediaPlayer = mp
            try { mp.setAudioAttributes(audioAttrs) } catch (_: Exception) {}
            suspendCancellableCoroutine<Unit> { cont ->
                var cancelled = false
                val watchdog = Runnable {
                    if (cont.isActive) cont.resumeWithException(IOException("Wiedergabe-Zeitüberschreitung"))
                }
                ui.postDelayed(watchdog, 8_000L)
                mp.setDataSource(tmpFile.absolutePath)
                mp.setOnPreparedListener {
                    try {
                        if (!cancelled) {
                            ui.removeCallbacks(watchdog)
                            ui.postDelayed(watchdog, it.duration.coerceAtLeast(1_000) + 4_000L)
                            it.start()
                            ui.post { onAudioStarted?.invoke() }
                        }
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }
                mp.setOnErrorListener { _, what, extra ->
                    if (cont.isActive) cont.resumeWithException(IOException("MediaPlayer-Fehler $what/$extra"))
                    true
                }
                cont.invokeOnCancellation {
                    cancelled = true
                    ui.removeCallbacks(watchdog)
                    try { mp.stop() } catch (_: Exception) {}
                }
                mp.setOnCompletionListener { ui.removeCallbacks(watchdog); if (cont.isActive) cont.resume(Unit) }
                mp.prepareAsync()
            }
        } finally {
            if (mediaPlayer === player) mediaPlayer = null
            try { player?.release() } catch (_: Exception) {}
            tmpFile.delete()
        }
    }

    /** System-TTS (Offline-Fallback). true = es war Ton zu hören. */
    private suspend fun speakSystem(text: String): Boolean {
        val engine = tts
        if (!ttsReady || engine == null) return false
        val clean = SpeechText.cleanForSpeech(text)
        if (clean.isEmpty()) return false
        val id = "donna_bubble_${++utteranceSeq}"
        val watchdogMs = 4_000L + clean.length * 90L
        val watchdog = Runnable {
            val cont = ttsWaiters.remove(id)
            if (cont != null && cont.isActive) {
                try { engine.stop() } catch (_: Exception) {}
                cont.resume(false)
            }
        }
        try {
            return suspendCancellableCoroutine<Boolean> { cont ->
                ttsWaiters[id] = cont
                cont.invokeOnCancellation {
                    ttsWaiters.remove(id)
                    try { engine.stop() } catch (_: Exception) {}
                }
                if (engine.speak(clean, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) {
                    ttsWaiters.remove(id)
                    if (cont.isActive) cont.resume(false)
                } else {
                    ui.postDelayed(watchdog, watchdogMs)
                }
            }
        } finally {
            ui.removeCallbacks(watchdog)
        }
    }

    companion object {
        /** Edge-TTS pro Satz: schnell oder gar nicht (danach Piper-lokal). */
        private const val EDGE_CONNECT_TIMEOUT_MS = 1_500
        private const val EDGE_READ_TIMEOUT_MS = 3_000
    }
}
