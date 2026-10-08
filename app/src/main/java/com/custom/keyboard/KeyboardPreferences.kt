package com.custom.keyboard

import android.content.Context
import android.content.SharedPreferences
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

    fun getAllShortcuts(): MutableMap<String, String> {
        val jsonStr = prefs.getString(KEY_SHORTCUTS_JSON, null)
        val map = mutableMapOf(
            "/email" to "my.email@gmail.com",
            "/phone" to "+1 555-0199",
            "/addr" to "123 Main Street, Suite 100",
            "/omw" to "On my way!",
            "/brb" to "Be right back!",
            "/thx" to "Thank you so much!"
        )
        if (!jsonStr.isNullOrEmpty()) {
            try {
                val json = JSONObject(jsonStr)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = json.getString(k)
                }
            } catch (_: Exception) {}
        }
        return map
    }

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
    }
}
