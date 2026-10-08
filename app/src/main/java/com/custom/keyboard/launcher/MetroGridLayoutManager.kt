package com.custom.keyboard.launcher

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

/**
 * Lays tiles out the way the Windows 10 Mobile Start screen does. The grid is made of 2×2-cell
 * blocks: medium, wide and large tiles take whole blocks (first free spot, row by row), while
 * small tiles share a block four at a time. A full-width row such as a group header closes the
 * current group and starts a new one underneath, like named tile groups on the phone.
 *
 * Every frame is computed up front (Start screens hold tens of tiles, not thousands), and only
 * the tiles near the viewport are attached, so recycling still works as usual.
 */
class MetroGridLayoutManager(
    private val specOf: (position: Int) -> Spec
) : RecyclerView.LayoutManager() {

    /** A tile's footprint in cells, or a fixed pixel height for full-width rows. */
    data class Spec(val cols: Int, val rows: Int, val fullWidthHeightPx: Int = 0)

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
        val pitch = cellPitch
        val blockCols = columns / 2
        val result = ArrayList<Rect>(count)
        var groupTop = paddingTop.toFloat()
        // Occupied 2×2 blocks of the current group, one BooleanArray per block row.
        val blocks = ArrayList<BooleanArray>()
        // [blockRow, blockCol, nextQuadrant] of the block that small tiles are currently filling.
        var smallBlock: IntArray? = null

        fun isFree(row: Int, col: Int, w: Int, h: Int): Boolean {
            for (r in row until row + h) {
                val line = blocks.getOrNull(r) ?: continue
                for (c in col until col + w) if (line[c]) return false
            }
            return true
        }

        fun occupy(row: Int, col: Int, w: Int, h: Int) {
            while (blocks.size < row + h) blocks.add(BooleanArray(blockCols))
            for (r in row until row + h) for (c in col until col + w) blocks[r][c] = true
        }

        fun firstFit(w: Int, h: Int): Pair<Int, Int> {
            var row = 0
            while (true) {
                for (col in 0..blockCols - w) if (isFree(row, col, w, h)) return row to col
                row++
            }
        }

        fun cellRect(cellRow: Int, cellCol: Int, cols: Int, rows: Int): Rect {
            val left = paddingLeft + cellCol * pitch
            val top = groupTop + cellRow * pitch
            return Rect(
                left.roundToInt(),
                top.roundToInt(),
                (left + cols * pitch - gutterPx).roundToInt(),
                (top + rows * pitch - gutterPx).roundToInt()
            )
        }

        for (position in 0 until count) {
            val spec = specOf(position)
            if (spec.fullWidthHeightPx > 0) {
                val top = groupTop + blocks.size * 2 * pitch
                result.add(Rect(paddingLeft, top.roundToInt(), width - paddingRight, (top + spec.fullWidthHeightPx).roundToInt()))
                groupTop = top + spec.fullWidthHeightPx
                blocks.clear()
                smallBlock = null
                continue
            }
            if (spec.cols <= 1 && spec.rows <= 1) {
                val block = smallBlock ?: firstFit(1, 1).let { (r, c) ->
                    occupy(r, c, 1, 1)
                    intArrayOf(r, c, 0)
                }
                val quadrant = block[2]
                result.add(cellRect(block[0] * 2 + quadrant / 2, block[1] * 2 + quadrant % 2, 1, 1))
                block[2] = quadrant + 1
                smallBlock = if (block[2] >= 4) null else block
                continue
            }
            val w = ((spec.cols + 1) / 2).coerceIn(1, blockCols)
            val h = ((spec.rows + 1) / 2).coerceAtLeast(1)
            val (r, c) = firstFit(w, h)
            occupy(r, c, w, h)
            result.add(cellRect(r * 2, c * 2, w * 2, h * 2))
        }
        contentHeight = (groupTop + blocks.size * 2 * pitch).roundToInt() + paddingBottom
        return result
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
