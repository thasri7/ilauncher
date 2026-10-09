package com.custom.keyboard

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class KeyboardPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("ikeys_prefs", Context.MODE_PRIVATE)

    var fontSizeSp: Float
        get() = prefs.getFloat(KEY_FONT_SIZE, 22f)
        set(value) = prefs.edit().putFloat(KEY_FONT_SIZE, value).apply()

    var rowHeightDp: Int
        get() = prefs.getInt(KEY_ROW_HEIGHT, 54)
        set(value) = prefs.edit().putInt(KEY_ROW_HEIGHT, value).apply()

    var typefaceStyle: String
        get() = prefs.getString(KEY_TYPEFACE_STYLE, "sans") ?: "sans"
        set(value) = prefs.edit().putString(KEY_TYPEFACE_STYLE, value).apply()

    var secondaryLanguage: String
        get() = prefs.getString(KEY_SECONDARY_LANG, "ar") ?: "ar"
        set(value) = prefs.edit().putString(KEY_SECONDARY_LANG, value).apply()

    var showNumberRow: Boolean
        get() = prefs.getBoolean(KEY_SHOW_NUMBERS, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_NUMBERS, value).apply()

    var hapticFeedback: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTIC, value).apply()

    var soundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND, true)
        set(value) = prefs.edit().putBoolean(KEY_SOUND, value).apply()

    var doubleTapPeriod: Boolean
        get() = prefs.getBoolean(KEY_DOUBLE_TAP_PERIOD, true)
        set(value) = prefs.edit().putBoolean(KEY_DOUBLE_TAP_PERIOD, value).apply()

    var autoCorrect: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CORRECT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CORRECT, value).apply()

    var autoCapitalize: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CAPITALIZE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CAPITALIZE, value).apply()

    var longPressDelayMs: Long
        get() = prefs.getLong(KEY_LONG_PRESS_DELAY, 320L)
        set(value) = prefs.edit().putLong(KEY_LONG_PRESS_DELAY, value).apply()

    var keyPopupsEnabled: Boolean
        get() = prefs.getBoolean(KEY_KEY_POPUPS, true)
        set(value) = prefs.edit().putBoolean(KEY_KEY_POPUPS, value).apply()

    var theme: String
        get() = prefs.getString(KEY_THEME, "slate") ?: "slate" // "slate", "amoled", "neon"
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

    /**
     * Text shortcuts ("/omw" → "On my way!"). A few generic ones are seeded once; after that the
     * stored list is the only source, so deleting a shortcut sticks.
     */
    fun getAllShortcuts(): MutableMap<String, String> {
        val jsonStr = prefs.getString(KEY_SHORTCUTS_JSON, null)
            ?: JSONObject(mapOf("/omw" to "On my way!", "/brb" to "Be right back!", "/thx" to "Thank you so much!"))
                .toString().also { prefs.edit().putString(KEY_SHORTCUTS_JSON, it).apply() }
        val map = mutableMapOf<String, String>()
        try {
            val json = JSONObject(jsonStr)
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = json.getString(k)
            }
        } catch (_: Exception) {}
        return map
    }

    // ── Quick replies, recent emoji, clipboard history (all stored on the device only) ─────

    private fun getList(key: String): MutableList<String> = try {
        val arr = JSONArray(prefs.getString(key, "[]"))
        MutableList(arr.length()) { arr.getString(it) }
    } catch (_: Exception) {
        mutableListOf()
    }

    private fun putList(key: String, list: List<String>) {
        prefs.edit().putString(key, JSONArray(list).toString()).apply()
    }

    /** The user's quick replies; seeded once with a few everyday ones they can delete. */
    var quickReplies: List<String>
        get() {
            if (!prefs.contains(KEY_QUICK_REPLIES)) {
                putList(KEY_QUICK_REPLIES, listOf("On my way!", "Running 5 minutes late.", "Can I call you back?", "Thank you!"))
            }
            return getList(KEY_QUICK_REPLIES)
        }
        set(value) = putList(KEY_QUICK_REPLIES, value.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(30))

    /** Most recently used emoji first. */
    val recentEmojis: List<String> get() = getList(KEY_RECENT_EMOJI)

    fun recordEmoji(emoji: String) {
        putList(KEY_RECENT_EMOJI, (listOf(emoji) + getList(KEY_RECENT_EMOJI).filter { it != emoji }).take(16))
    }

    /** Clipboard history, newest first. */
    val clipHistory: List<String> get() = getList(KEY_CLIP_HISTORY)

    fun recordClip(text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || clean.length > 2000) return
        putList(KEY_CLIP_HISTORY, (listOf(clean) + getList(KEY_CLIP_HISTORY).filter { it != clean }).take(20))
    }

    /** Clipboard history; pinned clips stay. */
    fun clearClipHistory() = putList(KEY_CLIP_HISTORY, emptyList())

    /** Clips the user pinned: kept forever, shown first. */
    val pinnedClips: List<String> get() = getList(KEY_PINNED_CLIPS)

    fun togglePinnedClip(text: String): Boolean {
        val pinned = getList(KEY_PINNED_CLIPS)
        val nowPinned = text !in pinned
        putList(KEY_PINNED_CLIPS, if (nowPinned) (listOf(text) + pinned).take(30) else pinned - text)
        return nowPinned
    }

    /** Swipe a finger across the letters to type a word. */
    var glideTyping: Boolean
        get() = prefs.getBoolean(KEY_GLIDE, true)
        set(value) = prefs.edit().putBoolean(KEY_GLIDE, value).apply()

    /** Show the swipe trail while gliding. */
    var glideTrail: Boolean
        get() = prefs.getBoolean(KEY_GLIDE_TRAIL, true)
        set(value) = prefs.edit().putBoolean(KEY_GLIDE_TRAIL, value).apply()

    /** Suggest the next word from what you usually type. */
    var nextWordHints: Boolean
        get() = prefs.getBoolean(KEY_NEXT_WORD, true)
        set(value) = prefs.edit().putBoolean(KEY_NEXT_WORD, value).apply()

    /** Last language picked in the translate panel (BCP-47 code). */
    var translateTarget: String
        get() = prefs.getString(KEY_TRANSLATE_TARGET, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TRANSLATE_TARGET, value).apply()

    fun saveShortcut(trigger: String, expansion: String) {
        val current = getAllShortcuts()
        current[trigger.trim().lowercase()] = expansion.trim()
        val json = JSONObject(current as Map<*, *>)
        prefs.edit().putString(KEY_SHORTCUTS_JSON, json.toString()).apply()
    }

    fun deleteShortcut(trigger: String) {
        val current = getAllShortcuts()
        current.remove(trigger.trim().lowercase())
        val json = JSONObject(current as Map<*, *>)
        prefs.edit().putString(KEY_SHORTCUTS_JSON, json.toString()).apply()
    }

    companion object {
        private const val KEY_FONT_SIZE = "font_size_sp"
        private const val KEY_ROW_HEIGHT = "row_height_dp"
        private const val KEY_TYPEFACE_STYLE = "typeface_style"
        private const val KEY_SECONDARY_LANG = "secondary_lang"
        private const val KEY_SHOW_NUMBERS = "show_numbers"
        private const val KEY_HAPTIC = "haptic_feedback"
        private const val KEY_SOUND = "sound_feedback"
        private const val KEY_DOUBLE_TAP_PERIOD = "double_tap_period"
        private const val KEY_AUTO_CORRECT = "auto_correct"
        private const val KEY_AUTO_CAPITALIZE = "auto_capitalize"
        private const val KEY_LONG_PRESS_DELAY = "long_press_delay_ms"
        private const val KEY_KEY_POPUPS = "key_popups_enabled"
        private const val KEY_THEME = "keyboard_theme"
        private const val KEY_SHORTCUTS_JSON = "custom_shortcuts_json"
        private const val KEY_QUICK_REPLIES = "quick_replies_json"
        private const val KEY_RECENT_EMOJI = "recent_emoji_json"
        private const val KEY_CLIP_HISTORY = "clip_history_json"
        private const val KEY_TRANSLATE_TARGET = "translate_target"
        private const val KEY_PINNED_CLIPS = "pinned_clips_json"
        private const val KEY_GLIDE = "glide_typing"
        private const val KEY_GLIDE_TRAIL = "glide_trail"
        private const val KEY_NEXT_WORD = "next_word_hints"
    }
}
