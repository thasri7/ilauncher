package com.custom.keyboard

class UnitConverter {

    // Regex for unit queries: 50usd, 100eur, 10km, 70kg, 25c, 100mi, 150lb
    private val unitRegex = Regex("""^(\d+(?:\.\d+)?)\s*([a-zA-Z]+)$""")

    fun convert(query: String): String? {
        val match = unitRegex.find(query.trim().lowercase()) ?: return null
        val (valStr, unit) = match.destructured
        val value = valStr.toDoubleOrNull() ?: return null

        return when (unit) {
            "usd" -> String.format("≈ %.1f SAR / %.1f EUR", value * 3.75, value * 0.92)
            "eur" -> String.format("≈ %.1f USD / %.1f SAR", value * 1.08, value * 4.08)
            "sar" -> String.format("≈ %.1f USD / %.1f EUR", value / 3.75, (value / 3.75) * 0.92)
            "km" -> String.format("≈ %.1f miles", value * 0.621371)
            "mi", "miles" -> String.format("≈ %.1f km", value * 1.60934)
            "kg" -> String.format("≈ %.1f lbs", value * 2.20462)
            "lb", "lbs" -> String.format("≈ %.1f kg", value / 2.20462)
            "c" -> String.format("≈ %.1f °F", (value * 9 / 5) + 32)
            "f" -> String.format("≈ %.1f °C", (value - 32) * 5 / 9)
            else -> null
        }
    }
}
