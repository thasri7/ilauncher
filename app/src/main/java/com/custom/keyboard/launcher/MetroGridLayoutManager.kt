package com.custom.keyboard.launcher

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView LayoutManager for the Start screen. Placement comes from [MetroGridPacker]; every
 * frame is computed up front (Start screens hold tens of tiles, not thousands) and only the
 * tiles near the viewport are attached, so recycling still works as usual.
 */
class MetroGridLayoutManager(
    private val specOf: (position: Int) -> MetroGridPacker.Spec
) : RecyclerView.LayoutManager() {

    var columns = 6
        set(value) {
            val even = (value / 2 * 2).coerceIn(4, 12)
            if (field != even) {
                field = even
                requestLayout()
            }
        }

    var gutterPx = 0
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    private var frames: List<Rect> = emptyList()
    private var contentHeight = 0
    private var scrollY = 0
    private var pendingScrollPosition = RecyclerView.NO_POSITION

    /** Distance between neighbouring cell origins, gutter included. */
    val cellPitch: Float
        get() = if (width == 0) 0f else (width - paddingLeft - paddingRight + gutterPx).toFloat() / columns

    override fun generateDefaultLayoutParams(): RecyclerView.LayoutParams =
        RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    override fun canScrollVertically(): Boolean = true

    override fun supportsPredictiveItemAnimations(): Boolean = false

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        if (state.itemCount == 0) {
            removeAndRecycleAllViews(recycler)
            frames = emptyList()
            contentHeight = 0
            scrollY = 0
            return
        }
        frames = computeFrames(state.itemCount)
        if (pendingScrollPosition in frames.indices) {
            scrollY = frames[pendingScrollPosition].top - paddingTop
        }
        pendingScrollPosition = RecyclerView.NO_POSITION
        scrollY = scrollY.coerceIn(0, maxScroll())
        detachAndScrapAttachedViews(recycler)
        fill(recycler)
    }

    private fun computeFrames(count: Int): List<Rect> {
        val packed = MetroGridPacker.pack(
            specs = List(count) { specOf(it) },
            columns = columns,
            pitch = cellPitch,
            gutter = gutterPx,
            originX = paddingLeft,
            originY = paddingTop,
            fullWidthRight = width - paddingRight
        )
        contentHeight = packed.contentBottom + paddingBottom
        return packed.frames.map { Rect(it.left, it.top, it.right, it.bottom) }
    }

    private fun maxScroll(): Int = (contentHeight - height).coerceAtLeast(0)

    /** Recycles tiles that left the viewport and attaches the ones that entered it. */
    private fun fill(recycler: RecyclerView.Recycler) {
        val margin = height / 2
        val visibleTop = scrollY - margin
        val visibleBottom = scrollY + height + margin

        for (i in childCount - 1 downTo 0) {
            val child = getChildAt(i) ?: continue
            val frame = frames.getOrNull(getPosition(child))
            if (frame == null || frame.bottom < visibleTop || frame.top > visibleBottom) {
                removeAndRecycleView(child, recycler)
            }
        }

        val attached = HashSet<Int>()
        for (i in 0 until childCount) getChildAt(i)?.let { attached.add(getPosition(it)) }

        val count = minOf(frames.size, itemCount)
        for (position in 0 until count) {
            val frame = frames[position]
            if (frame.bottom < visibleTop || frame.top > visibleBottom || position in attached) continue
            val view = recycler.getViewForPosition(position)
            addView(view)
            view.measure(
                View.MeasureSpec.makeMeasureSpec(frame.width(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(frame.height(), View.MeasureSpec.EXACTLY)
            )
            layoutDecorated(view, frame.left, frame.top - scrollY, frame.right, frame.bottom - scrollY)
        }
    }

    override fun scrollVerticallyBy(dy: Int, recycler: RecyclerView.Recycler, state: RecyclerView.State): Int {
        if (frames.isEmpty()) return 0
        val target = (scrollY + dy).coerceIn(0, maxScroll())
        val consumed = target - scrollY
        if (consumed == 0) return 0
        scrollY = target
        offsetChildrenVertical(-consumed)
        fill(recycler)
        return consumed
    }

    override fun scrollToPosition(position: Int) {
        pendingScrollPosition = position
        requestLayout()
    }

    override fun smoothScrollToPosition(recyclerView: RecyclerView, state: RecyclerView.State, position: Int) {
        val frame = frames.getOrNull(position) ?: return
        recyclerView.smoothScrollBy(0, (frame.top - paddingTop) - scrollY)
    }

    override fun computeVerticalScrollOffset(state: RecyclerView.State): Int = scrollY

    override fun computeVerticalScrollRange(state: RecyclerView.State): Int = contentHeight

    override fun computeVerticalScrollExtent(state: RecyclerView.State): Int = height
}
