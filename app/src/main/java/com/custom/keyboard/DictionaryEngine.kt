package com.custom.keyboard

import android.content.Context
import kotlin.math.abs
import kotlin.math.min

class DictionaryEngine(context: Context) {

    private val userPrefs = context.getSharedPreferences("ikeys_user_dict", Context.MODE_PRIVATE)
    private val keyboardPrefs = KeyboardPreferences(context)
    private val learnedWords = mutableSetOf<String>()
    var isIncognito: Boolean = false

    private val baseWords = listOf(
        "the", "be", "to", "of", "and", "a", "in", "that", "have", "i", "it", "for", "not", "on", "with",
        "he", "as", "you", "do", "at", "this", "but", "his", "by", "from", "they", "we", "say", "her",
        "she", "or", "an", "will", "my", "one", "all", "would", "there", "their", "what", "so", "up",
        "out", "if", "about", "who", "get", "which", "go", "me", "when", "make", "can", "like", "time",
        "no", "just", "him", "know", "take", "people", "into", "year", "your", "good", "some", "could",
        "them", "see", "other", "than", "then", "now", "look", "only", "come", "its", "over", "think",
        "also", "back", "after", "use", "two", "how", "our", "work", "first", "well", "way", "even",
        "new", "want", "because", "any", "these", "give", "day", "most", "us", "is", "are", "was",
        "were", "been", "has", "had", "done", "does", "did", "doing", "going", "goes", "went", "gone",
        "please", "thank", "thanks", "hello", "hi", "hey", "yes", "sure", "okay", "fine", "cool",
        "today", "tomorrow", "yesterday", "tonight", "morning", "night", "week", "month", "ready",
        "great", "awesome", "perfect", "love", "happy", "need", "feel", "call", "send", "message",
        "email", "phone", "check", "meet", "meeting", "work", "office", "home", "arrive", "leaving",
        "receive", "friend", "family", "help", "problem", "solve", "start", "finish", "complete",
        "always", "never", "sometimes", "often", "maybe", "really", "very", "much", "more", "little",
        "where", "why", "here", "somewhere", "anywhere", "everything", "something", "nothing",
        "before", "again", "still", "already", "almost", "enough", "together", "around", "through",
        "between", "under", "above", "without", "inside", "outside", "behind", "across", "important",
        "possible", "different", "similar", "special", "simple", "easy", "hard", "difficult", "quick",
        "fast", "slow", "early", "late", "next", "last", "right", "left", "true", "false", "open", "close",
        "world", "life", "place", "thing", "case", "system", "group", "number", "part", "point", "child",
        "detail", "details", "info", "information", "profile", "account", "address", "order", "card", "view"
    )

    private val commonTypos = mapOf(
        "teh" to "the",
        "recieve" to "receive",
        "seperate" to "separate",
        "untill" to "until",
        "definately" to "definitely",
        "occured" to "occurred",
        "alot" to "a lot",
        "tommorrow" to "tomorrow",
        "thier" to "their",
        "becuase" to "because",
        "hellp" to "help",
        "pls" to "please",
        "thx" to "thanks",
        "idk" to "I don't know"
    )

    private val allDictionaryWords = mutableListOf<String>()

    init {
        allDictionaryWords.addAll(baseWords)
        val saved = userPrefs.getStringSet("words", emptySet()) ?: emptySet()
        learnedWords.addAll(saved)
        allDictionaryWords.addAll(learnedWords)
    }

    fun checkShortcut(query: String): String? {
        val clean = query.trim().lowercase()
        val allShortcuts = keyboardPrefs.getAllShortcuts()
        return allShortcuts[clean]
    }

    fun learnWord(word: String) {
        if (isIncognito) return
        val clean = word.trim().lowercase()
        if (clean.length > 1 && !learnedWords.contains(clean)) {
            learnedWords.add(clean)
            allDictionaryWords.add(clean)
            userPrefs.edit().putStringSet("words", learnedWords).apply()
        }
    }

    data class SuggestionResult(
        val exact: String,
        val topMatch: String,
        val alternative: String
    )

    fun getSuggestions(query: String): SuggestionResult {
        val clean = query.trim().lowercase()
        if (clean.isEmpty()) {
            return SuggestionResult("", "", "")
        }

        // 1. Text expander shortcut check
        val shortcut = checkShortcut(clean)
        if (shortcut != null) {
            return SuggestionResult(query, shortcut, clean)
        }

        // 2. Exact typo map
        val mappedTypo = commonTypos[clean]
        if (mappedTypo != null) {
            return SuggestionResult(query, mappedTypo, clean)
        }

        // 3. Prefix matches
        val prefixMatches = allDictionaryWords.filter { it.startsWith(clean) && it != clean }

        // 4. Fuzzy matches (only allow close typo distance <= 1 for safety)
        val bestMatch = if (allDictionaryWords.contains(clean)) {
            clean
        } else if (prefixMatches.isNotEmpty()) {
            prefixMatches.first()
        } else {
            // Only suggest typo correction if very close (1 edit away)
            findClosestWord(clean) ?: clean
        }

        val altMatch = when {
            prefixMatches.size > 1 -> prefixMatches[1]
            prefixMatches.size == 1 && prefixMatches[0] != bestMatch -> prefixMatches[0]
            else -> ""
        }

        return SuggestionResult(
            exact = query,
            topMatch = if (bestMatch.isNotEmpty()) preserveCase(bestMatch, query) else query,
            alternative = if (altMatch.isNotEmpty()) preserveCase(altMatch, query) else ""
        )
    }

    fun matchGlidePath(pathChars: List<Char>): String? {
        if (pathChars.size < 2) return null
        val startChar = pathChars.first().lowercaseChar()
        val endChar = pathChars.last().lowercaseChar()

        val candidates = allDictionaryWords.filter { word ->
            word.length >= 2 &&
            word.first().lowercaseChar() == startChar &&
            word.last().lowercaseChar() == endChar
        }

        if (candidates.isEmpty()) return null

        var bestScore = -1
        var bestWord: String? = null
        val pathSet = pathChars.map { it.lowercaseChar() }

        for (word in candidates) {
            var matchIdx = 0
            for (ch in word) {
                val found = pathSet.indexOf(ch)
                if (found >= matchIdx) {
                    matchIdx = found
                }
            }
            val score = 100 - abs(word.length - pathChars.size) * 5
            if (score > bestScore) {
                bestScore = score
                bestWord = word
            }
        }
        return bestWord ?: candidates.firstOrNull()
    }

    private fun findClosestWord(typo: String): String? {
        var minDistance = 2 // Only accept 1 typo edit difference
        var candidate: String? = null

        for (word in allDictionaryWords) {
            if (abs(word.length - typo.length) > 1) continue
            val dist = levenshteinDistance(typo, word)
            if (dist < minDistance) {
                minDistance = dist
                candidate = word
                if (dist == 1) break
            }
        }
        return candidate
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = min(
                    dp[i - 1][j] + 1,
                    min(dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
                )
            }
        }
        return dp[s1.length][s2.length]
    }

    private fun preserveCase(target: String, source: String): String {
        return when {
            source.all { it.isUpperCase() } -> target.uppercase()
            source.isNotEmpty() && source[0].isUpperCase() -> target.replaceFirstChar { it.uppercase() }
            else -> target
        }
    }
}
