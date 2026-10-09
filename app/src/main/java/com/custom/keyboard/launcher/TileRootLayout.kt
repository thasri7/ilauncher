package com.custom.keyboard.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.hypot

/**
 * Root of every tile. It detects long-press itself, even when the touch lands on content that
 * consumes touches (widgets, music buttons), and in customise mode it takes every touch away
 * from that content so tiles can be selected and dragged.
 */
@SuppressLint("ViewConstructor")
class TileRootLayout(context: Context, private val interceptAll: () -> Boolean) : FrameLayout(context) {
    var onLongPress: (() -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var longPressed = false
    private val longPressRunnable = Runnable {
        longPressed = true
        onLongPress?.invoke()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressed = false
                downX = ev.x
                downY = ev.y
                postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (hypot(ev.x - downX, ev.y - downY) > slop) removeCallbacks(longPressRunnable)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                // A long-press must not also count as a tap.
                if (longPressed && ev.actionMasked == MotionEvent.ACTION_UP) ev.action = MotionEvent.ACTION_CANCEL
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = interceptAll() || longPressed

    override fun performLongClick(): Boolean {
        val handler = onLongPress ?: return super.performLongClick()
        handler()
        return true
    }
}
