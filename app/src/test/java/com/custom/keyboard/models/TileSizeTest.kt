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
    fun autoGrowFollowsUsageButNeverShrinks() {
        assertEquals(TileSize.SMALL, TileSize.grownFor(TileSize.SMALL, 4))
        assertEquals(TileSize.MEDIUM, TileSize.grownFor(TileSize.SMALL, 5))
        assertEquals(TileSize.WIDE, TileSize.grownFor(TileSize.SMALL, 30))
        assertEquals(TileSize.WIDE, TileSize.grownFor(TileSize.MEDIUM, 30))
        assertEquals(TileSize.LARGE, TileSize.grownFor(TileSize.LARGE, 100))
        assertEquals(TileSize.WIDE, TileSize.grownFor(TileSize.WIDE, 0))
        // A tall 1×3 tile never gets squashed into 2×2.
        assertEquals(TileSize(1, 3), TileSize.grownFor(TileSize(1, 3), 10))
    }

    @Test
    fun everyWholeCellSizeFromOneToFour() {
        assertEquals(16, TileSize.entries.size)
        assertEquals(TileSize(3, 2), TileSize.valueOf("3x2"))
        assertEquals(TileSize.WIDE, TileSize.valueOf("WIDE"))
        assertEquals("1x4", TileSize(1, 4).name)
    }
}
