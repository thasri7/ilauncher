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

data class TileItem(
    val id: String,
    val type: TileType,
    var title: String,
    var packageName: String? = null,
    var spanX: Int = 1, // 1 (half width) or 2 (full width)
    var spanY: Int = 1, // 1 (normal height) or 2 (double height)
    var accentColorHex: String = "#0078D7",
    var customSubtitle: String = "",
    var badgeCount: String = "",
    var launchCount: Int = 0,
    var contactPhone: String = "",
    var isEditMode: Boolean = false
)
