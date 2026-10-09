package com.custom.keyboard.launcher

import org.json.JSONArray
import org.json.JSONObject

/**
 * Looks you can switch between in one tap: a few built-in ones plus any the user saves. A theme
 * holds only how Start looks (accent, tiles, corners, gaps, dim, labels), never the tiles
 * themselves, so applying one never moves anything. Themes can be shared as small JSON files.
 */
object MetroThemes {

    data class Theme(
        val name: String,
        val accent: String,
        val opacity: Int,
        val corners: Int,
        val gutter: Int,
        val dim: Int,
        val finish: String,
        val colorMode: String,
        val labels: Boolean,
        val columns: Int,
        val builtIn: Boolean = false
    )

    val builtIns = listOf(
        Theme("Metro", "#0050EF", 85, 0, 4, 35, "flat", "accent", true, 6, builtIn = true),
        Theme("Lumia", "#A4C400", 100, 0, 4, 35, "flat", "accent", true, 4, builtIn = true),
        Theme("Glass", "#1BA1E2", 45, 14, 6, 20, "glass", "accent", true, 6, builtIn = true),
        Theme("Midnight", "#647687", 92, 6, 4, 55, "flat", "accent", true, 6, builtIn = true),
        Theme("Colourful", "#E51400", 90, 6, 4, 35, "flat", "icon", true, 6, builtIn = true),
        Theme("Soft", "#AA00FF", 75, 14, 8, 25, "glass", "accent", true, 4, builtIn = true),
        Theme("Minimal", "#000000", 30, 14, 8, 10, "glass", "accent", false, 6, builtIn = true)
    )

    fun current(prefs: TilePreferences, name: String) = Theme(
        name = name,
        accent = prefs.accentColor,
        opacity = prefs.tileOpacity,
        corners = prefs.cornerRadiusDp,
        gutter = prefs.gutterDp,
        dim = prefs.wallpaperDim,
        finish = prefs.tileFinish,
        colorMode = prefs.tileColorMode,
        labels = prefs.showLabels,
        columns = prefs.columns
    )

    fun apply(prefs: TilePreferences, t: Theme) {
        prefs.accentColor = t.accent
        prefs.accentFromWallpaper = false
        prefs.tileOpacity = t.opacity
        prefs.cornerRadiusDp = t.corners
        prefs.gutterDp = t.gutter
        prefs.wallpaperDim = t.dim
        prefs.tileFinish = t.finish
        prefs.tileColorMode = t.colorMode
        prefs.showLabels = t.labels
        prefs.columns = t.columns
    }

    /** True when Start currently looks exactly like [t]. */
    fun matches(prefs: TilePreferences, t: Theme): Boolean {
        val c = current(prefs, t.name)
        return c.accent.equals(t.accent, ignoreCase = true) &&
            c.copy(accent = t.accent, builtIn = t.builtIn) == t
    }

    fun toJson(t: Theme): JSONObject = JSONObject()
        .put("format", "ilauncher-theme")
        .put("name", t.name)
        .put("accent", t.accent)
        .put("opacity", t.opacity)
        .put("corners", t.corners)
        .put("gutter", t.gutter)
        .put("dim", t.dim)
        .put("finish", t.finish)
        .put("colorMode", t.colorMode)
        .put("labels", t.labels)
        .put("columns", t.columns)

    /** Reads a theme; values out of range are clamped so a hand-edited file can't break Start. */
    fun fromJson(o: JSONObject): Theme? {
        if (o.optString("format", "ilauncher-theme") != "ilauncher-theme") return null
        val accent = o.optString("accent").takeIf { Regex("#[0-9A-Fa-f]{6}").matches(it) } ?: return null
        return Theme(
            name = o.optString("name").trim().ifEmpty { "Theme" }.take(40),
            accent = accent.uppercase(),
            opacity = o.optInt("opacity", 85).coerceIn(20, 100),
            corners = o.optInt("corners", 0).coerceIn(0, 24),
            gutter = o.optInt("gutter", 4).coerceIn(0, 12),
            dim = o.optInt("dim", 35).coerceIn(0, 90),
            finish = if (o.optString("finish") == "glass") "glass" else "flat",
            colorMode = if (o.optString("colorMode") == "icon") "icon" else "accent",
            labels = o.optBoolean("labels", true),
            columns = if (o.optInt("columns", 6) <= 4) 4 else 6
        )
    }

    fun saved(prefs: TilePreferences): List<Theme> = runCatching {
        val arr = JSONArray(prefs.savedThemesJson)
        (0 until arr.length()).mapNotNull { fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun save(prefs: TilePreferences, theme: Theme) {
        val list = saved(prefs).filter { !it.name.equals(theme.name, ignoreCase = true) } + theme
        prefs.savedThemesJson = JSONArray().apply { list.forEach { put(toJson(it)) } }.toString()
    }

    fun delete(prefs: TilePreferences, theme: Theme) {
        val list = saved(prefs).filter { it.name != theme.name }
        prefs.savedThemesJson = JSONArray().apply { list.forEach { put(toJson(it)) } }.toString()
    }
}
