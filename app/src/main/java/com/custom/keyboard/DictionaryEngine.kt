package com.custom.keyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Suggestions, corrections, swipe typing and next-word hints for the keyboard, backed by
 * [WordModel]: 20,000 common English words bundled with the app plus the words you type, which
 * it learns (unless incognito). Everything stays on the phone.
 */
class DictionaryEngine(context: Context) {

    private val userPrefs = context.getSharedPreferences("ikeys_user_dict", Context.MODE_PRIVATE)
    private val keyboardPrefs = KeyboardPreferences(context)
    /** Replaced once the big list has loaded in the background. */
    @Volatile
    private var model = WordModel()
    private val main = Handler(Looper.getMainLooper())
    @Volatile
    var isLoaded = false
        private set
    var isIncognito: Boolean = false

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


    init {
        // Your own words load straight away; the big list follows in the background.
        model.setLearned(loadLearned())
        model.setPairs(loadPairs())
        val app = context.applicationContext
        Executors.newSingleThreadExecutor().execute {
            // Built entirely off the main thread, so opening the keyboard never stutters.
            val full = WordModel()
            try {
                app.assets.open("words_en.txt").bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val tab = line.indexOf('\t')
                        if (tab > 0) full.addWord(line.substring(0, tab), line.substring(tab + 1).toLongOrNull() ?: 1L)
                    }
                }
            } catch (_: Exception) {
            }
            full.finishLoading()
            main.post {
                // Bring over anything learned while the list was loading.
                full.setLearned(model.learnedWords())
                full.setPairs(model.pairsSnapshot())
                model = full
                isLoaded = true
            }
        }
    }

    private fun loadLearned(): Map<String, Int> {
        val out = HashMap<String, Int>()
        runCatching {
            val o = JSONObject(userPrefs.getString("learned_json", "{}") ?: "{}")
            o.keys().forEach { out[it] = o.optInt(it, 1) }
        }
        // Words saved by the older keyboard.
        userPrefs.getStringSet("words", emptySet())?.forEach { if (it !in out) out[it] = 1 }
        return out
    }

    private fun loadPairs(): Map<String, Map<String, Int>> = runCatching {
        val o = JSONObject(userPrefs.getString("pairs_json", "{}") ?: "{}")
        o.keys().asSequence().associateWith { k ->
            val inner = o.getJSONObject(k)
            inner.keys().asSequence().associateWith { inner.optInt(it, 1) }
        }
    }.getOrDefault(emptyMap())

    private val saveRunnable = Runnable {
        userPrefs.edit()
            .putString("learned_json", JSONObject(model.learnedWords() as Map<*, *>).toString())
            .putString("pairs_json", JSONObject(model.pairsSnapshot().mapValues { JSONObject(it.value as Map<*, *>) } as Map<*, *>).toString())
            .remove("words")
            .apply()
    }

    /** Saves a moment after typing stops, not on every word. */
    private fun scheduleSave() {
        main.removeCallbacks(saveRunnable)
        main.postDelayed(saveRunnable, 2000)
    }

    fun checkShortcut(query: String): String? {
        val clean = query.trim().lowercase()
        val allShortcuts = keyboardPrefs.getAllShortcuts()
        return allShortcuts[clean]
    }

    fun learnWord(word: String) {
        if (isIncognito) return
        model.learn(word.trim())
        scheduleSave()
    }

    /** Remembers that [next] followed [previous], for next-word hints. */
    fun learnPair(previous: String, next: String) {
        if (isIncognito) return
        model.learnPair(previous.trim(), next.trim())
        scheduleSave()
    }

    fun forgetWordUse(word: String) {
        model.unlearnOnce(word.trim())
        scheduleSave()
    }

    fun forgetWord(word: String) {
        model.forget(word.trim())
        scheduleSave()
    }

    fun predictNext(previous: String): List<String> = if (isIncognito) emptyList() else model.predictNext(previous)

    fun isKnown(word: String): Boolean = model.contains(word)

    data class SuggestionResult(
        val exact: String,
        val topMatch: String,
        val alternative: String
    )

    fun getSuggestions(query: String): SuggestionResult {
        val clean = query.trim().lowercase()
        if (clean.isEmpty()) return SuggestionResult("", "", "")

        // 1. Text expander shortcut check
        checkShortcut(clean)?.let { return SuggestionResult(query, it, clean) }
        // 2. Well-known typos
        commonTypos[clean]?.let { return SuggestionResult(query, it, clean) }

        val known = model.contains(clean)
        val completions = model.completions(clean, 3)
        val corrections = if (!known && completions.isEmpty()) model.corrections(clean, 2) else emptyList()
        val ranked = (if (known) listOf(clean) else emptyList()) + completions + corrections
        val best = ranked.firstOrNull() ?: clean
        val alt = ranked.firstOrNull { it != best && it != clean }.orEmpty()
        return SuggestionResult(
            exact = query,
            topMatch = preserveCase(best, query),
            alternative = if (alt.isNotEmpty()) preserveCase(alt, query) else ""
        )
    }

    /** Best word for a swipe over [pathChars], or null. */
    fun matchGlidePath(pathChars: List<Char>): String? = model.swipe(pathChars, 1).firstOrNull()

    /** Up to three words for a swipe, most likely first. */
    fun glideCandidates(pathChars: List<Char>): List<String> = model.swipe(pathChars, 3)

    private fun preserveCase(target: String, source: String): String {
        return when {
            source.length > 1 && source.all { it.isUpperCase() } -> target.uppercase()
            source.isNotEmpty() && source[0].isUpperCase() -> target.replaceFirstChar { it.uppercase() }
            else -> target
        }
    }
}
