package com.custom.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Locale

class MathCalculatorTest {
    private val calc = MathCalculator()

    @Before
    fun englishDecimals() {
        // MathCalculator formats with the default locale; pin it so "2.50" isn't "2,50".
        Locale.setDefault(Locale.US)
    }

    @Test
    fun basicOperations() {
        assertEquals("540", calc.evaluate("45*12"))
        assertEquals("40", calc.evaluate("25 + 15"))
        assertEquals("55", calc.evaluate("80-25"))
        assertEquals("2.50", calc.evaluate("5/2"))
    }

    @Test
    fun rejectsNonMathAndDivisionByZero() {
        assertNull(calc.evaluate("chrome"))
        assertNull(calc.evaluate("5/0"))
    }
}
