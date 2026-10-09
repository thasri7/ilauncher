package com.custom.keyboard.launcher

import com.custom.keyboard.models.TileItem
import com.custom.keyboard.models.TileSize
import com.custom.keyboard.models.TileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartArrangerTest {
    private fun app(name: String, size: TileSize = TileSize.MEDIUM) = TileItem(name, TileType.APP_SHORTCUT, name, "pkg.$name", size)
    private fun header(name: String) = TileItem("h$name", TileType.SECTION_HEADER, name)

    @Test
    fun sortsInsideEachGroupAndKeepsGroupNames() {
        val tiles = listOf(app("b"), app("a"), header("Work"), app("d"), app("c"))
        assertEquals(listOf("a", "b", "Work", "c", "d"), StartArranger.alphabetical(tiles).map { it.title })
    }

    @Test
    fun packTightlyPutsBigTilesFirstKeepingOrder() {
        val tiles = listOf(app("s1", TileSize.SMALL), app("w", TileSize.WIDE), app("s2", TileSize.SMALL), app("l", TileSize.LARGE))
        assertEquals(listOf("l", "w", "s1", "s2"), StartArranger.packTightly(tiles).map { it.title })
    }

    @Test
    fun groupsByCategoryWithOtherForStragglers() {
        val clock = TileItem("clock", TileType.CLOCK_WEATHER, "Clock")
        val tiles = listOf(clock, app("chat1"), app("game1"), app("chat2"), app("game2"), app("lonely"))
        val kinds = mapOf("chat1" to "Social", "chat2" to "Social", "game1" to "Games", "game2" to "Games", "lonely" to "Money")
        val out = StartArranger.byCategory(tiles, { kinds[it.title] }, { if (it.title.startsWith("game")) 10 else 1 })
        assertEquals(listOf("Clock", "Games", "game1", "game2", "Social", "chat1", "chat2", "Other", "lonely"), out.map { it.title })
    }

    @Test
    fun movesWholeGroups() {
        val work = header("Work")
        val tiles = listOf(app("a"), header("Fun"), app("b"), work, app("c"))
        assertEquals(listOf("a", "Work", "c", "Fun", "b"), StartArranger.moveGroup(tiles, work, up = true)!!.map { it.title })
        // The unnamed first group stays first.
        assertNull(StartArranger.moveGroup(StartArranger.moveGroup(tiles, work, up = true)!!, work, up = true))
    }
}
