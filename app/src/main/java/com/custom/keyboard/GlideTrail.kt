package com.custom.keyboard

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View

/**
 * The fading line drawn under your finger while swipe typing. It lives in the keys' overlay, so
 * it costs nothing when you aren't swiping, and fades out when the finger lifts.
 */
class GlideTrail {
    private var host: View? = null
    private val origin = IntArray(2)
    private val points = ArrayList<Float>()
    private var fade: ValueAnimator? = null
    private var visible = true

    private val drawable = object : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        override fun draw(canvas: Canvas) {
            if (points.size < 4) return
            val density = host?.resources?.displayMetrics?.density ?: 1f
            val count = points.size / 2
            // Thicker and brighter towards the finger.
            val segments = 6
            for (s in 0 until segments) {
                val from = count * s / segments
                val to = (count * (s + 1) / segments).coerceAtMost(count - 1)
                if (to <= from) continue
                path.reset()
                path.moveTo(points[from * 2], points[from * 2 + 1])
                for (i in from + 1..to) path.lineTo(points[i * 2], points[i * 2 + 1])
                val t = (s + 1f) / segments
                paint.strokeWidth = (2f + 4f * t) * density
                paint.color = android.graphics.Color.argb((alphaValue * (0.35f + 0.65f * t)).toInt(), 56, 189, 248)
                canvas.drawPath(path, paint)
            }
        }

        private var alphaValue = 255

        override fun getAlpha(): Int = alphaValue

        override fun setAlpha(alpha: Int) {
            alphaValue = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {}

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    fun start(view: View, rawX: Float, rawY: Float, show: Boolean) {
        visible = show
        fade?.cancel()
        points.clear()
        if (!show) return
        if (host !== view) {
            host?.overlay?.remove(drawable)
            host = view
            view.overlay.add(drawable)
        }
        drawable.setBounds(0, 0, view.width, view.height)
        drawable.alpha = 255
        view.getLocationOnScreen(origin)
        add(rawX, rawY)
    }

    fun add(rawX: Float, rawY: Float) {
        if (!visible || host == null) return
        points.add(rawX - origin[0])
        points.add(rawY - origin[1])
        // Only the recent part of the stroke stays drawn.
        if (points.size > 160) points.subList(0, points.size - 160).clear()
        drawable.invalidateSelf()
        host?.invalidate()
    }

    fun finish() {
        if (!visible || points.isEmpty()) return
        fade?.cancel()
        fade = ValueAnimator.ofInt(255, 0).apply {
            duration = 220
            addUpdateListener {
                drawable.alpha = it.animatedValue as Int
                host?.invalidate()
            }
            doOnEnd {
                points.clear()
                host?.invalidate()
            }
            start()
        }
    }

    private fun ValueAnimator.doOnEnd(block: () -> Unit) = addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) = block()
    })
}
