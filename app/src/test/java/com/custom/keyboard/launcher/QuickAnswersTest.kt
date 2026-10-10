package com.custom.keyboard.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class QuickAnswersTest {
    private val today = Calendar.getInstance().apply { clear(); set(2026, Calendar.OCTOBER, 10) }
    private fun a(q: String, rates: Map<String, Double>? = null) = QuickAnswers.answer(q, rates, "INR", today)?.text

    @Test
    fun maths() {
        assertEquals("= 60", a("(12+3)*4"))
        assertEquals("= 1,024", a("2^10"))
        assertEquals("= 12", a("15% of 80"))
        assertEquals("= 2.5", a("5/2"))
        assertNull(a("5/0"))
        assertNull(a("hello"))
    }

    @Test
    fun units() {
        assertEquals("= 6.213712 mi", a("10 km"))
        assertEquals("= 3.048 m", a("10 ft to m"))
        assertEquals("= 212 °F", a("100 c"))
        assertEquals("= 154.323584 lb", a("70kg"))
        assertNull(a("10 km to kg"))
    }

    @Test
    fun currencyUsesTheRatesGiven() {
        val rates = mapOf("USD" to 1.0, "INR" to 83.0, "EUR" to 0.9)
        assertEquals("= 8,300.00 INR", a("100 usd", rates))
        assertEquals("= 90.00 EUR", a("100 usd to eur", rates))
        assertNull(a("100 usd"))
    }

    @Test
    fun dates() {
        assertEquals("76 days", a("days until 25 dec"))
        assertEquals("Thursday", a("what day is 1 jan 2026"))
        // 10 Oct + 15 days = 25 Oct.
        assertEquals(true, a("today + 15 days")?.contains("25"))
    }
}
