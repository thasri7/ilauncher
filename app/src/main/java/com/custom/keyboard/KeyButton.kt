package com.custom.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.widget.Button

/**
 * A letter key: the letter is ordinary one-line text, centred, and the long-press symbol is
 * drawn small at the top centre. Drawing the symbol separately means the letter can never be
 * pushed down or clipped, whatever the key height.
 */
@SuppressLint("ViewConstructor", "AppCompatCustomView")
class KeyButton(context: Context, hintColor: Int) : Button(context) {
    var symbol: String? = null
        set(value) {
            field = value
            updatePadding()
            invalidate()
        }

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hintColor
        textAlign = Paint.Align.CENTER
    }

    override fun setTextSize(unit: Int, size: Float) {
        super.setTextSize(unit, size)
        updatePadding()
    }

    private fun hintSize() = textSize * 0.42f

    /** Nudges the letter down a little to make room for the symbol, keeping it near the middle. */
    private fun updatePadding() {
        // Called by Button's own constructor before this class has set up its fields.
        @Suppress("SENSELESS_COMPARISON")
        if (hintPaint == null) return
        val top = if (symbol.isNullOrEmpty()) 0 else (hintSize() * 0.7f).toInt()
        if (paddingTop != top) setPadding(0, top, 0, 0)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = symbol ?: return
        hintPaint.textSize = hintSize()
        hintPaint.typeface = typeface
        // Baseline just below the top edge of the key, centred.
        val y = hintSize() * 1.15f + height * 0.04f
        canvas.drawText(h, width / 2f, y, hintPaint)
    }
}
