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
    DEVICE_SETTINGS,
    /** A W10M tile folder; its apps live in [TileItem.children]. */
    FOLDER,
    /** Slideshow of photos the user picked. */
    PHOTOS,
    /** An Android home-screen widget hosted inside a tile. */
    WIDGET
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

    companion object {
        /**
         * Smart auto-grow: the size an app tile earns from how often it is opened. Small starts
         * growing at 5 launches and medium at 30; wide is the largest auto size.
         */
        fun grownFor(current: TileSize, launches: Int): TileSize = when {
            current == SMALL && launches >= 5 -> if (launches >= 30) WIDE else MEDIUM
            current == MEDIUM && launches >= 30 -> WIDE
            else -> current
        }

        /**
         * Maps a tile saved by the old 2-column launcher (spanX/spanY of 1–2 half-width columns)
         * to the closest real tile size.
         */
        fun fromLegacySpans(spanX: Int, spanY: Int): TileSize = when {
            spanX >= 2 && spanY >= 2 -> LARGE
            spanX >= 2 -> WIDE
            else -> MEDIUM
        }
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
    var liveEnabled: Boolean = true,
    /** Set when the user resized the tile themselves; auto-grow never changes it after that. */
    var sizeLocked: Boolean = false,
    /** App shortcut (deep link) id when this app tile opens a shortcut instead of the app. */
    var shortcutId: String? = null,
    /** Bound widget id for WIDGET tiles, or -1. */
    var appWidgetId: Int = -1,
    /** Apps inside a FOLDER tile. */
    val children: MutableList<TileItem> = mutableListOf()
)
