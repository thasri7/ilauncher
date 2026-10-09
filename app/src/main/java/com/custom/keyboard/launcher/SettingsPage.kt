package com.custom.keyboard.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.custom.keyboard.R
import kotlin.math.abs

/** Launcher settings: a category list, each opening its own page (Back returns to the list). */
class SettingsPage(
    private val context: Context,
    private val ui: MetroUi,
    private val prefs: TilePreferences,
    private val metroOverlay: MetroOverlay,
    private val host: Host
) {
    interface Host {
        fun applyLookAndFeel()
        fun applyPageTransition()
        fun showAddTileSheet()
        fun showWidgetPicker()
        fun showIconPackPicker()
        fun requestNotificationAccess()
        fun requestCalendarAccess()
        fun pickBackgroundPicture()
        fun openAccessibilitySettings()
        fun openHomeSettings()
        fun openKeyboardSettings()
        fun exportBackup()
        fun importBackup()
        fun confirmReset()
        fun showWeatherSetup()
        fun setWeatherUnit(unit: String)
        fun applyWallpaperAccent()
        fun showPrivateApps()
        fun applyDrawerSettings()
        fun applySystemBars()
        val privateAppCount: Int
        val weatherLocationLabel: String
        val iconPackLabel: String
        val notificationAccess: Boolean
        val calendarAccess: Boolean
        val gestureServiceEnabled: Boolean
        val hasBackgroundPicture: Boolean
    }

    private data class Section(val key: String, val icon: Int, val title: String, val summary: () -> String)

    private val sections = listOf(
        Section("start", R.drawable.ic_m_apps, "Start", { "Tiles per row, gaps, corners, lock layout" }),
        Section("background", R.drawable.ic_m_photo, "Background", { if (prefs.backgroundMode == "picture") "Picture in tiles" else "Wallpaper · parallax" }),
        Section("colours", R.drawable.ic_m_palette, "Colours & icons", { if (prefs.accentFromWallpaper) "Matching wallpaper · ${host.iconPackLabel}" else host.iconPackLabel }),
        Section("tiles", R.drawable.ic_m_live, "Live tiles & motion", { "Animations, tilt, live tiles, auto-grow" }),
        Section("apps", R.drawable.ic_m_search, "All apps", { "${if (prefs.drawerStyle == "grid") "Grid" else "List"} · private apps" }),
        Section("gestures", R.drawable.ic_m_lock, "Gestures", { "Swipe down, double-tap" }),
        Section("weather", R.drawable.ic_m_sun, "Weather", { host.weatherLocationLabel }),
        Section("privacy", R.drawable.ic_m_notifications, "Live data & permissions", { if (host.notificationAccess) "Notification access on" else "Notification access off" }),
        Section("backup", R.drawable.ic_m_backup, "Backup & reset", { "Save or restore your Start" }),
        Section("launcher", R.drawable.ic_m_home, "Launcher", { "Default home app, keyboard, widgets" })
    )

    /** Shows the category list, or one category's page when [section] is given. */
    fun show(section: String? = null, scrollY: Int = 0, animate: Boolean = true, scrollToColors: Boolean = false) {
        val target = if (scrollToColors) "colours" else section
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, ui.dp(32))
        }
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(page)
        }
        val rerender = { show(target, scroll.scrollY, animate = false) }

        page.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(6), ui.dp(4), ui.dp(6), 0)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_m_back)
                setPadding(ui.dp(11), ui.dp(11), ui.dp(11), ui.dp(11))
                background = ui.ripple()
                contentDescription = "Back"
                layoutParams = LinearLayout.LayoutParams(ui.dp(44), ui.dp(44))
                setOnClickListener { if (target != null) show(null) else metroOverlay.dismiss() }
            })
            addView(ui.text("SETTINGS", 13f, ui.accentText, Typeface.create("sans-serif-medium", Typeface.NORMAL)).apply {
                letterSpacing = 0.1f
                setPadding(ui.dp(6), 0, 0, 0)
            })
        })

        if (target == null) {
            page.addView(ui.pageTitle("settings"))
            sections.forEach { s ->
                page.addView(ui.action(s.icon, s.title, s.summary()) { show(s.key) })
            }
        } else {
            page.addView(ui.pageTitle(sections.firstOrNull { it.key == target }?.title?.lowercase() ?: "settings"))
            when (target) {
                "start" -> start(page)
                "background" -> background(page, rerender)
                "colours" -> colours(page, rerender)
                "tiles" -> tiles(page)
                "apps" -> apps(page)
                "gestures" -> gestures(page)
                "weather" -> weather(page)
                "privacy" -> privacy(page)
                "backup" -> backup(page)
                "launcher" -> launcher(page)
            }
        }

        metroOverlay.show(scroll, MetroOverlay.Style.PANEL, animate = animate, onBack = {
            if (target != null) {
                show(null)
                true
            } else false
        })
        if (animate) MetroMotion.cascadeIn((0 until page.childCount).mapNotNull { page.getChildAt(it) }.take(14), ui.dp(48).toFloat())
        if (scrollY > 0) scroll.post { scroll.scrollTo(0, scrollY) }
    }

    private fun start(page: LinearLayout) {
        page.addView(ui.caption("Tiles per row"))
        page.addView(ui.chips(listOf("4 · Bigger", "6 · More"), if (prefs.columns <= 4) 0 else 1) { i ->
            prefs.columns = if (i == 0) 4 else 6
            host.applyLookAndFeel()
        })
        page.addView(chipSetting("Tile gap", listOf("None", "Thin", "Normal", "Wide"), listOf(0, 2, 4, 8), prefs.gutterDp) { prefs.gutterDp = it })
        page.addView(chipSetting("Corners", listOf("Square", "Soft", "Round"), listOf(0, 6, 14), prefs.cornerRadiusDp) { prefs.cornerRadiusDp = it })
        page.addView(chipSetting("Tile transparency", listOf("Solid", "Light", "Medium", "Glass"), listOf(100, 85, 70, 50), prefs.tileOpacity) { prefs.tileOpacity = it })
        page.addView(ui.toggleRow("Tile labels", "Show names on medium and larger tiles", prefs.showLabels) {
            prefs.showLabels = it
            host.applyLookAndFeel()
        })
        page.addView(ui.toggleRow("Lock Start", "Stop tiles being moved, resized or unpinned by accident", prefs.layoutLocked) { prefs.layoutLocked = it })
        page.addView(ui.toggleRow("Suggested now", "Apps you usually open at this hour, above your tiles", prefs.suggestionsEnabled) {
            prefs.suggestionsEnabled = it
            host.applyLookAndFeel()
        })
        page.addView(ui.toggleRow("Hide status bar", "More room for tiles; swipe down from the top to see it", prefs.hideStatusBar) {
            prefs.hideStatusBar = it
            host.applySystemBars()
        })
        page.addView(ui.action(R.drawable.ic_m_add, "Add tiles") { host.showAddTileSheet() })
    }

    private fun background(page: LinearLayout, rerender: () -> Unit) {
        page.addView(ui.chips(listOf("Wallpaper", "Picture in tiles"), if (prefs.backgroundMode == "picture") 1 else 0) { i ->
            if (i == 1 && !host.hasBackgroundPicture) {
                host.pickBackgroundPicture()
            } else {
                prefs.backgroundMode = if (i == 1) "picture" else "wallpaper"
                host.applyLookAndFeel()
                rerender()
            }
        })
        if (prefs.backgroundMode == "picture") {
            page.addView(ui.caption("The picture shows only through your tiles, like Windows 10 Mobile."))
            page.addView(ui.action(R.drawable.ic_m_photo, "Choose picture") { host.pickBackgroundPicture() })
        } else {
            page.addView(chipSetting("Wallpaper dim", listOf("Off", "Light", "Medium", "Dark"), listOf(0, 20, 35, 55), prefs.wallpaperDim) { prefs.wallpaperDim = it })
            page.addView(ui.toggleRow("Wallpaper parallax", "The wallpaper glides as you scroll and swipe", prefs.wallpaperParallax) { prefs.wallpaperParallax = it })
        }
    }

    private fun colours(page: LinearLayout, rerender: () -> Unit) {
        page.addView(ui.toggleRow("Match wallpaper", "Take the accent colour from your wallpaper", prefs.accentFromWallpaper) {
            prefs.accentFromWallpaper = it
            host.applyWallpaperAccent()
            rerender()
        })
        page.addView(ui.caption("Accent colour"))
        val accents = METRO_ACCENTS.map { Color.parseColor(it.second) }
        page.addView(ui.swatches(accents, prefs.accentColorInt.takeIf { it in accents }) { picked ->
            if (picked != null) {
                prefs.accentColor = String.format("#%06X", 0xFFFFFF and picked)
                // Picking a colour by hand stops following the wallpaper.
                prefs.accentFromWallpaper = false
                host.applyLookAndFeel()
                rerender()
            }
        })
        page.addView(ui.caption("Tile colour"))
        page.addView(ui.chips(listOf("Accent", "From app icon"), if (prefs.tileColorMode == "icon") 1 else 0) { i ->
            prefs.tileColorMode = if (i == 1) "icon" else "accent"
            host.applyLookAndFeel()
        })
        page.addView(ui.action(R.drawable.ic_m_apps, "Icon pack", host.iconPackLabel) { host.showIconPackPicker() })
        page.addView(ui.toggleRow("Themed icons", "White glyph icons for apps that support them (Android 13+)", prefs.themedIcons) {
            prefs.themedIcons = it
            host.applyLookAndFeel()
        })
    }

    private fun tiles(page: LinearLayout) {
        page.addView(ui.toggleRow("Animations", "Turnstile when opening apps and returning to Start", prefs.animationsEnabled) { prefs.animationsEnabled = it })
        page.addView(ui.toggleRow("Tilt effect", "Tiles tilt toward your finger", prefs.tiltEnabled) { prefs.tiltEnabled = it })
        page.addView(ui.toggleRow("Live tiles", "Flip to show messages, alarms, photos and more", prefs.liveTilesEnabled) {
            prefs.liveTilesEnabled = it
            host.applyLookAndFeel()
        })
        page.addView(ui.toggleRow("Smart auto-grow", "Tiles you open often grow (small → medium → wide); sizes you set stay", prefs.autoGrowEnabled) { prefs.autoGrowEnabled = it })
        val transitions = listOf("slide", "cube", "depth")
        page.addView(ui.caption("Swipe to All apps"))
        page.addView(ui.chips(listOf("Slide", "Cube", "Depth"), transitions.indexOf(prefs.pageTransition).coerceAtLeast(0)) { i ->
            prefs.pageTransition = transitions[i]
            host.applyPageTransition()
        })
    }

    private fun apps(page: LinearLayout) {
        page.addView(ui.caption("Layout"))
        page.addView(ui.chips(listOf("List", "Grid"), if (prefs.drawerStyle == "grid") 1 else 0) { i ->
            prefs.drawerStyle = if (i == 1) "grid" else "list"
            host.applyDrawerSettings()
        })
        val sorts = listOf("az", "used", "recent")
        page.addView(ui.caption("Order"))
        page.addView(ui.chips(listOf("A to Z", "Most used", "Newest"), sorts.indexOf(prefs.drawerSort).coerceAtLeast(0)) { i ->
            prefs.drawerSort = sorts[i]
            host.applyDrawerSettings()
        })
        page.addView(ui.toggleRow("Recently added", "Show apps installed in the last few days at the top", prefs.showRecentlyAdded) {
            prefs.showRecentlyAdded = it
            host.applyDrawerSettings()
        })
        page.addView(ui.action(R.drawable.ic_m_lock, "Private apps", "${host.privateAppCount} hidden · unlock to see them") { host.showPrivateApps() })
    }

    private fun gestures(page: LinearLayout) {
        val swipeActions = listOf("notifications", "search", "none")
        page.addView(ui.caption("Swipe down on Start"))
        page.addView(ui.chips(listOf("Notifications", "Search", "Nothing"), swipeActions.indexOf(prefs.swipeDownAction).coerceAtLeast(0)) { i ->
            prefs.swipeDownAction = swipeActions[i]
        })
        val tapActions = listOf("lock", "glance", "none")
        page.addView(ui.caption("Double-tap empty space"))
        page.addView(ui.chips(listOf("Lock screen", "Glance", "Nothing"), tapActions.indexOf(prefs.doubleTapAction).coerceAtLeast(0)) { i ->
            prefs.doubleTapAction = tapActions[i]
        })
        page.addView(ui.action(
            R.drawable.ic_m_lock,
            "Gesture helper",
            if (host.gestureServiceEnabled) "On · lock screen and notifications work everywhere" else "Off · needed for double-tap to lock"
        ) { host.openAccessibilitySettings() })
        page.addView(ui.caption("Glance also works as a screen saver: Settings › Display › Screen saver › Glance."))
        page.addView(ui.caption("Swipe across a tile with unread messages to see them."))
    }

    private fun weather(page: LinearLayout) {
        page.addView(ui.action(R.drawable.ic_m_location, "Location", host.weatherLocationLabel) { host.showWeatherSetup() })
        page.addView(ui.caption("Units"))
        page.addView(ui.chips(listOf("Celsius", "Fahrenheit"), if (prefs.weatherUnit == "F") 1 else 0) { i ->
            host.setWeatherUnit(if (i == 1) "F" else "C")
        })
        page.addView(ui.caption("Weather data by Open-Meteo.com, refreshed every 30 minutes."))
    }

    private fun privacy(page: LinearLayout) {
        page.addView(ui.action(
            R.drawable.ic_m_notifications,
            "Notification access",
            if (host.notificationAccess) "On · counts, message previews and music are live" else "Off · tap to allow unread counts and live previews"
        ) { host.requestNotificationAccess() })
        page.addView(ui.action(
            R.drawable.ic_m_calendar,
            "Calendar events",
            if (host.calendarAccess) "On · the Calendar tile shows what's next" else "Off · tap to show upcoming events"
        ) { host.requestCalendarAccess() })
        page.addView(ui.caption("Notifications, calendar and usage stay on this phone. Only weather uses the internet."))
    }

    private fun backup(page: LinearLayout) {
        page.addView(ui.action(R.drawable.ic_m_backup, "Back up Start", "Save tiles and settings to a file") { host.exportBackup() })
        page.addView(ui.action(R.drawable.ic_m_restore, "Restore Start", "Load a backup file") { host.importBackup() })
        page.addView(ui.action(R.drawable.ic_m_reset, "Reset Start", "Put the default tiles back", danger = true) { host.confirmReset() })
    }

    private fun launcher(page: LinearLayout) {
        page.addView(ui.action(R.drawable.ic_m_home, "Set as default home app", "Needed for app shortcuts and pinning") { host.openHomeSettings() })
        page.addView(ui.action(R.drawable.ic_m_widgets, "Add a widget") { host.showWidgetPicker() })
        page.addView(ui.action(R.drawable.ic_m_keyboard, "Keyboard settings & themes") { host.openKeyboardSettings() })
    }

    private fun chipSetting(title: String, labels: List<String>, values: List<Int>, current: Int, save: (Int) -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.caption(title))
            val selected = values.indexOf(current).takeIf { it >= 0 } ?: values.indices.minBy { abs(values[it] - current) }
            addView(ui.chips(labels, selected) { i ->
                save(values[i])
                host.applyLookAndFeel()
            })
        }
}
