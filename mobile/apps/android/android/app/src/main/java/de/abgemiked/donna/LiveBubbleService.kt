package com.yourcompany.donna

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.animation.ValueAnimator
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.view.animation.LinearInterpolator
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
import android.widget.ImageView
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
    private var cachedStatus: String = OrbVisuals.LABEL_LISTENING
    private var cachedTranscript: String = ""
    private var cachedAnswer: CharSequence = ""
    private var cardContainer: LinearLayout? = null

    // #122 Paket B: Zustandsanzeige (Orb), leuchtender Rand, Tasten, pulsierende Blase, Haptik, Benachrichtigung.
    // Was gezeigt wird, entscheidet die reine Abbildung OrbVisuals/HapticCues/BubbleNotification (JVM-getestet).
    private var currentState: OverlayState = OverlayState.IDLE
    private var orbView: DonnaOrbView? = null
    private var edgeView: RainbowEdgeView? = null
    private var stopButton: View? = null
    private var bubbleIcon: View? = null
    private var pulseAnimator: ValueAnimator? = null
    private var bubbleWindowVisible = true
    private val haptics = HapticCues()
    private var lastNotification: NotificationSpec? = null
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

    // #122 Paket C: Tippmodus. true = Panel fokussierbar + Tastatur. Gesetzt/zurückgestellt NUR in setTypingMode().
    private var typingMode = false
    private var inputRow: LinearLayout? = null
    private var inputField: EditText? = null
    // #134 P7: Panel-Aufbau (Pille/Karte) — Referenzen der Kopfzeile, des Koerpers und der Tasten
    private var rootView: LinearLayout? = null
    private var bodyColumn: LinearLayout? = null
    private var actionRow: LinearLayout? = null
    private var keyboardButton: View? = null
    private var closeButton: View? = null
    private var micButton: View? = null
    private var headerMicButton: View? = null
    private var layoutMode = PanelLayoutMode.COMPACT
    // Nach dem Senden im Tippmodus bleibt die Karte, bis die Anfrage laeuft (kein Aufblitzen der Pille); Regel: PanelLayoutRules
    private var holdCard = false
    // Breitenwechsel Pille <-> Karte: verankerte Seite (null = noch nicht gewechselt)
    private var widthAnchorRight: Boolean? = null
    // Kontext mit der gewaehlten Darstellung (Hell/Dunkel/System, #134 P1): ALLE Farben kommen nur ueber ihn (themeColor)
    private var themedCtx: Context? = null
    private var panelYBeforeTyping: Int? = null
    private var typingHadFocus = false
    // tatsaechlich im Fenster gesetzt (anders als typingMode: bleibt true, wenn das Zurueckstellen scheitert -> erneut versuchen)
    private var windowFocusable = false
    private val typingHandler by lazy { Handler(mainLooper) }
    private var imeWasVisible = false
    private var answerMaxPercent = PanelFocus.ANSWER_MAX_PERCENT_NORMAL

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val prefsOverlay by lazy { getSharedPreferences(TtsEngines.PREFS, MODE_PRIVATE) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfAndBubble()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_MUTE) {
            // Benachrichtigungs-Aktion „Stumm“: Mikrofon aus bis zum nächsten Tipp (kein Gespräch → nichts zu tun)
            val c = controller
            if (c == null) stopSelf() else c.muteMic()
            return START_NOT_STICKY
        }
        if (!startAsForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = if (openWindows()) {
            // Gelocktes Modell: Fenster stehen (Blase nur, wenn eingeschaltet — #133) → Panel öffnen und SOFORT zuhören (Funktionstasten-Flow).
            if (controller == null) controller = ConversationController(this, this)
            showPanel()
            if (!BubbleVisibility.sessionVisible(panelView != null, bubbleView != null)) {
                // weder Panel noch Blase (addView scheiterte): keine unsichtbare Sitzung weiterlaufen lassen
                stopSelfAndBubble()
                return START_NOT_STICKY
            }
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
        refreshThemedContext()
        if (panelView != null) {
            hidePanel()
            showPanel()
            if (!BubbleVisibility.sessionVisible(panelView != null, bubbleView != null)) stopSelfAndBubble()
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
        val spec = BubbleNotification.spec(currentState)
        lastNotification = spec
        val notification = buildNotification(spec)
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

    /** Benachrichtigung aus der reinen Beschreibung ([BubbleNotification]); Aktionen über den vorhandenen Dienst-Intent. */
    private fun buildNotification(spec: NotificationSpec): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setContentTitle("Donna")
            .setContentText(spec.text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(tapIntent)
            .setOngoing(true)
        spec.actions.forEach { a ->
            val intent = Intent(this, LiveBubbleService::class.java)
                .setAction(if (a == NotificationAction.END) ACTION_STOP else ACTION_MUTE)
            val pi = PendingIntent.getService(this, a.ordinal + 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(Notification.Action.Builder(Icon.createWithResource(this, R.mipmap.ic_launcher), a.label, pi).build())
        }
        return builder.build()
    }

    /** Nur bei geänderter Beschreibung neu senden (Text „Donna hört zu“, Aktion „Stumm“ nur beim Zuhören). */
    private fun updateNotification(state: OverlayState) {
        val spec = BubbleNotification.spec(state)
        if (spec == lastNotification || (bubbleView == null && panelView == null)) return
        lastNotification = spec
        try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(spec)) } catch (_: Exception) {}
    }

    // ── Fenster öffnen (#133) ───────────────────────────────────────────────────
    /**
     * Ohne Overlay-Recht kein Fenster. Die schwebende Blase gibt es nur mit eingeschalteter Einstellung (Standard aus);
     * ohne sie erscheint nur das Panel — Schließen/Beenden (✕, „Beenden“, Benachrichtigung), Tippen und Ziehen laufen
     * im Panel, das Zurück-ins-Zuhören übernimmt die Statuszeile (Blasen-Tap entfällt).
     * @return true, wenn die Sitzung starten darf.
     */
    private fun openWindows(): Boolean {
        if (!Settings.canDrawOverlays(this)) return false
        refreshThemedContext()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val wanted = BubbleVisibility.showFloatingBubble(FloatingBubbleSetting.read(prefsOverlay))
        return if (wanted) showBubble() else true
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
        // Fenster etwas größer als die Blase: Platz für den Puls (bis ~1,14×), ohne am Fensterrand abzuschneiden
        // Fenster-Sichtbarkeit (Bildschirm aus, andere Vollbild-App): Puls anhalten, beim Wiedererscheinen nur
        // fortsetzen, wenn noch aufgenommen wird (gleiches Kriterium wie RainbowEdgeView/DonnaOrbView).
        val container = object : FrameLayout(this) {
            override fun onWindowVisibilityChanged(visibility: Int) {
                super.onWindowVisibilityChanged(visibility)
                onBubbleWindowVisibility(visibility == View.VISIBLE)
            }

            override fun onDetachedFromWindow() {
                onBubbleWindowVisibility(false)
                super.onDetachedFromWindow()
            }
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_donna_mic)
            imageTintList = ColorStateList.valueOf(themeColor(R.color.donna_on_accent))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeColor(R.color.donna_accent))
                setStroke(dp(2), themeColor(R.color.donna_on_accent))
            }
        }
        container.addView(icon, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        container.minimumWidth = BUBBLE_BOX_DP.let { dp(it) }
        container.minimumHeight = BUBBLE_BOX_DP.let { dp(it) }
        bubbleIcon = icon
        container.contentDescription = "Donna Live-Blase. Antippen: weiterreden. Lange drücken: schließen."
        return container
    }

    /** Hält die Blase vollständig auf dem Bildschirm (kein Verschwinden nach Drag/Rotation). */
    private fun clampPosition(lp: WindowManager.LayoutParams) {
        val size = dp(BUBBLE_BOX_DP)
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

    /** Statuszeile: stoppt das Sprechen; ohne schwebende Blase führt sie zusätzlich aus Ruhe/Fehler zurück ins Zuhören. */
    private fun onStatusTapped() {
        val c = controller ?: return
        c.stopSpeakingByTap()
        if (BubbleVisibility.statusTapResumes(bubbleShown = bubbleView != null, typing = typingMode)) c.resumeIfIdle()
    }

    /**
     * Tap bringt das Panel wieder her, falls es geschlossen wurde. Im gelockten Modell läuft das Gespräch mit
     * der Blase (Service); das Panel ist während der Sitzung offen, ✕ oder langes Drücken beenden die Sitzung.
     */
    private fun onBubbleTapped() {
        // Aus IDLE/ERROR (Zustand „Bereit“) zurück ins Zuhören; self-guard,
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
        // Feste Breite je Darstellung (Pille/Karte; für X-Clamp bekannt), max. Bildschirmbreite − Rand; Höhe wächst mit Inhalt.
        val w = dp(PanelLayoutRules.windowWidthDp(currentLayoutMode(), resources.configuration.screenWidthDp))
        val lp = WindowManager.LayoutParams(
            w,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            // NICHT-MODAL: ohne FLAG_LAYOUT_NO_LIMITS bleibt das Panel im Bildschirm; NOT_TOUCH_MODAL → Tippen
            // außerhalb des Panels bedient die App dahinter weiter. Ob das Fenster fokussierbar ist, entscheidet
            // NUR PanelFocus.spec (Tippmodus, Paket C) über applyPanelSpec — hier immer die Ruhe-Beschreibung.
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = prefs.getInt(PREF_PANEL_X, dp(12))
            y = prefs.getInt(PREF_PANEL_Y, (params?.y ?: dp(120)) + dp(64))
        }
        applyPanelSpec(lp, PanelFocus.spec(typing = false)) // Panel startet nie fokussierbar
        applyPanelBlur(lp)
        clampPanel(lp, w)
        panelParams = lp
        try {
            val view = buildPanel(lp, wm)
            wm.addView(view, lp)
            panelView = view
            applyVisual() // Rotations-Rebuild: Orb/Rand/Tasten sofort zum aktuellen Zustand (inkl. renderLayout)
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

    private fun hidePanel(reason: PanelTypingEvent = PanelTypingEvent.PANEL_CLOSED) {
        // Tippmodus zuerst beenden (Tastatur zu, Flags/Position zurück), solange die Views noch hängen
        setTypingMode(reason)
        // Nur die Panel-Views entfernen — die Gesprächsschleife (controller) läuft unberührt weiter
        // (Sitzungs-Teardown passiert über stopSelfAndBubble/removeBubble).
        panelView?.let { v -> try { windowManager?.removeView(v) } catch (_: Exception) {} }
        panelView = null
        panelParams = null
        statusView = null
        orbView = null
        edgeView?.setActive(false, false) // Animator sicher stoppen, bevor die Referenz fällt
        edgeView = null
        stopButton = null
        inputRow = null
        inputField = null
        keyboardButton = null
        closeButton = null
        micButton = null
        headerMicButton = null
        actionRow = null
        bodyColumn = null
        rootView = null
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

    /**
     * Panel (#134 P7): EIN Fenster, zwei Darstellungen ([PanelLayoutMode], entschieden von der reinen Funktion).
     * Kopfzeile (Orb, Zustandswort, Tastatur, Schliessen) gibt es immer; der Koerper (Frage, Antwort, Aktionszeile,
     * Tippfeld) erscheint erst bei Inhalt. Alles haengt im selben View-Baum, nur Sichtbarkeiten wechseln ([renderLayout]):
     * so ueberlebt der Tippmodus den Wechsel Pille -> Karte.
     */
    private fun buildPanel(lp: WindowManager.LayoutParams, wm: WindowManager): View {
        layoutMode = currentLayoutMode()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dimenPx(R.dimen.donna_radius_card)
                // A5: etwas transparenter Panel-Grund (App dahinter scheint durch) — Lesbarkeit bleibt durch den
                // getönten Grund und (wo verfügbar) den Fenster-Blur dahinter; ohne Blur dient das höhere Alpha als Scrim.
                setColor(panelSurfaceColor())
                setStroke(dp(1), themeColor(R.color.donna_border))
            }
            elevation = dp(8).toFloat()
            contentDescription = "Donna Live-Panel"
        }
        rootView = root

        // Kopfzeile: ganze Zeile ziehbar (Drag), Orb + Zustandswort, Tastatur, Schliessen.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(PanelLayoutRules.TOUCH_MIN_DP)
        }
        // #122 Paket B: Zustandsanzeige (Orb, dieselbe View wie im Activity-Overlay).
        val orb = DonnaOrbView(this).apply {
            setPalette(
                themeColor(R.color.donna_accent), themeColor(R.color.donna_accent2),
                themeColor(R.color.donna_muted), themeColor(R.color.donna_orb_error),
            )
            setLook(
                themeColor(R.color.donna_orb_core),
                intArrayOf(
                    themeColor(R.color.donna_orb_violet), themeColor(R.color.donna_orb_magenta),
                    themeColor(R.color.donna_orb_yellow), themeColor(R.color.donna_orb_blue),
                    themeColor(R.color.donna_orb_indigo),
                ),
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO // Zustand steht im Zustandswort
            setRingSource { controller?.followUpFraction() ?: 1f }
        }
        orbView = orb
        val orbPx = dp(PanelLayoutRules.orbDp(layoutMode))
        header.addView(orb, LinearLayout.LayoutParams(orbPx, orbPx))
        // Zustandswort (hört zu / denkt / spricht / Fehler). Tippen darauf stoppt Donna sofort, solange sie spricht
        // (unabhängig vom Sprach-Barge-in); ohne schwebende Blase führt es aus Ruhe/Fehler zurück ins Zuhören.
        val status = TextView(this).apply {
            text = cachedStatus
            typeface = font(DonnaFonts.Face.DISPLAY_BOLD)
            setTextColor(themeColor(R.color.donna_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(12), 0, dp(4), 0)
            minHeight = dp(PanelLayoutRules.TOUCH_MIN_DP)
            // keine feste contentDescription: TalkBack liest den Text, also den tatsaechlichen Zustand
            setOnClickListener { onStatusTapped() }
        }
        statusView = status
        header.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // Pille: Mikrofon nur, wenn es etwas bewirkt (Bereit/Stumm/Fehler) - sonst muesste man das Wort antippen
        val headerMic = iconButton(R.drawable.ic_donna_mic, "Per Sprache weiterreden", themeColor(R.color.donna_on_action), themeColor(R.color.donna_action)) { onMicTapped() }
        headerMicButton = headerMic
        header.addView(headerMic, LinearLayout.LayoutParams(dp(PanelLayoutRules.TOUCH_MIN_DP), dp(PanelLayoutRules.TOUCH_MIN_DP)))
        val keyboard = iconButton(R.drawable.ic_donna_keyboard, "Tippen statt sprechen", themeColor(R.color.donna_muted), null) { openTyping() }
        keyboardButton = keyboard
        header.addView(keyboard, LinearLayout.LayoutParams(dp(PanelLayoutRules.TOUCH_MIN_DP), dp(PanelLayoutRules.TOUCH_MIN_DP)))
        // Schliessen = Gespraechsende (wie bisher „Beenden“: Controller feuert StopRequested, der Dienst schliesst danach);
        // ohne Controller direkt schliessen. „Stopp“ (nur Sprechen abbrechen) ist eine eigene Taste im Koerper.
        val close = iconButton(R.drawable.ic_donna_close, "Gespräch beenden", themeColor(R.color.donna_text), themeColor(R.color.donna_overlay_chip)) {
            controller?.endConversation() ?: stopSelfAndBubble()
        }
        closeButton = close
        header.addView(close, LinearLayout.LayoutParams(dp(PanelLayoutRules.TOUCH_MIN_DP), dp(PanelLayoutRules.TOUCH_MIN_DP)))
        root.addView(
            header,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        // Drag am ganzen Header: die Tasten verbrauchen ihre Touches selbst (bleiben klickbar); ein Tipp auf das
        // Zustandswort wird vom Drag-Handler als Klick weitergereicht.
        attachPanelDrag(header, lp, wm)
        attachPanelDrag(status, lp, wm)

        // Koerper (nur in der Karte sichtbar): Frage, Antwort + Karten, Aktionszeile, Tippfeld.
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bodyColumn = body

        // Mikes Frage (Mitschrift, partiell + final): klein, gedämpft.
        val transcript = TextView(this).apply {
            text = cachedTranscript
            visibility = if (cachedTranscript.isBlank()) View.GONE else View.VISIBLE
            typeface = font(DonnaFonts.Face.SANS_REGULAR)
            setTextColor(themeColor(R.color.donna_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 3 // sehr lange Mitschrift zieht das Fenster nicht hoch
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, dp(8))
        }
        transcriptView = transcript
        body.addView(
            transcript,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        // Antwort + Karten in EINEM höhengedeckelten ScrollView: so wird das Panel nie höher als der
        // Bildschirm und die Ja/Nein-Buttons bleiben IMMER per Scrollen erreichbar (Review-Finding, Querformat).
        val answer = TextView(this).apply {
            text = cachedAnswer
            typeface = font(DonnaFonts.Face.SANS_REGULAR)
            setTextColor(themeColor(R.color.donna_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setLineSpacing(dp(3).toFloat(), 1f)
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
        val scroll = object : ScrollView(this@LiveBubbleService) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                // Höhe je Messung aus der aktuellen Beschreibung: im Tippmodus kleiner, damit die Tastatur nichts verdeckt
                val maxContentH = minOf(dp(420), resources.displayMetrics.heightPixels * answerMaxPercent / 100)
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxContentH, MeasureSpec.AT_MOST))
            }
        }.apply {
            visibility = if (cachedAnswer.isBlank() && cachedCards.isEmpty()) View.GONE else View.VISIBLE
            addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        answerScroll = scroll
        body.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        cachedCards.toList().forEach { renderCard(it) }
        if (cardsArmed) armActionButtons()

        // Aktionszeile: „Stopp“ nur beim Sprechen, rechts die runde Mikrofon-Taste (weiterreden).
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val stop = buildStopPill()
        stopButton = stop
        actions.addView(stop, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(PanelLayoutRules.TOUCH_MIN_DP)))
        actions.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) // Platzhalter
        val mic = iconButton(R.drawable.ic_donna_mic, "Per Sprache weiterreden", themeColor(R.color.donna_on_action), themeColor(R.color.donna_action)) { onMicTapped() }
        micButton = mic
        actions.addView(mic, LinearLayout.LayoutParams(dp(PanelLayoutRules.TOUCH_MIN_DP), dp(PanelLayoutRules.TOUCH_MIN_DP)))
        actionRow = actions
        body.addView(
            actions,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(12) },
        )

        // #122 Paket C: Eingabezeile (einzeilig, Senden-Taste) — nur im Tippmodus sichtbar.
        body.addView(
            buildInputRow(),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(12) },
        )
        root.addView(
            body,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.marginEnd = dp(12) },
        )

        // #122 Paket B: leuchtender Rand ÜBER dem Panel (höhere Elevation als root, sonst läge er darunter). Nicht
        // klickbar, GONE solange das Mikrofon nicht aufnimmt.
        val edge = RainbowEdgeView(this, dimenPx(R.dimen.donna_radius_card)).apply { elevation = dp(9).toFloat() }
        edgeView = edge
        val frame = object : FrameLayout(this) {
            // Zurück-Taste im Tippmodus: Tastatur schließen + Fenster zurückstellen (erst danach wieder App-Zurück)
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (typingMode && event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) setTypingMode(PanelTypingEvent.BACK)
                    return true
                }
                return super.dispatchKeyEvent(event)
            }

            // Fokus weg (Tipp in die App dahinter) → nicht fokussierbar zurückstellen
            override fun onWindowFocusChanged(hasFocus: Boolean) {
                super.onWindowFocusChanged(hasFocus)
                if (!typingMode) return
                if (hasFocus) typingHadFocus = true
                else if (typingHadFocus) setTypingMode(PanelTypingEvent.FOCUS_LOST)
            }
        }
        // Tastatur über ihre eigene Taste geschlossen (Android 11+): zurückstellen
        frame.setOnApplyWindowInsetsListener { v, insets ->
            if (typingMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (insets.isVisible(WindowInsets.Type.ime())) imeWasVisible = true
                else if (imeWasVisible) setTypingMode(PanelTypingEvent.KEYBOARD_CLOSED)
            }
            v.onApplyWindowInsets(insets)
        }
        // MATCH_PARENT-Breite von root ist tragend: Das Fenster hat feste Breite (je Darstellung, [PanelLayoutRules]); nur
        // so bekommt der Rand im zweiten Messdurchgang (MATCH_PARENT-Kind) eine feste Vorgabe, die zur Panelbreite passt (#133).
        // Der Rand bestimmt die Fenstergroesse nie: er beansprucht bei „hoechstens“-Vorgabe nichts (EdgeBounds).
        frame.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        frame.addView(edge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return frame
    }

    private fun currentLayoutMode(): PanelLayoutMode = PanelLayoutMode.of(
        currentState, cachedTranscript.isNotBlank(), cachedAnswer.isNotBlank(), cachedCards.isNotEmpty(), typingMode, holdCard,
    )

    /**
     * Setzt die Entscheidung von [PanelLayoutMode.of]/[PanelLayoutRules] um: Sichtbarkeiten, Abstaende, Orbgroesse und die
     * Fensterbreite. Billig und idempotent — wird nach jeder Aenderung von Zustand, Text, Karten oder Tippmodus gerufen.
     */
    private fun renderLayout() {
        val view = panelView ?: return
        val mode = currentLayoutMode()
        layoutMode = mode
        val compact = mode == PanelLayoutMode.COMPACT
        rootView?.setPadding(if (compact) dp(10) else dp(20), if (compact) dp(8) else dp(12), dp(8), if (compact) dp(8) else dp(20))
        orbView?.let { o ->
            val px = dp(PanelLayoutRules.orbDp(mode))
            val p = o.layoutParams
            if (p != null && (p.width != px || p.height != px)) { p.width = px; p.height = px; o.layoutParams = p }
        }
        bodyColumn?.visibility = if (compact) View.GONE else View.VISIBLE
        statusView?.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 15f else 14f)
            setTextColor(themeColor(if (compact) R.color.donna_text else R.color.donna_accent))
        }
        closeButton?.background =
            if (compact) GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(themeColor(R.color.donna_overlay_chip)) } else null
        keyboardButton?.visibility = if (PanelLayoutRules.showKeyboardButton(typingMode, currentState)) View.VISIBLE else View.GONE
        val stop = PanelLayoutRules.showStop(currentState, typingMode)
        val mic = PanelLayoutRules.showMic(mode, typingMode, currentState)
        stopButton?.visibility = if (stop) View.VISIBLE else View.GONE
        micButton?.visibility = if (mic) View.VISIBLE else View.GONE
        headerMicButton?.visibility = if (PanelLayoutRules.showHeaderMic(mode, typingMode, currentState)) View.VISIBLE else View.GONE
        actionRow?.visibility = if (stop || mic) View.VISIBLE else View.GONE
        inputRow?.visibility = if (typingMode) View.VISIBLE else View.GONE
        // Fensterbreite je Darstellung (fest, damit der Rand eine genaue Vorgabe bekommt)
        val lp = panelParams ?: return
        val w = dp(PanelLayoutRules.windowWidthDp(mode, resources.configuration.screenWidthDp))
        if (lp.width != w) {
            val screenPx = resources.displayMetrics.widthPixels
            val growing = w > lp.width
            // Seite beim Verbreitern festlegen und beim Zurueckwechseln wiederverwenden (sonst Versatz um die Breitendifferenz)
            val right = if (growing || widthAnchorRight == null) PanelLayoutRules.anchorRight(lp.x, lp.width, screenPx) else widthAnchorRight!!
            widthAnchorRight = if (growing) right else null
            lp.x = PanelLayoutRules.xAfterWidthChange(lp.x, lp.width, w, right, screenPx)
            lp.width = w
            clampPanel(lp, w)
            try { windowManager?.updateViewLayout(view, lp) } catch (_: Exception) {}
            view.post {
                val p = panelParams ?: return@post
                clampPanel(p, p.width)
                try { windowManager?.updateViewLayout(view, p) } catch (_: Exception) {}
            }
        }
    }

    private fun font(face: DonnaFonts.Face): android.graphics.Typeface = DonnaFonts.typeface(this, face)

    /** Runde Icon-Taste: 48-dp-Tippziel, 20-dp-Symbol (Vector-Drawable, Farbe per Tint), deutsche contentDescription. */
    private fun iconButton(iconRes: Int, description: String, tint: Int, bg: Int?, onClick: () -> Unit): ImageView =
        ImageView(this).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(tint)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(14), dp(14), dp(14), dp(14))
            if (bg != null) background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(bg) }
            contentDescription = description
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** „Stopp“: Umriss-Pille mit Stopp-Symbol (bricht nur die Sprachausgabe ab, das Gespräch läuft weiter). */
    private fun buildStopPill(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        minimumHeight = dp(PanelLayoutRules.TOUCH_MIN_DP)
        setPadding(dp(16), 0, dp(18), 0)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dimenPx(R.dimen.donna_radius_pill)
            setColor(android.graphics.Color.TRANSPARENT)
            setStroke(dp(1), themeColor(R.color.donna_control_border))
        }
        addView(
            ImageView(this@LiveBubbleService).apply {
                setImageResource(R.drawable.ic_donna_stop)
                imageTintList = ColorStateList.valueOf(themeColor(R.color.donna_text))
            },
            LinearLayout.LayoutParams(dp(16), dp(16)).also { it.marginEnd = dp(8) },
        )
        addView(TextView(this@LiveBubbleService).apply {
            text = "Stopp"
            typeface = font(DonnaFonts.Face.SANS_BOLD)
            setTextColor(themeColor(R.color.donna_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        })
        contentDescription = "Sprachausgabe stoppen"
        isClickable = true
        setOnClickListener { controller?.stopSpeakingByTap() }
    }

    /** Runde Mikrofon-Taste: Donna unterbrechen (falls sie spricht) und wieder zuhören. */
    private fun onMicTapped() {
        val c = controller ?: return
        c.stopSpeakingByTap()
        c.resumeIfIdle()
    }

    /** Eingabezeile des Tippmodus: einzeiliges Feld in einer Pille (Senden-Taste der Tastatur) + Senden als Icon-Taste. */
    private fun buildInputRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = if (typingMode) View.VISIBLE else View.GONE
        }
        val field = object : EditText(this) {
            // Zurück bei offener Tastatur kommt zuerst hier an (vor der Tastatur): alles zurückstellen
            override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
                if (keyCode == KeyEvent.KEYCODE_BACK && typingMode) {
                    if (event.action == KeyEvent.ACTION_UP) setTypingMode(PanelTypingEvent.BACK)
                    return true
                }
                return super.onKeyPreIme(keyCode, event)
            }
        }.apply {
            hint = "Donna fragen…"
            typeface = font(DonnaFonts.Face.SANS_REGULAR)
            setHintTextColor(themeColor(R.color.donna_muted))
            setTextColor(themeColor(R.color.donna_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dimenPx(R.dimen.donna_radius_pill)
                setColor(themeColor(R.color.donna_overlay_chip))
                setStroke(dp(1), themeColor(R.color.donna_border))
            }
            minHeight = dp(PanelLayoutRules.TOUCH_MIN_DP)
            setPadding(dp(18), dp(8), dp(18), dp(8))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            filters = arrayOf(InputFilter.LengthFilter(TypedInput.MAX_CHARS))
            contentDescription = "Nachricht an Donna eingeben"
            setOnEditorActionListener { _, actionId, event ->
                val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_SEND || enter) { sendTyped(); true } else false
            }
        }
        inputField = field
        row.addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginEnd = dp(8) })
        row.addView(
            iconButton(R.drawable.ic_donna_send, "Nachricht an Donna senden", themeColor(R.color.donna_on_accent_solid), themeColor(R.color.donna_accent_solid)) { sendTyped() },
            LinearLayout.LayoutParams(dp(PanelLayoutRules.TOUCH_MIN_DP), dp(PanelLayoutRules.TOUCH_MIN_DP)),
        )
        inputRow = row
        return row
    }

    // ── #122 Paket C: Tippmodus ─────────────────────────────────────────────────
    /** „Tippen“-Taste: Mikrofon/Sprachausgabe anhalten (Gespräch läuft weiter), dann Panel fokussierbar machen. */
    private fun openTyping() {
        if (typingMode || panelView == null || !PanelFocus.canOpen(currentState)) return
        if (PanelFocus.stopsAudioOnOpen(currentState)) controller?.pauseForTyping()
        setTypingMode(PanelTypingEvent.OPEN)
    }

    /** Senden (Taste und Enter): leer → nichts; sonst Feld leeren, ZUERST zurückstellen, dann über den Controller senden. */
    private fun sendTyped() {
        val raw = inputField?.text?.toString()
        if (TypedInput.prepare(raw) == null) return
        inputField?.setText("")
        holdCard = true // vor dem Zurueckstellen: der Tippmodus endet, die Mitschrift folgt erst mit submitText
        setTypingMode(PanelTypingEvent.SENT) // Tastatur zu + nicht fokussierbar, bevor die Anfrage startet
        controller?.submitText(raw)
    }

    /**
     * EINZIGE Stelle, die den Tippmodus ein- und ausschaltet. Jeder Ausgang (Senden, Tastatur zu, Zurück, Panel
     * schließen, Gesprächsende, Dienst-Ende, Aufnahme startet, Fokusverlust, Fehler) ruft sie mit seinem Ereignis;
     * die Entscheidung trifft [PanelFocus], hier wird sie nur umgesetzt (Flags, Position, Höhe, Tastatur).
     */
    private fun setTypingMode(event: PanelTypingEvent) {
        val next = PanelFocus.typingAfter(event)
        if (next == typingMode) { ensureWindowRestored(); return }
        typingMode = next
        if (next) { typingHadFocus = false; imeWasVisible = false }
        try {
            applyTypingToWindow(next)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Tippmodus konnte nicht umgestellt werden: ${e.message}")
            typingMode = PanelFocus.typingAfter(PanelTypingEvent.ERROR)
            windowFocusable = true // Flags wurden lokal verändert, das Fenster ist nicht sicher zurückgestellt → erneut versuchen
            ensureWindowRestored()
        }
    }

    /** Scheiterte das Zurückstellen früher, jetzt erneut versuchen (kein dauerhaft fokussierbares Fenster). */
    private fun ensureWindowRestored() {
        if (typingMode || !windowFocusable) return
        try { applyTypingToWindow(false) } catch (e: Exception) {
            android.util.Log.w(TAG, "Zurückstellen des Panels gescheitert, nächster Versuch folgt: ${e.message}")
        }
    }

    private fun applyTypingToWindow(on: Boolean) {
        val spec = PanelFocus.spec(on)
        val field = inputField
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        typingHandler.removeCallbacksAndMessages(null) // verzögerte Tastatur-/Fokus-Callbacks abräumen
        if (!on) {
            // ERST die Tastatur verstecken, DANN die Flags zurückstellen
            field?.windowToken?.let { imm?.hideSoftInputFromWindow(it, 0) }
            field?.clearFocus()
        }
        answerMaxPercent = spec.answerMaxPercent
        renderLayout() // Pille -> Karte (Tippfeld sichtbar, Tastatur-Taste weg) bzw. zurueck
        answerScroll?.requestLayout()
        val view = panelView
        val lp = panelParams
        if (lp != null) {
            applyPanelSpec(lp, spec)
            if (spec.moveToTop) {
                if (panelYBeforeTyping == null) panelYBeforeTyping = lp.y
                lp.y = dp(24) // oben: die Tastatur (unten) verdeckt weder Zustandsanzeige noch Antwort
            } else {
                panelYBeforeTyping?.let { lp.y = it }
                panelYBeforeTyping = null
            }
            if (view != null) windowManager?.updateViewLayout(view, lp)
            windowFocusable = on // erst nach erfolgreichem Umstellen
            // Klemmen erst NACH dem Relayout (echte Höhe bekannt)
            if (view != null) view.post {
                val p = panelParams ?: return@post
                clampPanel(p, p.width)
                try { windowManager?.updateViewLayout(view, p) } catch (_: Exception) {}
            }
        } else {
            windowFocusable = false // kein Fenster: nichts zurückzustellen
        }
        if (on) {
            // Fokus-Wechsel des Fensters ist asynchron: Tastatur kurz nach dem Umstellen anfordern (zweiter Versuch als Netz)
            field?.let { f ->
                for (delayMs in longArrayOf(120L, 400L)) {
                    typingHandler.postDelayed({
                        if (typingMode && f.isAttachedToWindow) {
                            f.requestFocus()
                            imm?.showSoftInput(f, InputMethodManager.SHOW_IMPLICIT)
                        }
                    }, delayMs)
                }
            }
            // Sicherheitsnetz: kein Fokus binnen der Frist → Tippmodus beenden, kurzer Hinweis
            typingHandler.postDelayed({
                if (PanelFocus.focusMissing(typingMode, typingHadFocus || panelView?.hasWindowFocus() == true)) {
                    setTypingMode(PanelTypingEvent.ERROR)
                    onError(PanelFocus.FOCUS_FAILED_STATUS)
                }
            }, PanelFocus.FOCUS_TIMEOUT_MS)
        }
    }

    /**
     * Fenster-Beschreibung → echte Flags: NUR hier wird FLAG_NOT_FOCUSABLE gesetzt bzw. entfernt. Mit Tippmodus
     * fokussierbar (Tastatur kann erscheinen; ADJUST_NOTHING, weil das Panel selbst nach oben rückt), sonst wie bisher.
     */
    private fun applyPanelSpec(lp: WindowManager.LayoutParams, spec: PanelWindowSpec) {
        lp.flags = if (spec.focusable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        lp.softInputMode = if (spec.focusable) {
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        } else {
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED
        }
    }

    /** Panel per Titel verschieben (Schließen-Button behält eigenen Klick). */
    @Suppress("ClickableViewAccessibility") // Titel trägt contentDescription; Drag ist Zusatzgeste.
    private fun attachPanelDrag(handle: View, lp: WindowManager.LayoutParams, wm: WindowManager) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y; touchX = event.rawX; touchY = event.rawY; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (!moved && kotlin.math.abs(dx) <= slop && kotlin.math.abs(dy) <= slop) return@setOnTouchListener true // noch ein Tipp
                    lp.x = startX + dx
                    lp.y = startY + dy
                    clampPanel(lp, lp.width)
                    moved = true
                    panelView?.let { try { wm.updateViewLayout(it, lp) } catch (_: Exception) {} }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved) {
                        prefs.edit().putInt(PREF_PANEL_X, lp.x).putInt(PREF_PANEL_Y, lp.y).apply()
                        panelYBeforeTyping = null // im Tippmodus verschoben: die neue Position gilt, kein Zurückspringen
                    } else if (event.action == MotionEvent.ACTION_UP) {
                        handle.performClick() // Tipp ohne Ziehen = Klick (Zustandswort); die Kopfzeile selbst hat keinen
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun removeBubble() {
        hidePanel(PanelTypingEvent.SERVICE_ENDED)
        dragHandler?.removeCallbacksAndMessages(null)
        dragHandler = null
        updateBubblePulse(false)
        bubbleView?.let { v -> try { windowManager?.removeView(v) } catch (_: Exception) {} }
        bubbleView = null
        bubbleIcon = null
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

    /**
     * Farben NUR hier und NUR ueber den Darstellungs-Kontext ([themed]): gewaehlt Hell/Dunkel ueberschreibt den
     * Nachtmodus der Konfiguration, "system" bleibt unveraendert (ThemeChoice, #134 P1).
     */
    private fun themeColor(id: Int): Int = androidx.core.content.ContextCompat.getColor(themed(), id)

    private fun themed(): Context = themedCtx ?: this

    /** Baut den Darstellungs-Kontext aus der gespeicherten Wahl neu (beim Start und bei jeder Konfigurationsaenderung). */
    private fun refreshThemedContext() {
        val night = ThemeChoice.nightOverride(ThemeChoice.read(prefsOverlay))
        themedCtx = if (night == null) null else try {
            val cfg = Configuration(resources.configuration)
            cfg.uiMode = ThemeChoice.uiModeWithNight(cfg.uiMode, night)
            createConfigurationContext(cfg)
        } catch (_: Exception) { null }
    }
    /** Design-Token aus res/values/dimens.xml (#122 Paket D; Paritaet zu src/theme/tokens.ts prueft designTokens.test.ts). */
    private fun dimenPx(id: Int): Float = resources.getDimension(id)
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
        withAlpha(themeColor(R.color.donna_overlay_panel), if (blurSupported()) PANEL_ALPHA_BLUR else PANEL_ALPHA_PLAIN)

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
        currentState = state
        if (holdCard && PanelLayoutRules.holdReleasedBy(state)) holdCard = false
        // Beginnt eine Aufnahme oder endet das Gespräch, ist der Tippmodus vorbei (Fenster zurückstellen)
        PanelFocus.exitForState(typingMode, state)?.let { setTypingMode(it) }
        ensureWindowRestored()
        // Text aus der gemeinsamen Abbildung; „Bis gleich.“ bzw. der Klartext des Fehler-Endes kommt über onError
        // (Controller setzt ihn direkt danach); bei ERROR kommt der Fehlertext über onError.
        if (state != OverlayState.ERROR) cachedStatus = OrbVisuals.of(state, true).label
        statusView?.text = cachedStatus
        applyVisual()
        haptics.onState(state)?.let { performCue(it) }
        updateNotification(state)
    }

    private fun animationsEnabled(): Boolean = ValueAnimator.areAnimatorsEnabled()

    /** Zustand → Orb, Rand, Stopp-Taste, Blasenpuls (Entscheidung: [OrbVisuals]; hier nur Umsetzung). */
    private fun applyVisual() {
        val v = OrbVisuals.of(currentState, animationsEnabled(), 1f)
        orbView?.setState(currentState)
        edgeView?.setActive(v.edgeGlow, v.edgeRotating)
        renderLayout() // Stopp/Mikrofon/Pille-oder-Karte folgen dem Zustand
        updateBubblePulse(v.bubblePulse && bubbleView != null && bubbleWindowVisible)
    }

    private fun onBubbleWindowVisibility(visible: Boolean) {
        bubbleWindowVisible = visible
        if (visible) applyVisual() else updateBubblePulse(false)
    }

    /** Die schwebende Blase pulsiert, solange aufgenommen wird; der Animator stoppt sicher bei jedem anderen Zustand. */
    private fun updateBubblePulse(on: Boolean) {
        val icon = bubbleIcon
        if (!on || icon == null) {
            pulseAnimator?.cancel()
            pulseAnimator = null
            icon?.scaleX = 1f
            icon?.scaleY = 1f
            return
        }
        if (pulseAnimator?.isRunning == true) return
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BubblePulse.PERIOD_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val sc = BubblePulse.scale(it.animatedFraction)
                icon.scaleX = sc
                icon.scaleY = sc
            }
            start()
        }
    }

    /** Kurzes Signal (nur Start des Zuhörens nach einem Aufruf, Gesprächsende); ohne VIBRATE-Recht über die View. */
    private fun performCue(cue: HapticCue) {
        val view = bubbleView ?: panelView ?: return
        view.performHapticFeedback(
            if (cue == HapticCue.LISTEN_START) HapticFeedbackConstants.VIRTUAL_KEY else HapticFeedbackConstants.LONG_PRESS,
        )
    }

    override fun onPartialTranscript(text: String) {
        cachedTranscript = text
        transcriptView?.let { it.text = text; it.visibility = if (text.isBlank()) View.GONE else View.VISIBLE }
        renderLayout()
    }

    override fun onUserUtterance(text: String) {
        holdCard = false // ab jetzt traegt die Mitschrift die Karte
        cachedTranscript = text
        transcriptView?.let { it.text = text; it.visibility = View.VISIBLE }
        // Neue Frage → alte Antwort leeren
        cachedAnswer = ""
        answerView?.text = ""
        // Scroll nur verbergen, wenn auch keine Karten da sind (Antwort + Karten teilen sich den Scroll).
        if (cachedCards.isEmpty()) answerScroll?.visibility = View.GONE
        renderLayout()
    }

    override fun setAnswerHtml(html: CharSequence) {
        cachedAnswer = html
        answerView?.text = html
        renderLayout()
    }

    override fun showAnswer() {
        answerScroll?.visibility = View.VISIBLE
        renderLayout()
    }

    override fun autoscroll() {
        answerScroll?.let { s -> s.post { s.fullScroll(View.FOCUS_DOWN) } }
    }

    /** #122: Gespräch zu Ende → über den vorhandenen Schließ-Weg (wie Long-Press/ACTION_STOP) schließen. */
    override fun onConversationEnded() {
        setTypingMode(PanelTypingEvent.CONVERSATION_ENDED)
        stopSelfAndBubble()
    }

    override fun isTypingActive(): Boolean = typingMode

    override fun onError(message: String) {
        cachedStatus = message
        statusView?.text = cachedStatus
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
        renderLayout()
    }
    override fun armCards() { cardsArmed = true; armActionButtons() }
    // Cache-basiert statt View-Baum: nach einem Ja/Nein-Tap ist die Spec aus cachedCards entfernt → kein
    // falsches Blockieren des Relisten; robust auch wenn das Panel gerade nicht gebaut ist.
    override fun hasPendingActionCards(): Boolean = cachedCards.any { it is CardSpec.Action }

    // ── #52 Sprach-Bestätigung (Kachel-Tap bleibt zusätzlich) ─────────────────────
    private fun pendingActionSpecs(): List<CardSpec.Action> = cachedCards.filterIsInstance<CardSpec.Action>()

    private fun isVoiceConfirmable(spec: CardSpec.Action): Boolean =
        VoiceConfirm.isVoiceConfirmable(JsonSafe.str(spec.action, "type", ""))

    /** Genau EINE einfache Ja/Nein-Karte (keine Auswahl-/Fehlerkarte daneben) → eindeutig adressierbar. */
    override fun hasVoiceConfirmableCard(): Boolean {
        val pending = pendingActionSpecs()
        return pending.size == 1 && isVoiceConfirmable(pending[0])
    }

    /**
     * Wie der Ja-Tap: dieselbe Aktion über denselben [ActionExecutor], Karte wird entfernt (kein zweites Mal
     * ausführbar). Fehltipp-/Voreiligkeits-Schutz: nur wenn die Karten scharf sind ([cardsArmed]).
     */
    override fun confirmCardByVoice(): Boolean {
        if (!cardsArmed) return false
        val pending = pendingActionSpecs()
        if (pending.size != 1 || !isVoiceConfirmable(pending[0])) return false
        val spec = pending[0]
        removeActionCard(spec) // ZUERST entfernen (Cache = Wahrheit), dann ausführen → nie doppelt ausführbar
        actionExecutor.execute(spec.action)
        return true
    }

    /** Wie der Nein-Tap: wartende Aktions-Karten verwerfen. */
    override fun dismissCardsByVoice() {
        pendingActionSpecs().forEach { removeActionCard(it) }
    }

    private fun removeActionCard(spec: CardSpec.Action) {
        val view = specOf.entries.firstOrNull { it.value === spec }?.key
        (view?.parent as? ViewGroup)?.removeView(view) // Hierarchie-Listener nimmt die Spec aus cachedCards
        cachedCards.remove(spec) // idempotent: auch ohne gebautes Panel
        // Keine leere Panel-Hülle zurücklassen (Review R6): ohne Karten und ohne Antworttext Bereiche ausblenden.
        if (cachedCards.isEmpty()) {
            cardContainer?.visibility = View.GONE
            if (cachedAnswer.isBlank()) answerScroll?.visibility = View.GONE
        }
        renderLayout()
    }

    /** Neue Karte: ERST bauen — nur was eine View ergibt, wird gecacht (sonst zählt hasPendingActionCards falsch). */
    private fun addCard(spec: CardSpec) {
        val view = buildCardView(spec) ?: return
        cachedCards.add(spec)
        val c = cardContainer ?: return // kein Panel: Cache genügt, der Rebuild rendert die Karte neu
        specOf[view] = spec
        c.addView(view)
        c.visibility = View.VISIBLE
        answerScroll?.visibility = View.VISIBLE // Karte sichtbar machen, auch ohne Antworttext
        renderLayout()
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
                setColor(themeColor(R.color.donna_surface)); cornerRadius = dimenPx(R.dimen.donna_radius_lg); setStroke(1, themeColor(R.color.donna_border))
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
        // Symbol kommt vom Server; fehlt es, gar keine View (keine leere Zeile)
        JsonSafe.str(d, "condition_icon", "").takeIf { it.isNotBlank() }?.let { icon ->
            topRow.addView(TextView(this).apply { text = icon; setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f) })
        }
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
            "Tief ${JsonSafe.numText(d, "temp_min")}° Hoch ${JsonSafe.numText(d, "temp_max")}°",
            "Feuchte ${JsonSafe.numText(d, "humidity")}%",
            "Wind ${JsonSafe.numText(d, "wind_kmh")} km/h",
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
                setColor(themeColor(R.color.donna_surface)); cornerRadius = dimenPx(R.dimen.donna_radius_lg); setStroke(1, themeColor(R.color.donna_border))
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.bottomMargin = dp(8) }
            setOnClickListener { openCheckedUrl(checked) }
            addView(ImageView(this@LiveBubbleService).apply {
                setImageResource(R.drawable.ic_donna_map)
                imageTintList = ColorStateList.valueOf(themeColor(R.color.donna_accent))
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).also { it.marginEnd = dp(12) }
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
            addView(ImageView(this@LiveBubbleService).apply {
                setImageResource(R.drawable.ic_donna_chevron_right)
                imageTintList = ColorStateList.valueOf(themeColor(R.color.donna_accent))
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
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
        /** Fensterbreite der Blase: 56-dp-Symbol + Platz für den Puls. */
        private const val BUBBLE_BOX_DP = 68
        private const val CHANNEL_ID = "donna_live_bubble"
        private const val NOTIF_ID = 4201
        const val ACTION_STOP = "com.yourcompany.donna.LIVE_BUBBLE_STOP"
        /** Benachrichtigungs-Aktion Stumm (#122 Paket B). */
        const val ACTION_MUTE = "com.yourcompany.donna.LIVE_BUBBLE_MUTE"
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
