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
 * A tile's size in whole grid cells: any width and height from 1 to 4 (1×1 up to 4×4).
 * Small, Medium, Wide and Large are the classic Windows 10 Mobile sizes.
 */
data class TileSize(val cols: Int, val rows: Int) {
    init {
        require(cols in 1..MAX && rows in 1..MAX) { "tile size $cols×$rows" }
    }

    /** Stored form, e.g. "3x2". */
    val name: String get() = "${cols}x$rows"

    val label: String get() = "$cols × $rows"

    val area: Int get() = cols * rows

    /** One cell thick in either direction: room for an icon or a single line only. */
    val isTiny: Boolean get() = cols == 1 || rows == 1

    /** At least 3 cells wide and more than one row: room for extra detail. */
    val isRoomy: Boolean get() = cols >= 3 && rows >= 2

    val isLarge: Boolean get() = cols >= 3 && rows >= 3

    /** Tap on the resize button: the classic W10M cycle, then the closest classic size. */
    fun nextInCycle(): TileSize = when (this) {
        MEDIUM -> SMALL
        SMALL -> WIDE
        WIDE -> LARGE
        LARGE -> MEDIUM
        else -> MEDIUM
    }

    companion object {
        const val MAX = 4
        val SMALL = TileSize(1, 1)
        val MEDIUM = TileSize(2, 2)
        val WIDE = TileSize(4, 2)
        val LARGE = TileSize(4, 4)

        /** Every size, smallest first. */
        val entries: List<TileSize> = (1..MAX).flatMap { r -> (1..MAX).map { c -> TileSize(c, r) } }.sortedBy { it.area }

        /** Parses "3x2", and the old names "SMALL", "MEDIUM", "WIDE", "LARGE". */
        fun valueOf(name: String): TileSize = when (name.uppercase()) {
            "SMALL" -> SMALL
            "MEDIUM" -> MEDIUM
            "WIDE" -> WIDE
            "LARGE" -> LARGE
            else -> {
                val parts = name.lowercase().split('x').map { it.trim().toInt() }
                TileSize(parts[0].coerceIn(1, MAX), parts[1].coerceIn(1, MAX))
            }
        }

        fun of(cols: Int, rows: Int) = TileSize(cols.coerceIn(1, MAX), rows.coerceIn(1, MAX))

        /**
         * Smart auto-grow: the size an app tile earns from how often it is opened. Below medium it
         * grows to medium at 5 launches; medium grows to wide at 30. Never shrinks.
         */
        fun grownFor(current: TileSize, launches: Int): TileSize {
            val earned = when {
                launches >= 30 -> WIDE
                launches >= 5 -> MEDIUM
                else -> current
            }
            return if (earned.area > current.area && earned.cols >= current.cols && earned.rows >= current.rows) earned else current
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
