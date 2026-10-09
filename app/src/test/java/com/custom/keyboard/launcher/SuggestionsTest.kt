package com.custom.keyboard.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class SuggestionsTest {
    private fun hours(vararg pairs: Pair<Int, Int>) = IntArray(24).also { b -> pairs.forEach { (h, n) -> b[h] = n } }

    private val usage = mapOf(
        "news" to hours(8 to 6, 9 to 2),
        "maps" to hours(18 to 5),
        "music" to hours(7 to 3, 8 to 1),
        "bank" to hours(8 to 1)
    )

    @Test
    fun ranksByThisHourAndItsNeighbours() {
        // news 3*6+0+2=20, music 3*1+3+0=6, bank 3*1=3; maps has nothing near 8.
        assertEquals(listOf("news", "music", "bank"), Suggestions.forHour(usage, 8, 5))
    }

    @Test
    fun wrapsAroundMidnightAndSkipsExcluded() {
        val late = mapOf("chat" to hours(0 to 2, 23 to 4))
        assertEquals(listOf("chat"), Suggestions.forHour(late, 23, 5))
        assertEquals(emptyList<String>(), Suggestions.forHour(usage, 8, 5, exclude = setOf("news", "music", "bank")))
    }

    @Test
    fun partOfDayNames() {
        assertEquals("morning", Suggestions.partOfDay(9))
        assertEquals("night", Suggestions.partOfDay(2))
    }
}
