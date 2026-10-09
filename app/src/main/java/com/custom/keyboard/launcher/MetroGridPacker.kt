package com.custom.keyboard.launcher

import kotlin.math.roundToInt

/**
 * The Windows 10 Mobile Start packing rules, free of Android types so they can be unit-tested.
 *
 * The grid is made of 2×2-cell blocks: medium, wide and large tiles take whole blocks (first
 * free spot, row by row), while small tiles share a block four at a time. A full-width row such
 * as a group header or an open folder closes the current group and starts a new one under it.
 */
object MetroGridPacker {

    /** A tile's footprint in cells, or a fixed pixel height for full-width rows. */
    data class Spec(val cols: Int, val rows: Int, val fullWidthHeightPx: Int = 0)

    data class Frame(val left: Int, val top: Int, val right: Int, val bottom: Int)

    class Result(val frames: List<Frame>, val contentBottom: Int)

    /**
     * @param columns even number of cell columns
     * @param pitch distance between neighbouring cell origins, gutter included
     * @param originX/originY top-left of the grid; [fullWidthRight] is where full-width rows end
     */
    fun pack(specs: List<Spec>, columns: Int, pitch: Float, gutter: Int, originX: Int, originY: Int, fullWidthRight: Int): Result {
        val blockCols = (columns / 2).coerceAtLeast(1)
        val result = ArrayList<Frame>(specs.size)
        var groupTop = originY.toFloat()
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

        fun cellFrame(cellRow: Int, cellCol: Int, cols: Int, rows: Int): Frame {
            val left = originX + cellCol * pitch
            val top = groupTop + cellRow * pitch
            return Frame(
                left.roundToInt(),
                top.roundToInt(),
                (left + cols * pitch - gutter).roundToInt(),
                (top + rows * pitch - gutter).roundToInt()
            )
        }

        for (spec in specs) {
            if (spec.fullWidthHeightPx > 0) {
                val top = groupTop + blocks.size * 2 * pitch
                result.add(Frame(originX, top.roundToInt(), fullWidthRight, (top + spec.fullWidthHeightPx).roundToInt()))
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
                result.add(cellFrame(block[0] * 2 + quadrant / 2, block[1] * 2 + quadrant % 2, 1, 1))
                block[2] = quadrant + 1
                smallBlock = if (block[2] >= 4) null else block
                continue
            }
            val w = ((spec.cols + 1) / 2).coerceIn(1, blockCols)
            val h = ((spec.rows + 1) / 2).coerceAtLeast(1)
            val (r, c) = firstFit(w, h)
            occupy(r, c, w, h)
            result.add(cellFrame(r * 2, c * 2, w * 2, h * 2))
        }
        return Result(result, (groupTop + blocks.size * 2 * pitch).roundToInt())
    }
}
