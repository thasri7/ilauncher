package com.custom.keyboard

class MathCalculator {

    // Regex for basic math like 25+15, 100*4, 50/2, 80-25
    private val mathRegex = Regex("""^(\d+(?:\.\d+)?)\s*([\+\-\*\/×÷])\s*(\d+(?:\.\d+)?)$""")

    fun evaluate(query: String): String? {
        val match = mathRegex.find(query.trim()) ?: return null
        val (num1Str, op, num2Str) = match.destructured

        val num1 = num1Str.toDoubleOrNull() ?: return null
        val num2 = num2Str.toDoubleOrNull() ?: return null

        val result = when (op) {
            "+", " " -> num1 + num2
            "-", "−" -> num1 - num2
            "*", "×", "x" -> num1 * num2
            "/", "÷" -> if (num2 != 0.0) num1 / num2 else return null
            else -> return null
        }

        return if (result % 1.0 == 0.0) {
            result.toLong().toString()
        } else {
            String.format("%.2f", result)
        }
    }
}
