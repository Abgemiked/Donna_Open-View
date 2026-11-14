package com.yourcompany.donna

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import kotlin.math.min

/**
 * Animierter Orb + Glow des Overlays (UX-02). Reine Canvas-Zeichnung, keine Zusatz-Abhängigkeit.
 *
 * - LISTENING: Kern + Glow folgen der Lautstärke ([setLevel], gespeist aus onRmsChanged)
 * - THINKING: Kern atmet, ein Bogen rotiert (~1,6 s)
 * - SPEAKING: pulsiert ~1 Hz mit auslaufender Welle
 * - ERROR: einmaliges Schütteln, Bernstein
 * - IDLE: ruhiger, statischer Glow
 *
 * Respektiert „Animationen entfernen“ (ValueAnimator.areAnimatorsEnabled() == false): dann statische Zustandsfarben.
 */
class DonnaOrbView(context: Context) : View(context) {

    private var state = OverlayState.IDLE
    private var level = 0f
    private var targetLevel = 0f

    private var colorAccent = 0
    private var colorAccent2 = 0
    private var colorMuted = 0
    private var colorError = 0

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val shaderMatrix = Matrix()
    private val arcRect = RectF()
    private var glowShader: RadialGradient? = null
    private var sweepShader: SweepGradient? = null
    private var shaderColor = 0

    private var animator: ValueAnimator? = null
    private var stateSince = 0L
    private var nowMs = 0L

    private val density = resources.displayMetrics.density

    /** Farben aus dem Theme (colors.xml — Dark/Light/Dynamic Colors). */
    fun setPalette(accent: Int, accent2: Int, muted: Int, error: Int) {
        colorAccent = accent
        colorAccent2 = accent2
        colorMuted = muted
        colorError = error
        shaderColor = 0
        invalidate()
    }

    fun setState(newState: OverlayState) {
        if (state == newState) return
        state = newState
        stateSince = SystemClock.uptimeMillis()
        if (newState != OverlayState.LISTENING) targetLevel = 0f
        if (!OrbMath.isContinuous(newState)) level = 0f
        updateAnimator()
        invalidate()
    }

    /** Lautstärke 0…1 (aus [OrbMath.levelFromRms]); wird geglättet. */
    fun setLevel(newLevel: Float) {
        targetLevel = newLevel.coerceIn(0f, 1f)
    }

    private fun animationsEnabled(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()

    private fun needsAnimator(): Boolean =
        animationsEnabled() && isAttachedToWindow && windowVisibility == VISIBLE &&
            (OrbMath.isContinuous(state) || state == OverlayState.ERROR)

    private fun updateAnimator() {
        if (!needsAnimator()) {
            animator?.cancel()
            animator = null
            return
        }
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                nowMs = SystemClock.uptimeMillis()
                level = OrbMath.smooth(level, targetLevel)
                if (state == OverlayState.ERROR && nowMs - stateSince >= OrbMath.ERROR_SHAKE_MS) {
                    // Schütteln ist einmalig — danach wieder statisch
                    cancel()
                    animator = null
                }
                invalidate()
            }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimator()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateAnimator()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (96 * density).toInt()
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    private fun stateColor(): Int = when (state) {
        OverlayState.IDLE -> colorMuted
        OverlayState.LISTENING -> colorAccent
        OverlayState.THINKING -> colorAccent2
        OverlayState.SPEAKING -> colorAccent
        OverlayState.ERROR -> colorError
    }

    private fun ensureShaders(color: Int, cx: Float, cy: Float) {
        if (glowShader != null && shaderColor == color) return
        shaderColor = color
        glowShader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(ColorUtils.setAlphaComponent(color, 190), ColorUtils.setAlphaComponent(color, 0)),
            null, Shader.TileMode.CLAMP,
        )
        sweepShader = SweepGradient(
            cx, cy,
            intArrayOf(ColorUtils.setAlphaComponent(color, 0), color),
            floatArrayOf(0f, 1f),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shaderColor = 0 // Sweep-Mittelpunkt neu setzen
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val animated = animator != null
        // Phasen starten am ZUSTANDSWECHSEL (stateSince): Atmen/Pulsieren beginnt mit dem Wechsel, nicht irgendwo mitten im Zyklus
        val t = if (animated) (nowMs - stateSince).coerceAtLeast(0L) else 0L
        val color = stateColor()
        ensureShaders(color, cx, cy)

        val phaseThinking = if (animated) OrbMath.phase(t, OrbMath.THINKING_PERIOD_MS) else 0f
        val phaseSpeaking = if (animated) OrbMath.phase(t, OrbMath.SPEAKING_PERIOD_MS) else 0f
        val phaseIdle = if (animated) OrbMath.phase(t, OrbMath.LISTENING_IDLE_PERIOD_MS) else 0f
        val lvl = if (animated) level else 0f

        val base = min(w, h)
        // Kern so dimensioniert, dass der größte Glow (2,1 × Kernradius bei maximaler Lautstärke) in die View passt
        val coreR = base * 0.19f * OrbMath.coreScale(state, lvl, phaseThinking, phaseSpeaking, phaseIdle)
        val glowR = coreR * (1.55f + 0.55f * lvl)
        val glowAlpha = (OrbMath.glowStrength(state, lvl, phaseSpeaking) * 255f).toInt().coerceIn(0, 255)

        val shake = if (animated && state == OverlayState.ERROR) {
            OrbMath.shakeOffset(t, 7f * density)
        } else 0f

        canvas.save()
        canvas.translate(shake, 0f)

        // Glow
        shaderMatrix.reset()
        shaderMatrix.setScale(glowR, glowR)
        shaderMatrix.postTranslate(cx, cy)
        glowShader?.setLocalMatrix(shaderMatrix)
        glowPaint.shader = glowShader
        glowPaint.alpha = glowAlpha
        canvas.drawCircle(cx, cy, glowR, glowPaint)

        // Auslaufende Welle beim Sprechen
        if (state == OverlayState.SPEAKING && animated) {
            ringPaint.color = color
            ringPaint.strokeWidth = 2f * density
            ringPaint.alpha = ((1f - phaseSpeaking) * 140f).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, coreR * (1f + 0.55f * phaseSpeaking), ringPaint)
        }

        // Sprechen: zusätzlich ein STATISCHER Ring (auch ohne Animation) — Zuhören und Sprechen sind so
        // auch bei „Animationen entfernen“ optisch unterscheidbar
        if (state == OverlayState.SPEAKING) {
            ringPaint.color = color
            ringPaint.strokeWidth = 1.5f * density
            ringPaint.alpha = 150
            canvas.drawCircle(cx, cy, coreR * 1.38f, ringPaint)
        }

        // Kern + Glanzpunkt
        corePaint.color = color
        canvas.drawCircle(cx, cy, coreR, corePaint)
        highlightPaint.color = ColorUtils.setAlphaComponent(colorAccent2, 110)
        canvas.drawCircle(cx - coreR * 0.28f, cy - coreR * 0.30f, coreR * 0.42f, highlightPaint)

        // Denk-Bogen: rotiert; im statischen Modus ein fester Bogen als Zustandsmerkmal
        if (state == OverlayState.THINKING) {
            val stroke = 3f * density
            arcPaint.strokeWidth = stroke
            arcPaint.shader = sweepShader
            val r = coreR * 1.28f
            arcRect.set(cx - r, cy - r, cx + r, cy + r)
            canvas.save()
            canvas.rotate(phaseThinking * 360f, cx, cy)
            canvas.drawArc(arcRect, 0f, 270f, false, arcPaint)
            canvas.restore()
        }
        canvas.restore()
    }
}
