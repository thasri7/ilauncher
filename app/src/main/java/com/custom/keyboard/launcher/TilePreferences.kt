package com.custom.keyboard.launcher

import android.content.Context
import android.content.SharedPreferences
import com.custom.keyboard.models.TileItem
import com.custom.keyboard.models.TileType
import org.json.JSONArray
import org.json.JSONObject

class TilePreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("metro_launcher_prefs", Context.MODE_PRIVATE)

    var metroTheme: String
        get() = prefs.getString("metro_theme", "cyber_dark") ?: "cyber_dark"
        set(value) = prefs.edit().putString("metro_theme", value).apply()

    var autoGrowEnabled: Boolean
        get() = prefs.getBoolean("auto_grow_enabled", true)
        set(value) = prefs.edit().putBoolean("auto_grow_enabled", value).apply()

    fun loadTiles(): MutableList<TileItem> {
        val raw = prefs.getString("tiles_json", null)
        if (raw.isNullOrEmpty()) {
            return getDefaultTiles()
        }
        val list = mutableListOf<TileItem>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    TileItem(
                        id = obj.getString("id"),
                        type = TileType.valueOf(obj.getString("type")),
                        title = obj.getString("title"),
                        packageName = obj.optString("packageName").takeIf { it.isNotEmpty() },
                        spanX = obj.optInt("spanX", 1),
                        spanY = obj.optInt("spanY", 1),
                        accentColorHex = obj.optString("accentColorHex", "#0078D7"),
                        customSubtitle = obj.optString("customSubtitle", ""),
                        badgeCount = obj.optString("badgeCount", ""),
                        launchCount = obj.optInt("launchCount", 0),
                        contactPhone = obj.optString("contactPhone", "")
                    )
                )
            }
        } catch (_: Exception) {
            return getDefaultTiles()
        }
        return list
    }

    fun saveTiles(tiles: List<TileItem>) {
        val arr = JSONArray()
        for (tile in tiles) {
            val obj = JSONObject().apply {
                put("id", tile.id)
                put("type", tile.type.name)
                put("title", tile.title)
                put("packageName", tile.packageName ?: "")
                put("spanX", tile.spanX)
                put("spanY", tile.spanY)
                put("accentColorHex", tile.accentColorHex)
                put("customSubtitle", tile.customSubtitle)
                put("badgeCount", tile.badgeCount)
                put("launchCount", tile.launchCount)
                put("contactPhone", tile.contactPhone)
            }
            arr.put(obj)
        }
        prefs.edit().putString("tiles_json", arr.toString()).apply()
    }

    fun recordAppLaunch(packageName: String, tiles: MutableList<TileItem>): Boolean {
        var changed = false
        val tile = tiles.firstOrNull { it.packageName == packageName }
        if (tile != null) {
            tile.launchCount++
            // Smart auto-grow: if launched >= 3 times and still small 1x1, grow to wide 2x1!
            if (autoGrowEnabled && tile.launchCount >= 3 && tile.spanX == 1) {
                tile.spanX = 2
                changed = true
            }
            saveTiles(tiles)
        }
        return changed
    }

    fun resetToDefaults(): MutableList<TileItem> {
        val defaults = getDefaultTiles()
        saveTiles(defaults)
        return defaults
    }

    private fun getDefaultTiles(): MutableList<TileItem> {
        return mutableListOf(
            TileItem(
                id = "calendar_tile",
                type = TileType.CALENDAR_BIG,
                title = "Calendar",
                spanX = 2,
                spanY = 1,
                accentColorHex = "#F3F4F6",
                customSubtitle = "Today"
            ),
            TileItem(
                id = "weather_tile",
                type = TileType.WEATHER_LIVE,
                title = "The Weather Channel",
                spanX = 1,
                spanY = 1,
                accentColorHex = "#0078D7",
                customSubtitle = "72° Sunny"
            ),
            TileItem(
                id = "battery_tile",
                type = TileType.BATTERY_STATUS,
                title = "Battery",
                spanX = 1,
                spanY = 1,
                accentColorHex = "#107C41"
            ),
            TileItem(
                id = "search_tile",
                type = TileType.EXPRESS_SEARCH,
                title = "Express Search & Keyboard",
                spanX = 2,
                spanY = 1,
                accentColorHex = "#00B7C3"
            ),
            TileItem(
                id = "storage_tile",
                type = TileType.STORAGE_STATS,
                title = "Device Health",
                spanX = 1,
                spanY = 1,
                accentColorHex = "#8764B8"
            ),
            TileItem(
                id = "kb_settings_tile",
                type = TileType.KEYBOARD_SETTINGS,
                title = "Custom Keyboard",
                customSubtitle = "Customize & Switch IME",
                spanX = 1,
                spanY = 1,
                accentColorHex = "#D83B01"
            )
        )
    }
}
