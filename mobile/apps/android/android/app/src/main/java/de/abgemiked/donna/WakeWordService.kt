package com.yourcompany.donna

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder.AudioSource
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * WakeWordService — DONNA-73: "Hey Donna" Wake-Word-Erkennung auf Android.
 *
 * Aufnahme: AudioRecord (PCM 16kHz Mono) in 2-Sekunden-Frames.
 * Jeder Frame wird als WAV-Datei an POST /wake-word/check gesendet.
 * Bei {"match": true} → VoiceInputActivity öffnen.
 *
 * Läuft als Foreground Service (microphone type) damit Android die
 * Aufnahme im Hintergrund nicht unterbricht.
 *
 * Datenschutz: Audio wird NUR an die eigene Backend-Instanz gesendet
 * und nie persistiert. Kein Cloud-Routing.
 */
class WakeWordService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var listenJob: Job? = null
    @Volatile private var activeRecorder: AudioRecord? = null

    companion object {
        private const val WAKE_WORD_ENABLED = false // DONNA-151: deaktiviert — reaktivierbar auf true
        private const val TAG = "DonnaWakeWord"

        private const val CHANNEL_ID = "donna_wake_word"
        private const val NOTIF_ID = 1004

        private const val SAMPLE_RATE = 16_000      // Hz — Whisper-optimal
        private const val FRAME_SECONDS = 2         // Sekunden pro Frame
        private const val SAMPLES_PER_FRAME = SAMPLE_RATE * FRAME_SECONDS
        private const val BYTES_PER_SAMPLE = 2      // 16-bit PCM
        private const val MIN_FRAME_BYTES = 500     // Mindestgröße — unter diesem Wert = Stille

        // HTTP-Clients: kurzes Timeout für Wake-Word (Frame muss schnell entschieden werden)
        private val http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()

        fun start(ctx: Context) {
            if (!WAKE_WORD_ENABLED) { Log.d(TAG, "WakeWord deaktiviert (WAKE_WORD_ENABLED=false)"); return }
            val intent = Intent(ctx, WakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, WakeWordService::class.java))
        }
    }

    // ── Service Lifecycle ─────────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForegroundCompat()
        // Guard gegen Mehrfachstart (onStartCommand wird bei START_STICKY-Neustart erneut aufgerufen)
        if (listenJob?.isActive == true) {
            Log.d(TAG, "Listen-Loop läuft bereits — onStartCommand ignoriert")
            return START_STICKY
        }
        listenJob = startListenLoop()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        // recorder.stop() unterbricht blockierendes recorder.read() sofort —
        // ohne das wartet scope.cancel() bis zu 2s auf den nächsten Frame-Abschluss
        activeRecorder?.apply { try { stop() } catch (_: Exception) {} }
        scope.cancel()
    }

    // ── Foreground Notification ───────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Donna Spracherkennung",
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = "Wartet auf 'Hey Donna'"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun startForegroundCompat() {
        val launchIntent = packageManager
            .getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Donna")
            .setContentText("Warte auf 'Hey Donna'…")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    // ── Listen Loop ───────────────────────────────────────────────────────

    private fun startListenLoop(): Job = scope.launch {
        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ),
            SAMPLES_PER_FRAME * BYTES_PER_SAMPLE
        )

        var recorder: AudioRecord? = null
        try {
            recorder = AudioRecord(
                AudioSource.VOICE_RECOGNITION,  // optimiert für Speech — unterdrückt Umgebungsgeräusche
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord nicht initialisiert — Mikrofon-Permission fehlt?")
                return@launch
            }

            activeRecorder = recorder
            recorder.startRecording()
            Log.i(TAG, "Wake-Word-Listener gestartet (AudioRecord 16kHz Mono)")

            val frameBuffer = ShortArray(SAMPLES_PER_FRAME)

            while (isActive) {
                // 2 Sekunden aufnehmen
                var samplesRead = 0
                while (isActive && samplesRead < SAMPLES_PER_FRAME) {
                    val n = recorder.read(
                        frameBuffer,
                        samplesRead,
                        SAMPLES_PER_FRAME - samplesRead
                    )
                    if (n <= 0) break
                    samplesRead += n
                }

                if (!isActive) break
                if (samplesRead < SAMPLES_PER_FRAME / 2) continue  // Unvollständiger Frame

                // Zu WAV konvertieren und an Backend schicken
                val wavBytes = buildWav(frameBuffer, samplesRead)
                if (wavBytes.size < MIN_FRAME_BYTES) continue

                val match = checkWakeWord(wavBytes)
                if (match) {
                    Log.i(TAG, "Wake-Word erkannt — starte VoiceInputActivity")
                    openVoiceInput()
                    // Kurze Pause nach Erkennung — verhindert Doppelstart
                    delay(2_000)
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "RECORD_AUDIO-Permission fehlt: ${e.message}")
        } finally {
            activeRecorder = null
            recorder?.apply {
                try { stop() } catch (_: Exception) {}
                release()
            }
            Log.d(TAG, "AudioRecord freigegeben")
        }
    }

    // ── WAV Builder ───────────────────────────────────────────────────────

    /**
     * Konvertiert einen PCM-16-Short-Array in gültigen WAV-ByteArray.
     * WAV-Header: 44 Bytes Standard-Header + PCM-Samples.
     */
    private fun buildWav(samples: ShortArray, sampleCount: Int): ByteArray {
        val pcmBytes = sampleCount * BYTES_PER_SAMPLE
        val totalDataLen = pcmBytes + 36
        val bos = ByteArrayOutputStream(44 + pcmBytes)
        val dos = DataOutputStream(bos)

        // RIFF Header
        dos.writeBytes("RIFF")
        dos.writeIntLE(totalDataLen)
        dos.writeBytes("WAVE")
        // fmt chunk
        dos.writeBytes("fmt ")
        dos.writeIntLE(16)      // chunk size
        dos.writeShortLE(1)     // PCM = 1
        dos.writeShortLE(1)     // Mono
        dos.writeIntLE(SAMPLE_RATE)
        dos.writeIntLE(SAMPLE_RATE * BYTES_PER_SAMPLE)  // byte rate
        dos.writeShortLE(BYTES_PER_SAMPLE.toShort())    // block align
        dos.writeShortLE(16)    // bits per sample
        // data chunk
        dos.writeBytes("data")
        dos.writeIntLE(pcmBytes)

        // PCM samples (little-endian)
        val buf = ByteBuffer.allocate(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) buf.putShort(samples[i])
        dos.write(buf.array())
        dos.flush()

        return bos.toByteArray()
    }

    // ── Wake-Word Check ───────────────────────────────────────────────────

    private suspend fun checkWakeWord(wavBytes: ByteArray): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "audio",
                        "frame.wav",
                        wavBytes.toRequestBody("audio/wav".toMediaType())
                    )
                    .build()

                val request = Request.Builder()
                    .url("${BuildConfig.DONNA_API_URL}/wake-word/check")
                    .addHeader("Authorization", "Bearer ${TokenStore.getToken(applicationContext) ?: ""}")
                    .post(body)
                    .build()

                http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.d(TAG, "Wake-Word-Check HTTP ${resp.code}")
                        return@withContext false
                    }
                    val json = JSONObject(resp.body?.string() ?: return@withContext false)
                    val match = json.optBoolean("match", false)
                    if (match) {
                        Log.i(TAG, "Wake-Word Transkript: ${json.optString("transcript", "")}")
                    }
                    match
                }
            } catch (e: Exception) {
                Log.d(TAG, "Wake-Word-Check Fehler: ${e.message}")
                false
            }
        }
    }

    // ── Voice Input öffnen ─────────────────────────────────────────────────

    private fun openVoiceInput() {
        val intent = Intent(this, VoiceInputActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("source", "wake_word")
        }
        startActivity(intent)
    }
}

// ── DataOutputStream Extensions (Little-Endian WAV) ──────────────────────────

private fun DataOutputStream.writeIntLE(v: Int) {
    write(v and 0xFF)
    write((v shr 8) and 0xFF)
    write((v shr 16) and 0xFF)
    write((v shr 24) and 0xFF)
}

private fun DataOutputStream.writeShortLE(v: Short) {
    write(v.toInt() and 0xFF)
    write((v.toInt() shr 8) and 0xFF)
}
