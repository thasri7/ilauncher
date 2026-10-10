package com.custom.keyboard.launcher

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import com.custom.keyboard.models.TileItem
import com.custom.keyboard.models.TileSize
import com.custom.keyboard.models.TileType
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class TilePreferences(context: Context) {
    companion object {
        const val MAIN_SPACE = "main"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences("metro_launcher_prefs", Context.MODE_PRIVATE)

    // ── Start screen look ────────────────────────────────────────────────────────────────
    /** 4 = Windows 10 Mobile default, 6 = "Show more tiles". */
    var columns by intPref("grid_columns", 6)
    var gutterDp by intPref("tile_gutter_dp", 4)
    var cornerRadiusDp by intPref("tile_corner_dp", 0)
    /** Tile opacity in percent; lower values let the wallpaper show through like W10M transparency. */
    var tileOpacity by intPref("tile_opacity", 85)
    /** Black scrim over the wallpaper, in percent. */
    var wallpaperDim by intPref("wallpaper_dim", 35)
    /** "wallpaper": tiles float over the system wallpaper; "picture": a picture shows only through the tiles. */
    var backgroundMode by stringPref("background_mode", "wallpaper")
    var accentColor by stringPref("accent_color", "#0050EF")
    /** Take the accent from the wallpaper's main colour (Android 8.1+). */
    var accentFromWallpaper by boolPref("accent_from_wallpaper", false)
    /** "accent" paints every tile with the accent, "icon" derives each app tile's colour from its icon. */
    var tileColorMode by stringPref("tile_color_mode", "accent")
    var themedIcons by boolPref("themed_icons", true)
    /** Package of the selected icon pack, or "" for system icons. */
    var iconPack by stringPref("icon_pack", "")
    var showLabels by boolPref("show_labels", true)

    // ── Motion ───────────────────────────────────────────────────────────────────────────
    var animationsEnabled by boolPref("animations_enabled", true)
    var tiltEnabled by boolPref("tilt_enabled", true)
    var liveTilesEnabled by boolPref("live_tiles_enabled", true)
    /** "slide", "cube" or "depth". */
    var pageTransition by stringPref("page_transition", "slide")

    // ── Gestures ─────────────────────────────────────────────────────────────────────────
    /** Swipe down at the top of Start: "notifications", "search" or "none". */
    var swipeDownAction by stringPref("gesture_swipe_down", "notifications")
    /** Double-tap on empty Start space: "lock", "glance" or "none". */
    var doubleTapAction by stringPref("gesture_double_tap", "lock")

    // ── Weather ──────────────────────────────────────────────────────────────────────────
    var weatherPlace by stringPref("weather_place", "")
    var weatherLatitude by stringPref("weather_lat", "")
    var weatherLongitude by stringPref("weather_lon", "")
    /** Follow the phone's approximate location instead of a fixed city. */
    var weatherUseDevice by boolPref("weather_use_device", false)
    /** "C" or "F"; defaults to Fahrenheit only where that is the norm. */
    var weatherUnit by stringPref("weather_unit", if (Locale.getDefault().country in setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW")) "F" else "C")
    var weatherCache by stringPref("weather_cache", "")

    // ── Behaviour ────────────────────────────────────────────────────────────────────────
    /** Locks Start so tiles can't be moved, resized or unpinned by accident. */
    var layoutLocked by boolPref("layout_locked", false)
    /** Moves the wallpaper as Start scrolls and pages (when the wallpaper supports it). */
    var wallpaperParallax by boolPref("wallpaper_parallax", true)
    var hideStatusBar by boolPref("hide_status_bar", false)
    /** All apps: "list" or "grid". */
    var drawerStyle by stringPref("drawer_style", "list")
    /** All apps order: "az", "used" or "recent". */
    var drawerSort by stringPref("drawer_sort", "az")
    var showRecentlyAdded by boolPref("show_recently_added", true)
    /** "Suggested now" strip at the top of Start. */
    var suggestionsEnabled by boolPref("suggestions_enabled", true)
    var autoGrowEnabled by boolPref("auto_grow_enabled", true)
    /** "flat" tiles, or "glass": a frosted sheen and a hairline edge on every tile. */
    var tileFinish by stringPref("tile_finish", "flat")
    /** How often live tiles flip: "slow", "normal" or "fast". */
    var liveSpeed by stringPref("live_speed", "normal")
    /** Pause live tiles, parallax and stack rotation while Android's battery saver is on. */
    var batterySaverPause by boolPref("battery_saver_pause", true)
    /** While charging, Start turns into Glance after a minute without touches. */
    var nightstand by boolPref("nightstand", false)
    /** Web search: "google", "bing", "duckduckgo", "brave" or "ecosia". */
    var searchEngine by stringPref("search_engine", "google")
    /** New folders and stacks are named after what their apps have in common. */
    var folderAutoName by boolPref("folder_auto_name", true)
    /** Two-finger swipe down on Start: "search", "notifications", "overview" or "none". */
    var twoFingerAction by stringPref("gesture_two_finger", "search")
    /** Pinch in on Start: "overview", "settings" or "none". */
    var pinchAction by stringPref("gesture_pinch", "overview")
    /** Swipe right on Start: "space" (next space), "search" or "none". */
    var swipeRightAction by stringPref("gesture_swipe_right", "space")
    var stepGoal by intPref("step_goal", 8000)

    // ── Text page (AP15-style names page) ─────────────────────────────────────────────
    var textPageEnabled by boolPref("text_page", true)
    /** "az", "use" or "size". */
    var textOrder by stringPref("text_order", "az")
    var textMinSp by intPref("text_min_sp", 14)
    var textMaxSp by intPref("text_max_sp", 40)
    /** How fast unused apps shrink: "slow", "normal" or "fast". */
    var textShrink by stringPref("text_shrink", "normal")
    /** "accent" (top apps in accent), "white" or "app" (each app's own colour). */
    var textColor by stringPref("text_color", "accent")
    /** "light", "regular" or "bold". */
    var textFont by stringPref("text_font", "light")
    /** "lower", "upper" or "asis". */
    var textCase by stringPref("text_case", "lower")
    /** "start", "center" or "end". */
    var textAlign by stringPref("text_align", "start")
    var textSpacingDp by intPref("text_spacing_dp", 10)
    var textLetterStrip by boolPref("text_letter_strip", true)
    /** "use" (names grow with use) or "equal" (every name the same size). */
    var textSizing by stringPref("text_sizing", "use")
    /** Opacity of names in percent. */
    var textOpacity by intPref("text_opacity", 100)
    var textShadow by boolPref("text_shadow", true)
    /** Behind the Text page: "wallpaper", "dim" or "black". */
    var textBackground by stringPref("text_background", "wallpaper")
    /** Mark apps that have notifications. */
    var textNotify by boolPref("text_notify", true)

    // ── Icons, names, locks ─────────────────────────────────────────────────────────────
    /** "system", "circle", "squircle", "rounded" or "teardrop" for apps with adaptive icons. */
    var iconShape by stringPref("icon_shape", "system")

    private fun mapPref(key: String): Map<String, String> = runCatching {
        val o = JSONObject(prefs.getString(key, "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())

    private fun putMapEntry(key: String, entry: String, value: String?) {
        val o = runCatching { JSONObject(prefs.getString(key, "{}") ?: "{}") }.getOrDefault(JSONObject())
        if (value.isNullOrEmpty()) o.remove(entry) else o.put(entry, value)
        prefs.edit().putString(key, o.toString()).apply()
    }

    /** App key → "pack:<pack>:<drawable>" or "file" (a picture saved for it). */
    fun iconOverrides(): Map<String, String> = mapPref("icon_overrides")
    fun setIconOverride(app: String, spec: String?) = putMapEntry("icon_overrides", app, spec)

    /** App key → the name the user gave it (All apps, search, Text page, new tiles). */
    fun labelOverrides(): Map<String, String> = mapPref("label_overrides")
    fun setLabelOverride(app: String, name: String?) = putMapEntry("label_overrides", app, name)

    /** Apps that need fingerprint, face or PIN to open from the launcher. */
    var lockedApps: Set<String>
        get() = prefs.getStringSet("locked_apps", emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet("locked_apps", value).apply()

    // ── Today page ──────────────────────────────────────────────────────────────────────
    var todayEnabled by boolPref("today_page", true)
    /** Cards on the Today page, in order; a card not listed is off. */
    var todayCards by stringPref("today_cards", "weather,agenda,hub,alarm,screen,steps,photos,note,battery")
    var todayNote by stringPref("today_note", "")

    // ── Hub ─────────────────────────────────────────────────────────────────────────────
    /** Days the Hub keeps messages. */
    var hubKeepDays by intPref("hub_keep_days", 7)

    /** Apps left out of the Hub. */
    var hubMuted: Set<String>
        get() = prefs.getStringSet("hub_muted", emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet("hub_muted", value).apply()

    /** Per-app Text page settings: custom name, colour, fixed size, hidden. */
    data class TextOverride(val name: String = "", val color: String = "", val level: Int = -1, val hidden: Boolean = false) {
        val isDefault get() = name.isEmpty() && color.isEmpty() && level < 0 && !hidden
    }

    fun textOverrides(): Map<String, TextOverride> = runCatching {
        val o = JSONObject(prefs.getString("text_overrides", "{}") ?: "{}")
        o.keys().asSequence().associateWith { k ->
            val v = o.getJSONObject(k)
            TextOverride(v.optString("name"), v.optString("color"), v.optInt("level", -1), v.optBoolean("hidden"))
        }
    }.getOrDefault(emptyMap())

    fun setTextOverride(pkg: String, value: TextOverride) {
        val o = runCatching { JSONObject(prefs.getString("text_overrides", "{}") ?: "{}") }.getOrDefault(JSONObject())
        if (value.isDefault) o.remove(pkg) else o.put(pkg, JSONObject()
            .put("name", value.name).put("color", value.color).put("level", value.level).put("hidden", value.hidden))
        prefs.edit().putString("text_overrides", o.toString()).apply()
    }
    var textSearch by boolPref("text_search", true)

    /** Days for an unused app's weight to halve on the Text page. */
    val textHalfLifeDays: Double
        get() = when (textShrink) {
            "slow" -> 21.0
            "fast" -> 4.0
            else -> 10.0
        }

    /**
     * How much each app is used lately: every launch adds 1, and the total halves every
     * [halfLifeDays] without use. Apps launched before this was kept start from their count.
     */
    fun heat(now: Long = System.currentTimeMillis(), halfLifeDays: Double = textHalfLifeDays): Map<String, Double> {
        val raw = prefs.getString("heat_json", null)
        if (raw == null) return usageCounts().mapValues { it.value.toDouble() }
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { k ->
                val a = o.getJSONArray(k)
                TextCloud.decay(a.getDouble(0), a.getLong(1), now, halfLifeDays)
            }
        }.getOrDefault(emptyMap())
    }

    private fun recordHeat(packageName: String, now: Long) {
        val o = runCatching { JSONObject(prefs.getString("heat_json", null) ?: seedHeat(now)) }.getOrDefault(JSONObject())
        val old = o.optJSONArray(packageName)
        // Stored at the slowest rate so changing the shrink speed later still works.
        val score = if (old == null) 0.0 else TextCloud.decay(old.getDouble(0), old.getLong(1), now, 21.0)
        o.put(packageName, JSONArray().put(score + 1.0).put(now))
        prefs.edit().putString("heat_json", o.toString()).apply()
    }

    /** First run: start every app's weight from its launch count so far. */
    private fun seedHeat(now: Long): String {
        val o = JSONObject()
        usageCounts().forEach { (pkg, n) -> o.put(pkg, JSONArray().put(n.toDouble()).put(now)) }
        return o.toString()
    }

    fun resetHeat() = prefs.edit().putString("heat_json", "{}").apply()

    /** Set once the first-run app tiles were added, so unpinning them all doesn't bring them back. */
    var appsSeeded by boolPref("apps_seeded", false)

    val accentColorInt: Int
        get() = try { Color.parseColor(accentColor) } catch (_: Exception) { Color.parseColor("#0050EF") }

    // ── Spaces: several Start screens, each with its own tiles ───────────────────────────

    data class Space(val id: String, val name: String)

    var currentSpace by stringPref("current_space", MAIN_SPACE)

    fun spaces(): List<Space> {
        val list = runCatching {
            val arr = JSONArray(prefs.getString("spaces_json", "[]"))
            (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Space(o.getString("id"), o.optString("name")) } }
        }.getOrDefault(emptyList())
        return if (list.none { it.id == MAIN_SPACE }) listOf(Space(MAIN_SPACE, "start")) + list else list
    }

    fun saveSpaces(spaces: List<Space>) {
        val arr = JSONArray()
        spaces.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name)) }
        prefs.edit().putString("spaces_json", arr.toString()).apply()
    }

    fun deleteSpaceTiles(id: String) {
        if (id != MAIN_SPACE) prefs.edit().remove(tilesKey(id)).apply()
    }

    private fun tilesKey(space: String) = if (space == MAIN_SPACE) "tiles_json" else "tiles_json_$space"

    /** Tiles of every space, for "is this app pinned anywhere" and clean-up. */
    fun tilesOf(space: String): MutableList<TileItem> {
        val raw = prefs.getString(tilesKey(space), null) ?: return mutableListOf()
        return runCatching { parseTiles(JSONArray(raw)) }.getOrDefault(mutableListOf())
    }

    fun saveTilesOf(space: String, tiles: List<TileItem>) {
        prefs.edit().putString(tilesKey(space), tilesToJson(tiles).toString()).apply()
    }

    // ── Themes ──────────────────────────────────────────────────────────────────────────

    /** Saved looks as JSON objects (see [MetroThemes]). */
    var savedThemesJson by stringPref("themes_json", "[]")

    // ── Steps: the step counter counts since boot, so remember where today started ──────

    var stepsDay by stringPref("steps_day", "")
    var stepsBaseline by intPref("steps_baseline", -1)
    var stepsLast by intPref("steps_last", -1)

    fun loadTiles(): MutableList<TileItem> {
        val raw = prefs.getString(tilesKey(currentSpace), null)
        if (raw.isNullOrEmpty()) return if (currentSpace == MAIN_SPACE) getDefaultTiles() else mutableListOf()
        return try {
            parseTiles(JSONArray(raw))
        } catch (_: Exception) {
            getDefaultTiles()
        }
    }

    private fun parseTiles(arr: JSONArray): MutableList<TileItem> {
        val list = mutableListOf<TileItem>()
        for (i in 0 until arr.length()) {
            parseTile(arr.getJSONObject(i))?.let { list.add(it) }
        }
        return list
    }

    private fun parseTile(obj: JSONObject): TileItem? {
        val type = runCatching { TileType.valueOf(obj.getString("type")) }.getOrNull() ?: return null
        // Layouts saved before the 4/6-column grid only had spanX/spanY on a 2-column grid and
        // hard-coded colours; migrate them to real tile sizes and let them follow the accent.
        val isLegacy = !obj.has("size")
        val size = if (isLegacy) {
            TileSize.fromLegacySpans(obj.optInt("spanX", 1), obj.optInt("spanY", 1))
        } else {
            runCatching { TileSize.valueOf(obj.getString("size")) }.getOrDefault(TileSize.MEDIUM)
        }
        val color = if (isLegacy) null else obj.optString("accentColorHex", "").takeIf { it.isNotEmpty() }
        val tile = TileItem(
            id = obj.getString("id"),
            type = type,
            title = obj.optString("title", ""),
            packageName = obj.optString("packageName").takeIf { it.isNotEmpty() },
            size = size,
            accentColorHex = color,
            customSubtitle = obj.optString("customSubtitle", ""),
            launchCount = obj.optInt("launchCount", 0),
            contactPhone = obj.optString("contactPhone", ""),
            liveEnabled = obj.optBoolean("liveEnabled", true),
            sizeLocked = obj.optBoolean("sizeLocked", false),
            shortcutId = obj.optString("shortcutId").takeIf { it.isNotEmpty() },
            appWidgetId = obj.optInt("appWidgetId", -1)
        )
        obj.optJSONArray("children")?.let { tile.children.addAll(parseTiles(it)) }
        obj.optJSONObject("extras")?.let { ex -> ex.keys().forEach { k -> tile.extras[k] = ex.optString(k) } }
        return tile
    }

    private fun tilesToJson(tiles: List<TileItem>): JSONArray {
        val arr = JSONArray()
        for (tile in tiles) {
            val obj = JSONObject().apply {
                put("id", tile.id)
                put("type", tile.type.name)
                put("title", tile.title)
                put("packageName", tile.packageName ?: "")
                put("size", tile.size.name)
                put("accentColorHex", tile.accentColorHex ?: "")
                put("customSubtitle", tile.customSubtitle)
                put("launchCount", tile.launchCount)
                put("contactPhone", tile.contactPhone)
                put("liveEnabled", tile.liveEnabled)
                put("sizeLocked", tile.sizeLocked)
                put("shortcutId", tile.shortcutId ?: "")
                put("appWidgetId", tile.appWidgetId)
                if (tile.children.isNotEmpty()) put("children", tilesToJson(tile.children))
                if (tile.extras.isNotEmpty()) put("extras", JSONObject(tile.extras as Map<*, *>))
            }
            arr.put(obj)
        }
        return arr
    }

    fun saveTiles(tiles: List<TileItem>) {
        prefs.edit().putString(tilesKey(currentSpace), tilesToJson(tiles).toString()).apply()
    }

    /** Launch counts for every app, pinned or not; feeds "Most used" in All apps. */
    fun usageCounts(): Map<String, Int> {
        val raw = prefs.getString("usage_json", null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { obj.optInt(it, 0) }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Packages hidden from All apps and search; shown only in Private apps after unlocking. */
    var hiddenApps: Set<String>
        get() = prefs.getStringSet("hidden_apps", emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet("hidden_apps", value).apply()

    /** Launches per app for each hour of the day (24 buckets), for "Suggested now". */
    fun hourlyUsage(): Map<String, IntArray> {
        val raw = prefs.getString("hourly_usage_json", null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { key ->
                val arr = obj.getJSONArray(key)
                IntArray(24) { arr.optInt(it) }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun recordHour(packageName: String, hour: Int) {
        val usage = hourlyUsage().toMutableMap()
        val buckets = usage[packageName] ?: IntArray(24)
        buckets[hour.coerceIn(0, 23)]++
        usage[packageName] = buckets
        val obj = JSONObject()
        usage.forEach { (pkg, b) -> obj.put(pkg, JSONArray(b.toList())) }
        prefs.edit().putString("hourly_usage_json", obj.toString()).apply()
    }

    /**
     * Records a launch. Returns the tile that auto-grew because it is used a lot (small → medium
     * → wide, never for tiles the user sized themselves), or null when nothing changed size.
     */
    fun recordAppLaunch(packageName: String, tiles: MutableList<TileItem>): TileItem? {
        val usage = usageCounts().toMutableMap()
        usage[packageName] = (usage[packageName] ?: 0) + 1
        prefs.edit().putString("usage_json", JSONObject(usage).toString()).apply()
        recordHour(packageName, Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
        recordHeat(packageName, System.currentTimeMillis())

        val tile = tiles.firstOrNull { it.packageName == packageName && it.shortcutId == null } ?: return null
        tile.launchCount++
        var grown: TileItem? = null
        val earned = TileSize.grownFor(tile.size, tile.launchCount)
        if (autoGrowEnabled && !tile.sizeLocked && earned != tile.size) {
            tile.size = earned
            grown = tile
        }
        saveTiles(tiles)
        return grown
    }

    // ── Shortcuts pinned by other apps (e.g. "Add to Home screen" in a browser) ──────────

    data class PendingPin(val packageName: String, val shortcutId: String, val label: String)

    fun queuePinnedShortcut(pin: PendingPin) {
        val arr = runCatching { JSONArray(prefs.getString("pending_pins", "[]")) }.getOrDefault(JSONArray())
        arr.put(JSONObject().put("pkg", pin.packageName).put("id", pin.shortcutId).put("label", pin.label))
        prefs.edit().putString("pending_pins", arr.toString()).apply()
    }

    fun takePendingPins(): List<PendingPin> {
        val raw = prefs.getString("pending_pins", null) ?: return emptyList()
        prefs.edit().remove("pending_pins").apply()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PendingPin(o.getString("pkg"), o.getString("id"), o.optString("label"))
            }
        }.getOrDefault(emptyList())
    }

    // ── Backup & restore ─────────────────────────────────────────────────────────────────

    /** Every launcher setting plus the tile layout, as JSON. Pictures and widgets are not included. */
    fun exportJson(): String {
        val settings = JSONObject()
        for ((key, value) in prefs.all) {
            if (key == "pending_pins") continue
            val typed = when (value) {
                is Boolean -> JSONObject().put("t", "b").put("v", value)
                is Int -> JSONObject().put("t", "i").put("v", value)
                is Long -> JSONObject().put("t", "l").put("v", value)
                is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                is String -> JSONObject().put("t", "s").put("v", value)
                else -> null
            }
            if (typed != null) settings.put(key, typed)
        }
        return JSONObject()
            .put("format", "ilauncher-start")
            .put("version", 2)
            .put("settings", settings)
            .toString(2)
    }

    /**
     * Replaces settings and layout with a backup made by [exportJson]. Widget tiles are dropped
     * because widget ids belong to this install. Returns false if the file isn't a backup.
     */
    fun importJson(raw: String): Boolean {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return false
        if (root.optString("format") != "ilauncher-start") return false
        val settings = root.optJSONObject("settings") ?: return false
        val editor = prefs.edit().clear()
        for (key in settings.keys()) {
            val typed = settings.optJSONObject(key) ?: continue
            when (typed.optString("t")) {
                "b" -> editor.putBoolean(key, typed.optBoolean("v"))
                "i" -> editor.putInt(key, typed.optInt("v"))
                "l" -> editor.putLong(key, typed.optLong("v"))
                "f" -> editor.putFloat(key, typed.optDouble("v").toFloat())
                "s" -> editor.putString(key, typed.optString("v"))
            }
        }
        editor.putBoolean("apps_seeded", true)
        editor.apply()
        // Widget ids belong to the old install; drop those tiles in every space.
        spaces().forEach { space ->
            saveTilesOf(space.id, tilesOf(space.id).filter { it.type != TileType.WIDGET })
        }
        return true
    }

    fun resetToDefaults(): MutableList<TileItem> {
        val defaults = getDefaultTiles()
        saveTiles(defaults)
        return defaults
    }

    private fun getDefaultTiles(): MutableList<TileItem> = mutableListOf(
        TileItem(id = "clock_tile", type = TileType.CLOCK_WEATHER, title = "Clock", size = TileSize.WIDE),
        TileItem(id = "calendar_tile", type = TileType.CALENDAR_BIG, title = "Calendar", size = TileSize.MEDIUM),
        TileItem(id = "weather_tile", type = TileType.WEATHER_LIVE, title = "Weather", size = TileSize.WIDE),
        TileItem(id = "battery_tile", type = TileType.BATTERY_STATUS, title = "Battery", size = TileSize.MEDIUM),
        TileItem(id = "search_tile", type = TileType.EXPRESS_SEARCH, title = "Search", size = TileSize.WIDE),
        TileItem(id = "media_tile", type = TileType.MEDIA_PLAYER, title = "Music", size = TileSize.WIDE),
        TileItem(id = "storage_tile", type = TileType.STORAGE_STATS, title = "Device", size = TileSize.MEDIUM),
        TileItem(
            id = "kb_settings_tile",
            type = TileType.KEYBOARD_SETTINGS,
            title = "Keyboard",
            customSubtitle = "Themes & layouts",
            size = TileSize.SMALL
        ),
        TileItem(id = "settings_tile", type = TileType.DEVICE_SETTINGS, title = "Settings", size = TileSize.SMALL)
    )

    private fun intPref(key: String, default: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Int = prefs.getInt(key, default)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
            prefs.edit().putInt(key, value).apply()
        }
    }

    private fun boolPref(key: String, default: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = prefs.getBoolean(key, default)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            prefs.edit().putBoolean(key, value).apply()
        }
    }

    private fun stringPref(key: String, default: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): String = prefs.getString(key, default) ?: default
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
            prefs.edit().putString(key, value).apply()
        }
    }
}
