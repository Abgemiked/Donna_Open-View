package com.yourcompany.donna

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.speech.tts.TextToSpeech
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * TTSModule — TTS für React Native (Haupt-App).
 *
 * Voice-Batch: Server-TTS ist Edge-TTS (Backend /tts, audio/mpeg) — Engine "edge" im A/B-Test gegen Piper-lokal.
 *
 * speakViaServer(text, voice, promise):
 *   POST /tts?voice=… {text, was_voice_input: true}
 *   → 204 → promise.resolve("live_guard") — kein Audio
 *   → MP3 → temp-File → MediaPlayer.play() → "ok"
 *   → Fehler → "failed" (KEIN System-TTS: JS wechselt auf die andere Engine)
 * speakViaKokoro(text, promise): Legacy — wie oben, aber mit System-TTS-Fallback.
 *
 * speak(text): direkte System-TTS (interner Fallback, kein Netzwerk)
 * stop(): stoppt beides (Kokoro-Player + System-TTS)
 */
class TTSModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var systemTtsReady = false
    private var mediaPlayer: MediaPlayer? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    // Tracks the currently running speakViaKokoro coroutine — cancelled on new request
    private var ttsJob: Job? = null

    companion object {
        private const val API_BASE = "https://your-donna-instance.example.com"
        /** Backend-Limit pro /tts-Request (TTSRequest.text max_length=2000). */
        private const val SERVER_MAX_CHARS = 1_900
        /** Edge ist schnell oder gar nicht: lange Wartezeiten sind schlechter als der Wechsel zu Piper-lokal. */
        private const val SERVER_CONNECT_TIMEOUT_MS = 3_000
        private const val SERVER_READ_TIMEOUT_MS = 8_000

        // USAGE_ASSISTANT (API 26+) → Samsung One UI 8 „KI-Stimme"-Lautstärkeregler
        // Fallback auf USAGE_MEDIA für ältere Android-Versionen (nicht relevant für S25 Ultra)
        private val ASSISTANT_AUDIO_ATTRS: AudioAttributes =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            } else {
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            }
    }

    private val apiToken: String
        get() = TokenStore.getToken(reactApplicationContext) ?: ""

    init {
        // System-Default-Engine — auf Samsung = Samsung SMT mit natürlichen Stimmen
        tts = TextToSpeech(reactContext, this)
    }

    override fun getName() = "TTSModule"

    // ── System-TTS init ───────────────────────────────────────────────────────

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            applyVoiceSettings()
        } else {
            tts?.shutdown()
            tts = TextToSpeech(reactContext, { s ->
                if (s == TextToSpeech.SUCCESS) applyVoiceSettings()
            }, "com.google.android.tts")
        }
    }

    private fun applyVoiceSettings() {
        tts?.language = Locale.GERMAN
        val voices = tts?.voices
        // Samsung Neural TTS (com.samsung.SMT) Stimmen haben andere Namen als Google TTS.
        // Priorität: Samsung neural voices → Google neural voices → erste deutsche Stimme
        val best = voices?.firstOrNull { v -> v.name.startsWith("de-de-x-sms") }           // Samsung SMT
            ?: voices?.firstOrNull { v -> v.name.startsWith("de-DE-SMTf") }                // Samsung SMT alt
            ?: voices?.firstOrNull { v -> v.name == "de-de-x-nfh-network" }               // Google Neural
            ?: voices?.firstOrNull { v -> v.name == "de-de-x-nfh-local" }
            ?: voices?.firstOrNull { v -> v.name == "de-de-x-deb-network" }
            ?: voices?.firstOrNull { v -> v.locale.language == "de" && !v.isNetworkConnectionRequired }
            ?: voices?.firstOrNull { v -> v.locale.language == "de" }
        best?.let { tts?.voice = it }
        tts?.setPitch(0.92f)
        tts?.setSpeechRate(0.93f)
        // Echtes Ende der System-TTS melden (Auto-Relisten im Gesprächsmodus, Voice-Barge-in)
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { TtsEvents.emit(reactContext, "started") }
            override fun onDone(utteranceId: String?) { TtsEvents.emit(reactContext, "ended") }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { TtsEvents.emit(reactContext, "ended") }
            override fun onError(utteranceId: String?, errorCode: Int) { TtsEvents.emit(reactContext, "ended") }
            // onStop (Ersetzen/Stoppen) meldet bewusst nichts: der Aufrufer weiss es selbst
        })
        systemTtsReady = true
        android.util.Log.i("TTSModule", "TTS bereit — Stimme: ${best?.name ?: "default"}, Engine: ${tts?.defaultEngine}")
    }

    // ── Text-Cleaning (Markdown → Sprache) ───────────────────────────────────

    private fun cleanText(text: String): String = text
        .replace(Regex("\\*+"), "")
        .replace(Regex("#{1,6}\\s"), "")
        .replace(Regex("https?://\\S+"), "")
        .replace(Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)"), "$1")
        .replace("•", "")
        .trim()

    // ── Server-TTS (Edge-TTS ueber das Donna-Backend, POST /tts → audio/mpeg) ─

    /** Das Backend lehnt Texte > 2000 Zeichen ab (422); an der letzten Satzgrenze darunter kappen. */
    private fun trimForServer(clean: String): String {
        if (clean.length <= SERVER_MAX_CHARS) return clean
        val cut = clean.substring(0, SERVER_MAX_CHARS)
        val end = maxOf(cut.lastIndexOf(". "), cut.lastIndexOf("! "), cut.lastIndexOf("? "))
        return if (end > SERVER_MAX_CHARS / 2) cut.substring(0, end + 1) else cut
    }

    /** POST /tts → Audio-Bytes (MP3). null = HTTP 204 (nichts Sprechbares / Live-Guard). Wirft bei Fehlern. */
    private fun fetchServerAudio(clean: String, voice: String?): ByteArray? {
        val edgeVoice = TtsEngines.normalizeEdgeVoice(voice)
        val url = URL("$API_BASE/tts?voice=$edgeVoice")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $apiToken")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "audio/mpeg")
            connectTimeout = SERVER_CONNECT_TIMEOUT_MS
            readTimeout = SERVER_READ_TIMEOUT_MS
            doOutput = true
        }
        try {
            val body = """{"text":${org.json.JSONObject.quote(clean)},"was_voice_input":true}"""
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code == 204) return null
            if (code != 200) throw Exception("HTTP $code")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    /** Audio-Bytes in tmp-File schreiben (MediaPlayer braucht File oder URL) und abspielen; meldet started/ended. */
    private suspend fun playServerAudio(audioBytes: ByteArray) {
        val tmpFile = File(reactContext.cacheDir, "donna_tts_${System.currentTimeMillis()}.${WavEncoder.extensionFor(audioBytes)}")
        withContext(Dispatchers.IO) {
            FileOutputStream(tmpFile).use { it.write(audioBytes) }
        }

        val player = MediaPlayer()
        try {
            player.apply {
                setAudioAttributes(ASSISTANT_AUDIO_ATTRS)
                setDataSource(tmpFile.absolutePath)
                prepare()
                setOnCompletionListener {
                    it.release()
                    // nur wenn dies noch DER aktuelle Player ist (nicht durch eine neue Ausgabe ersetzt)
                    val current = mediaPlayer === it
                    if (current) mediaPlayer = null
                    tmpFile.delete()
                    if (current) TtsEvents.emit(reactContext, "ended")
                }
                setOnErrorListener { mp, _, _ ->
                    mp.release()
                    val current = mediaPlayer === mp
                    if (current) mediaPlayer = null
                    tmpFile.delete()
                    if (current) TtsEvents.emit(reactContext, "ended")
                    false
                }
            }
            mediaPlayer = player
            player.start()
        } catch (e: Exception) {
            // setDataSource/prepare/start geworfen (kaputtes Audio): Player freigeben, Temp-Datei loeschen —
            // sonst leckt jeder Fehlversuch einen nativen Player + eine Datei. Der Aufrufer meldet "failed".
            if (mediaPlayer === player) mediaPlayer = null
            try { player.release() } catch (_: Exception) {}
            tmpFile.delete()
            throw e
        }
        TtsEvents.emit(reactContext, "started")
    }

    /**
     * Edge-TTS (A/B-Engine "edge"): Text → Backend → MP3 → MediaPlayer. KEIN System-TTS-Fallback — der JS-Aufrufer
     * entscheidet (Piper-lokal als andere Engine, danach System-TTS).
     * Promise: "ok" = Audio läuft, "live_guard" = 204 (kein Audio), "failed" = Netz/Server/Wiedergabe fehlgeschlagen,
     * "cancelled" = unterbrochen (Stopp/neue Antwort) — der Aufrufer startet dann nichts mehr.
     */
    @ReactMethod
    fun speakViaServer(text: String, voice: String?, promise: Promise) {
        val clean = trimForServer(cleanText(text))
        if (clean.isEmpty()) { promise.resolve("empty"); return }

        // Laufenden TTS-Job + Wiedergabe abbrechen (neue Antwort = alte TTS sofort stoppen)
        ttsJob?.cancel()
        stopMediaPlayer()
        tts?.stop()

        ttsJob = scope.launch {
            try {
                val audioBytes = withContext(Dispatchers.IO) { fetchServerAudio(clean, voice) }
                if (audioBytes == null) {
                    promise.resolve("live_guard")
                    TtsEvents.emit(reactContext, "ended")   // kein Ton → Sprachausgabe ist „fertig“
                    return@launch
                }
                playServerAudio(audioBytes)
                promise.resolve("ok")
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Normaler Cancel (Stopp/neue Antwort): "cancelled" → die JS-Kette bricht ab (kein Fallback, kein Warten)
                promise.resolve("cancelled")
                throw e
            } catch (e: Exception) {
                android.util.Log.w("TTSModule", "Edge-TTS fehlgeschlagen: ${e.message}")
                promise.resolve("failed")
            }
        }
    }

    /**
     * Legacy-Pfad (DONNA-36): Server-TTS mit System-TTS als Fallback. Neue JS-Ketten nutzen [speakViaServer].
     * Auflösung: "ok" = Audio läuft, "live_guard" = 204-Antwort (kein Audio), "fallback" = System-TTS.
     */
    @ReactMethod
    fun speakViaKokoro(text: String, promise: Promise) {
        val clean = trimForServer(cleanText(text))
        if (clean.isEmpty()) { promise.resolve("empty"); return }

        ttsJob?.cancel()
        stopMediaPlayer()
        tts?.stop()

        ttsJob = scope.launch {
            try {
                val audioBytes = withContext(Dispatchers.IO) { fetchServerAudio(clean, null) }
                if (audioBytes == null) {
                    promise.resolve("live_guard")
                    TtsEvents.emit(reactContext, "ended")
                    return@launch
                }
                playServerAudio(audioBytes)
                promise.resolve("ok")
            } catch (e: kotlinx.coroutines.CancellationException) {
                promise.resolve("cancelled")
                throw e
            } catch (e: Exception) {
                android.util.Log.w("TTSModule", "Server-TTS fehlgeschlagen, Fallback: ${e.message}")
                speakSystemTts(clean)
                promise.resolve("fallback")
            }
        }
    }

    // ── System-TTS (Fallback / direkt) ───────────────────────────────────────

    /** Direkte System-TTS — wird intern als Fallback + von speakViaKokoro genutzt. */
    private fun speakSystemTts(cleanText: String) {
        if (!systemTtsReady || cleanText.isEmpty()) return
        // setAudioAttributes übernimmt das Routing vollständig — kein KEY_PARAM_STREAM nötig
        tts?.setAudioAttributes(ASSISTANT_AUDIO_ATTRS)
        tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "donna_main")
    }

    /** Exposed für direkten Aufruf (z. B. wenn Kokoro nicht gewünscht). */
    // ── Token Bridge (DONNA-103) ──────────────────────────────────────────────

    /**
     * Gibt den gespeicherten API-Token an die JS-Schicht zurück.
     * Wird beim App-Start aufgerufen um setApiToken() zu befüllen.
     */
    @ReactMethod
    fun getApiToken(promise: Promise) {
        val token = TokenStore.getToken(reactApplicationContext)
        if (token != null) {
            promise.resolve(token)
        } else {
            promise.resolve(null)
        }
    }

    @ReactMethod
    fun speak(text: String) {
        val clean = cleanText(text)
        if (clean.isEmpty()) return
        stopMediaPlayer()
        speakSystemTts(clean)
    }

    /**
     * DONNA-153 Phase 1: On-Device TTS via Samsung Neural TTS (SMT).
     * Kein Netzwerk, ~60ms Latenz. Promise resolved mit "ok" sobald Ausgabe startet.
     * Ersetzt speakViaKokoro() als Primärpfad wenn Samsung SMT verfügbar.
     */
    @ReactMethod
    fun speakOnDevice(text: String, promise: Promise) {
        val clean = cleanText(text)
        if (clean.isEmpty()) { promise.resolve("empty"); return }
        // Laufende TTS abbrechen (konsistent mit speakViaKokoro-Verhalten)
        ttsJob?.cancel()
        ttsJob = null
        stopMediaPlayer()
        tts?.stop()
        if (!systemTtsReady) {
            promise.reject("TTS_NOT_READY", "System TTS not initialized")
            return
        }
        speakSystemTts(clean)
        promise.resolve("ok")
    }

    /**
     * DONNA-153: Prüft ob On-Device TTS bereit ist.
     */
    @ReactMethod
    fun isOnDeviceReady(promise: Promise) {
        promise.resolve(systemTtsReady)
    }

    // ── Stop ─────────────────────────────────────────────────────────────────

    @ReactMethod
    fun stop() {
        ttsJob?.cancel()
        ttsJob = null
        stopMediaPlayer()
        tts?.stop()
    }

    private fun stopMediaPlayer() {
        mediaPlayer?.let {
            try { if (it.isPlaying) it.stop() } catch (_: Exception) {}
            it.release()
        }
        mediaPlayer = null
    }

    fun shutdown() {
        ttsJob?.cancel()
        ttsJob = null
        stopMediaPlayer()
        tts?.shutdown()
    }
}
