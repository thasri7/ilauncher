package com.custom.keyboard.launcher

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * "Picture in tiles": one picture fixed to the screen that only shows through the tiles, as on
 * Windows 10 Mobile. [screenOffset] reports where the tile sits on screen so each tile shows its
 * own slice of the picture; as Start scrolls the tiles slide across the picture.
 */
class StartBackdrop(private val picture: Bitmap, screenWidth: Int, screenHeight: Int) {
    // Centre-crop the picture to the screen once; tiles only translate it.
    private val baseMatrix = Matrix().apply {
        val scale = maxOf(screenWidth / picture.width.toFloat(), screenHeight / picture.height.toFloat())
        setScale(scale, scale)
        postTranslate((screenWidth - picture.width * scale) / 2f, (screenHeight - picture.height * scale) / 2f)
    }

    fun tileDrawable(tint: Int, cornerRadius: Float, screenOffset: () -> Pair<Float, Float>): Drawable =
        WindowDrawable(tint, cornerRadius, screenOffset)

    private inner class WindowDrawable(
        private val tint: Int,
        private val radius: Float,
        private val screenOffset: () -> Pair<Float, Float>
    ) : Drawable() {
        private val shader = BitmapShader(picture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        private val picturePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { shader = this@WindowDrawable.shader }
        private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint }
        private val local = Matrix()
        private val rect = RectF()

        override fun draw(canvas: Canvas) {
            val (x, y) = screenOffset()
            // Canvas point p is screen point p + (x, y), so shift the screen-mapped picture back.
            local.set(baseMatrix)
            local.postTranslate(-x, -y)
            shader.setLocalMatrix(local)
            rect.set(bounds)
            canvas.drawRoundRect(rect, radius, radius, picturePaint)
            canvas.drawRoundRect(rect, radius, radius, tintPaint)
        }

        override fun getOutline(outline: Outline) {
            outline.setRoundRect(bounds, radius)
        }

        override fun setAlpha(alpha: Int) {
            picturePaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            picturePaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
