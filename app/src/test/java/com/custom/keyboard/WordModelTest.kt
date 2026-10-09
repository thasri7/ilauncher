package com.custom.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordModelTest {
    private fun model() = WordModel().apply {
        listOf("hello" to 9000L, "help" to 8000L, "hell" to 500L, "the" to 99999L, "they" to 20000L, "there" to 15000L,
            "world" to 7000L, "word" to 6000L, "would" to 9500L, "good" to 9000L, "going" to 8000L, "great" to 7000L)
            .forEach { (w, c) -> addWord(w, c) }
        finishLoading()
    }

    @Test
    fun completionsAreRankedByFrequency() {
        assertEquals(listOf("hello", "help", "hell"), model().completions("hel"))
    }

    @Test
    fun correctsOneTypoAndSwappedLetters() {
        assertEquals("hello", model().corrections("helo").first())
        assertEquals("would", model().corrections("woudl").first())
    }

    @Test
    fun swipeFindsTheWordAlongThePath() {
        // A finger gliding h → e → l → o crosses the keys in between.
        val path = "hgfrertyuiklo".toList()
        assertEquals("hello", model().swipe(path).first())
        assertFalse("world" in model().swipe(path))
    }

    @Test
    fun swipePrefersTheCommonWordThatFits() {
        assertEquals("the", model().swipe("tyghgfre".toList()).first())
    }

    @Test
    fun learnedWordsNeedTwoUsesAndThenRise() {
        val m = model()
        m.learn("hellothere")
        assertFalse(m.contains("hellothere"))
        m.learn("hellothere")
        assertTrue(m.contains("hellothere"))
        m.learn("hell"); m.learn("hell"); m.learn("hell")
        assertEquals("hell", m.completions("hel").first())
    }

    @Test
    fun predictsTheWordYouUsuallyTypeNext() {
        val m = model()
        repeat(3) { m.learnPair("good", "morning") }
        m.learnPair("good", "night")
        assertEquals(listOf("morning", "night"), m.predictNext("Good"))
    }
}
