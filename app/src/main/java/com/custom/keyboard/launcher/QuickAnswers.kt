package com.custom.keyboard.launcher

import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Instant answers for search, worked out on the phone: maths ("(12+3)*4^2", "15% of 80"),
 * units ("10 km to mi", "70 kg", "100 f"), currency ("100 usd to inr", with live rates passed in)
 * and dates ("days until 25 dec", "today + 30 days", "what day is 1 jan 2027"). Pure, so tested.
 */
object QuickAnswers {

    data class Answer(val text: String, val detail: String)

    fun answer(query: String, rates: Map<String, Double>? = null, homeCurrency: String = "USD", today: Calendar = Calendar.getInstance()): Answer? {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return null
        return percentOf(q) ?: currency(q, rates, homeCurrency) ?: units(q) ?: dates(q, today) ?: math(q)
    }

    // ── Maths ───────────────────────────────────────────────────────────────────────────

    fun format(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "—"
        if (abs(v - v.roundToLong()) < 1e-9 && abs(v) < 1e15) return String.format(Locale.US, "%,d", v.roundToLong())
        val s = String.format(Locale.US, "%,.6f", v).trimEnd('0').trimEnd('.')
        return s
    }

    private fun math(q: String): Answer? {
        // Needs at least one operator between numbers, so plain numbers or words don't match.
        if (!Regex("""[0-9.)]\s*[-+*/x×÷^%]\s*[0-9.(]""").containsMatchIn(q) && !q.startsWith("sqrt")) return null
        val v = runCatching { Parser(q.replace('×', '*').replace('÷', '/').replace(Regex("(?<=\\d)\\s*x\\s*(?=\\d)"), "*")).parse() }.getOrNull() ?: return null
        return Answer("= ${format(v)}", q)
    }

    private fun percentOf(q: String): Answer? {
        val m = Regex("""^([\d.]+)\s*%\s*of\s*([\d.]+)$""").find(q) ?: return null
        val p = m.groupValues[1].toDoubleOrNull() ?: return null
        val n = m.groupValues[2].toDoubleOrNull() ?: return null
        return Answer("= ${format(p * n / 100)}", "${format(p)}% of ${format(n)}")
    }

    /** Recursive-descent parser: + - * / ^ %, brackets, unary minus, sqrt(). */
    private class Parser(val s: String) {
        var i = 0
        fun parse(): Double {
            val v = expr()
            skip()
            if (i != s.length) error("junk")
            return v
        }
        fun skip() { while (i < s.length && s[i] == ' ') i++ }
        fun expr(): Double {
            var v = term()
            while (true) {
                skip()
                v = when (s.getOrNull(i)) {
                    '+' -> { i++; v + term() }
                    '-' -> { i++; v - term() }
                    else -> return v
                }
            }
        }
        fun term(): Double {
            var v = power()
            while (true) {
                skip()
                v = when (s.getOrNull(i)) {
                    '*' -> { i++; v * power() }
                    '/' -> { i++; val d = power(); if (d == 0.0) error("div0"); v / d }
                    '%' -> { i++; v / 100 }
                    else -> return v
                }
            }
        }
        fun power(): Double {
            val b = unary()
            skip()
            if (s.getOrNull(i) == '^') { i++; return b.pow(power()) }
            return b
        }
        fun unary(): Double {
            skip()
            if (s.getOrNull(i) == '-') { i++; return -unary() }
            if (s.getOrNull(i) == '+') { i++; return unary() }
            return atom()
        }
        fun atom(): Double {
            skip()
            if (s.startsWith("sqrt", i)) {
                i += 4
                return kotlin.math.sqrt(atom())
            }
            if (s.getOrNull(i) == '(') {
                i++
                val v = expr()
                skip()
                if (s.getOrNull(i) != ')') error("bracket")
                i++
                return v
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == ',')) i++
            if (start == i) error("number")
            return s.substring(start, i).replace(",", "").toDouble()
        }
    }

    // ── Units ───────────────────────────────────────────────────────────────────────────

    private data class Unit(val names: List<String>, val group: String, val toBase: Double, val label: String)

    private val unitList = listOf(
        Unit(listOf("mm", "millimetre", "millimeter", "millimetres", "millimeters"), "len", 0.001, "mm"),
        Unit(listOf("cm", "centimetre", "centimeter", "centimetres", "centimeters"), "len", 0.01, "cm"),
        Unit(listOf("m", "metre", "meter", "metres", "meters"), "len", 1.0, "m"),
        Unit(listOf("km", "kilometre", "kilometer", "kilometres", "kilometers"), "len", 1000.0, "km"),
        Unit(listOf("in", "inch", "inches"), "len", 0.0254, "in"),
        Unit(listOf("ft", "foot", "feet"), "len", 0.3048, "ft"),
        Unit(listOf("yd", "yard", "yards"), "len", 0.9144, "yd"),
        Unit(listOf("mi", "mile", "miles"), "len", 1609.344, "mi"),
        Unit(listOf("mg", "milligram", "milligrams"), "mass", 0.001, "mg"),
        Unit(listOf("g", "gram", "grams"), "mass", 1.0, "g"),
        Unit(listOf("kg", "kilo", "kilos", "kilogram", "kilograms"), "mass", 1000.0, "kg"),
        Unit(listOf("oz", "ounce", "ounces"), "mass", 28.349523125, "oz"),
        Unit(listOf("lb", "lbs", "pound", "pounds"), "mass", 453.59237, "lb"),
        Unit(listOf("st", "stone", "stones"), "mass", 6350.29318, "st"),
        Unit(listOf("ml", "millilitre", "milliliter", "millilitres", "milliliters"), "vol", 0.001, "ml"),
        Unit(listOf("l", "litre", "liter", "litres", "liters"), "vol", 1.0, "l"),
        Unit(listOf("gal", "gallon", "gallons"), "vol", 3.785411784, "gal"),
        Unit(listOf("cup", "cups"), "vol", 0.2365882365, "cups"),
        Unit(listOf("floz"), "vol", 0.0295735295625, "fl oz"),
        Unit(listOf("kmh", "km/h", "kph"), "speed", 1 / 3.6, "km/h"),
        Unit(listOf("mph"), "speed", 0.44704, "mph"),
        Unit(listOf("m/s", "mps"), "speed", 1.0, "m/s"),
        Unit(listOf("b", "byte", "bytes"), "data", 1.0, "B"),
        Unit(listOf("kb"), "data", 1e3, "KB"),
        Unit(listOf("mb"), "data", 1e6, "MB"),
        Unit(listOf("gb"), "data", 1e9, "GB"),
        Unit(listOf("tb"), "data", 1e12, "TB"),
        Unit(listOf("s", "sec", "second", "seconds"), "time", 1.0, "s"),
        Unit(listOf("min", "mins", "minute", "minutes"), "time", 60.0, "min"),
        Unit(listOf("h", "hr", "hrs", "hour", "hours"), "time", 3600.0, "h"),
        Unit(listOf("day", "days"), "time", 86400.0, "days"),
        Unit(listOf("week", "weeks"), "time", 604800.0, "weeks")
    )

    /** Sensible "other" unit when only one is given ("10 km" → miles). */
    private val partner = mapOf(
        "km" to "mi", "mi" to "km", "m" to "ft", "ft" to "m", "cm" to "in", "in" to "cm", "yd" to "m", "mm" to "in",
        "kg" to "lb", "lb" to "kg", "g" to "oz", "oz" to "g", "st" to "kg", "mg" to "g",
        "l" to "gal", "gal" to "l", "ml" to "fl oz", "cups" to "ml", "fl oz" to "ml",
        "km/h" to "mph", "mph" to "km/h", "m/s" to "km/h",
        "GB" to "MB", "MB" to "GB", "TB" to "GB", "KB" to "MB", "B" to "KB",
        "h" to "min", "min" to "s", "days" to "h", "weeks" to "days", "s" to "min"
    )

    private fun unitNamed(name: String) = unitList.firstOrNull { name in it.names }

    private fun units(q: String): Answer? {
        val m = Regex("""^(-?[\d.,]+)\s*([a-z/°]+)(?:\s+(?:to|in|as)\s+([a-z/°]+))?$""").find(q) ?: return null
        val value = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val fromName = m.groupValues[2].removePrefix("°")
        val toName = m.groupValues[3].removePrefix("°")
        temperature(value, fromName, toName)?.let { return it }
        val from = unitNamed(fromName) ?: return null
        val to = if (toName.isNotEmpty()) unitNamed(toName) ?: return null else unitList.firstOrNull { it.label == partner[from.label] } ?: return null
        if (to.group != from.group) return null
        val result = value * from.toBase / to.toBase
        return Answer("= ${format(result)} ${to.label}", "${format(value)} ${from.label}")
    }

    private fun temperature(v: Double, from: String, to: String): Answer? {
        val names = mapOf("c" to "C", "celsius" to "C", "f" to "F", "fahrenheit" to "F", "k" to "K", "kelvin" to "K")
        val f = names[from] ?: return null
        val t = if (to.isEmpty()) (if (f == "C") "F" else "C") else names[to] ?: return null
        val c = when (f) { "C" -> v; "F" -> (v - 32) * 5 / 9; else -> v - 273.15 }
        val out = when (t) { "C" -> c; "F" -> c * 9 / 5 + 32; else -> c + 273.15 }
        return Answer("= ${format((out * 10).roundToLong() / 10.0)} °$t".replace("°K", "K"), "${format(v)} °$f".replace("°K", "K"))
    }

    // ── Currency ────────────────────────────────────────────────────────────────────────

    private fun currency(q: String, rates: Map<String, Double>?, home: String): Answer? {
        val m = Regex("""^([\d.,]+)\s*([a-z]{3})(?:\s+(?:to|in)\s+([a-z]{3}))?$""").find(q) ?: return null
        val r = rates ?: return null
        val value = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val from = m.groupValues[2].uppercase(Locale.ROOT)
        val to = m.groupValues[3].uppercase(Locale.ROOT).ifEmpty { if (from == home) "USD" else home }
        // Rates are "units per 1 USD".
        val fromRate = r[from] ?: return null
        val toRate = r[to] ?: return null
        val result = value / fromRate * toRate
        return Answer("= ${String.format(Locale.US, "%,.2f", result)} $to", "${format(value)} $from · live rate")
    }

    // ── Dates ───────────────────────────────────────────────────────────────────────────

    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** "25 dec", "25 december 2026", "dec 25", "25/12/2026". */
    fun parseDate(text: String, today: Calendar): Calendar? {
        val t = text.trim().lowercase(Locale.ROOT)
        Regex("""^(\d{1,2})[/.-](\d{1,2})(?:[/.-](\d{2,4}))?$""").find(t)?.let { m ->
            val d = m.groupValues[1].toInt()
            val mo = m.groupValues[2].toInt() - 1
            val y = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < 100) 2000 + it else it }
            return build(d, mo, y, today)
        }
        Regex("""^(\d{1,2})(?:st|nd|rd|th)?\s+([a-z]+)(?:\s+(\d{4}))?$""").find(t)?.let { m ->
            val mo = months.indexOfFirst { m.groupValues[2].startsWith(it) }.takeIf { it >= 0 } ?: return null
            return build(m.groupValues[1].toInt(), mo, m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt(), today)
        }
        Regex("""^([a-z]+)\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(\d{4}))?$""").find(t)?.let { m ->
            val mo = months.indexOfFirst { m.groupValues[1].startsWith(it) }.takeIf { it >= 0 } ?: return null
            return build(m.groupValues[2].toInt(), mo, m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt(), today)
        }
        return null
    }

    private fun build(d: Int, mo: Int, y: Int?, today: Calendar): Calendar? {
        if (mo !in 0..11 || d !in 1..31) return null
        val c = Calendar.getInstance().apply { clear(); set(y ?: today.get(Calendar.YEAR), mo, d) }
        // No year given and the date has passed: the next one.
        if (y == null && Countdown.daysBetween(today, c) < 0) c.add(Calendar.YEAR, 1)
        return c
    }

    private fun longDate(c: Calendar) = String.format(Locale.getDefault(), "%tA, %<te %<tB %<tY", c)

    private fun dates(q: String, today: Calendar): Answer? {
        Regex("""^days? (?:until|till|to) (.+)$""").find(q)?.let { m ->
            val c = parseDate(m.groupValues[1], today) ?: return null
            val n = Countdown.daysBetween(today, c)
            return Answer("$n days", "until ${longDate(c)}")
        }
        Regex("""^days? since (.+)$""").find(q)?.let { m ->
            val c = parseDate(m.groupValues[1], today) ?: return null
            if (Countdown.daysBetween(today, c) > 0) c.add(Calendar.YEAR, -1)
            return Answer("${-Countdown.daysBetween(today, c)} days", "since ${longDate(c)}")
        }
        Regex("""^(?:today|now)\s*([+-])\s*(\d+)\s*(days?|weeks?|months?|years?)$""").find(q)?.let { m ->
            return shifted(today, if (m.groupValues[1] == "-") -m.groupValues[2].toInt() else m.groupValues[2].toInt(), m.groupValues[3])
        }
        Regex("""^(\d+)\s*(days?|weeks?|months?|years?)\s+(from now|from today|later|ago)$""").find(q)?.let { m ->
            val n = m.groupValues[1].toInt()
            return shifted(today, if (m.groupValues[3] == "ago") -n else n, m.groupValues[2])
        }
        Regex("""^what day (?:is|was) (.+)$""").find(q)?.let { m ->
            val c = parseDate(m.groupValues[1], today) ?: return null
            return Answer(String.format(Locale.getDefault(), "%tA", c), longDate(c))
        }
        return null
    }

    private fun shifted(today: Calendar, n: Int, unit: String): Answer {
        val c = today.clone() as Calendar
        when {
            unit.startsWith("week") -> c.add(Calendar.DAY_OF_YEAR, 7 * n)
            unit.startsWith("month") -> c.add(Calendar.MONTH, n)
            unit.startsWith("year") -> c.add(Calendar.YEAR, n)
            else -> c.add(Calendar.DAY_OF_YEAR, n)
        }
        return Answer(longDate(c), "${if (n >= 0) "+" else ""}$n $unit")
    }
}
