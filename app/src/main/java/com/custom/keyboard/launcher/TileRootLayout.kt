package com.custom.keyboard.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.hypot

/**
 * Root of every tile. It detects long-press itself, even when the touch lands on content that
 * consumes touches (widgets, music buttons), and in customise mode it takes touches away from
 * that content so tiles can be selected and dragged, except for the edit buttons (unpin,
 * resize, more), which must keep receiving their taps.
 */
@SuppressLint("ViewConstructor")
class TileRootLayout(context: Context, private val interceptAll: () -> Boolean) : FrameLayout(context) {
    var onLongPress: (() -> Unit)? = null
    /** Views that keep their own touches in customise mode. */
    var touchTargets: () -> List<View> = { emptyList() }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val hitPadding = (6 * resources.displayMetrics.density).toInt()
    private val hitRect = Rect()
    private var downX = 0f
    private var downY = 0f
    private var downOnTarget = false
    private var longPressed = false
    private val longPressRunnable = Runnable {
        longPressed = true
        onLongPress?.invoke()
    }

    private fun hitsTarget(x: Float, y: Float): Boolean = touchTargets().any { v ->
        if (v.visibility != View.VISIBLE) return@any false
        v.getHitRect(hitRect)
        // Targets live in the tile's motion frame, which sits at 0,0 in this layout.
        val parent = v.parent as? View
        if (parent != null && parent !== this) hitRect.offset(parent.left, parent.top)
        hitRect.inset(-hitPadding, -hitPadding)
        hitRect.contains(x.toInt(), y.toInt())
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressed = false
                downX = ev.x
                downY = ev.y
                downOnTarget = hitsTarget(ev.x, ev.y)
                if (!downOnTarget) postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
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

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = (interceptAll() && !downOnTarget) || longPressed

    override fun performLongClick(): Boolean {
        val handler = onLongPress ?: return super.performLongClick()
        handler()
        return true
    }
}
