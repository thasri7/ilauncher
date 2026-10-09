package com.custom.keyboard.launcher

import kotlin.math.roundToInt

/**
 * Start-screen packing, free of Android types so it can be unit-tested.
 *
 * Tiles are any whole number of cells (1×1 to 4×4). Each goes in the first free spot that fits,
 * scanning row by row, so later tiles fill holes left by earlier ones. A full-width row such as a
 * group header or an open folder closes the current group and starts a new one under it.
 */
object MetroGridPacker {

    /**
     * A tile's footprint in cells, or a fixed pixel height for full-width rows. [hidden] tiles
     * (in a folded group) take no room and get an empty frame where the group ends.
     */
    data class Spec(val cols: Int, val rows: Int, val fullWidthHeightPx: Int = 0, val hidden: Boolean = false)

    data class Frame(val left: Int, val top: Int, val right: Int, val bottom: Int)

    class Result(val frames: List<Frame>, val contentBottom: Int)

    /**
     * @param columns number of cell columns
     * @param pitch distance between neighbouring cell origins, gutter included
     * @param originX/originY top-left of the grid; [fullWidthRight] is where full-width rows end
     */
    fun pack(specs: List<Spec>, columns: Int, pitch: Float, gutter: Int, originX: Int, originY: Int, fullWidthRight: Int): Result {
        val cols = columns.coerceAtLeast(1)
        val result = ArrayList<Frame>(specs.size)
        var groupTop = originY.toFloat()
        // Occupied cells of the current group, one BooleanArray per cell row.
        val cells = ArrayList<BooleanArray>()

        fun isFree(row: Int, col: Int, w: Int, h: Int): Boolean {
            for (r in row until row + h) {
                val line = cells.getOrNull(r) ?: continue
                for (c in col until col + w) if (line[c]) return false
            }
            return true
        }

        fun firstFit(w: Int, h: Int): Pair<Int, Int> {
            var row = 0
            while (true) {
                for (col in 0..cols - w) if (isFree(row, col, w, h)) return row to col
                row++
            }
        }

        for (spec in specs) {
            if (spec.hidden) {
                val top = (groupTop + cells.size * pitch).roundToInt()
                result.add(Frame(originX, top, originX, top))
                continue
            }
            if (spec.fullWidthHeightPx > 0) {
                val top = groupTop + cells.size * pitch
                result.add(Frame(originX, top.roundToInt(), fullWidthRight, (top + spec.fullWidthHeightPx).roundToInt()))
                groupTop = top + spec.fullWidthHeightPx
                cells.clear()
                continue
            }
            val w = spec.cols.coerceIn(1, cols)
            val h = spec.rows.coerceAtLeast(1)
            val (r, c) = firstFit(w, h)
            while (cells.size < r + h) cells.add(BooleanArray(cols))
            for (y in r until r + h) for (x in c until c + w) cells[y][x] = true
            val left = originX + c * pitch
            val top = groupTop + r * pitch
            result.add(
                Frame(
                    left.roundToInt(),
                    top.roundToInt(),
                    (left + w * pitch - gutter).roundToInt(),
                    (top + h * pitch - gutter).roundToInt()
                )
            )
        }
        return Result(result, (groupTop + cells.size * pitch).roundToInt())
    }
}
