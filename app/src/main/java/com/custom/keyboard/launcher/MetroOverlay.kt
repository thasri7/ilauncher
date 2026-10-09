package com.custom.keyboard.launcher

import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout

/**
 * Hosts every transient surface of the launcher (context menus, sheets, full-screen panels and
 * prompts) above the Start screen, with a scrim, Metro entrance motion and back-to-dismiss.
 * Only one surface is shown at a time; showing a new one replaces the current one.
 */
class MetroOverlay(private val host: FrameLayout, private val insets: () -> Rect) {

    enum class Style {
        /** Flyout next to an anchor view, swivels in. */
        POPUP,
        /** Full-width sheet that slides up from the bottom. */
        SHEET,
        /** Full-screen page that slides in from the right. */
        PANEL,
        /** Centred card near the top, clear of the keyboard. */
        DIALOG
    }

    private var layer: FrameLayout? = null
    private var card: View? = null
    private var style = Style.SHEET
    private var hingeTop = true
    private var onDismissed: (() -> Unit)? = null
    /** Lets a surface handle Back itself (e.g. a settings sub-page going back to the list). */
    private var onBack: (() -> Boolean)? = null

    val isShowing: Boolean get() = layer != null

    private val density = host.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    fun show(
        content: View,
        style: Style,
        anchor: View? = null,
        animate: Boolean = true,
        onBack: (() -> Boolean)? = null,
        onDismissed: (() -> Unit)? = null
    ) {
        removeNow()
        this.style = style
        this.onDismissed = onDismissed
        this.onBack = onBack
        hingeTop = true
        val inset = insets()

        val scrim = FrameLayout(host.context).apply {
            setBackgroundColor(if (style == Style.PANEL) 0xF2101114.toInt() else 0x99000000.toInt())
            isClickable = true
            setOnClickListener { dismiss() }
        }
        content.isClickable = true

        val params = when (style) {
            Style.SHEET -> FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                topMargin = inset.top + dp(72)
                leftMargin = dp(8)
                rightMargin = dp(8)
                bottomMargin = dp(8)
            }
            Style.PANEL -> FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            Style.DIALOG -> FrameLayout.LayoutParams(minOf(host.width - dp(32), dp(380)), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = inset.top + dp(64)
            }
            Style.POPUP -> FrameLayout.LayoutParams(minOf(host.width - dp(24), dp(320)), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        when (style) {
            Style.SHEET -> content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, content.paddingBottom + inset.bottom)
            Style.PANEL -> content.setPadding(content.paddingLeft, content.paddingTop + inset.top, content.paddingRight, content.paddingBottom + inset.bottom)
            else -> Unit
        }
        scrim.addView(content, params)
        host.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        layer = scrim
        card = content

        // Hide until laid out so the entrance starts from the right place.
        content.alpha = 0f
        content.post {
            if (card !== content) return@post
            if (style == Style.POPUP) placeNear(content, anchor)
            if (!animate) {
                content.alpha = 1f
                return@post
            }
            scrim.alpha = 0f
            scrim.animate().alpha(1f).setDuration(160).start()
            enter(content)
        }
    }

    private fun placeNear(content: View, anchor: View?) {
        val hostLoc = IntArray(2).also { host.getLocationInWindow(it) }
        val inset = insets()
        val margin = dp(12)
        if (anchor == null) {
            content.x = (host.width - content.width) / 2f
            content.y = (host.height - content.height) / 2f
            return
        }
        val a = IntArray(2).also { anchor.getLocationInWindow(it) }
        val ax = a[0] - hostLoc[0]
        val ay = a[1] - hostLoc[1]
        val below = ay + anchor.height + dp(6)
        val above = ay - content.height - dp(6)
        hingeTop = below + content.height <= host.height - inset.bottom - margin || above < inset.top + margin
        val y = if (hingeTop) below.coerceAtMost(host.height - inset.bottom - margin - content.height) else above
        content.x = ax.toFloat().coerceIn(margin.toFloat(), (host.width - content.width - margin).toFloat().coerceAtLeast(margin.toFloat()))
        content.y = y.toFloat().coerceAtLeast((inset.top + margin).toFloat())
    }

    private fun enter(content: View) {
        val decelerate = DecelerateInterpolator(2.2f)
        when (style) {
            Style.POPUP -> MetroMotion.swivelIn(content, hingeTop)
            Style.SHEET -> {
                content.alpha = 1f
                content.translationY = content.height.toFloat()
                content.animate().translationY(0f).setStartDelay(0).setDuration(300).setInterpolator(decelerate).start()
            }
            Style.PANEL -> {
                content.translationX = content.width * 0.18f
                content.animate().translationX(0f).alpha(1f).setStartDelay(0).setDuration(320).setInterpolator(decelerate).start()
            }
            Style.DIALOG -> MetroMotion.swivelIn(content, hingeTop = true)
        }
    }

    /** Back pressed: the surface's own back step if it has one, otherwise dismiss. */
    fun handleBack(): Boolean {
        if (layer == null) return false
        if (onBack?.invoke() == true) return true
        return dismiss()
    }

    /** Animates the current surface out. Returns false when nothing was showing. */
    fun dismiss(): Boolean {
        val scrim = layer ?: return false
        val content = card ?: return false
        layer = null
        card = null
        onBack = null
        val callback = onDismissed
        onDismissed = null
        val finish: () -> Unit = {
            host.removeView(scrim)
            callback?.invoke()
        }
        scrim.animate().alpha(0f).setStartDelay(0).setDuration(170).start()
        val accelerate = AccelerateInterpolator(1.6f)
        when (style) {
            Style.POPUP, Style.DIALOG -> MetroMotion.swivelOut(content, hingeTop, finish)
            Style.SHEET -> content.animate().translationY(content.height.toFloat()).setStartDelay(0).setDuration(200)
                .setInterpolator(accelerate).withEndAction(finish).start()
            Style.PANEL -> content.animate().translationX(content.width * 0.15f).alpha(0f).setStartDelay(0).setDuration(180)
                .setInterpolator(accelerate).withEndAction(finish).start()
        }
        return true
    }

    private fun removeNow() {
        val scrim = layer ?: return
        scrim.animate().cancel()
        host.removeView(scrim)
        layer = null
        card = null
        onDismissed = null
        onBack = null
    }
}
