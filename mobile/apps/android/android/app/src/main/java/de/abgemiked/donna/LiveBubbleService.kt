package com.yourcompany.donna

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * LiveBubbleService (A2, Increment 1) — verschiebbarer, NICHT-modaler, touch-durchlässiger Chat-Head
 * („Live-Bubble") über allen Apps, wie Gemini/Bixby.
 *
 * Increment 1 (dieser Stand): schwebende Blase (WindowManager, TYPE_APPLICATION_OVERLAY,
 * FLAG_NOT_FOCUSABLE/FLAG_NOT_TOUCH_MODAL → App dahinter bleibt bedienbar), per Finger verschiebbar
 * (Position gemerkt + auf den Bildschirm geklemmt), Tippen öffnet das bestehende Donna-Overlay
 * (VoiceInputActivity, bewährter Sprach-Loop inkl. A3-Barge-in), langes Drücken beendet die Blase.
 *
 * Spätere Increments (PLAN_live_bubble.md Option B): der Sprach-Loop wandert in diesen Service
 * (nicht-modales Mini-Panel statt Vollbild-Activity) + MediaProjection/Kamera als Vision-Quellen.
 *
 * START_NOT_STICKY: die Blase ist eine vom Nutzer geschaltete Komfortfunktion — nach Prozess-Kill NICHT
 * ohne Vordergrund-Kontext wieder-starten (sonst startForeground-Crash). FGS-Typ Increment 1 = "specialUse"
 * (nur UI); microphone/mediaProjection kommen hinzu, wenn der Loop hier hostet. GERÄT: FGS-Start auf One UI prüfen.
 */
class LiveBubbleService : Service() {

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var dragHandler: Handler? = null

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
            .setContentText("Live-Bubble aktiv — tippen zum Öffnen")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
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

    /** Increment 1: Tap öffnet das bestehende Donna-Overlay (bewährter Sprach-Loop). */
    private fun onBubbleTapped() {
        try {
            startActivity(
                Intent(this, VoiceInputActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Overlay-Start fehlgeschlagen: ${e.message}")
        }
    }

    private fun removeBubble() {
        dragHandler?.removeCallbacksAndMessages(null)
        dragHandler = null
        bubbleView?.let { v -> try { windowManager?.removeView(v) } catch (_: Exception) {} }
        bubbleView = null
        params = null
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

    companion object {
        private const val TAG = "LiveBubbleService"
        private const val PREFS = "donna_live_bubble"
        private const val PREF_X = "bubble_x"
        private const val PREF_Y = "bubble_y"
        private const val CHANNEL_ID = "donna_live_bubble"
        private const val NOTIF_ID = 4201
        const val ACTION_STOP = "com.yourcompany.donna.LIVE_BUBBLE_STOP"

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
