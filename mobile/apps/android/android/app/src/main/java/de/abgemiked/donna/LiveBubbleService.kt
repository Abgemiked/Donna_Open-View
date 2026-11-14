package com.yourcompany.donna

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.content.ActivityNotFoundException
import android.net.Uri
import com.yourcompany.donna.overlay.ActionCardBuilder
import com.yourcompany.donna.overlay.ActionExecutor
import org.json.JSONObject

/**
 * LiveBubbleService (A2, Increment 1) — verschiebbarer, NICHT-modaler, touch-durchlässiger Chat-Head
 * („Live-Bubble") über allen Apps, wie Gemini/Bixby.
 *
 * Increment 1: schwebende Blase (WindowManager, TYPE_APPLICATION_OVERLAY,
 * FLAG_NOT_FOCUSABLE/FLAG_NOT_TOUCH_MODAL → App dahinter bleibt bedienbar), per Finger verschiebbar
 * (Position gemerkt + auf den Bildschirm geklemmt), langes Drücken beendet die Blase.
 *
 * Increment 3a: Tippen klappt ein NICHT-MODALES In-Overlay-Panel auf/zu (Kopfzeile mit ziehbarem Titel
 * + Schließen, Gesprächs-Container) statt die Vollbild-Activity zu starten. Das Panel bleibt nicht-modal:
 * die App dahinter ist außerhalb des Panels weiter bedienbar. VoiceInputActivity = nur noch Lockscreen-Fallback
 * (E3 2026-11-01: der frühere „Sprechen"→Alt-Overlay-Handover ist entfernt — Reden läuft ab 3c/3d in der Blase).
 *
 * Increment 3c/3d (gelocktes Modell 2026-11-01): die vollständige Gesprächsschleife läuft IM Service über
 * den [ConversationController] — beim Erscheinen der Blase öffnet sich das Panel und Donna HÖRT SOFORT ZU
 * (STT), auf Sprache streamt die Antwort ins Panel (Transkript + Antworttext) und wird gesprochen
 * (SpeechDriver, Audio-Fokus); Barge-in lauscht währenddessen. KEIN Begrüßungs-Tap, KEIN Weg mehr ins alte
 * Vollbild-Overlay (nur Lockscreen-Fallback). ✕/langes Drücken beenden die Sitzung.
 *
 * Spätere Increments: A4 MediaProjection/Kamera als Vision-Quellen, A5 transparente moderne Optik,
 * Aktions-/Wetter-/Map-Karten im Panel (in 3c/3d zurückgestellt).
 *
 * START_NOT_STICKY: nach Prozess-Kill NICHT ohne Vordergrund-Kontext wieder-starten. FGS-Typ = specialUse
 * (schwebende Blase) + microphone (Dauer-STT der Schleife, Android 14+ Pflicht für AudioRecord/SpeechRecognizer).
 */
class LiveBubbleService : Service(), ConversationController.Host {

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var dragHandler: Handler? = null

    // Increment 3a: nicht-modales In-Overlay-Panel (ersetzt beim Tap die alte Vollbild-Activity).
    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null

    // Increment 3c/3d: die vollständige Gesprächsschleife (STT + SSE /chat + TTS + Barge-in) IM Service.
    private var controller: ConversationController? = null

    // Panel-Inhalts-Views (bei Rotation neu gebaut → über Felder referenziert) + gecachter Zustand fürs Rebuild.
    private var statusView: TextView? = null
    private var transcriptView: TextView? = null
    private var answerView: TextView? = null
    private var answerScroll: ScrollView? = null
    private var cachedStatus: String = STATUS_LISTENING
    private var cachedTranscript: String = ""
    private var cachedAnswer: CharSequence = ""
    private var cardContainer: LinearLayout? = null
    // Karten (Aktion/Wetter/Map) als DATEN gecacht → überstehen den Rotations-Rebuild (onConfigurationChanged).
    private val cachedCards = mutableListOf<CardSpec>()
    // View → zugehörige CardSpec: entfernt der Ja/Nein-Tap die Karten-View, nimmt der cardContainer-
    // OnHierarchyChangeListener die Spec aus cachedCards — sonst ließe ein Rotations-Rebuild eine bereits
    // beantwortete Aktionskarte wieder auferstehen (erneut ausführbar).
    private val specOf = java.util.IdentityHashMap<View, CardSpec>()
    private var cardsArmed = false

    private sealed class CardSpec {
        class Action(val action: JSONObject) : CardSpec()
        class Weather(val data: JSONObject) : CardSpec()
        class Map(val url: String) : CardSpec()
    }

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfAndBubble()
            return START_NOT_STICKY
        }
        if (!startAsForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = if (showBubble()) {
            // Gelocktes Modell: Blase erschienen → Panel öffnen und SOFORT zuhören (Funktionstasten-Flow).
            if (controller == null) controller = ConversationController(this, this)
            showPanel()
            controller?.start()
            true
        } else {
            stopSelfAndBubble()
            false
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        removeBubble()
        running = false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Blase in den (ggf. gedrehten/gefalteten) Bildschirm klemmen.
        val bv = bubbleView
        val bp = params
        if (bv != null && bp != null) {
            clampPosition(bp)
            try { windowManager?.updateViewLayout(bv, bp) } catch (_: Exception) {}
        }
        // Offenes Panel neu aufbauen → frische Breite, Farben (Hell/Dunkel) und Clamp. Die Gesprächsschleife
        // (controller) läuft unverändert weiter; buildPanel wendet den gecachten Zustand wieder an.
        if (panelView != null) {
            hidePanel()
            showPanel()
        }
    }

    // ── Foreground-Notification (Pflicht für Foreground-Service) ───────────────
    private fun startAsForeground(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Donna Live-Bubble", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Zeigt Donnas schwebende Blase an"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setContentTitle("Donna")
            .setContentText("Donna-Gespräch aktiv")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // specialUse (schwebende Blase) + microphone NUR wenn RECORD_AUDIO erteilt. startForeground mit
                // MICROPHONE-Typ ohne das Recht wirft auf Android 14+ eine SecurityException → der Service stürbe
                // und der Mikro-Hinweis käme nie. Ohne Recht nur specialUse; der Controller zeigt dann den
                // Onboarding-Hinweis (und der Nutzer erteilt das Mikro im Onboarding).
                val micGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.RECORD_AUDIO,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val fgsType = if (micGranted) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
                startForeground(NOTIF_ID, notification, fgsType)
            } else {
                startForeground(NOTIF_ID, notification)
            }
            true
        } catch (e: Exception) {
            android.util.Log.w(TAG, "startForeground fehlgeschlagen: ${e.message}")
            false
        }
    }

    // ── Chat-Head anzeigen ──────────────────────────────────────────────────────
    /** @return true, wenn die Blase tatsächlich angezeigt wird. */
    private fun showBubble(): Boolean {
        if (bubbleView != null) return true
        if (!Settings.canDrawOverlays(this)) return false
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val view = buildBubble()
        bubbleView = view

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            // NOT_FOCUSABLE + NOT_TOUCH_MODAL: Touches außerhalb der Blase gehen an die App dahinter
            // (die bleibt voll bedienbar); LAYOUT_NO_LIMITS: frei schiebbar (Position wird geklemmt).
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = prefs.getInt(PREF_X, dp(16))
            y = prefs.getInt(PREF_Y, dp(120))
        }
        clampPosition(lp)   // gespeicherte/Default-Position könnte nach Rotation/Fold offscreen sein
        params = lp
        attachDrag(view, lp, wm)
        return try {
            wm.addView(view, lp)
            true
        } catch (e: Exception) {
            android.util.Log.w(TAG, "addView fehlgeschlagen: ${e.message}")
            bubbleView = null
            false
        }
    }

    private fun buildBubble(): View {
        val size = dp(56)
        val container = FrameLayout(this)
        val icon = TextView(this).apply {
            text = "🎙"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeColor(R.color.donna_accent))
                setStroke(dp(2), themeColor(R.color.donna_on_accent))
            }
        }
        container.addView(icon, FrameLayout.LayoutParams(size, size))
        container.contentDescription = "Donna Live-Bubble — tippen zum Öffnen, lange drücken zum Schließen"
        return container
    }

    /** Hält die Blase vollständig auf dem Bildschirm (kein Verschwinden nach Drag/Rotation). */
    private fun clampPosition(lp: WindowManager.LayoutParams) {
        val size = dp(56)
        val dm = resources.displayMetrics
        lp.x = lp.x.coerceIn(0, (dm.widthPixels - size).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (dm.heightPixels - size).coerceAtLeast(0))
    }

    // ── Drag + Tap + Long-Press ──────────────────────────────────────────────────
    @Suppress("ClickableViewAccessibility")  // Increment 1: Overlay-Blase; contentDescription gesetzt
    private fun attachDrag(view: View, lp: WindowManager.LayoutParams, wm: WindowManager) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        val handler = Handler(mainLooper)
        dragHandler = handler
        val longPressRunnable = Runnable { if (!moved) stopSelfAndBubble() }

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    handler.postDelayed(longPressRunnable, longPressMs)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (!moved && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                        moved = true
                        handler.removeCallbacks(longPressRunnable)
                    }
                    if (moved) {
                        lp.x = startX + dx
                        lp.y = startY + dy
                        clampPosition(lp)
                        try { wm.updateViewLayout(view, lp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (moved) {
                        prefs.edit().putInt(PREF_X, lp.x).putInt(PREF_Y, lp.y).apply()
                    } else if (event.action == MotionEvent.ACTION_UP) {
                        v.performClick()
                        onBubbleTapped()
                    }
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Tap bringt das Panel wieder her, falls es geschlossen wurde. Im gelockten Modell läuft das Gespräch mit
     * der Blase (Service); das Panel ist während der Sitzung offen, ✕ oder langes Drücken beenden die Sitzung.
     */
    private fun onBubbleTapped() {
        // Aus IDLE/ERROR (Status „Tippe die Blase an, um weiterzureden") zurück ins Zuhören; self-guard,
        // d. h. no-op während Donna aktiv lauscht/spricht. Danach sicherstellen, dass das Panel sichtbar ist.
        controller?.resumeIfIdle()
        if (panelView == null) showPanel()
    }

    // ── Nicht-modales Gesprächs-Panel ───────────────────────────────────────────
    /** Overlay-Fenstertyp (TYPE_APPLICATION_OVERLAY ab O, sonst TYPE_PHONE). */
    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun showPanel() {
        if (panelView != null) return
        val wm = windowManager ?: return
        if (!Settings.canDrawOverlays(this)) return
        // Feste Breite (für X-Clamp bekannt), max. Bildschirmbreite − Rand; Höhe wächst mit Inhalt.
        val w = minOf(dp(360), (resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(dp(240)))
        val lp = WindowManager.LayoutParams(
            w,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            // NICHT-MODAL: ohne FLAG_LAYOUT_NO_LIMITS bleibt das Panel im Bildschirm; NOT_FOCUSABLE +
            // NOT_TOUCH_MODAL → Tippen außerhalb des Panels bedient die App dahinter weiter. (Kein
            // Texteingabefeld in dieser Shell; IME-Fokus kommt erst mit dem Loop in 3b–3d.)
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = prefs.getInt(PREF_PANEL_X, dp(12))
            y = prefs.getInt(PREF_PANEL_Y, (params?.y ?: dp(120)) + dp(64))
        }
        applyPanelBlur(lp)
        clampPanel(lp, w)
        panelParams = lp
        try {
            val view = buildPanel(lp, wm)
            wm.addView(view, lp)
            panelView = view
            // Nach dem Layout mit der ECHTEN Höhe neu klemmen (Default-/Drag-Y könnte unten überstehen).
            view.post {
                val p = panelParams ?: return@post
                clampPanel(p, p.width)
                try { wm.updateViewLayout(view, p) } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Panel addView fehlgeschlagen: ${e.message}")
            panelView = null
            panelParams = null
        }
    }

    private fun hidePanel() {
        // Nur die Panel-Views entfernen — die Gesprächsschleife (controller) läuft unberührt weiter
        // (Sitzungs-Teardown passiert über stopSelfAndBubble/removeBubble).
        panelView?.let { v -> try { windowManager?.removeView(v) } catch (_: Exception) {} }
        panelView = null
        panelParams = null
        statusView = null
        transcriptView = null
        answerView = null
        answerScroll = null
        cardContainer = null // nur die View-Referenz; cachedCards bleibt für den Rebuild
        // View→Spec-Map leeren: sonst pinnen die alten Karten-Views den detachten Panel-Baum bis zum
        // nächsten clearCards. cachedCards bleibt die Wahrheit; buildPanel/renderCard füllt specOf neu.
        specOf.clear()
    }

    /** Hält das Panel vollständig im Bildschirm (X per bekannter Breite, Y per echter/geschätzter Höhe). */
    private fun clampPanel(lp: WindowManager.LayoutParams, w: Int) {
        val dm = resources.displayMetrics
        val h = panelView?.height?.takeIf { it > 0 } ?: dp(220)
        lp.x = lp.x.coerceIn(0, (dm.widthPixels - w).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (dm.heightPixels - h).coerceAtLeast(0))
    }

    private fun buildPanel(lp: WindowManager.LayoutParams, wm: WindowManager): View {
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(12), pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(22).toFloat()
                // A5: etwas transparenter Panel-Grund (App dahinter scheint durch) — Lesbarkeit bleibt durch den
                // getönten Grund und (wo verfügbar) den Fenster-Blur dahinter; ohne Blur dient das höhere Alpha als Scrim.
                setColor(panelSurfaceColor())
                setStroke(dp(1), themeColor(R.color.donna_border))
            }
            elevation = dp(8).toFloat()
            contentDescription = "Donna Live-Panel"
        }

        // Kopfzeile: ganze Zeile ziehbar (Drag am Header) + eigenständiger Schließen-Button.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            contentDescription = "Donna — Kopfzeile ziehen zum Verschieben"
        }
        val title = TextView(this).apply {
            text = "Donna"
            setTextColor(themeColor(R.color.donna_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val close = TextView(this).apply {
            text = "✕"
            setTextColor(themeColor(R.color.donna_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            minWidth = dp(48)
            minHeight = dp(48)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            contentDescription = "Gespräch beenden"
            setOnClickListener { stopSelfAndBubble() }
        }
        header.addView(title)
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) // Spacer
        header.addView(close)
        root.addView(
            header,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        // Drag am ganzen Header: Touches über „✕" verbraucht der Button selbst (bleibt klickbar).
        attachPanelDrag(header, lp, wm)

        // Statuszeile (hört zu / denkt / spricht / Fehler).
        val status = TextView(this).apply {
            text = cachedStatus
            setTextColor(themeColor(R.color.donna_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(10), 0, dp(4))
            // FIX 2 Tap-to-Stop: Tippen auf die Statuszeile stoppt Eva SOFORT, während Donna spricht (unabhängig
            // vom Sprach-Barge-in; „ich hab's schon gelesen"). Eigene Kind-View → kollidiert nicht mit ✕/Karten;
            // onClick funktioniert ohne Fensterfokus (Panel bleibt FLAG_NOT_FOCUSABLE).
            minHeight = dp(48) // tappbares Stop-Ziel ≥ 48dp
            contentDescription = "Donna-Status — während des Sprechens tippen zum Stoppen"
            setOnClickListener { controller?.stopSpeakingByTap() }
        }
        statusView = status
        root.addView(
            status,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        // Mikes Äußerung (Transkript, partiell + final).
        val transcript = TextView(this).apply {
            text = cachedTranscript
            visibility = if (cachedTranscript.isBlank()) View.GONE else View.VISIBLE
            setTextColor(themeColor(R.color.donna_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(2), 0, dp(8))
        }
        transcriptView = transcript
        root.addView(
            transcript,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        // Antwort + Karten in EINEM höhengedeckelten ScrollView: so wird das Panel nie höher als der
        // Bildschirm und die Ja/Nein-Buttons bleiben IMMER per Scrollen erreichbar (Review-Finding, Querformat).
        val answer = TextView(this).apply {
            text = cachedAnswer
            setTextColor(themeColor(R.color.donna_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        answerView = answer
        val cards = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (cachedCards.isEmpty()) View.GONE else View.VISIBLE
            setPadding(0, dp(8), 0, 0)
            // Ja/Nein-Tap entfernt die Karten-View → zugehörige Spec aus dem Cache nehmen (keine Resurrection).
            setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View?, child: View?) {}
                override fun onChildViewRemoved(parent: View?, child: View?) {
                    specOf.remove(child)?.let { cachedCards.remove(it) }
                }
            })
        }
        cardContainer = cards
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(answer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(cards, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val maxContentH = minOf(dp(420), resources.displayMetrics.heightPixels * 55 / 100)
        val scroll = object : ScrollView(this@LiveBubbleService) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxContentH, MeasureSpec.AT_MOST))
        }.apply {
            visibility = if (cachedAnswer.isBlank() && cachedCards.isEmpty()) View.GONE else View.VISIBLE
            addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        answerScroll = scroll
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        cachedCards.toList().forEach { renderCard(it) }
        if (cardsArmed) armActionButtons()

        return root
    }

    /** Panel per Titel verschieben (Schließen-Button behält eigenen Klick). */
    @Suppress("ClickableViewAccessibility") // Titel trägt contentDescription; Drag ist Zusatzgeste.
    private fun attachPanelDrag(handle: View, lp: WindowManager.LayoutParams, wm: WindowManager) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y; touchX = event.rawX; touchY = event.rawY; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (event.rawX - touchX).toInt()
                    lp.y = startY + (event.rawY - touchY).toInt()
                    clampPanel(lp, lp.width)
                    moved = true
                    panelView?.let { try { wm.updateViewLayout(it, lp) } catch (_: Exception) {} }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved) prefs.edit().putInt(PREF_PANEL_X, lp.x).putInt(PREF_PANEL_Y, lp.y).apply()
                    true
                }
                else -> false
            }
        }
    }

    private fun removeBubble() {
        hidePanel()
        dragHandler?.removeCallbacksAndMessages(null)
        dragHandler = null
        bubbleView?.let { v -> try { windowManager?.removeView(v) } catch (_: Exception) {} }
        bubbleView = null
        params = null
        controller?.release() // STT/Barge-in/Pipeline/TTS/Audio-Fokus freigeben (nur terminal gerufen)
        controller = null
    }

    private fun stopSelfAndBubble() {
        removeBubble()
        running = false
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        stopSelf()
    }

    private fun themeColor(id: Int): Int = androidx.core.content.ContextCompat.getColor(this, id)
    private fun dp(n: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, n.toFloat(), resources.displayMetrics).toInt()

    /** Farbe mit überschriebenem Alpha-Kanal (0..255) — für den halbtransparenten Panel-Grund (A5). */
    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

    /**
     * A5: Cross-Window-Blur nur ab Android 12 UND wenn das System ihn aktuell erlaubt (Akkusparen/GPU-Limit
     * schalten ihn ab). Fällt er weg, bleibt der Panel-Grund als lesbarer Scrim (höheres Alpha).
     */
    private fun blurSupported(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return windowManager?.isCrossWindowBlurEnabled == true
    }

    /** A5: Panel-Grund — mit Blur frosted/transparenter, ohne Blur als lesbarer Scrim. */
    private fun panelSurfaceColor(): Int =
        withAlpha(themeColor(R.color.donna_surface), if (blurSupported()) PANEL_ALPHA_BLUR else PANEL_ALPHA_PLAIN)

    /** A5: frosted-glass-Blur hinter dem Panel (OneUI-9/Gemini-Look), wenn unterstützt. */
    private fun applyPanelBlur(lp: WindowManager.LayoutParams) {
        // SDK-Guard direkt hier (nicht nur in blurSupported()), damit der Lint-NewApi-Check die API-31-Zugriffe
        // auf blurBehindRadius/FLAG_BLUR_BEHIND als abgesichert erkennt.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (windowManager?.isCrossWindowBlurEnabled != true) return
        lp.blurBehindRadius = dp(24)
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
    }

    // ── ConversationController.Host (Panel-UI-Updates, Main-Thread) ────────────────
    override fun onStatus(state: OverlayState) {
        cachedStatus = when (state) {
            OverlayState.LISTENING -> STATUS_LISTENING
            OverlayState.THINKING -> STATUS_THINKING
            OverlayState.SPEAKING -> STATUS_SPEAKING
            OverlayState.IDLE -> STATUS_IDLE
            OverlayState.ERROR -> cachedStatus // Fehlertext kommt über onError
        }
        statusView?.text = cachedStatus
    }

    override fun onPartialTranscript(text: String) {
        cachedTranscript = text
        transcriptView?.let { it.text = text; it.visibility = View.VISIBLE }
    }

    override fun onUserUtterance(text: String) {
        cachedTranscript = text
        transcriptView?.let { it.text = text; it.visibility = View.VISIBLE }
        // Neue Frage → alte Antwort leeren
        cachedAnswer = ""
        answerView?.text = ""
        // Scroll nur verbergen, wenn auch keine Karten da sind (Antwort + Karten teilen sich den Scroll).
        if (cachedCards.isEmpty()) answerScroll?.visibility = View.GONE
    }

    override fun setAnswerHtml(html: CharSequence) {
        cachedAnswer = html
        answerView?.text = html
    }

    override fun showAnswer() {
        answerScroll?.visibility = View.VISIBLE
    }

    override fun autoscroll() {
        answerScroll?.let { s -> s.post { s.fullScroll(View.FOCUS_DOWN) } }
    }

    override fun onError(message: String) {
        cachedStatus = message
        statusView?.text = message
    }

    // ── Karten (Aktion/Wetter/Map) ────────────────────────────────────────────────
    override fun onActionCard(action: JSONObject) = addCard(CardSpec.Action(action))
    override fun onWeatherCard(data: JSONObject) = addCard(CardSpec.Weather(data))
    override fun onMapCard(url: String) {
        if (UrlPolicy.check(url) == null) return // verbotenes Schema → keine Karte
        addCard(CardSpec.Map(url))
    }
    override fun clearCards() {
        cachedCards.clear() // ZUERST leeren: das folgende removeAllViews feuert onChildViewRemoved ins leere Cache
        cardsArmed = false
        cardContainer?.let { it.removeAllViews(); it.visibility = View.GONE }
        specOf.clear()
    }
    override fun armCards() { cardsArmed = true; armActionButtons() }
    // Cache-basiert statt View-Baum: nach einem Ja/Nein-Tap ist die Spec aus cachedCards entfernt → kein
    // falsches Blockieren des Relisten; robust auch wenn das Panel gerade nicht gebaut ist.
    override fun hasPendingActionCards(): Boolean = cachedCards.any { it is CardSpec.Action }

    /** Neue Karte: ERST bauen — nur was eine View ergibt, wird gecacht (sonst zählt hasPendingActionCards falsch). */
    private fun addCard(spec: CardSpec) {
        val view = buildCardView(spec) ?: return
        cachedCards.add(spec)
        val c = cardContainer ?: return // kein Panel: Cache genügt, der Rebuild rendert die Karte neu
        specOf[view] = spec
        c.addView(view)
        c.visibility = View.VISIBLE
        answerScroll?.visibility = View.VISIBLE // Karte sichtbar machen, auch ohne Antworttext
    }

    private val actionExecutor by lazy {
        ActionExecutor(object : ActionExecutor.Host {
            // WIRFT bewusst (kein safeStart): fehlt die Ziel-App, fängt der ActionExecutor die Exception und
            // nutzt seinen ACTION_SEARCH-/onActionFailed-Fallback (wie in VoiceInputActivity).
            override fun startActivity(intent: Intent) {
                this@LiveBubbleService.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            override fun getLaunchIntentForPackage(packageName: String): Intent? =
                packageManager.getLaunchIntentForPackage(packageName)
            override fun sendToTesla(target: String) = this@LiveBubbleService.sendToTesla(target)
            override fun teslaTargetOf(action: JSONObject): String = NavActionLogic.targetOfAction(action)
            override fun openCheckedUrl(checked: UrlPolicy.Checked?) = this@LiveBubbleService.openCheckedUrl(checked)
            override fun onActionFailed(e: Exception) { toast(startFailureMessage(e)) }
        })
    }
    private val actionCardBuilder by lazy {
        ActionCardBuilder(object : ActionCardBuilder.Host {
            override val ctx: Context get() = this@LiveBubbleService
            override fun dp(n: Int): Int = this@LiveBubbleService.dp(n)
            override fun setRole(v: View, className: String, checked: (() -> Boolean)?) { /* a11y-Rolle optional; Buttons tragen contentDescription */ }
            override val armTag: String get() = ARM_TAG
            override val colorSurface: Int get() = themeColor(R.color.donna_surface)
            override val colorBorder: Int get() = themeColor(R.color.donna_border)
            override val colorText: Int get() = themeColor(R.color.donna_text)
            override val colorOnAccent: Int get() = themeColor(R.color.donna_on_accent)
            override val colorAccent: Int get() = themeColor(R.color.donna_accent)
            override val colorMuted: Int get() = themeColor(R.color.donna_muted)
            override val colorCard: Int get() = themeColor(R.color.donna_card)
            // SICHER: Aktion läuft NUR auf expliziten Ja-Tap (= Bestätigung), nie automatisch (kein maybeAutoSend).
            override fun executeAction(action: JSONObject) = actionExecutor.execute(action)
            override fun sendToTesla(target: String) = this@LiveBubbleService.sendToTesla(target)
            override fun startMapsNavigation(dest: String, lat: Double?, lon: Double?) =
                this@LiveBubbleService.startMapsNavigation(dest, lat, lon)
            override fun teslaTargetOf(action: JSONObject): String = NavActionLogic.targetOfAction(action)
        })
    }

    /** Baut die View zu einer Karten-Spec (Null = nicht darstellbar → nicht cachen/rendern). */
    private fun buildCardView(spec: CardSpec): View? = when (spec) {
        is CardSpec.Action -> actionCardBuilder.buildActionCard(spec.action)?.also { it.tag = ACTION_CARD_TAG }
        is CardSpec.Weather -> buildWeatherCard(spec.data)
        is CardSpec.Map -> buildMapCard(spec.url)
    }

    /** Hängt eine gecachte Karte beim (Rotations-)Rebuild an den cardContainer. */
    private fun renderCard(spec: CardSpec) {
        val container = cardContainer ?: return
        val view = buildCardView(spec) ?: return
        specOf[view] = spec
        container.addView(view)
        container.visibility = View.VISIBLE
    }

    /** Bestätigen-Tasten scharf schalten (walk über alle ARM_TAG-Views). */
    private fun armActionButtons() {
        val container = cardContainer ?: return
        fun walk(v: View) {
            if (v.tag == ARM_TAG) { v.isEnabled = true; v.alpha = 1f }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(container)
    }

    // Wetter-Karte (aus VoiceInputActivity übertragen; Service-Context + themeColor).
    private fun buildWeatherCard(d: JSONObject): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(themeColor(R.color.donna_surface)); cornerRadius = dp(14).toFloat(); setStroke(1, themeColor(R.color.donna_border))
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.bottomMargin = dp(8) }
        }
        card.addView(TextView(this).apply {
            text = JsonSafe.str(d, "location", ""); setTextColor(themeColor(R.color.donna_muted)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(4) }
        })
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(4) }
        }
        topRow.addView(TextView(this).apply {
            text = "${JsonSafe.numText(d, "temp_c")}°"; setTextColor(themeColor(R.color.donna_text)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 52f)
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        topRow.addView(TextView(this).apply {
            text = JsonSafe.str(d, "condition_icon", "🌡️"); setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
        })
        card.addView(topRow)
        card.addView(TextView(this).apply {
            text = JsonSafe.str(d, "condition", ""); setTextColor(themeColor(R.color.donna_accent2)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(10) }
        })
        card.addView(View(this).apply {
            background = GradientDrawable().apply { setColor(themeColor(R.color.donna_border)) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).also { it.bottomMargin = dp(10) }
        })
        val detailsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        listOf(
            "↓${JsonSafe.numText(d, "temp_min")}° ↑${JsonSafe.numText(d, "temp_max")}°",
            "💧 ${JsonSafe.numText(d, "humidity")}%",
            "💨 ${JsonSafe.numText(d, "wind_kmh")} km/h",
            "Gefühlt ${JsonSafe.numText(d, "feels_like_c")}°",
        ).forEach { detail ->
            detailsRow.addView(TextView(this).apply {
                text = detail; setTextColor(themeColor(R.color.donna_muted)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        card.addView(detailsRow)
        return card
    }

    // Maps-Karte (aus VoiceInputActivity übertragen). URL wurde in onMapCard bereits gegen die Policy geprüft.
    private fun buildMapCard(mapsUrl: String): View {
        val checked = UrlPolicy.check(mapsUrl)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(themeColor(R.color.donna_surface)); cornerRadius = dp(14).toFloat(); setStroke(1, themeColor(R.color.donna_border))
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.bottomMargin = dp(8) }
            setOnClickListener { openCheckedUrl(checked) }
            addView(TextView(this@LiveBubbleService).apply {
                text = "🗺️"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.marginEnd = dp(12) }
            })
            addView(LinearLayout(this@LiveBubbleService).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@LiveBubbleService).apply {
                    text = "In Google Maps öffnen"; setTextColor(themeColor(R.color.donna_accent)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                })
                addView(TextView(this@LiveBubbleService).apply {
                    text = if (checked != null) "Ziel: ${checked.displayTarget()}" else "Link nicht erlaubt"
                    setTextColor(themeColor(R.color.donna_muted)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                })
            })
            addView(TextView(this@LiveBubbleService).apply {
                text = "›"; setTextColor(themeColor(R.color.donna_accent)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            })
        }
    }

    private fun sendToTesla(target: String) {
        val msg = when (TeslaNav.send(this, target)) {
            TeslaNav.Result.SENT -> "An Tesla-App übergeben"
            TeslaNav.Result.NOT_INSTALLED -> "Tesla-App nicht installiert"
            TeslaNav.Result.EMPTY -> "Kein Ziel angegeben"
            TeslaNav.Result.FAILED -> "Senden an Tesla fehlgeschlagen"
        }
        toast(msg)
    }

    private fun startMapsNavigation(dest: String, lat: Double?, lon: Double?) {
        val q = if (lat != null && lon != null) "$lat,$lon" else Uri.encode(dest)
        val uri = Uri.parse("google.navigation:q=$q&mode=d")
        safeStart(Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.google.android.apps.maps") })
    }

    private fun openCheckedUrl(checked: UrlPolicy.Checked?) {
        if (checked == null) { toast("Dieser Link wird aus Sicherheitsgründen nicht geöffnet."); return }
        safeStart(Intent(Intent.ACTION_VIEW, Uri.parse(checked.url)))
    }

    /** startActivity aus dem Service: IMMER mit NEW_TASK (SAW erlaubt den Start); freundliche Meldung statt Crash. */
    private fun safeStart(intent: Intent) {
        try { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { toast(startFailureMessage(e)) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun startFailureMessage(e: Exception): String = when (e) {
        is ActivityNotFoundException -> "Dafür ist keine passende App installiert."
        is SecurityException -> "Diese Aktion ist nicht erlaubt."
        else -> "App konnte nicht geöffnet werden"
    }

    companion object {
        private const val TAG = "LiveBubbleService"
        private const val PREFS = "donna_live_bubble"
        private const val PREF_X = "bubble_x"
        private const val PREF_Y = "bubble_y"
        private const val PREF_PANEL_X = "panel_x"
        private const val PREF_PANEL_Y = "panel_y"
        /** A5 Panel-Grund-Deckkraft: mit Blur frosted (~84%, transparenter), ohne Blur lesbarer Scrim (~90%). */
        private const val PANEL_ALPHA_BLUR = 0xD6
        private const val PANEL_ALPHA_PLAIN = 0xE6
        private const val STATUS_LISTENING = "Ich höre zu…"
        private const val STATUS_THINKING = "Donna denkt nach…"
        private const val STATUS_SPEAKING = "Donna spricht… (tippen zum Stoppen)"
        private const val STATUS_IDLE = "Tippe die Blase an, um weiterzureden."
        private const val CHANNEL_ID = "donna_live_bubble"
        private const val NOTIF_ID = 4201
        const val ACTION_STOP = "com.yourcompany.donna.LIVE_BUBBLE_STOP"
        private const val ARM_TAG = "donna_arm_button"       // Fehltipp-Schutz: Bestätigen-Tasten erst nach Stream-Ende scharf
        private const val ACTION_CARD_TAG = "donna_action_card" // markiert unbestätigte Aktions-Karten (hasPendingActionCards)

        // running spiegelt den tatsächlichen Service-Zustand (gesetzt in onStartCommand, false in onDestroy).
        @Volatile
        var running: Boolean = false
            private set

        /** Startet die Live-Bubble (no-op ohne Overlay-Recht — das erteilt das Onboarding vorab). */
        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, LiveBubbleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            // stopService → onDestroy (räumt Blase ab, setzt running=false); No-op, wenn nichts läuft.
            try { context.stopService(Intent(context, LiveBubbleService::class.java)) } catch (_: Exception) {}
        }
    }
}
