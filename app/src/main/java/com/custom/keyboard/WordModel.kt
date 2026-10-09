package com.custom.keyboard

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The keyboard's word knowledge: a frequency-ranked English list, the words you type yourself
 * (they rise as you use them) and which word you tend to type after which. Pure Kotlin so the
 * suggestion and swipe-typing logic is unit-tested.
 */
class WordModel {
    private val frequency = HashMap<String, Double>(32_000)
    /** Words by first letter, most common first, for fast prefix and swipe lookups. */
    private val byFirst = HashMap<Char, MutableList<String>>()
    private val learned = HashMap<String, Int>()
    private val pairs = HashMap<String, HashMap<String, Int>>()

    val size: Int get() = frequency.size

    fun addWord(word: String, count: Long) {
        val w = word.lowercase()
        if (w.isEmpty() || frequency.containsKey(w)) return
        frequency[w] = ln(count.toDouble() + 1.0)
        byFirst.getOrPut(w[0]) { ArrayList() }.add(w)
    }

    /** Call after adding the bundled words so lists are ordered by score. */
    fun finishLoading() {
        byFirst.values.forEach { list -> list.sortByDescending { score(it) } }
    }

    fun setLearned(words: Map<String, Int>) {
        learned.clear()
        learned.putAll(words)
        words.forEach { (w, n) -> if (!frequency.containsKey(w) && w.isNotEmpty() && n >= 2) byFirst.getOrPut(w[0]) { ArrayList() }.add(w) }
    }

    fun learnedWords(): Map<String, Int> = learned

    fun setPairs(all: Map<String, Map<String, Int>>) {
        pairs.clear()
        all.forEach { (k, v) -> pairs[k] = HashMap(v) }
    }

    fun pairsSnapshot(): Map<String, Map<String, Int>> = pairs

    fun learn(word: String) {
        val w = word.lowercase()
        if (w.length < 2 || !w.all { it.isLetter() || it == '\'' }) return
        val count = (learned[w] ?: 0) + 1
        learned[w] = count
        // A new word joins suggestions on its second use, so one-off typos don't.
        if (count == 2 && !frequency.containsKey(w)) byFirst.getOrPut(w[0]) { ArrayList() }.add(w)
    }

    /** Takes back one use, e.g. when a swipe guessed the wrong word. */
    fun unlearnOnce(word: String) {
        val w = word.lowercase()
        val n = learned[w] ?: return
        if (n <= 1) learned.remove(w) else learned[w] = n - 1
    }

    fun forget(word: String) {
        val w = word.lowercase()
        learned.remove(w)
        if (!frequency.containsKey(w)) byFirst[w.firstOrNull() ?: return]?.remove(w)
        pairs.remove(w)
        pairs.values.forEach { it.remove(w) }
    }

    fun learnPair(previous: String, next: String) {
        val a = previous.lowercase()
        val b = next.lowercase()
        if (a.isEmpty() || b.length < 2) return
        val map = pairs.getOrPut(a) { HashMap() }
        map[b] = (map[b] ?: 0) + 1
        // Keep it small: the 12 most used followers per word.
        if (map.size > 12) map.remove(map.minByOrNull { it.value }!!.key)
    }

    /** Words you usually type after [previous], most likely first. */
    fun predictNext(previous: String, max: Int = 3): List<String> =
        pairs[previous.lowercase()]?.entries?.sortedByDescending { it.value }?.take(max)?.map { it.key }.orEmpty()

    fun contains(word: String): Boolean {
        val w = word.lowercase()
        return frequency.containsKey(w) || (learned[w] ?: 0) >= 2
    }

    /** Higher is more likely: how common the word is, plus how often you typed it. */
    fun score(word: String): Double {
        val known = frequency[word]
        val mine = learned[word] ?: 0
        val boost = if (mine > 0 && (known != null || mine >= 2)) 2.5 + ln(mine.toDouble() + 1.0) * 1.5 else 0.0
        return (known ?: 3.0) + boost
    }

    /** Completions of [prefix], most likely first. */
    fun completions(prefix: String, max: Int = 3): List<String> {
        val p = prefix.lowercase()
        if (p.isEmpty()) return emptyList()
        val list = byFirst[p[0]] ?: return emptyList()
        return list.asSequence().filter { it.length > p.length && it.startsWith(p) }
            .sortedByDescending { score(it) }.take(max).toList()
    }

    /** Close spellings of a word not in the list (1 edit, 2 for long words), most likely first. */
    fun corrections(typed: String, max: Int = 2): List<String> {
        val t = typed.lowercase()
        if (t.length < 3) return emptyList()
        val limit = if (t.length >= 7) 2 else 1
        val firsts = listOf(t[0]) + neighbours(t[0])
        return firsts.flatMap { byFirst[it].orEmpty() }
            .asSequence()
            .filter { abs(it.length - t.length) <= limit && it != t }
            .map { it to editDistance(t, it, limit) }
            .filter { it.second <= limit }
            .sortedWith(compareBy<Pair<String, Int>> { it.second }.thenByDescending { score(it.first) - if (it.first[0] != t[0]) 3.0 else 0.0 })
            .take(max).map { it.first }.toList()
    }

    // ── Swipe typing ────────────────────────────────────────────────────────────────────

    /**
     * The words a swipe most likely meant. [path] is every letter key the finger passed over, in
     * order. A word fits when it starts at the first key, ends at (or next to) the last key, and
     * its letters appear along the path in order. Fits are ranked by how common the word is and
     * how well the path length matches the distance between its letters on the keyboard.
     */
    fun swipe(path: List<Char>, max: Int = 3): List<String> {
        val keys = collapse(path.map { it.lowercaseChar() }.filter { it in KEY_POS })
        if (keys.size < 2) return emptyList()
        val first = keys.first()
        val last = keys.last()
        val ends = setOf(last) + neighbours(last)
        val candidates = byFirst[first].orEmpty()
        val scored = ArrayList<Pair<String, Double>>()
        for (word in candidates) {
            if (word.length < 2) continue
            val letters = collapse(word.toList().filter { it in KEY_POS })
            if (letters.size < 2 || letters.last() !in ends) continue
            if (letters.size > keys.size) continue
            if (!isSubsequence(letters, keys)) continue
            val expected = expectedKeys(letters)
            val mismatch = abs(keys.size - expected)
            val endBonus = if (letters.last() == last) 0.0 else -1.5
            scored.add(word to (score(word) - mismatch * 0.45 + endBonus + letters.size * 0.15))
        }
        return scored.sortedByDescending { it.second }.take(max).map { it.first }
    }

    private fun collapse(chars: List<Char>): List<Char> {
        val out = ArrayList<Char>(chars.size)
        for (c in chars) if (out.isEmpty() || out.last() != c) out.add(c)
        return out
    }

    private fun isSubsequence(word: List<Char>, path: List<Char>): Boolean {
        var i = 0
        for (c in path) if (i < word.size && c == word[i]) i++
        return i == word.size
    }

    /** About how many keys a finger crosses gliding through [letters]. */
    private fun expectedKeys(letters: List<Char>): Int {
        var total = 1.0
        for (i in 1 until letters.size) {
            val a = KEY_POS.getValue(letters[i - 1])
            val b = KEY_POS.getValue(letters[i])
            total += max(abs(a.first - b.first), abs(a.second - b.second).toFloat()).toDouble().coerceAtLeast(1.0)
        }
        return total.roundToInt()
    }

    private fun neighbours(c: Char): List<Char> {
        val p = KEY_POS[c] ?: return emptyList()
        return KEY_POS.filter { (k, q) -> k != c && abs(q.first - p.first) <= 1.0f && abs(q.second - p.second) <= 1 }.keys.toList()
    }

    /** Edit distance where swapping two neighbouring letters ("teh") counts as one edit. */
    private fun editDistance(a: String, b: String, limit: Int): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) {
            var rowMin = Int.MAX_VALUE
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = min(min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = min(v, d[i - 2][j - 2] + 1)
                d[i][j] = v
                rowMin = min(rowMin, v)
            }
            if (rowMin > limit) return limit + 1
        }
        return d[a.length][b.length]
    }

    companion object {
        /** QWERTY key centres: x in key widths (rows are staggered), y = row. */
        val KEY_POS: Map<Char, Pair<Float, Int>> = buildMap {
            "qwertyuiop".forEachIndexed { i, c -> put(c, i.toFloat() to 0) }
            "asdfghjkl".forEachIndexed { i, c -> put(c, i + 0.5f to 1) }
            "zxcvbnm".forEachIndexed { i, c -> put(c, i + 1.5f to 2) }
        }
    }
}
