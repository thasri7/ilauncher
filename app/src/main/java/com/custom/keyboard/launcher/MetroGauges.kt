package com.custom.keyboard.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/** Animates a 0..1 value towards a target and redraws; shared by the tile gauges. */
abstract class AnimatedLevelView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    protected var shown = 0f
        private set
    private var animator: ValueAnimator? = null
    protected val density = resources.displayMetrics.density

    fun setLevel(fraction: Float, animate: Boolean = true) {
        val target = fraction.coerceIn(0f, 1f)
        if (target == shown && animator?.isRunning != true) return
        animator?.cancel()
        if (!animate || !isAttachedToWindow) {
            shown = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(shown, target).apply {
            duration = 600
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener {
                shown = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }
}

/** Thin ring gauge (storage, memory): white arc over a translucent track. */
class RingGauge(context: Context, attrs: AttributeSet? = null) : AnimatedLevelView(context, attrs) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x40FFFFFF
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFFFFF.toInt()
        strokeCap = Paint.Cap.ROUND
    }
    private val oval = RectF()

    override fun onDraw(canvas: Canvas) {
        val stroke = (minOf(width, height) * 0.09f).coerceAtLeast(2 * density)
        track.strokeWidth = stroke
        arc.strokeWidth = stroke
        val inset = stroke / 2 + 1
        val size = minOf(width, height) - 2 * inset
        oval.set((width - size) / 2f, (height - size) / 2f, (width + size) / 2f, (height + size) / 2f)
        canvas.drawOval(oval, track)
        if (shown > 0f) canvas.drawArc(oval, -90f, 360f * shown, false, arc)
    }
}

/** Horizontal battery glyph whose fill follows the charge; red when low, with a bolt when charging. */
class BatteryGlyph(context: Context, attrs: AttributeSet? = null) : AnimatedLevelView(context, attrs) {
    var charging = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFFFFF.toInt()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bolt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = RectF()
    private val inner = RectF()
    private val cap = RectF()
    private val boltPath = Path()

    override fun onDraw(canvas: Canvas) {
        val stroke = (height * 0.09f).coerceAtLeast(1.5f * density)
        outline.strokeWidth = stroke
        val capW = height * 0.16f
        body.set(stroke / 2, stroke / 2, width - capW - stroke, height - stroke / 2)
        val r = height * 0.18f
        canvas.drawRoundRect(body, r, r, outline)
        cap.set(body.right + stroke * 0.4f, height * 0.3f, width.toFloat(), height * 0.7f)
        fill.color = 0xFFFFFFFF.toInt()
        canvas.drawRoundRect(cap, r / 2, r / 2, fill)

        val pad = stroke * 1.6f
        inner.set(body.left + pad, body.top + pad, body.right - pad, body.bottom - pad)
        inner.right = inner.left + inner.width() * shown
        fill.color = if (shown <= 0.15f && !charging) 0xFFFF5A4E.toInt() else 0xFFFFFFFF.toInt()
        if (inner.width() > 0) canvas.drawRoundRect(inner, r / 2, r / 2, fill)

        if (charging) {
            // Bolt cut-out in the tile colour behind, drawn as a dark translucent glyph.
            bolt.color = 0xB3000000.toInt()
            val cx = body.centerX()
            val cy = body.centerY()
            val h = body.height() * 0.62f
            boltPath.reset()
            boltPath.moveTo(cx + h * 0.12f, cy - h / 2)
            boltPath.lineTo(cx - h * 0.28f, cy + h * 0.06f)
            boltPath.lineTo(cx - h * 0.02f, cy + h * 0.06f)
            boltPath.lineTo(cx - h * 0.12f, cy + h / 2)
            boltPath.lineTo(cx + h * 0.28f, cy - h * 0.06f)
            boltPath.lineTo(cx + h * 0.02f, cy - h * 0.06f)
            boltPath.close()
            canvas.drawPath(boltPath, bolt)
        }
    }
}
