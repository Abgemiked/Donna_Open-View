package com.yourcompany.donna

/**
 * VoiceInputActivity — Pulse-Styled Bottom-Sheet Overlay für Donna
 *
 * Erscheint als Bottom-Sheet über der aktuellen App, wenn Donna per
 * Android Assistant / Side-Button aufgerufen wird.
 *
 * Design: Pulse Theme (dunkelblau #03090f, Cyan-Akzent #38bdf8)
 */

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.Html
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

class VoiceInputActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private var speechRecognizer: SpeechRecognizer? = null
    // Android-TTS: nur als Offline-Fallback wenn Piper-Server nicht erreichbar
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    // Piper-Server TTS (primär)
    private var mediaPlayer: android.media.MediaPlayer? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // UI-Elemente
    private lateinit var statusText: TextView
    private lateinit var partialText: TextView
    private lateinit var cardContainer: LinearLayout   // Wetter- / Map-Karte
    private lateinit var responseText: TextView
    private lateinit var inputField: EditText
    private lateinit var micButton: TextView   // TextView als Icon-Button
    private lateinit var sendButton: TextView
    private lateinit var loadingBar: ProgressBar
    private var micBg: GradientDrawable? = null
    private lateinit var historyPanel: LinearLayout  // Gesprächsverlauf-Panel
    private lateinit var contentPanel: LinearLayout  // Das eigentliche Overlay-Panel (für IME)

    private var isListening = false
    private var isPttMode = false   // true = PTT — stoppt & sendet beim Loslassen

    // ── Session-Tracking (Gesprächsverlauf) ──────────────────────────────────
    private var currentSessionId: String? = null
    private var isAuthenticated = false

    // ── Pulse Theme ──────────────────────────────────────────────────────────
    private val colorBg        = Color.parseColor("#03090f")
    private val colorCard      = Color.parseColor("#0d1626")
    private val colorSurface   = Color.parseColor("#080f1a")
    private val colorAccent    = Color.parseColor("#38bdf8")
    private val colorAccent2   = Color.parseColor("#7dd3fc")
    private val colorText      = Color.parseColor("#e0f2fe")
    private val colorMuted     = Color.parseColor("#7a9db8")
    private val colorBorder    = Color.parseColor("#1a3040")
    private val colorHandle    = Color.parseColor("#1e3d52")
    private val colorMicActive = Color.parseColor("#0c2a3e")

    companion object {
        private const val MIC_PERMISSION_RC = 200
    }

    /** dp → px */
    private fun dp(n: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, n.toFloat(), resources.displayMetrics
    ).toInt()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            applyTtsSettings()
        } else {
            // Fallback: Google TTS explizit
            tts?.shutdown()
            tts = TextToSpeech(this, { s2 ->
                if (s2 == TextToSpeech.SUCCESS) applyTtsSettings()
            }, "com.google.android.tts")
        }
    }

    private fun applyTtsSettings() {
        tts?.language = Locale.GERMAN
        val voices = tts?.voices
        // Google TTS: nfh = weiblich neural (beste verfügbare Stimme)
        // Fallback-Kette: nfh-network → nfh-local → deb-network → erste DE-Stimme
        val best = voices?.firstOrNull { v -> v.name == "de-de-x-nfh-network" }
            ?: voices?.firstOrNull { v -> v.name == "de-de-x-nfh-local" }
            ?: voices?.firstOrNull { v -> v.name == "de-de-x-deb-network" }
            ?: voices?.firstOrNull { v -> v.locale.language == "de" }
        best?.let { tts?.voice = it }
        tts?.setPitch(0.92f)   // wärmer, voller — weniger hell/hoch
        tts?.setSpeechRate(0.93f)
        ttsReady = true
    }

    /** System-TTS (Android / Samsung SMT) — nur als Offline-Fallback. */
    private fun speak(text: String) {
        if (!ttsReady || text.isBlank()) return
        val clean = text
            .replace(Regex("\\*+"), "")
            .replace(Regex("#{1,6}\\s"), "")
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)"), "$1")
            .replace("•", "")
            .trim()
        if (clean.isEmpty()) return
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "donna")
    }

    /**
     * Piper-Backend-TTS (primär) — POST /tts → WAV-Bytes → MediaPlayer.
     *
     * Timeout: 2500 ms connectTimeout. Bei HTTP 204 (Live-Guard) → kein Audio.
     * Bei Fehler / Timeout → Fallback auf System-TTS [speak()].
     */
    private fun speakViaPiper(text: String) {
        val clean = text
            .replace(Regex("\\*+"), "")
            .replace(Regex("#{1,6}\\s"), "")
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)"), "$1")
            .replace("•", "")
            .trim()
        if (clean.isEmpty()) return

        // Laufende Wiedergabe stoppen
        mediaPlayer?.let {
            try { if (it.isPlaying) it.stop() } catch (_: Exception) {}
            it.release()
        }
        mediaPlayer = null
        tts?.stop()

        scope.launch {
            try {
                val audioBytes = withContext(Dispatchers.IO) {
                    val url = URL(BuildConfig.DONNA_API_URL + "/tts")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Authorization", "Bearer ${TokenStore.getToken(this@VoiceInputActivity) ?: ""}")
                        setRequestProperty("Content-Type", "application/json")
                        connectTimeout = 2_500
                        readTimeout = 15_000
                        doOutput = true
                    }
                    val body = """{"text":${org.json.JSONObject.quote(clean)},"voice":"de_DE-mls-medium","speaker_id":184}"""
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                    val code = conn.responseCode
                    if (code == 204) return@withContext null   // Live-Guard — kein Audio
                    if (code != 200) throw Exception("HTTP $code")

                    conn.inputStream.use { it.readBytes() }
                }

                if (audioBytes == null) return@launch  // Live-Guard: Stille

                val tmpFile = File(cacheDir, "donna_overlay_tts_${System.currentTimeMillis()}.wav")
                withContext(Dispatchers.IO) {
                    FileOutputStream(tmpFile).use { it.write(audioBytes) }
                }

                val player = android.media.MediaPlayer()
                mediaPlayer = player  // sofort zuweisen, damit onPause() ihn findet
                player.apply {
                    setDataSource(tmpFile.absolutePath)
                    setOnCompletionListener {
                        it.release()
                        mediaPlayer = null
                        tmpFile.delete()
                    }
                    setOnErrorListener { mp, _, _ ->
                        mp.release()
                        mediaPlayer = null
                        tmpFile.delete()
                        false
                    }
                }
                try {
                    player.prepare()
                    player.start()
                } catch (e: Exception) {
                    tmpFile.delete()
                    player.release()
                    mediaPlayer = null
                    speak(text)
                }

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e  // Coroutine-Cancel korrekt propagieren — kein TTS-Fallback nötig, Activity stirbt ohnehin
            } catch (e: Exception) {
                android.util.Log.w("VoiceInputActivity", "speakViaPiper failed: ${e.message}")
                speak(text)
            }
        }
    }

    /**
     * Konvertiert einfaches Markdown zu HTML für Html.fromHtml().
     * Unterstützt: **fett**, # Überschriften, Bullet-Listen, URLs als Links.
     */
    private fun markdownToHtml(text: String): String {
        val lines = text.lines()
        val sb = StringBuilder()
        lines.forEachIndexed { idx, rawLine ->
            var line = rawLine
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
            // Überschriften → fett
            line = line.replace(Regex("^#{1,3}\\s+(.+)$")) { "<b>${it.groupValues[1]}</b>" }
            // Bullet-Listen
            line = line.replace(Regex("^[\\t ]*[*•\\-]\\s+(.+)$")) { "• ${it.groupValues[1]}" }
            // **fett**
            line = line.replace(Regex("\\*\\*([^*]+)\\*\\*")) { "<b>${it.groupValues[1]}</b>" }
            // *kursiv* (einzelne Sterne)
            line = line.replace(Regex("(?<![*])\\*([^*\n]+)\\*(?![*])")) { "<i>${it.groupValues[1]}</i>" }
            // Verbleibende Sterne entfernen
            line = line.replace(Regex("\\*+"), "")
            // URLs als anklickbare Links
            line = line.replace(Regex("https?://\\S+")) {
                "<a href=\"${it.value}\">${it.value}</a>"
            }
            sb.append(line)
            if (idx < lines.size - 1) sb.append("<br>")
        }
        return sb.toString()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // WakeWordService hält AudioRecord dauerhaft — stop() unterbricht recorder.read() sofort
        // (activeRecorder.stop() in onDestroy), aber Service-Teardown ist async → 300ms warten
        WakeWordService.stop(this)
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(this, "Donna braucht Overlay-Berechtigung — bitte erlauben", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        // Sperrbildschirm: Overlay wie Bixby über Lock-Screen anzeigen
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        // System-Default-Engine nutzen (auf S25 Ultra = Samsung SMT) — kein expliziter Engine-Call
        // Falls Samsung SMT als System-Default erlaubt ist, kommen die natürlichen Stimmen
        tts = TextToSpeech(this, this)
        setupWindow()
        val km = getSystemService(android.app.KeyguardManager::class.java)
        if (km?.isKeyguardLocked == true) {
            authenticateWithBiometric()
        } else {
            // Gerät ist bereits entsperrt — kein zusätzlicher Biometrie-Prompt nötig
            isAuthenticated = true
        }
        val root = buildUI()
        setContentView(root)

        // Nav-Bar + IME-Inset: Content-Panel springt über Tastatur wenn Textfeld aktiv
        // Padding auf contentPanel, nicht auf dimRoot (der bleibt Vollbild)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomPad = if (ime.bottom > 0) ime.bottom else (dp(24) + navBar.bottom)
            contentPanel.setPadding(dp(20), dp(16), dp(20), bottomPad)
            WindowInsetsCompat.CONSUMED
        }

        // Swipe-Down auf dem Content-Panel → schließen
        // Außerhalb-Tippen wird direkt vom dimRoot-ClickListener in buildUI() übernommen.
        val swipeDetector = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onFling(
                    e1: MotionEvent?, e2: MotionEvent,
                    velocityX: Float, velocityY: Float,
                ): Boolean {
                    if (velocityY > 600 && (e2.y - (e1?.y ?: e2.y)) > 80) {
                        finish()
                        return true
                    }
                    return false
                }
            })
        root.setOnTouchListener { v, event ->
            swipeDetector.onTouchEvent(event)
            v.onTouchEvent(event)
            true
        }

        // Erste Eingabe: 300ms warten bis WakeWordService AudioRecord freigegeben hat,
        // dann SpeechRecognizer starten — verhindert Mikrofon-Konflikt
        android.os.Handler(mainLooper).postDelayed({
            if (!isDestroyed && !isFinishing) requestMicAndListen()
        }, 300)
    }

    /**
     * Seitentaste (KEYCODE_ASSIST) loslassen → erste Aufnahme stoppen + senden.
     * Volume-Down als zuverlässige PTT-Alternative für weitere Eingaben.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return when {
            // Seitentaste / Assistant-Key loslassen → erste PTT-Aufnahme beenden
            event.keyCode == KeyEvent.KEYCODE_ASSIST &&
            event.action == KeyEvent.ACTION_UP -> {
                // DONNA-30: Biometrie noch ausstehend — nicht interagieren.
                // onAuthenticationSucceeded() setzt isAuthenticated; bis dahin ignorieren.
                if (!isAuthenticated) return super.dispatchKeyEvent(event)
                if (isListening) {
                    isPttMode = false
                    speechRecognizer?.stopListening()
                    statusText.text = "VERARBEITE…"
                    statusText.setTextColor(colorMuted)
                }
                true
            }
            // Volume-Tasten: NICHT abfangen → System regelt Lautstärke normal
            else -> super.dispatchKeyEvent(event)
        }
    }

    override fun onPause() {
        super.onPause()
        // finish() absichtlich NICHT hier — BiometricPrompt, Tastatur und andere
        // System-Dialoge rufen onPause() aus, würden die Activity sonst sofort töten.
        if (isListening) {
            speechRecognizer?.stopListening()
            isListening = false
            isPttMode = false
        }
        tts?.stop()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    /** Nur wenn der User bewusst wegnavigiert (Home-Button) — nicht bei System-Dialogen. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        tts?.shutdown()
        // WakeWordService nach Sprachinput wieder starten
        WakeWordService.start(this)
        mediaPlayer?.release()
        mediaPlayer = null
        scope.cancel()
    }

    // ── Window-Setup ──────────────────────────────────────────────────────────

    private fun setupWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Kein FLAG_DIM_BEHIND — wir zeichnen den Dim-Layer selbst im Root-View.
        // Dadurch kann windowIsTranslucent=false bleiben (Lock-Screen-Kompatibilität)
        // und wir haben trotzdem die Bixby-artige Transparenz.

        val params = window.attributes
        // TYPE_APPLICATION_OVERLAY (API 26+) → system-weites Overlay über allen Apps.
        // Ohne diesen Type erscheint das Fenster nur im eigenen App-Kontext und nicht
        // über fremden Apps (YouTube, Chrome etc.) wenn Donna via Seitentaste gerufen wird.
        // Für API < 26 nutzen wir TYPE_PHONE als Fallback (deprecated, aber funktional).
        @Suppress("DEPRECATION")
        params.type = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        params.gravity = Gravity.BOTTOM or Gravity.FILL_HORIZONTAL
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        params.height = ViewGroup.LayoutParams.MATCH_PARENT  // Vollbild für Dim-Layer
        window.attributes = params

        window.setBackgroundDrawableResource(android.R.color.transparent)
    }

    // ── UI-Aufbau (Pulse Design) ──────────────────────────────────────────────

    private fun buildUI(): View {
        val ctx = this

        // ── Vollbild-Wrapper mit matt-grauem Dim-Layer (Bixby-Stil) ──────────
        // windowIsTranslucent=false → wir müssen den Hintergrund selbst zeichnen.
        // Der Dim-Layer ist ein halbdurchsichtiges Schwarz über dem Hintergrund,
        // sodass die laufende App noch erkennbar ist (wie bei Bixby / Google Assistant).
        val dimRoot = android.widget.FrameLayout(ctx).apply {
            setBackgroundColor(Color.argb(160, 0, 0, 0))  // ~63 % Dunkel — matt-grau Überzug
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            // Tippen auf den Dim-Layer (außerhalb der Panel) → schließen
            setOnClickListener { finish() }
        }

        // ── Content-Panel — dunkelblau, abgerundete Ecken oben ──────────────
        contentPanel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
            background = GradientDrawable().apply {
                setColor(colorCard)
                cornerRadii = floatArrayOf(
                    dp(20).toFloat(), dp(20).toFloat(),
                    dp(20).toFloat(), dp(20).toFloat(),
                    0f, 0f, 0f, 0f
                )
                setStroke(1, colorBorder)
            }
            // Touches auf dem Panel NICHT an dimRoot weitergeben
            setOnClickListener { /* consume */ }
        }

        // ── Handle Pill ──
        val handle = View(ctx).apply {
            background = GradientDrawable().apply {
                setColor(colorHandle)
                cornerRadius = dp(2).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).also {
                it.gravity = Gravity.CENTER_HORIZONTAL
                it.bottomMargin = dp(16)
            }
        }
        contentPanel.addView(handle)

        // ── Header Row: Avatar + "DONNA" ──
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(14) }
        }

        // Avatar circle mit "D"
        val avatar = TextView(ctx).apply {
            text = "D"
            setTextColor(colorBg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(colorAccent)
                cornerRadius = dp(7).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).also {
                it.marginEnd = dp(10)
            }
        }
        headerRow.addView(avatar)

        // "DONNA" Title — klickbar für Gesprächsverlauf
        val title = TextView(ctx).apply {
            text = "DONNA"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(colorAccent)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.25f
            isClickable = true
            isFocusable = true
            setOnClickListener { showHistoryDialog() }
        }
        headerRow.addView(title)

        // Status-Dot (grün = aktiv)
        val statusDot = View(ctx).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#22c55e"))
                cornerRadius = dp(4).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).also {
                it.marginStart = dp(10)
            }
        }
        headerRow.addView(statusDot)
        contentPanel.addView(headerRow)

        // ── Gesprächsverlauf-Panel (initial unsichtbar) ──
        historyPanel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(8) }
        }
        contentPanel.addView(historyPanel)

        // ── Status-Text ──
        statusText = TextView(ctx).apply {
            text = "HÖRE ZU"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(colorMuted)
            gravity = Gravity.CENTER_HORIZONTAL
            letterSpacing = 0.15f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(8) }
        }
        contentPanel.addView(statusText)

        // ── Partial-Transcript ──
        partialText = TextView(ctx).apply {
            text = ""
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(colorAccent2)
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(8) }
        }
        contentPanel.addView(partialText)

        // ── Ladebalken ──
        loadingBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            progressDrawable?.setColorFilter(colorAccent, android.graphics.PorterDuff.Mode.SRC_IN)
            indeterminateDrawable?.setColorFilter(colorAccent, android.graphics.PorterDuff.Mode.SRC_IN)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(10) }
        }
        contentPanel.addView(loadingBar)

        // ── Karten-Container (Wetter / Maps) ──
        cardContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(10) }
        }
        contentPanel.addView(cardContainer)

        // ── Response-Text ──
        responseText = TextView(ctx).apply {
            text = ""
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(colorText)
            setTextIsSelectable(true)      // Long-Press → kopieren
            movementMethod = LinkMovementMethod.getInstance()  // Scrollen + Links anklickbar
            maxLines = 8
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(colorSurface)
                cornerRadius = dp(12).toFloat()
                setStroke(1, colorBorder)
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(14) }
        }
        contentPanel.addView(responseText)

        // ── Input Row ──
        val inputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Mic-Button (Pulse style) — Touch-Hold = PTT, kurzer Tap = Toggle
        micBg = GradientDrawable().apply {
            setColor(colorCard)
            cornerRadius = dp(22).toFloat()
            setStroke(1, colorBorder)
        }
        micButton = TextView(ctx).apply {
            text = "🎤"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            background = micBg
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).also {
                it.marginEnd = dp(8)
            }
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        isPttMode = true
                        if (!isListening) requestMicAndListen()
                        performClick()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (isPttMode && isListening) {
                            // Loslassen → Aufnahme stoppen, Ergebnis wird auto-gesendet
                            speechRecognizer?.stopListening()
                            setListeningState(false)
                            statusText.text = "VERARBEITE…"
                        }
                        isPttMode = false
                        true
                    }
                    else -> false
                }
            }
        }
        inputRow.addView(micButton)

        // Text-Eingabe (Pulse dark style)
        inputField = EditText(ctx).apply {
            hint = "Nachricht eingeben…"
            setHintTextColor(colorMuted)
            setTextColor(colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = GradientDrawable().apply {
                setColor(colorSurface)
                cornerRadius = dp(22).toFloat()
                setStroke(1, colorBorder)
            }
            setPadding(dp(16), dp(10), dp(16), dp(10))
            maxLines = 3
            highlightColor = Color.argb(80, 56, 189, 248)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .also { it.marginEnd = dp(8) }
        }
        inputRow.addView(inputField)

        // Senden-Button (Pulse cyan)
        sendButton = TextView(ctx).apply {
            text = "➤"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(colorBg)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(colorAccent)
                cornerRadius = dp(22).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setOnClickListener { onSendPressed() }
        }
        inputRow.addView(sendButton)

        contentPanel.addView(inputRow)

        // Container unten im dimRoot verankern
        val panelParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        )
        dimRoot.addView(contentPanel, panelParams)
        return dimRoot
    }

    // ── Spracherkennung ───────────────────────────────────────────────────────

    private fun requestMicAndListen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                MIC_PERMISSION_RC,
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MIC_PERMISSION_RC &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        } else {
            statusText.text = "KEIN MIKROFON — BITTE TIPPEN"
        }
    }

    private fun onMicPressed() {
        // Wird bei Touch-Hold nicht direkt gerufen — nur für performClick()-Kompatibilität
        // Tap-Toggle bleibt als Fallback wenn kein Touch-Listener feuert
        if (isListening) {
            speechRecognizer?.stopListening()
            setListeningState(false)
        }
    }

    /** Erstellt den RecognitionListener als wiederverwendbares Objekt.
     *  Wird von startListening() und beim ERROR_SERVER_DISCONNECTED-Retry genutzt. */
    private fun buildRecognitionListener(): RecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = setListeningState(true)
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            statusText.text = "VERARBEITE…"
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull() ?: return
            partialText.text = partial
            partialText.visibility = if (partial.isNotBlank()) View.VISIBLE else View.GONE
        }
        override fun onResults(results: Bundle?) {
            setListeningState(false)
            partialText.visibility = View.GONE
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (text.isNullOrBlank()) {
                statusText.text = "NICHTS ERKANNT — BITTE TIPPEN"
            } else {
                inputField.setText(text)
                sendToBackend(text)
            }
        }
        override fun onError(error: Int) {
            setListeningState(false)
            partialText.visibility = View.GONE
            when (error) {
                7 -> { // ERROR_NO_MATCH — nichts erkannt
                    statusText.text = "HALTEN ZUM REDEN • TIPPEN"
                    statusText.setTextColor(colorMuted)
                }
                11 -> { // ERROR_SERVER_DISCONNECTED — Android 12+ / Samsung-typisch
                    // Google ASR-Server hat Verbindung getrennt → SpeechRecognizer
                    // neu erstellen und Aufnahme sofort wiederholen (silent retry)
                    speechRecognizer?.destroy()
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this@VoiceInputActivity)
                    speechRecognizer?.setRecognitionListener(buildRecognitionListener())
                    startListening()
                }
                else -> {
                    statusText.text = "FEHLER ($error) — 🎤 HALTEN ODER TIPPEN"
                    statusText.setTextColor(colorMuted)
                }
            }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusText.text = "SPRACHERKENNUNG NICHT VERFÜGBAR"
            return
        }
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(buildRecognitionListener())
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            startListening(intent)
        }
    }

    private fun setListeningState(listening: Boolean) {
        isListening = listening
        if (listening) {
            micButton.text = "⏹"
            micBg?.setColor(colorMicActive)
            micBg?.setStroke(dp(1), colorAccent)
            statusText.text = "HÖRE ZU"
            statusText.setTextColor(colorAccent2)
        } else {
            micButton.text = "🎤"
            micBg?.setColor(colorCard)
            micBg?.setStroke(1, colorBorder)
            statusText.setTextColor(colorMuted)
        }
    }

    // ── Nachricht senden ──────────────────────────────────────────────────────

    private fun onSendPressed() {
        val text = inputField.text.toString().trim()
        if (text.isNotBlank()) sendToBackend(text)
    }

    /** Letzten bekannten Standort holen (best-effort, kein Timeout-Warten). */
    private fun getLastLocation(): Location? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        val lm = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    // ── SSE-Antwort parsen ────────────────────────────────────────────────────
    private data class SseResult(
        val text: String,
        val weatherData: JSONObject? = null,
        val mapUrl: String? = null,
        val actions: List<JSONObject> = emptyList(),
    )

    private fun parseSseStream(stream: java.io.InputStream): SseResult {
        val reader = stream.bufferedReader(Charsets.UTF_8)
        val text = StringBuilder()
        var weatherData: JSONObject? = null
        var mapUrl: String? = null
        val actions = mutableListOf<JSONObject>()

        reader.forEachLine { line ->
            if (!line.startsWith("data: ")) return@forEachLine
            val data = line.removePrefix("data: ").trim()
            if (data == "[DONE]" || data.isBlank()) return@forEachLine
            try {
                val json = JSONObject(data)
                when (json.optString("type")) {
                    "card" -> {
                        val cardData = json.optJSONObject("data") ?: return@forEachLine
                        when (json.optString("card_type")) {
                            "weather" -> weatherData = cardData
                            "map"     -> mapUrl = cardData.optString("maps_url")
                        }
                    }
                    "action" -> {
                        actions.add(json.optJSONObject("action") ?: return@forEachLine)
                    }
                    "delta" -> {
                        val c = json.optString("content")
                        if (c.isNotBlank() && !c.startsWith("[error]") && !c.startsWith("[warn]"))
                            text.append(c)
                    }
                    else -> {
                        // Altes Format oder Plain-Text
                        val c = json.optString("content").ifBlank { json.optString("delta") }
                        if (c.isNotBlank()) text.append(c)
                    }
                }
            } catch (_: Exception) {
                // Kein JSON → als Text behandeln wenn sinnvoll
                if (!data.startsWith("{") && !data.startsWith("[")) text.append(data)
            }
        }
        val cleanText = text.toString().trim().replace(Regex("\\[DONNA_ACTION:\\{[^}]*\\}\\]"), "").trim()
        return SseResult(cleanText, weatherData, mapUrl, actions)
    }

    // ── Native Wetterkarte ────────────────────────────────────────────────────
    private fun buildWeatherCard(d: JSONObject): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(colorSurface)
                cornerRadius = dp(14).toFloat()
                setStroke(1, colorBorder)
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Standort
        card.addView(TextView(this).apply {
            text = d.optString("location", "").uppercase()
            setTextColor(colorMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            letterSpacing = 0.12f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(4) }
        })

        // Temperatur + Icon
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(4) }
        }
        topRow.addView(TextView(this).apply {
            text = "${d.optInt("temp_c", 0)}°"
            setTextColor(colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 52f)
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        topRow.addView(TextView(this).apply {
            text = d.optString("condition_icon", "🌡️")
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
        })
        card.addView(topRow)

        // Zustand
        card.addView(TextView(this).apply {
            text = d.optString("condition", "")
            setTextColor(colorAccent2)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(10) }
        })

        // Trennlinie
        card.addView(View(this).apply {
            background = GradientDrawable().apply { setColor(colorBorder) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1
            ).also { it.bottomMargin = dp(10) }
        })

        // Details-Reihe
        val detailsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        listOf(
            "↓${d.optInt("temp_min")}° ↑${d.optInt("temp_max")}°",
            "💧 ${d.optInt("humidity")}%",
            "💨 ${d.optInt("wind_kmh")} km/h",
            "Gefühlt ${d.optInt("feels_like_c")}°",
        ).forEach { detail ->
            detailsRow.addView(TextView(this).apply {
                text = detail
                setTextColor(colorMuted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        card.addView(detailsRow)
        return card
    }

    // ── Maps-Karte ────────────────────────────────────────────────────────────
    private fun buildMapCard(mapsUrl: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(colorSurface)
                cornerRadius = dp(14).toFloat()
                setStroke(1, colorBorder)
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(mapsUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
            addView(TextView(this@VoiceInputActivity).apply {
                text = "🗺️"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.marginEnd = dp(12) }
            })
            addView(LinearLayout(this@VoiceInputActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@VoiceInputActivity).apply {
                    text = "In Google Maps öffnen"
                    setTextColor(colorAccent)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                })
                addView(TextView(this@VoiceInputActivity).apply {
                    text = "Tippen zum Öffnen"
                    setTextColor(colorMuted)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                })
            })
            addView(TextView(this@VoiceInputActivity).apply {
                text = "›"
                setTextColor(colorAccent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            })
        }
    }

    // ── Aktions-Karte ────────────────────────────────────────────────────────
    private fun buildActionCard(action: JSONObject): View? {
        val type = action.optString("type")
        val label = when (type) {
            "create_event" -> "📅 Termin eintragen: ${action.optString("title")}"
            "set_alarm"    -> "⏰ Wecker stellen: ${action.optString("time")} ${action.optString("label")}".trim()
            "set_timer"    -> "⏱ Timer starten: ${action.optInt("minutes", 0)} Min ${action.optString("label")}".trim()
            "navigate"     -> "🗺 Navigation zu: ${action.optString("destination")}"
            "call"         -> "📞 Anrufen: ${action.optString("name").ifBlank { action.optString("number") }}"
            "sms"          -> "💬 SMS an: ${action.optString("name").ifBlank { action.optString("number") }}"
            "whatsapp"     -> "💬 WhatsApp an: ${action.optString("name").ifBlank { action.optString("number") }}"
            "play_music"   -> "🎵 Musik abspielen: ${action.optString("query")}"
            "note"         -> "📝 Notiz: ${action.optString("title")}"
            "open_url"     -> "🌐 Link öffnen: ${action.optString("title").ifBlank { action.optString("url") }}"
            else           -> return null
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(colorSurface)
                cornerRadius = dp(10).toFloat()
                setStroke(1, colorBorder)
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(8) }
        }

        // Titel
        card.addView(TextView(this).apply {
            text = label
            setTextColor(colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(10) }
        })

        // Buttons-Reihe
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // "Ja"-Button
        btnRow.addView(TextView(this).apply {
            text = "Ja"
            setTextColor(colorBg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(colorAccent)
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(16), dp(8), dp(16), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = dp(10) }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                executeAction(action)
                (card.parent as? ViewGroup)?.removeView(card)
            }
        })

        // "Nein"-Button
        btnRow.addView(TextView(this).apply {
            text = "Nein"
            setTextColor(colorMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(colorSurface)
                cornerRadius = dp(8).toFloat()
                setStroke(1, colorBorder)
            }
            setPadding(dp(16), dp(8), dp(16), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isClickable = true
            isFocusable = true
            setOnClickListener {
                (card.parent as? ViewGroup)?.removeView(card)
            }
        })

        card.addView(btnRow)
        return card
    }

    private fun executeAction(action: JSONObject) {
        try {
            when (action.optString("type")) {
                "create_event" -> {
                    val intent = Intent(Intent.ACTION_INSERT).apply {
                        data = android.provider.CalendarContract.Events.CONTENT_URI
                        putExtra(android.provider.CalendarContract.Events.TITLE, action.optString("title"))
                        val startStr = action.optString("start")
                        val endStr = action.optString("end")
                        if (startStr.isNotBlank()) {
                            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.getDefault())
                            runCatching { sdf.parse(startStr)?.time?.let { putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) } }
                            runCatching { sdf.parse(endStr)?.time?.let { putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, it) } }
                        }
                        val loc = action.optString("location")
                        if (loc.isNotBlank()) putExtra(android.provider.CalendarContract.Events.EVENT_LOCATION, loc)
                    }
                    startActivity(intent)
                }
                "set_alarm" -> {
                    val parts = action.optString("time").split(":")
                    val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                        if (parts.size == 2) {
                            putExtra(android.provider.AlarmClock.EXTRA_HOUR, parts[0].toIntOrNull() ?: 7)
                            putExtra(android.provider.AlarmClock.EXTRA_MINUTES, parts[1].toIntOrNull() ?: 0)
                        }
                        val lbl = action.optString("label")
                        if (lbl.isNotBlank()) putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, lbl)
                        putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)
                    }
                    startActivity(intent)
                }
                "set_timer" -> {
                    val intent = Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
                        putExtra(android.provider.AlarmClock.EXTRA_LENGTH, (action.optInt("minutes", 5)) * 60)
                        val lbl = action.optString("label")
                        if (lbl.isNotBlank()) putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, lbl)
                        putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)
                    }
                    startActivity(intent)
                }
                "navigate" -> {
                    val dest = action.optString("destination")
                    val uri = android.net.Uri.parse("google.navigation:q=${android.net.Uri.encode(dest)}&mode=d")
                    startActivity(Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.google.android.apps.maps") })
                }
                "call" -> {
                    val number = action.optString("number")
                    startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$number")))
                }
                "sms" -> {
                    val number = action.optString("number")
                    val msg = action.optString("message")
                    val intent = Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:$number")).apply {
                        putExtra("sms_body", msg)
                    }
                    startActivity(intent)
                }
                "whatsapp" -> {
                    val number = action.optString("number").replace("+", "").replace(" ", "")
                    val msg = action.optString("message")
                    val uri = android.net.Uri.parse("https://api.whatsapp.com/send?phone=$number&text=${android.net.Uri.encode(msg)}")
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
                "play_music" -> {
                    val query = action.optString("query")
                    val service = action.optString("service", "spotify")
                    val intent = if (service == "spotify") {
                        Intent(Intent.ACTION_VIEW, android.net.Uri.parse("spotify:search:${android.net.Uri.encode(query)}"))
                    } else {
                        Intent(Intent.ACTION_SEARCH).apply {
                            setPackage("com.google.android.apps.youtube.music")
                            putExtra(android.app.SearchManager.QUERY, query)
                        }
                    }
                    runCatching { startActivity(intent) }
                }
                "note" -> {
                    val title = action.optString("title")
                    val content = action.optString("content")
                    val samsungIntent = packageManager.getLaunchIntentForPackage("com.samsung.android.app.notes")
                    if (samsungIntent != null) {
                        startActivity(Intent(Intent.ACTION_SEND).apply {
                            setPackage("com.samsung.android.app.notes")
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, title)
                            putExtra(Intent.EXTRA_TEXT, content)
                        })
                    } else {
                        startActivity(Intent(Intent.ACTION_INSERT).apply {
                            data = android.net.Uri.parse("content://com.google.android.keep")
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, title)
                            putExtra(Intent.EXTRA_TEXT, content)
                        })
                    }
                }
                "open_url" -> {
                    val url = action.optString("url")
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                }
            }
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "App konnte nicht geöffnet werden", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // ── Nachricht senden ──────────────────────────────────────────────────────
    private fun sendToBackend(message: String) {
        statusText.text = "DONNA DENKT…"
        statusText.setTextColor(colorMuted)
        loadingBar.visibility = View.VISIBLE
        cardContainer.visibility = View.GONE
        responseText.visibility = View.GONE
        sendButton.isEnabled = false
        micButton.isEnabled = false

        val location = getLastLocation()

        scope.launch {
            val sseResult = withContext(Dispatchers.IO) {
                try {
                    val url = URL(BuildConfig.DONNA_API_URL + "/chat")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Authorization", "Bearer ${TokenStore.getToken(this@VoiceInputActivity) ?: ""}")
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("Accept", "text/event-stream")
                        currentSessionId?.let { setRequestProperty("X-Session-ID", it) }
                        if (isAuthenticated) setRequestProperty("X-Biometric-Auth", "true")
                        doOutput = true
                        connectTimeout = 20_000
                        readTimeout = 120_000
                    }
                    val body = JSONObject().apply {
                        put("message", message)
                        put("stream", true)
                        location?.let { put("lat", it.latitude); put("lon", it.longitude) }
                    }.toString()
                    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
                    if (conn.responseCode == 200) {
                        // Session-ID aus Response-Header lesen und merken
                        conn.getHeaderField("X-Session-ID")?.let { currentSessionId = it }
                        parseSseStream(conn.inputStream)
                    } else {
                        SseResult("Fehler ${conn.responseCode}")
                    }
                } catch (e: Exception) {
                    SseResult("Verbindungsfehler: ${e.localizedMessage}")
                }
            }

            loadingBar.visibility = View.GONE
            sendButton.isEnabled = true
            micButton.isEnabled = true

            // Karte anzeigen
            cardContainer.removeAllViews()
            when {
                sseResult.weatherData != null -> {
                    cardContainer.addView(buildWeatherCard(sseResult.weatherData))
                    cardContainer.visibility = View.VISIBLE
                }
                sseResult.mapUrl != null -> {
                    cardContainer.addView(buildMapCard(sseResult.mapUrl))
                    cardContainer.visibility = View.VISIBLE
                }
            }

            // Aktions-Karten anzeigen (Ja/Nein-Buttons)
            sseResult.actions.forEach { action ->
                val actionCard = buildActionCard(action)
                if (actionCard != null) cardContainer.addView(actionCard)
            }
            if (sseResult.actions.isNotEmpty()) cardContainer.visibility = View.VISIBLE

            // Textantwort mit Markdown-Rendering
            if (sseResult.text.isNotBlank()) {
                responseText.text = Html.fromHtml(
                    markdownToHtml(sseResult.text),
                    Html.FROM_HTML_MODE_COMPACT,
                )
                responseText.visibility = View.VISIBLE
                // Antwort vorlesen (Piper-Backend primär, System-TTS als Fallback)
                speakViaPiper(sseResult.text)
            }
            statusText.text = "HALTEN ZUM REDEN"
            statusText.setTextColor(colorMuted)
        }
    }

    // ── Biometric-Authentifizierung ──────────────────────────────────────────

    private fun authenticateWithBiometric() {
        val biometricManager = BiometricManager.from(this)
        // BIOMETRIC_STRONG (Fingerabdruck) + BIOMETRIC_WEAK (Samsung Face ID) + PIN als Fallback
        val allowedAuth =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val canAuth = biometricManager.canAuthenticate(allowedAuth)
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            // Kein Biometric verfügbar — Overlay trotzdem öffnen, kein Brain-Zugriff
            isAuthenticated = false
            return
        }
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                isAuthenticated = true
                statusText.text = "HEY MIKE"
                statusText.setTextColor(colorAccent)
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                isAuthenticated = false
            }
            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                isAuthenticated = false
            }
        })
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Donna")
            .setSubtitle("Fingerabdruck oder Gesicht")
            .setConfirmationRequired(false)  // kein "Bestätigen"-Button nach Gesichtserkennung
            .setAllowedAuthenticators(allowedAuth)
            .build()
        prompt.authenticate(promptInfo)
    }

    // ── Gesprächsverlauf ─────────────────────────────────────────────────────

    private data class SessionInfo(
        val sessionId: String,
        val startedAt: Double,
        val preview: String,
        val messageCount: Int,
    )

    private fun showHistoryDialog() {
        scope.launch {
            val sessions = withContext(Dispatchers.IO) { fetchSessions() }
            showHistoryPanel(sessions)
        }
    }

    private fun showHistoryPanel(sessions: List<SessionInfo>) {
        val ctx = this
        historyPanel.removeAllViews()
        historyPanel.visibility = View.VISIBLE

        // ── Trennlinie ──
        historyPanel.addView(View(ctx).apply {
            setBackgroundColor(colorBorder)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
            ).also { it.bottomMargin = dp(10) }
        })

        // ── Header: "GESPRÄCHE HEUTE" + ×-Button ──
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(8) }
        }
        headerRow.addView(TextView(ctx).apply {
            text = "GESPRÄCHE HEUTE"
            setTextColor(colorMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            letterSpacing = 0.2f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        headerRow.addView(TextView(ctx).apply {
            text = "×"
            setTextColor(colorMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            isClickable = true
            isFocusable = true
            setOnClickListener { hideHistoryPanel() }
        })
        historyPanel.addView(headerRow)

        // ── "Neues Gespräch"-Button ──
        historyPanel.addView(buildHistoryItem(
            time = "+",
            preview = "Neues Gespräch starten",
            accent = true
        ) {
            currentSessionId = null
            hideHistoryPanel()
            statusText.text = "HALTEN ZUM REDEN"
            statusText.setTextColor(colorMuted)
        })

        if (sessions.isEmpty()) {
            historyPanel.addView(TextView(ctx).apply {
                text = "Keine Gespräche der letzten 24 Stunden"
                setTextColor(colorMuted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(6), 0, dp(6))
            })
        } else {
            val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.GERMAN)
            sessions.forEach { s ->
                val time = fmt.format(java.util.Date((s.startedAt * 1000).toLong()))
                val preview = s.preview.take(58) + if (s.preview.length > 58) "…" else ""
                historyPanel.addView(buildHistoryItem(time, preview, accent = false) {
                    currentSessionId = s.sessionId
                    hideHistoryPanel()
                    responseText.visibility = View.GONE
                    cardContainer.removeAllViews()
                    cardContainer.visibility = View.GONE
                    statusText.text = "GESPRÄCH FORTSETZEN"
                    statusText.setTextColor(colorAccent)
                })
            }
        }
    }

    private fun buildHistoryItem(
        time: String,
        preview: String,
        accent: Boolean,
        onClick: () -> Unit,
    ): LinearLayout {
        val ctx = this
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = GradientDrawable().apply {
                setColor(colorSurface)
                cornerRadius = dp(8).toFloat()
                setStroke(1, colorBorder)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(6) }
            setOnClickListener { onClick() }

            // Zeit / Icon
            addView(TextView(ctx).apply {
                text = time
                setTextColor(if (accent) colorAccent else colorMuted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                minWidth = dp(36)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.marginEnd = dp(10) }
            })
            // Preview
            addView(TextView(ctx).apply {
                text = preview
                setTextColor(if (accent) colorAccent2 else colorText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
    }

    private fun hideHistoryPanel() {
        historyPanel.visibility = View.GONE
        historyPanel.removeAllViews()
    }

    private fun fetchSessions(): List<SessionInfo> {
        return try {
            val url = java.net.URL(BuildConfig.DONNA_API_URL + "/stm/sessions")
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer ${TokenStore.getToken(this@VoiceInputActivity) ?: ""}")
                connectTimeout = 5_000
                readTimeout = 5_000
            }
            if (conn.responseCode != 200) return emptyList()
            val json = conn.inputStream.bufferedReader().readText()
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                SessionInfo(
                    sessionId = obj.getString("session_id"),
                    startedAt = obj.getDouble("started_at"),
                    preview = obj.optString("preview", "…"),
                    messageCount = obj.optInt("message_count", 0),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
