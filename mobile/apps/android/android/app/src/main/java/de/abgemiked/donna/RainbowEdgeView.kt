package com.yourcompany.donna

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Leuchtender Regenbogen-Rand (#122 Paket B): umlaufender Sweep-Verlauf entlang eines abgerundeten Rechtecks,
 * langsam rotierend — nur sichtbar, solange das Mikrofon aufnimmt ([setActive]). Reine Canvas-Zeichnung.
 *
 * Lebenszyklus: Der Animator laeuft NUR, wenn aktiv + angehaengt + Fenster sichtbar + Animationen erlaubt
 * ([OrbVisuals]/„Animationen entfernen“ -> dann ein statischer Rand). Er stoppt bei jedem anderen Zustand, in
 * [onDetachedFromWindow] und wenn das Fenster verschwindet; im Ruhezustand ist die View GONE (kein Neuzeichnen).
 * Beruehrungen gehen durch (nicht klickbar).
 */
class RainbowEdgeView(context: Context, private val cornerRadiusPx: Float) : View(context) {

    private var active = false
    private var rotating = false
    private var animator: ValueAnimator? = null
    private var angle = 0f

    private val density = resources.displayMetrics.density
    private val rect = RectF()
    private val matrix = Matrix()
    private var shader: SweepGradient? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    init {
        visibility = GONE
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** [active] = Mikrofon nimmt auf (Rand an); [rotating] = zusaetzlich bewegt (Animationen erlaubt). */
    fun setActive(active: Boolean, rotating: Boolean) {
        this.active = active
        this.rotating = active && rotating
        visibility = if (active) VISIBLE else GONE
        updateAnimator()
        invalidate()
    }

    private fun needsAnimator(): Boolean = rotating && isAttachedToWindow && windowVisibility == VISIBLE

    private fun updateAnimator() {
        if (!needsAnimator()) {
            animator?.cancel()
            animator = null
            return
        }
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = RainbowEdge.PERIOD_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                angle = RainbowEdge.rotationDeg(SystemClock.uptimeMillis())
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

    /**
     * #133: Der Rand bestimmt die Fenstergroesse nie. Bei „hoechstens“ (Fenster WRAP_CONTENT) beansprucht er nichts;
     * der Rahmen misst ihn danach mit der festen Panelgroesse neu (MATCH_PARENT-Kind, 2. Durchgang) -> exakt so gross
     * wie das Panel, in jedem Zustand (Tippmodus, wachsende Antwort, Karten). Regel: [EdgeBounds].
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            EdgeBounds.measured(MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY, MeasureSpec.getSize(widthMeasureSpec)),
            EdgeBounds.measured(MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY, MeasureSpec.getSize(heightMeasureSpec)),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shader = SweepGradient(w / 2f, h / 2f, RainbowEdge.COLORS, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!active) return
        val sh = shader ?: return
        matrix.setRotate(if (rotating) angle else 0f, width / 2f, height / 2f)
        sh.setLocalMatrix(matrix)
        paint.shader = sh
        // Drei Striche uebereinander: breiter weicher Schein, mittlerer Ring, schmale helle Kante
        for ((widthDp, alpha) in GLOW_LAYERS) {
            val stroke = widthDp * density
            val inset = stroke / 2f
            rect.set(inset, inset, width - inset, height - inset)
            paint.strokeWidth = stroke
            paint.alpha = alpha
            canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, paint)
        }
    }

    private companion object {
        /** (Strichbreite dp, Alpha 0..255) von aussen nach innen. */
        val GLOW_LAYERS = listOf(10f to 55, 5f to 120, 2.5f to 235)
    }
}
