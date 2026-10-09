package com.custom.keyboard.launcher

import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * The Windows Phone / Windows 10 Mobile motion vocabulary: tilt, turnstile, live-tile flip and
 * peek, and the swivel used by menus.
 *
 * Sign convention (Android's camera): +rotationY pushes a view's right edge into the screen and
 * +rotationX pushes its bottom edge into the screen.
 */
object MetroMotion {
    private val decelerate = DecelerateInterpolator(2f)
    private val accelerate = AccelerateInterpolator(1.6f)

    fun centerPivot(v: View) {
        v.pivotX = v.width / 2f
        v.pivotY = v.height / 2f
    }

    // ── Tilt ─────────────────────────────────────────────────────────────────────────────

    /** Press feedback: the touched edge sinks into the screen; a centre press pushes the whole tile in. */
    fun tiltTo(v: View, x: Float, y: Float) {
        if (v.width == 0 || v.height == 0) return
        val density = v.resources.displayMetrics.density
        val nx = (x / v.width - 0.5f).coerceIn(-0.5f, 0.5f) * 2f
        val ny = (y / v.height - 0.5f).coerceIn(-0.5f, 0.5f) * 2f
        // Bigger tiles tilt less, otherwise a large tile swings like a door.
        val maxAngle = (10f * (110f * density / maxOf(v.width, v.height))).coerceIn(3f, 10f)
        val edge = maxOf(abs(nx), abs(ny))
        val scale = 0.985f - 0.045f * (1f - edge)
        v.cameraDistance = 6000f * density
        centerPivot(v)
        v.animate().setStartDelay(0)
            .rotationY(nx * maxAngle)
            .rotationX(ny * maxAngle)
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(80)
            .setInterpolator(decelerate)
            .start()
    }

    fun releaseTilt(v: View, restScale: Float = 1f) {
        v.animate().setStartDelay(0)
            .rotationX(0f)
            .rotationY(0f)
            .scaleX(restScale)
            .scaleY(restScale)
            .setDuration(200)
            .setInterpolator(OvershootInterpolator(1.4f))
            .start()
    }

    // ── Turnstile ────────────────────────────────────────────────────────────────────────

    private const val TURNSTILE_ANGLE = 75f

    /**
     * Turnstile functions take [motionView] to map a RecyclerView child to the view that should
     * move. RecyclerView's item animator cancels animations on the child itself whenever an item
     * changes, so tiles animate an inner frame instead.
     */
    private fun onScreenChildren(rv: RecyclerView): List<View> =
        (0 until rv.childCount).mapNotNull { rv.getChildAt(it) }
            .filter { it.bottom > 0 && it.top < rv.height }
            .sortedWith(compareBy({ it.top }, { it.left }))

    /** All tiles share one hinge on the left edge of the screen, which is what sells the effect. */
    private fun hingeOnScreenEdge(rv: RecyclerView, child: View, v: View) {
        v.cameraDistance = 9000f * v.resources.displayMetrics.density
        v.pivotX = -child.left.toFloat()
        v.pivotY = rv.height / 2f - child.top
    }

    /**
     * Swings every tile except [keep] away around the screen edge, top-left first, then runs
     * [onEnd]. [keep] stays put so the system can zoom the app open out of it.
     */
    fun turnstileOut(rv: RecyclerView, keep: View?, motionView: (View) -> View, onEnd: () -> Unit) {
        val tiles = onScreenChildren(rv).filter { it !== keep }
        if (tiles.isEmpty()) {
            onEnd()
            return
        }
        val stagger = minOf(22L, 160L / tiles.size)
        tiles.forEachIndexed { i, child ->
            val v = motionView(child)
            hingeOnScreenEdge(rv, child, v)
            v.animate()
                .rotationY(TURNSTILE_ANGLE)
                .alpha(0f)
                .withLayer()
                .setStartDelay(i * stagger)
                .setDuration(190)
                .setInterpolator(accelerate)
                .start()
        }
        rv.postDelayed({ onEnd() }, (tiles.size - 1) * stagger + 150)
    }

    /** The reverse of [turnstileOut]: tiles swing back in from behind the screen edge. */
    fun turnstileIn(rv: RecyclerView, motionView: (View) -> View) {
        val tiles = onScreenChildren(rv)
        val stagger = if (tiles.isEmpty()) 0L else minOf(24L, 220L / tiles.size)
        tiles.forEachIndexed { i, child ->
            val v = motionView(child)
            v.animate().cancel()
            hingeOnScreenEdge(rv, child, v)
            v.rotationY = TURNSTILE_ANGLE
            v.rotationX = 0f
            v.alpha = 0f
            v.animate()
                .rotationY(0f)
                .alpha(1f)
                .withLayer()
                .setStartDelay(i * stagger)
                .setDuration(340)
                .setInterpolator(decelerate)
                .withEndAction { centerPivot(v) }
                .start()
        }
    }

    /** Puts every tile back to rest, e.g. when a launch failed half-way through a turnstile. */
    fun resetTiles(rv: RecyclerView, motionView: (View) -> View) {
        for (i in 0 until rv.childCount) {
            val v = rv.getChildAt(i)?.let(motionView) ?: continue
            v.animate().cancel()
            v.rotationX = 0f
            v.rotationY = 0f
            v.alpha = 1f
            centerPivot(v)
        }
    }

    // ── Live tiles ───────────────────────────────────────────────────────────────────────

    /** Classic live-tile flip around the horizontal axis. */
    fun flip(surface: View, front: View, back: View, toBack: Boolean) {
        surface.cameraDistance = 7000f * surface.resources.displayMetrics.density
        centerPivot(surface)
        surface.animate()
            .rotationX(90f)
            .withLayer()
            .setStartDelay(0)
            .setDuration(210)
            .setInterpolator(accelerate)
            .withEndAction {
                showFace(front, back, toBack)
                surface.rotationX = -90f
                surface.animate().setStartDelay(0)
                    .rotationX(0f)
                    .withLayer()
                    .setDuration(280)
                    .setInterpolator(decelerate)
                    .start()
            }
            .start()
    }

    /** Peek: the front slides up and out while the back slides in from below. */
    fun peek(front: View, back: View, toBack: Boolean) {
        val h = (front.parent as? View)?.height?.toFloat() ?: return
        front.visibility = View.VISIBLE
        back.visibility = View.VISIBLE
        if (toBack) {
            front.translationY = 0f
            back.translationY = h
            front.animate().setStartDelay(0).translationY(-h).withLayer().setDuration(520).setInterpolator(decelerate).start()
            back.animate().setStartDelay(0).translationY(0f).withLayer().setDuration(520).setInterpolator(decelerate)
                .withEndAction { front.visibility = View.INVISIBLE }
                .start()
        } else {
            front.translationY = -h
            back.translationY = 0f
            front.animate().setStartDelay(0).translationY(0f).withLayer().setDuration(520).setInterpolator(decelerate).start()
            back.animate().setStartDelay(0).translationY(h).withLayer().setDuration(520).setInterpolator(decelerate)
                .withEndAction { back.visibility = View.INVISIBLE }
                .start()
        }
    }

    fun showFace(front: View, back: View, showBack: Boolean) {
        front.animate().cancel()
        back.animate().cancel()
        front.translationY = 0f
        back.translationY = 0f
        front.visibility = if (showBack) View.INVISIBLE else View.VISIBLE
        back.visibility = if (showBack) View.VISIBLE else View.INVISIBLE
    }

    /** A quick "pop" used after a tile is resized. */
    fun pop(v: View) {
        centerPivot(v)
        v.scaleX = 0.88f
        v.scaleY = 0.88f
        v.animate().setStartDelay(0).scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(2f)).start()
    }

    // ── Menus & panels ───────────────────────────────────────────────────────────────────

    /** Menus swivel in around their top (or bottom) edge, like W10M flyouts. */
    fun swivelIn(v: View, hingeTop: Boolean) {
        v.cameraDistance = 8000f * v.resources.displayMetrics.density
        v.pivotX = v.width / 2f
        v.pivotY = if (hingeTop) 0f else v.height.toFloat()
        v.rotationX = if (hingeTop) 65f else -65f
        v.alpha = 0f
        v.animate().setStartDelay(0).rotationX(0f).alpha(1f).setDuration(260).setInterpolator(decelerate).start()
    }

    fun swivelOut(v: View, hingeTop: Boolean, onEnd: () -> Unit) {
        v.pivotY = if (hingeTop) 0f else v.height.toFloat()
        v.animate().setStartDelay(0)
            .rotationX(if (hingeTop) 55f else -55f)
            .alpha(0f)
            .setDuration(150)
            .setInterpolator(accelerate)
            .withEndAction(onEnd)
            .start()
    }

    /** Rows of a freshly opened panel slide in one after another. */
    fun cascadeIn(views: List<View>, fromDx: Float) {
        views.forEachIndexed { i, v ->
            v.translationX = fromDx
            v.alpha = 0f
            v.animate()
                .translationX(0f)
                .alpha(1f)
                .setStartDelay(40L + i * 18L)
                .setDuration(320)
                .setInterpolator(decelerate)
                .start()
        }
    }
}
