package com.custom.keyboard.models

enum class TileType {
    CLOCK_WEATHER,
    CALENDAR_BIG,
    WEATHER_LIVE,
    BATTERY_STATUS,
    STORAGE_STATS,
    MEDIA_PLAYER,
    QUICK_CONTACT,
    SECTION_HEADER,
    EXPRESS_SEARCH,
    APP_SHORTCUT,
    KEYBOARD_SETTINGS,
    DEVICE_SETTINGS
}

/**
 * Windows 10 Mobile tile sizes, in grid cells (columns × rows). Every size except SMALL is a
 * whole number of 2×2 blocks, which is what lets the grid pack four small tiles into one block.
 */
enum class TileSize(val cols: Int, val rows: Int, val label: String) {
    SMALL(1, 1, "Small"),
    MEDIUM(2, 2, "Medium"),
    WIDE(4, 2, "Wide"),
    LARGE(4, 4, "Large");

    /** The resize-button cycle from Windows 10 Mobile: medium → small → wide → large → medium. */
    fun nextInCycle(): TileSize = when (this) {
        MEDIUM -> SMALL
        SMALL -> WIDE
        WIDE -> LARGE
        LARGE -> MEDIUM
    }
}

data class TileItem(
    val id: String,
    val type: TileType,
    var title: String,
    var packageName: String? = null,
    var size: TileSize = TileSize.MEDIUM,
    /** Per-tile colour override; null follows the launcher's accent / icon-colour setting. */
    var accentColorHex: String? = null,
    var customSubtitle: String = "",
    var launchCount: Int = 0,
    var contactPhone: String = "",
    var liveEnabled: Boolean = true
)
