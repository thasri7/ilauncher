package com.custom.keyboard.launcher

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * A tile's own picture: drawn centre-cropped with the tile's corners, with a soft shade along
 * the bottom so the label stays readable on bright photos.
 */
class CoverDrawable(private val bitmap: Bitmap, private val radius: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val matrix = Matrix()

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        super.onBoundsChange(bounds)
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0 || h <= 0) return
        val scale = maxOf(w / bitmap.width, h / bitmap.height)
        matrix.reset()
        matrix.setScale(scale, scale)
        matrix.postTranslate(bounds.left + (w - bitmap.width * scale) / 2f, bounds.top + (h - bitmap.height * scale) / 2f)
        paint.shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
        shade.shader = LinearGradient(0f, bounds.top + h * 0.55f, 0f, bounds.bottom.toFloat(), 0x00000000, 0x80000000.toInt(), Shader.TileMode.CLAMP)
        rect.set(bounds)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRoundRect(rect, radius, radius, paint)
        canvas.drawRoundRect(rect, radius, radius, shade)
    }

    override fun getOutline(outline: android.graphics.Outline) {
        outline.setRoundRect(bounds, radius)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
