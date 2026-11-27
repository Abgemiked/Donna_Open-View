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
import com.yourcompany.donna.overlay.CardKit
import com.yourcompany.donna.overlay.CardStyle
import com.yourcompany.donna.device.DeviceActionKind
import com.yourcompany.donna.device.DeviceActionRunner
import com.yourcompany.donna.device.TurnBudget
import com.yourcompany.donna.overlay.ActionExecutor
import com.yourcompany.donna.overlay.DeviceResultCardBuilder
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
        /** #147: Rückmeldung einer Geräte-Aktion (mit "Rückgängig", wenn es etwas zurückzunehmen gibt). */
        class Device(val message: String, val undoId: Long?, val opensClock: Boolean = false) : CardSpec()
        /** #219: Vorschau des eben aufgenommenen Screenshots; Tipp öffnet genau dieses Bild in der Galerie. */
        class Screenshot(val uri: String, val thumb: android.graphics.Bitmap) : CardSpec()
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
    private var headerMicButton: View? = null
    private var layoutMode = PanelLayoutMode.COMPACT
    // Nach dem Senden im Tippmodus bleibt die Karte, bis die Anfrage laeuft (kein Aufblitzen der Pille); Regel: PanelLayoutRules
    private var holdCard = false
    // Kontext mit der gewaehlten Darstellung (Hell/Dunkel/System, #134 P1): ALLE Farben kommen nur ueber ihn (themeColor)
    private var themedCtx: Context? = null
    // Position (unten mittig, #134): null = Standard; gesetzt = vom Nutzer verschoben (linke Kante, Abstand der Unterkante). Rechnung: PanelPlacement
    private var panelUser: UserSpot? = null
    // Tastaturhoehe in px (nur im Tippmodus, ab API 30 lesbar); 0 = unbekannt -> das Panel weicht nach oben aus
    private var keyboardPx = 0
    private var keyboardProvisional = false // angehoben/Untergrenze: die naechste exakte Messung ersetzt sie (PanelPlacement.keyboardUpdate)
    // Voller Meldungstext (lange Fehler/Hinweise): steht ungekuerzt im Inhalt, in der Bedienzeile nur das Kurzwort
    private var cachedNotice: String = ""
    private var noticeView: TextView? = null
    // Hoechsthoehe des scrollenden Inhalts aus dem Platz ueber der Unterkante (PanelPlacement.contentLimit)
    private var contentCapPx = Int.MAX_VALUE
    // Tippmodus: Rueckfall nach oben nur, wenn nach KEYBOARD_WAIT_MS keine Tastaturhoehe bekannt ist (PanelPlacement.needsTopFallback)
    private var topFallback = false
    private var typingStartedAt = 0L
    private var lastPanelHeight = 0
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
            .setSmallIcon(R.drawable.ic_stat_donna)
            .setColor(themeColor(R.color.donna_accent_solid))
            .setContentIntent(tapIntent)
            .setOngoing(true)
        spec.actions.forEach { a ->
            val intent = Intent(this, LiveBubbleService::class.java)
                .setAction(if (a == NotificationAction.END) ACTION_STOP else ACTION_MUTE)
            val pi = PendingIntent.getService(this, a.ordinal + 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_donna), a.label, pi).build())
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
        // EINE feste berechnete Breite (Bildschirm − 2 × Rand, höchstens 480 dp; Pille und Karte gleich, kein MATCH_PARENT); Höhe wächst mit Inhalt nach OBEN.
        val w = dp(PanelLayoutRules.windowWidthDp(resources.configuration.screenWidthDp))
        val lp = WindowManager.LayoutParams(
            w,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            // NICHT-MODAL: ohne FLAG_LAYOUT_NO_LIMITS bleibt das Panel im Bildschirm; NOT_TOUCH_MODAL → Tippen
            // außerhalb des Panels bedient die App dahinter weiter. Ob das Fenster fokussierbar ist, entscheidet
            // NUR PanelFocus.spec (Tippmodus, Paket C) über applyPanelSpec — hier immer die Ruhe-Beschreibung.
            // LAYOUT_IN_SCREEN: Bezug ist der echte Bildschirm; Statusleiste/Navigationsleiste rechnet PanelPlacement selbst ein.
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
        }
        loadPanelUser()
        keyboardPx = 0
        keyboardProvisional = false
        applyPanelSpec(lp, PanelFocus.spec(typing = false)) // Panel startet nie fokussierbar
        applyPanelBlur(lp)
        applyPlacement(lp)
        panelParams = lp
        try {
            val view = buildPanel(lp, wm)
            wm.addView(view, lp)
            panelView = view
            applyVisual() // Rotations-Rebuild: Orb/Rand/Tasten sofort zum aktuellen Zustand (inkl. renderLayout)
            // Nach dem Layout mit der ECHTEN Höhe neu platzieren (Klemmen oben, Tippmodus-Ausweichen).
            view.post { placePanel() }
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
        typingHandler.removeCallbacksAndMessages(null) // Tastatur-Nachlese nicht ueber das Panel hinaus
        statusView = null
        noticeView = null
        contentCapPx = Int.MAX_VALUE
        lastPanelHeight = 0
        orbView = null
        edgeView?.setActive(false, false) // Animator sicher stoppen, bevor die Referenz fällt
        edgeView = null
        stopButton = null
        inputRow = null
        inputField = null
        keyboardButton = null
        closeButton = null
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

    /**
     * Echter Bildschirm plus Systemleisten. API 30+: aus den Fenster-Metriken (Status-, Navigations-/Gestenleiste, Ausschnitt).
     * API 26–29 (kein Insets-Zugriff ohne Activity): Statusleistenhöhe und — nur wenn das Gerät eine Navigationsleiste
     * hat — deren Höhe aus den System-Ressourcen (Näherung; seitliche Leisten im Querformat entfallen dort).
     */
    private fun screenArea(): ScreenArea {
        val wm = windowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
            try {
                val m = wm.currentWindowMetrics
                val i = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                return ScreenArea(m.bounds.width(), m.bounds.height(), SysInsets(i.left, i.top, i.right, i.bottom))
            } catch (_: Exception) { /* Fallback unten */ }
        }
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") wm?.defaultDisplay?.getRealMetrics(dm)
        if (dm.widthPixels <= 0) { dm.widthPixels = resources.displayMetrics.widthPixels; dm.heightPixels = resources.displayMetrics.heightPixels }
        fun sys(name: String): Int {
            val id = resources.getIdentifier(name, "dimen", "android")
            return if (id > 0) resources.getDimensionPixelSize(id) else 0
        }
        val navId = resources.getIdentifier("config_showNavigationBar", "bool", "android")
        val nav = if (navId > 0 && resources.getBoolean(navId)) sys("navigation_bar_height") else 0
        return ScreenArea(dm.widthPixels, dm.heightPixels, SysInsets(0, sys("status_bar_height"), 0, nav))
    }

    /** Tastaturhöhe ab API 30 (nur im Tippmodus), sonst 0 = unbekannt. */
    private fun readKeyboardPx(): Int {
        if (!typingMode || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        return try {
            val wi = windowManager?.currentWindowMetrics?.windowInsets ?: return 0
            if (wi.isVisible(WindowInsets.Type.ime())) wi.getInsets(WindowInsets.Type.ime()).bottom else 0
        } catch (_: Exception) { 0 }
    }

    private fun panelHeightPx(): Int = panelView?.height?.takeIf { it > 0 } ?: dp(56)

    private fun currentSpot(lp: WindowManager.LayoutParams): PanelSpot =
        PanelPlacement.spot(
            screenArea(), lp.width, panelHeightPx(), panelUser, dimenPx(R.dimen.donna_space_lg).toInt(),
            typingMode, keyboardPx, dp(24), topFallback,
        )

    /** Setzt Gravity unten (mittig oder links) und Abstand von unten aus der reinen Rechnung [PanelPlacement]. */
    private fun applyPlacement(lp: WindowManager.LayoutParams) {
        val spot = currentSpot(lp)
        lp.gravity = Gravity.BOTTOM or (if (spot.centered) Gravity.CENTER_HORIZONTAL else Gravity.LEFT)
        lp.x = spot.x
        lp.y = spot.bottom
        // Inhaltshoehe aus dem Platz ueber der Unterkante: scrollt der Inhalt, statt dass das Panel oben herausragt
        val chrome = (panelHeightPx() - (answerScroll?.height ?: 0)).coerceAtLeast(dp(80))
        val cap = PanelPlacement.contentCap(screenArea(), lp.y, chrome, dp(120), typingMode && topFallback)
        if (cap != contentCapPx) { contentCapPx = cap; answerScroll?.requestLayout() }
    }

    /** Platzierung neu rechnen und ins Fenster geben (nach Layout, Tippmodus, Tastatur, Ziehen). */
    private fun placePanel() {
        val view = panelView ?: return
        val lp = panelParams ?: return
        applyPlacement(lp)
        try { windowManager?.updateViewLayout(view, lp) } catch (_: Exception) {}
    }

    /** Tastaturhöhe nachführen: ändert sie sich, sitzt das Panel direkt darüber. */
    private fun refreshKeyboardPlacement() {
        if (!typingMode) return
        val est = PanelPlacement.keyboardUpdate(KeyboardEstimate(keyboardPx, keyboardProvisional), true, 0, panelHeightPx(), panelParams?.y ?: 0, readKeyboardPx())
        if (est.px != keyboardPx || est.provisional != keyboardProvisional) { keyboardPx = est.px; keyboardProvisional = est.provisional; topFallback = false; placePanel() }
    }

    /** Nach der Wartezeit ohne jede Tastaturhoehe: Rueckfall nach oben (nie verdeckt). Diagnose-Logzeile fuers Geraet. */
    private fun checkKeyboardFallback() {
        val waited = android.os.SystemClock.uptimeMillis() - typingStartedAt
        if (PanelPlacement.needsTopFallback(typingMode, keyboardPx, readKeyboardPx(), waited, PanelPlacement.KEYBOARD_WAIT_MS)) {
            android.util.Log.i(TAG, "Tastatur: keine Hoehe nach ${waited}ms (metrics=${readKeyboardPx()}) weg=rueckfall-oben")
            topFallback = true
            placePanel()
        }
    }

    /** Gespeicherte Nutzerposition laden; Positionen von vor „unten mittig“ (Version < 2) werden einmalig verworfen. */
    private fun loadPanelUser() {
        if (PanelPlacement.discardStored(prefs.getInt(PREF_PANEL_POS_VERSION, 0))) {
            prefs.edit().remove(PREF_PANEL_X).remove(PREF_PANEL_Y).remove(PREF_PANEL_LEFT).remove(PREF_PANEL_BOTTOM)
                .putInt(PREF_PANEL_POS_VERSION, PanelPlacement.POS_VERSION).apply()
        }
        panelUser = PanelPlacement.stored(
            if (prefs.contains(PREF_PANEL_LEFT)) prefs.getInt(PREF_PANEL_LEFT, 0) else null,
            if (prefs.contains(PREF_PANEL_BOTTOM)) prefs.getInt(PREF_PANEL_BOTTOM, 0) else null,
        )
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

        // Bedienzeile (UNTERSTE Zeile des Panels, in Pille und Karte gleich): ganze Zeile ziehbar, Orb + Zustandswort/Live-Text, Mikrofon, Tastatur, Schliessen.
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
        // Drag am ganzen Header: die Tasten verbrauchen ihre Touches selbst (bleiben klickbar); ein Tipp auf das
        // Zustandswort wird vom Drag-Handler als Klick weitergereicht.
        attachPanelDrag(header, lp, wm)
        attachPanelDrag(status, lp, wm)

        // Koerper (nur in der Karte sichtbar): Frage, Antwort + Karten, Aktionszeile, Tippfeld.
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bodyColumn = body

        // Voller Meldungstext (lange Fehler/Hinweise): mehrzeilig, ungekuerzt, ueber allem anderen Inhalt.
        val notice = TextView(this).apply {
            text = cachedNotice
            visibility = if (cachedNotice.isBlank()) View.GONE else View.VISIBLE
            typeface = font(DonnaFonts.Face.SANS_REGULAR)
            setTextColor(themeColor(R.color.donna_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 8 // Meldungen sind <= ca. 110 Zeichen; Grenze schuetzt vor Ueberlaenge im Querformat
            setPadding(0, dp(4), 0, dp(8))
        }
        noticeView = notice
        body.addView(
            notice,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

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
                val maxContentH = minOf(dp(420), resources.displayMetrics.heightPixels * answerMaxPercent / 100, contentCapPx)
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

        // Aktionszeile: „Stopp“ nur beim Sprechen (das Mikrofon sitzt in der Bedienzeile unten).
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val stop = buildStopPill()
        stopButton = stop
        actions.addView(stop, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(PanelLayoutRules.TOUCH_MIN_DP)))
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
        // TalkBack: die Bedienzeile (Schließen, Mikrofon, Tastatur) kommt VOR dem Inhalt, obwohl sie unten sitzt.
        body.id = View.generateViewId()
        header.accessibilityTraversalBefore = body.id
        // Inhalt liegt DARÜBER, die Bedienzeile als letztes Kind darunter: die Karte wächst nach oben, die Bedienzeile bleibt stehen.
        root.addView(
            body,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.marginEnd = dp(12)
                it.bottomMargin = dp(4)
            },
        )
        root.addView(
            header,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
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
                if (insets.isVisible(WindowInsets.Type.ime())) {
                    imeWasVisible = true
                    // Tastaturhoehe aus den echten Insets des Fensters (Metriken des Dienst-Kontexts nur als Rueckfall)
                    // Der Listener-Wert ist FENSTER-relativ (Ueberlappung): absolut = Ueberlappung + unterer Versatz; vollstaendig ueberdeckt = nur Untergrenze, grosszuegig anheben (PanelPlacement.keyboardUpdate)
                    val overlap = insets.getInsets(WindowInsets.Type.ime()).bottom
                    val offset = panelParams?.y ?: 0
                    val metrics = readKeyboardPx()
                    val est = PanelPlacement.keyboardUpdate(KeyboardEstimate(keyboardPx, keyboardProvisional), true, overlap, v.height, offset, metrics)
                    val kb = est.px
                    if (kb != keyboardPx || est.provisional != keyboardProvisional) {
                        android.util.Log.i(TAG, "Tastatur: overlap=$overlap windowH=${v.height} offset=$offset metrics=$metrics derived=$kb weg=" +
                            (if (overlap > v.height) "absolut" else if (overlap > 0) "ueberlappung+versatz" else "metriken"))
                        keyboardPx = kb; keyboardProvisional = est.provisional; topFallback = false; v.post { placePanel() }
                    }
                } else if (imeWasVisible) setTypingMode(PanelTypingEvent.KEYBOARD_CLOSED)
            }
            v.onApplyWindowInsets(insets)
        }
        // MATCH_PARENT-Breite von root ist tragend: Das Fenster hat feste Breite (je Darstellung, [PanelLayoutRules]); nur
        // so bekommt der Rand im zweiten Messdurchgang (MATCH_PARENT-Kind) eine feste Vorgabe, die zur Panelbreite passt (#133).
        // Der Rand bestimmt die Fenstergroesse nie: er beansprucht bei „hoechstens“-Vorgabe nichts (EdgeBounds).
        // Hoehe geaendert (Antwort kommt stueckweise, Karten laden nach): neu platzieren/klemmen; nur bei Aenderung -> keine Endlosschleife
        frame.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val h = bottom - top
            if (h != lastPanelHeight) { lastPanelHeight = h; frame.post { placePanel() } }
        }
        frame.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        frame.addView(edge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return frame
    }

    private fun currentLayoutMode(): PanelLayoutMode = PanelLayoutMode.of(
        currentState, cachedAnswer.isNotBlank(), cachedCards.isNotEmpty(), typingMode, holdCard, cachedNotice.isNotBlank(),
    )

    /**
     * Setzt die Entscheidung von [PanelLayoutMode.of]/[PanelLayoutRules] um: Sichtbarkeiten, Abstaende, Orbgroesse und die
     * Fensterbreite. Billig und idempotent — wird nach jeder Aenderung von Zustand, Text, Karten oder Tippmodus gerufen.
     */
    private fun renderLayout() {
        val view = panelView ?: return
        val mode = currentLayoutMode()
        val modeChanged = mode != layoutMode
        layoutMode = mode
        val compact = mode == PanelLayoutMode.COMPACT
        // Unten/links/rechts in beiden Darstellungen gleich: die Bedienzeile springt beim Wechsel nicht, nur oben kommt Platz dazu.
        rootView?.setPadding(dp(14), if (compact) dp(8) else dp(14), dp(8), dp(8))
        orbView?.let { o ->
            val px = dp(PanelLayoutRules.orbDp(mode))
            val p = o.layoutParams
            if (p != null && (p.width != px || p.height != px)) { p.width = px; p.height = px; o.layoutParams = p }
        }
        bodyColumn?.visibility = if (compact) View.GONE else View.VISIBLE
        statusView?.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(themeColor(if (compact) R.color.donna_text else R.color.donna_accent))
        }
        refreshStatusLine()
        closeButton?.background =
            if (compact) GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(themeColor(R.color.donna_overlay_chip)) } else null
        keyboardButton?.visibility = if (PanelLayoutRules.showKeyboardButton(typingMode, currentState)) View.VISIBLE else View.GONE
        val stop = PanelLayoutRules.showStop(currentState, typingMode)
        stopButton?.visibility = if (stop) View.VISIBLE else View.GONE
        headerMicButton?.visibility = if (PanelLayoutRules.showBarMic(typingMode, currentState)) View.VISIBLE else View.GONE
        actionRow?.visibility = if (stop) View.VISIBLE else View.GONE
        inputRow?.visibility = if (typingMode) View.VISIBLE else View.GONE
        // Die Breite ist fest (kein Wechsel Pille <-> Karte); wechselt die Hoehe, bleibt die Unterkante stehen (Gravity unten).
        // Nach dem Layout neu platzieren (Klemmen unter der Statusleiste, Ausweichen im Tippmodus).
        if (modeChanged) view.post { placePanel() }
    }

    /** Zeile der Bedienleiste: Live-Text (neuester Teil sichtbar) oder Zustandswort — Entscheidung: [PanelLayoutRules.statusLine]. */
    private fun refreshStatusLine() {
        val sv = statusView ?: return
        val live = PanelLayoutRules.statusLine(currentLayoutMode(), currentState, cachedTranscript) == PanelLayoutRules.StatusLine.TRANSCRIPT
        // Zustandswort = Live-Region (polite); der laufende Live-Text nicht (sonst liest TalkBack jede Teil-Mitschrift vor)
        sv.accessibilityLiveRegion = if (live) View.ACCESSIBILITY_LIVE_REGION_NONE else View.ACCESSIBILITY_LIVE_REGION_POLITE
        if (live) {
            sv.setSingleLine(true)
            sv.ellipsize = android.text.TextUtils.TruncateAt.START
            sv.text = cachedTranscript
        } else {
            sv.setSingleLine(false)
            sv.maxLines = 2
            sv.ellipsize = android.text.TextUtils.TruncateAt.END
            sv.text = cachedStatus
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
        if (!next && cachedNotice.isNotBlank()) { cachedNotice = ""; noticeView?.visibility = View.GONE } // Hinweis endet mit dem Tippmodus
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
            // Tippmodus: das Panel sitzt direkt über der Tastatur (Höhe unbekannt: weicht nach oben aus, nie verdeckt);
            // nach dem Tippen ergibt dieselbe Rechnung die vorige Position von selbst (PanelPlacement.spot).
            keyboardPx = if (on) readKeyboardPx() else 0
            keyboardProvisional = false
            topFallback = false // Start: das Panel bleibt unten, erst die erscheinende Tastatur meldet eine Ueberlappung
            typingStartedAt = android.os.SystemClock.uptimeMillis()
            applyPlacement(lp)
            if (view != null) windowManager?.updateViewLayout(view, lp)
            windowFocusable = on // erst nach erfolgreichem Umstellen
            // Neu platzieren erst NACH dem Relayout (echte Höhe bekannt)
            if (view != null) view.post { placePanel() }
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
                            typingHandler.postDelayed({ refreshKeyboardPlacement() }, 250L) // Tastatur ist oben: Höhe lesen (wird mit dem Tippmodus abgeräumt)
                        }
                    }, delayMs)
                }
            }
            typingHandler.postDelayed({ checkKeyboardFallback() }, PanelPlacement.KEYBOARD_WAIT_MS)
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
     * fokussierbar (Tastatur kann erscheinen; ADJUST_NOTHING, weil das Panel sich selbst über die Tastatur setzt, siehe PanelPlacement), sonst wie bisher.
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
    private fun attachPanelDrag(handle: View, lp: WindowManager.LayoutParams, @Suppress("UNUSED_PARAMETER") wm: WindowManager) {
        var startLeft = 0
        var startBottom = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val spot = currentSpot(lp)
                    startLeft = PanelPlacement.leftOf(spot, screenArea(), lp.width)
                    startBottom = spot.bottom
                    touchX = event.rawX; touchY = event.rawY; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (!moved && kotlin.math.abs(dx) <= slop && kotlin.math.abs(dy) <= slop) return@setOnTouchListener true // noch ein Tipp
                    if (typingMode) return@setOnTouchListener true // im Tippmodus gesperrt: die tastaturbedingte Position wird nie zur Nutzerposition
                    panelUser = PanelPlacement.dragged(startLeft, startBottom, dx, dy, screenArea(), lp.width, panelHeightPx())
                    moved = true
                    placePanel()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved) {
                        val u0 = panelUser
                        if (u0 != null && PanelPlacement.snapsToDefault(u0, screenArea(), lp.width, dimenPx(R.dimen.donna_space_lg).toInt(), dp(24))) {
                            // nahe „unten mittig“ losgelassen: einrasten, Nutzerposition loeschen (wieder zentriert)
                            panelUser = null
                            prefs.edit().remove(PREF_PANEL_LEFT).remove(PREF_PANEL_BOTTOM).apply()
                            placePanel()
                        } else panelUser?.let { u ->
                            prefs.edit().putInt(PREF_PANEL_LEFT, u.left).putInt(PREF_PANEL_BOTTOM, u.bottom)
                                .putInt(PREF_PANEL_POS_VERSION, PanelPlacement.POS_VERSION).apply()
                        }
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
        val wasListening = currentState.isListening
        currentState = state
        if (state.isListening && !wasListening) {
            // neues Zuhoeren: steht nichts Vorheriges mehr da, auch die alte Mitschrift weg (sonst „Live-Text“ von gestern)
            val c = PanelContent(cachedTranscript, cachedAnswer, cachedCards.size).afterListeningStarted()
            if (c.transcript != cachedTranscript) {
                cachedTranscript = c.transcript
                transcriptView?.let { it.text = ""; it.visibility = View.GONE }
            }
        }
        if (holdCard && PanelLayoutRules.holdReleasedBy(state)) holdCard = false
        // Beginnt eine Aufnahme oder endet das Gespräch, ist der Tippmodus vorbei (Fenster zurückstellen)
        PanelFocus.exitForState(typingMode, state)?.let { setTypingMode(it) }
        ensureWindowRestored()
        // Text aus der gemeinsamen Abbildung; „Bis gleich.“ bzw. der Klartext des Fehler-Endes kommt über onError
        // (Controller setzt ihn direkt danach); bei ERROR kommt der Fehlertext über onError.
        if (state != OverlayState.ERROR) {
            cachedStatus = OrbVisuals.of(state, true).label
            cachedNotice = "" // die Meldung gehoert zum Fehler; ein neuer Zustand beendet sie
            noticeView?.visibility = View.GONE
        }
        refreshStatusLine()
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
        cachedTranscript = PanelContent(cachedTranscript, cachedAnswer, cachedCards.size).afterPartial(text).transcript
        transcriptView?.let { it.text = text; it.visibility = if (text.isBlank()) View.GONE else View.VISIBLE }
        renderLayout()
    }

    override fun onUserUtterance(text: String) {
        holdCard = false // ab jetzt traegt die Mitschrift die Karte
        // Nur der AKTUELLE Zug ist zu sehen: neue Frage -> Antwort und Karten des vorigen Zugs weg (PanelContent)
        val c = PanelContent(cachedTranscript, cachedAnswer, cachedCards.size).afterUtterance(text)
        cachedTranscript = c.transcript
        transcriptView?.let { it.text = text; it.visibility = View.VISIBLE }
        cachedAnswer = c.answer
        answerView?.text = ""
        if (c.cardCount == 0) clearCards()
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
        // Lange Meldung: in der Bedienzeile nur das Kurzwort, der volle Text steht ungekuerzt im Inhalt (Karte oeffnet sich)
        val split = PanelLayoutRules.statusSplit(OrbVisuals.of(currentState, true).label, message)
        cachedStatus = split.line
        cachedNotice = split.notice ?: ""
        noticeView?.let { it.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE; it.text = cachedNotice; it.visibility = if (cachedNotice.isBlank()) View.GONE else View.VISIBLE }
        refreshStatusLine()
        renderLayout()
    }

    // ── Karten (Aktion/Wetter/Map) ────────────────────────────────────────────────
    override fun onActionCard(action: JSONObject) = addCard(CardSpec.Action(action))

    private val deviceActionRunner by lazy {
        DeviceActionRunner(this).also {
            it.warmUp()
            // #219: Vorschau-Karte nach dem Screenshot (Thumbnail auf dem Worker laden, Karte auf Main)
            it.onScreenshotSaved = { uri -> deviceExecutor.execute { showScreenshotPreview(uri) } }
        }
    }

    private fun showScreenshotPreview(uri: String) {
        if (!com.yourcompany.donna.device.ScreenshotLocator.isMediaImageUri(uri)) return
        val thumb = try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                contentResolver.loadThumbnail(android.net.Uri.parse(uri), android.util.Size(dp(320), dp(200)), null)
            } else null
        } catch (_: Exception) { null } ?: return // ohne Thumbnail keine Karte (der Screenshot liegt trotzdem in der Galerie)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            // kein offenes Panel: keine Karte anlegen (sie würde Minuten später veraltet auftauchen)
            if (cardContainer != null) addCard(CardSpec.Screenshot(uri, thumb))
        }
    }

    private fun buildScreenshotCard(spec: CardSpec.Screenshot): View {
        val card = cardKit.card()
        val img = android.widget.ImageView(this).apply {
            setImageBitmap(spec.thumb)
            adjustViewBounds = true
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = "Screenshot-Vorschau. Antippen öffnet das Bild in der Galerie."
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200))
            setOnClickListener {
                if (!com.yourcompany.donna.device.ScreenshotPreview.openInGallery(this@LiveBubbleService, spec.uri)) {
                    android.widget.Toast.makeText(this@LiveBubbleService, "Die Galerie ließ sich nicht öffnen.", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        card.addView(cardKit.text("Screenshot gespeichert — antippen zum Öffnen", CardStyle.Type.EYEBROW, CardStyle.Role.MUTED, bottomDp = 4))
        card.addView(img)
        return card
    }

    /** #147: Geräte-Aktion ohne Karte; dieselbe Prüfung und derselbe Runner wie im Chat. Rückmeldung vom Gerät. */
    override fun onDeviceAction(action: JSONObject, budget: TurnBudget) {
        // Worker statt Main-Thread (Nicht stören wartet beim Rücklesen; der Executor hält die Reihenfolge der Antwort)
        deviceExecutor.execute {
            val result = deviceActionRunner.runGated(action, deviceActionsEnabled(), budget)
            // Rückmeldung kommt vom Gerät (sichtbar, unabhängig davon, ob danach ein Fehlerereignis folgt)
            val json = result.toJson()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                addCard(CardSpec.Device(result.message, if (json.optBoolean("undoable", false)) json.optLong("undoId") else null, json.optBoolean("undoOpensClock", false)))
            }
        }
    }

    private val deviceExecutor by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }

    private fun deviceActionsEnabled() =
        DeviceActionKind.enabledSet(getSharedPreferences(TtsEngines.PREFS, Context.MODE_PRIVATE))

    private val deviceResultCardBuilder by lazy {
        DeviceResultCardBuilder(this, { dp(it) }, { themeColor(it) })
    }
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
            override fun color(id: Int): Int = themeColor(id)
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
        is CardSpec.Screenshot -> buildScreenshotCard(spec)
        is CardSpec.Device -> deviceResultCardBuilder.build(spec.message, spec.undoId != null, if (spec.opensClock) DeviceResultCardBuilder.CLOCK_LABEL else "Rückgängig") {
            // die Taste trägt die Kennung SEINER Aktion und wirkt nur auf diese
            spec.undoId?.let { deviceActionRunner.undo(it, deviceActionsEnabled()).message } ?: ""
        }
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

    /** Gemeinsame Bausteine aller Karten (#151): Farben im Darstellungs-Kontext, Maße/Schrift nach [CardStyle]. */
    private val cardKit by lazy { CardKit(this, { dp(it) }, { themeColor(it) }) }

    // Wetter-Karte (aus VoiceInputActivity übertragen; Service-Context + themeColor), im gemeinsamen Kartenstil.
    private fun buildWeatherCard(d: JSONObject): View {
        val card = cardKit.card()
        card.addView(cardKit.text(JsonSafe.str(d, "location", ""), CardStyle.Type.EYEBROW, CardStyle.Role.MUTED, bottomDp = 4))
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(4) }
        }
        topRow.addView(cardKit.text("${JsonSafe.numText(d, "temp_c")}°", CardStyle.Type.DISPLAY).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        // Symbol kommt vom Server; fehlt es, gar keine View (keine leere Zeile)
        JsonSafe.str(d, "condition_icon", "").takeIf { it.isNotBlank() }?.let { icon ->
            topRow.addView(TextView(this).apply { text = icon; setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f) })
        }
        card.addView(topRow)
        card.addView(cardKit.text(JsonSafe.str(d, "condition", ""), CardStyle.Type.TITLE, bottomDp = CardStyle.BUTTON_GAP_DP))
        card.addView(cardKit.divider())
        // Details untereinander und umbrechbar statt vier schmaler Spalten
        listOf(
            "Tief ${JsonSafe.numText(d, "temp_min")}° Hoch ${JsonSafe.numText(d, "temp_max")}°",
            "Feuchte ${JsonSafe.numText(d, "humidity")}%",
            "Wind ${JsonSafe.numText(d, "wind_kmh")} km/h",
            "Gefühlt ${JsonSafe.numText(d, "feels_like_c")}°",
        ).forEach { detail -> card.addView(cardKit.text(detail, CardStyle.Type.CAPTION, CardStyle.Role.MUTED, bottomDp = 4)) }
        return card
    }

    // Maps-Karte (aus VoiceInputActivity übertragen). URL wurde in onMapCard bereits gegen die Policy geprüft.
    private fun buildMapCard(mapsUrl: String): View {
        val checked = UrlPolicy.check(mapsUrl)
        return cardKit.card(horizontal = true).apply {
            setOnClickListener { openCheckedUrl(checked) }
            addView(cardKit.icon(R.drawable.ic_donna_map, 26).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(CardStyle.BUTTON_GAP_DP)
            })
            addView(LinearLayout(this@LiveBubbleService).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(cardKit.text("In Google Maps öffnen", CardStyle.Type.TITLE, CardStyle.Role.ICON))
                addView(cardKit.text(
                    if (checked != null) "Ziel: ${checked.displayTarget()}" else "Link nicht erlaubt",
                    CardStyle.Type.CAPTION, CardStyle.Role.MUTED,
                ))
            })
            addView(cardKit.icon(R.drawable.ic_donna_chevron_right, 22))
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
        /** Seit „unten mittig“ (Version 2): linke Kante + Abstand der Unterkante; die alten Werte (oben links) werden einmalig verworfen. */
        private const val PREF_PANEL_LEFT = "panel_left"
        private const val PREF_PANEL_BOTTOM = "panel_bottom"
        private const val PREF_PANEL_POS_VERSION = "panel_pos_version"
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
