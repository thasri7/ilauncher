package com.custom.keyboard.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class TextCloudTest {
    @Test
    fun weightHalvesEachHalfLife() {
        val day = 86_400_000L
        assertEquals(4.0, TextCloud.decay(8.0, 0, 10 * day, 10.0), 1e-9)
        assertEquals(8.0, TextCloud.decay(8.0, 5 * day, 0, 10.0), 1e-9)
    }

    @Test
    fun mostUsedAreBiggestUnusedSmallestBigTilesStayBig() {
        val apps = (1..20).map { "app$it" }
        val heat = apps.mapIndexed { i, p -> p to (20 - i).toDouble() }.toMap() - "app20"
        val levels = TextCloud.levels(apps, heat) { if (it == "app19") TextCloud.levelForTile(4, 4) else 0 }
        assertEquals(4, levels["app1"])
        assertEquals(1, levels["app18"])
        assertEquals(4, levels["app19"])
        assertEquals(0, levels["app20"])
    }

    @Test
    fun sizesSpreadEvenly() {
        assertEquals(14f, TextCloud.sizeSp(0, 14, 38), 0.01f)
        assertEquals(26f, TextCloud.sizeSp(2, 14, 38), 0.01f)
        assertEquals(38f, TextCloud.sizeSp(4, 14, 38), 0.01f)
    }
}
