package com.custom.keyboard.models

import org.junit.Assert.assertEquals
import org.junit.Test

class TileSizeTest {
    @Test
    fun resizeCycleMatchesWindows10Mobile() {
        var size = TileSize.MEDIUM
        val seen = (1..4).map { size = size.nextInCycle(); size }
        assertEquals(listOf(TileSize.SMALL, TileSize.WIDE, TileSize.LARGE, TileSize.MEDIUM), seen)
    }

    @Test
    fun oldTwoColumnLayoutsMigrateToRealSizes() {
        assertEquals(TileSize.MEDIUM, TileSize.fromLegacySpans(1, 1))
        assertEquals(TileSize.MEDIUM, TileSize.fromLegacySpans(1, 2))
        assertEquals(TileSize.WIDE, TileSize.fromLegacySpans(2, 1))
        assertEquals(TileSize.LARGE, TileSize.fromLegacySpans(2, 2))
    }

    @Test
    fun everySizeButSmallIsWholeBlocks() {
        TileSize.entries.filter { it != TileSize.SMALL }.forEach {
            assertEquals(0, it.cols % 2)
            assertEquals(0, it.rows % 2)
        }
    }
}
