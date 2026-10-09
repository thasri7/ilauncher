package com.custom.keyboard.launcher

import android.graphics.Rect
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.ItemAnimator.ItemHolderInfo

/**
 * Item animator for Start. On top of the default moves/fades it animates restyled tiles: a
 * resized tile morphs smoothly from its old bounds to the new ones, and a recoloured tile pops.
 * Animation runs on the tile's inner motion frame, which RecyclerView never touches.
 */
class MetroItemAnimator(
    private val motionView: (RecyclerView.ViewHolder) -> View?,
    /** Where a drag-resize left the tile, if this change came from one. */
    private val dragHint: (RecyclerView.ViewHolder) -> Rect? = { null },
    /** The scale and alpha a tile rests at (smaller and dimmer in customise mode). */
    private val rest: (RecyclerView.ViewHolder) -> Pair<Float, Float> = { 1f to 1f }
) : DefaultItemAnimator() {

    private class Info : ItemHolderInfo() {
        var restyle = false
    }

    private val decelerate = DecelerateInterpolator(2.2f)

    override fun obtainHolderInfo(): ItemHolderInfo = Info()

    override fun recordPreLayoutInformation(
        state: RecyclerView.State,
        viewHolder: RecyclerView.ViewHolder,
        changeFlags: Int,
        payloads: MutableList<Any>
    ): ItemHolderInfo {
        val info = super.recordPreLayoutInformation(state, viewHolder, changeFlags, payloads)
        (info as? Info)?.restyle = MetroTileAdapter.PAYLOAD_RESTYLE in payloads
        return info
    }

    override fun animateChange(
        oldHolder: RecyclerView.ViewHolder,
        newHolder: RecyclerView.ViewHolder,
        preInfo: ItemHolderInfo,
        postInfo: ItemHolderInfo
    ): Boolean {
        val view = motionView(newHolder)
        if (oldHolder !== newHolder || (preInfo as? Info)?.restyle != true || view == null) {
            return super.animateChange(oldHolder, newHolder, preInfo, postInfo)
        }
        // After a drag-resize, morph from the stretched shape the finger left, not the old size.
        val from = dragHint(newHolder) ?: Rect(preInfo.left, preInfo.top, preInfo.right, preInfo.bottom)
        val oldW = from.width()
        val oldH = from.height()
        val newW = postInfo.right - postInfo.left
        val newH = postInfo.bottom - postInfo.top
        dispatchChangeFinished(newHolder, true)
        if (newW <= 0 || newH <= 0) return false
        // Never read the rest state from the view: another animation (the turnstile when coming
        // back from an app) may have it half-way, e.g. fully transparent.
        val (restScale, restAlpha) = rest(newHolder)
        view.animate().cancel()
        view.rotationX = 0f
        view.rotationY = 0f
        if (oldW == newW && oldH == newH) {
            // Same size (colour, name, live toggle): a quick pop.
            MetroMotion.centerPivot(view)
            view.scaleX = restScale * 0.9f
            view.scaleY = restScale * 0.9f
            view.alpha = restAlpha
            view.animate().setStartDelay(0).scaleX(restScale).scaleY(restScale).alpha(restAlpha).setDuration(220)
                .setInterpolator(decelerate).withLayer().start()
            return false
        }
        // Resize: start drawn at the old bounds, then morph into the new ones.
        view.pivotX = 0f
        view.pivotY = 0f
        view.translationX = (from.left - postInfo.left).toFloat()
        view.translationY = (from.top - postInfo.top).toFloat()
        view.scaleX = restScale * oldW / newW
        view.scaleY = restScale * oldH / newH
        view.animate().setStartDelay(0)
            .translationX(0f).translationY(0f).scaleX(restScale).scaleY(restScale).alpha(restAlpha)
            .setDuration(300).setInterpolator(decelerate).withLayer()
            .withEndAction { MetroMotion.centerPivot(view) }
            .start()
        return false
    }
}
