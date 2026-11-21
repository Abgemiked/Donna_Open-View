package com.yourcompany.donna

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import kotlin.math.min

/**
 * Animierter Orb des Overlays (UX-02, Optik #134 P7): ein weicher, langsam drehender Farbkranz (Violett, Magenta, Gelb,
 * Blau, Indigo) um einen dunklen Kern (im hellen Modus heller Kern mit Lavendel). Reine Canvas-Zeichnung, keine
 * Zusatz-Abhängigkeit. Weich wird der Kranz durch übereinandergelegte Verläufe mit nach außen auslaufender Deckkraft
 * (läuft auf allen API-Stufen ab minSdk 26; ein RenderEffect-Weichzeichner würde auch den scharfen Kern verwischen).
 *
 * - LISTENING: Kern + Glow folgen der Lautstärke ([setLevel], gespeist aus onRmsChanged)
 * - THINKING: Kern atmet, ein Bogen rotiert (~1,6 s)
 * - SPEAKING: pulsiert ~1 Hz mit auslaufender Welle
 * - ERROR: einmaliges Schütteln, Bernstein
 * - FOLLOW_UP (Weiterhoeren): wie LISTENING + ein Ring, der ueber das Hoerfenster abläuft (#122 Paket B)
 * - IDLE/ENDING/ENDED: ruhiger, grauer, statischer Glow
 *
 * Was gezeigt wird (Farbe, Ring, Bewegung), entscheidet die reine Abbildung [OrbVisuals]; diese View zeichnet nur.
 * Der Ring-Anteil kommt per [setRingSource] aus [FollowUpRing] (= FollowUpWindow.remainingMs der Steuerung):
 * kein eigener Zeitgeber.
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

    private var colorCore = 0
    private var wreath: IntArray? = null

    private val wreathPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val shaderMatrix = Matrix()
    private val arcRect = RectF()
    private var sweepShader: SweepGradient? = null
    private var shaderColor = 0
    private var wreathShader: SweepGradient? = null
    private var errorShader: SweepGradient? = null
    private var wreathShaderBuilt = false
    private var lastWreathAngle = 0f

    private var ringSource: (() -> Float)? = null
    /** Abbildung des aktuellen Zustands, beim Zustandswechsel/Paletten-/Animationswechsel gebildet (nicht je Frame). */
    private var visual: OrbVisual = OrbVisuals.of(OverlayState.IDLE, true)

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
        wreathShaderBuilt = false
        invalidate()
    }

    /** Kern- und Kranzfarben (themenabhängig, aus colors.xml des Darstellungs-Kontexts); ohne Aufruf gelten die Standardwerte. */
    fun setLook(core: Int, wreathColors: IntArray) {
        colorCore = core
        wreath = if (wreathColors.size >= 2) wreathColors.copyOf() else null
        wreathShaderBuilt = false
        invalidate()
    }

    private fun coreColor(): Int = if (colorCore != 0) colorCore else ContextCompat.getColor(context, R.color.donna_orb_core)

    private fun wreathColors(): IntArray = wreath ?: intArrayOf(
        ContextCompat.getColor(context, R.color.donna_orb_violet), ContextCompat.getColor(context, R.color.donna_orb_magenta),
        ContextCompat.getColor(context, R.color.donna_orb_yellow), ContextCompat.getColor(context, R.color.donna_orb_blue),
        ContextCompat.getColor(context, R.color.donna_orb_indigo),
    ).also { wreath = it }

    /** Liefert den Restanteil des Hoerfensters (1 -> 0); wird nur im Weiterhoeren und nur beim Zeichnen gelesen. */
    fun setRingSource(source: (() -> Float)?) {
        ringSource = source
    }

    fun setState(newState: OverlayState) {
        if (state == newState) return
        state = newState
        visual = OrbVisuals.of(newState, animationsEnabled())
        stateSince = SystemClock.uptimeMillis()
        if (!newState.isListening) targetLevel = 0f
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

    private fun toneColor(tone: OrbTone): Int = when (tone) {
        OrbTone.MUTED -> colorMuted
        OrbTone.ACCENT -> colorAccent
        OrbTone.ACCENT2 -> colorAccent2
        OrbTone.ERROR -> colorError
    }

    private fun ensureShaders(color: Int, cx: Float, cy: Float) {
        if (sweepShader != null && shaderColor == color) return
        shaderColor = color
        sweepShader = SweepGradient(
            cx, cy,
            intArrayOf(ColorUtils.setAlphaComponent(color, 0), color),
            floatArrayOf(0f, 1f),
        )
    }

    private fun ensureWreath(cx: Float, cy: Float) {
        if (wreathShaderBuilt) return
        val c = wreathColors()
        // Start = Ende: nahtloser Umlauf
        wreathShader = SweepGradient(cx, cy, c + c[0], null)
        errorShader = SweepGradient(cx, cy, intArrayOf(colorError, colorError), null)
        wreathShaderBuilt = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shaderColor = 0 // Sweep-Mittelpunkt neu setzen
        wreathShaderBuilt = false
    }

    /**
     * Kranz: gefüllte Scheiben mit dem Farb-Sweep, nach innen kleiner und dichter; die äußerste ist am blassesten →
     * weicher Rand. Der Kern liegt danach darüber (verdeckt die dichte Mitte). Ruhe: matt, Fehler: einfarbig bernsteinfarben.
     */
    private fun drawWreath(canvas: Canvas, cx: Float, cy: Float, outerR: Float, strength: Float, angle: Float, tone: OrbTone) {
        ensureWreath(cx, cy)
        shaderMatrix.reset()
        shaderMatrix.postRotate(angle, cx, cy)
        val shader = if (tone == OrbTone.ERROR) errorShader else wreathShader
        shader?.setLocalMatrix(shaderMatrix)
        wreathPaint.shader = shader
        val s = if (tone == OrbTone.MUTED) 0.35f else strength.coerceIn(0f, 1f)
        for (i in 0 until WREATH_LAYERS) {
            val layerAlpha = if (i == 0) 0.28f else 0.42f
            wreathPaint.alpha = (255f * s * layerAlpha).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, outerR * (1f - WREATH_STEP * i), wreathPaint)
        }
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
        val color = toneColor(visual.tone)
        ensureShaders(color, cx, cy)

        val phaseThinking = if (animated) OrbMath.phase(t, OrbMath.THINKING_PERIOD_MS) else 0f
        val phaseSpeaking = if (animated) OrbMath.phase(t, OrbMath.SPEAKING_PERIOD_MS) else 0f
        val phaseIdle = if (animated) OrbMath.phase(t, OrbMath.LISTENING_IDLE_PERIOD_MS) else 0f
        val lvl = if (animated) level else 0f

        val half = min(w, h) / 2f
        // Kranz bis fast an den Rand; der Kern darf bei maximaler Lautstärke (Faktor ~1,23) den Kranz nie ganz verdecken
        val outerR = half * 0.95f
        val coreR = half * 0.54f * OrbMath.coreScale(state, lvl, phaseThinking, phaseSpeaking, phaseIdle)
        val glow = OrbMath.glowStrength(state, lvl, phaseSpeaking)
        // beim Wechsel in Ruhe (Animator aus) den letzten Winkel halten statt auf 0 zu springen
        if (animated) lastWreathAngle = OrbMath.phase(nowMs, RainbowEdge.PERIOD_MS) * 360f
        val angle = lastWreathAngle

        val shake = if (animated && state == OverlayState.ERROR) {
            OrbMath.shakeOffset(t, 7f * density)
        } else 0f

        canvas.save()
        canvas.translate(shake, 0f)

        // Farbkranz (Deckkraft folgt dem Leuchten: laut/sprechend heller, Ruhe matt)
        drawWreath(canvas, cx, cy, outerR, 0.55f + 0.45f * glow, angle, visual.tone)

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

        // Weiterhoeren: Ring laeuft ueber das Hoerfenster ab (ohne Animationen: voller, statischer Ring)
        // Je Frame nur der Ring-Anteil (die Abbildung selbst liegt im Cache)
        OrbVisuals.ringFraction(state, animationsEnabled(), ringSource?.invoke() ?: 1f)?.let { fraction ->
            val r = outerR - 1.5f * density
            arcPaint.shader = null
            arcPaint.strokeWidth = 2.5f * density
            arcRect.set(cx - r, cy - r, cx + r, cy + r)
            arcPaint.color = ColorUtils.setAlphaComponent(colorMuted, 70)
            canvas.drawArc(arcRect, 0f, 360f, false, arcPaint)
            arcPaint.color = colorAccent2
            canvas.drawArc(arcRect, -90f, 360f * fraction, false, arcPaint)
        }

        // Kern (dunkel bzw. im hellen Modus hell mit Lavendel); der Zustand steckt in Kranz, Wellen und Ringen
        corePaint.color = coreColor()
        canvas.drawCircle(cx, cy, coreR, corePaint)

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

    private companion object {
        /** Anzahl der übereinandergelegten Kranz-Scheiben und ihr Radius-Schritt (Anteil des Außenradius). */
        const val WREATH_LAYERS = 5
        const val WREATH_STEP = 0.07f
    }
}
