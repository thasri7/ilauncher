package com.custom.keyboard.launcher

import com.custom.keyboard.launcher.MetroGridPacker.Spec
import org.junit.Assert.assertEquals
import org.junit.Test

class MetroGridPackerTest {
    private val small = Spec(1, 1)
    private val medium = Spec(2, 2)
    private val wide = Spec(4, 2)
    private val large = Spec(4, 4)

    /** Packs on a grid with a 10px pitch and no gutter, and draws it as text, one letter per tile. */
    private fun draw(columns: Int, vararg specs: Spec): List<String> {
        val result = MetroGridPacker.pack(specs.toList(), columns, pitch = 10f, gutter = 0, originX = 0, originY = 0, fullWidthRight = columns * 10)
        val rows = (result.contentBottom + 9) / 10
        val grid = Array(rows) { CharArray(columns) { '.' } }
        result.frames.forEachIndexed { i, f ->
            val ch = if (specs[i].fullWidthHeightPx > 0) '=' else 'A' + i
            for (y in f.top / 10 until (f.bottom + 9) / 10) for (x in f.left / 10 until f.right / 10) {
                check(grid[y][x] == '.') { "tiles overlap at $x,$y" }
                grid[y][x] = ch
            }
        }
        return grid.map { String(it) }
    }

    @Test
    fun smallTilesFillRowsLeftToRight() {
        assertEquals(listOf("ABCD", "EE..", "EE.."), draw(4, small, small, small, small, medium))
    }

    @Test
    fun anyWholeCellSizePacks() {
        // 3×2, then 1×2 beside it, then 2×3 underneath.
        assertEquals(listOf("AAAB", "AAAB", "CC..", "CC..", "CC.."), draw(4, Spec(3, 2), Spec(1, 2), Spec(2, 3)))
    }

    @Test
    fun wideNextToMediumOnSixColumns() {
        assertEquals(listOf("AAAABB", "AAAABB"), draw(6, wide, medium))
    }

    @Test
    fun laterMediumBackFillsTheGapLeftByAWideTile() {
        // The wide tile doesn't fit beside the first medium, so the next medium fills that hole.
        assertEquals(listOf("AACC", "AACC", "BBBB", "BBBB"), draw(4, medium, wide, medium))
    }

    @Test
    fun smallTilesFillHolesAroundABiggerTile() {
        assertEquals(listOf("ABCDDE", "FGHDDI"), draw(6, small, small, small, medium, small, small, small, small, small))
    }

    @Test
    fun headerStartsANewGroupBelowEverything() {
        assertEquals(
            listOf("AABB..", "AABB..", "======", "DDDD..", "DDDD..", "DDDD..", "DDDD.."),
            draw(6, medium, medium, Spec(0, 0, 10), large)
        )
    }

    @Test
    fun gutterShrinksTilesButKeepsThePitch() {
        val r = MetroGridPacker.pack(listOf(medium, medium), 4, pitch = 25f, gutter = 4, originX = 8, originY = 2, fullWidthRight = 108)
        assertEquals(MetroGridPacker.Frame(8, 2, 54, 48), r.frames[0])
        assertEquals(MetroGridPacker.Frame(58, 2, 104, 48), r.frames[1])
        assertEquals(52, r.contentBottom)
    }
}
