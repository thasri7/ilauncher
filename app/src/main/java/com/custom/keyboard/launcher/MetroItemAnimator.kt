package com.custom.keyboard.launcher

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
class MetroItemAnimator(private val motionView: (RecyclerView.ViewHolder) -> View?) : DefaultItemAnimator() {

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
        val oldW = preInfo.right - preInfo.left
        val oldH = preInfo.bottom - preInfo.top
        val newW = postInfo.right - postInfo.left
        val newH = postInfo.bottom - postInfo.top
        dispatchChangeFinished(newHolder, true)
        if (newW <= 0 || newH <= 0) return false
        val restScale = view.scaleX.takeIf { it > 0f } ?: 1f
        view.animate().cancel()
        if (oldW == newW && oldH == newH) {
            // Same size (colour, name, live toggle): a quick pop.
            MetroMotion.centerPivot(view)
            view.scaleX = restScale * 0.9f
            view.scaleY = restScale * 0.9f
            view.animate().setStartDelay(0).scaleX(restScale).scaleY(restScale).setDuration(220)
                .setInterpolator(decelerate).withLayer().start()
            return false
        }
        // Resize: start drawn at the old bounds, then morph into the new ones.
        view.pivotX = 0f
        view.pivotY = 0f
        view.translationX = (preInfo.left - postInfo.left).toFloat()
        view.translationY = (preInfo.top - postInfo.top).toFloat()
        view.scaleX = restScale * oldW / newW
        view.scaleY = restScale * oldH / newH
        view.animate().setStartDelay(0)
            .translationX(0f).translationY(0f).scaleX(restScale).scaleY(restScale)
            .setDuration(300).setInterpolator(decelerate).withLayer()
            .withEndAction { MetroMotion.centerPivot(view) }
            .start()
        return false
    }
}
