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

/** The full-screen launcher settings page. */
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
        fun applyWallpaperAccent()
        fun showPrivateApps()
        val privateAppCount: Int
        fun setWeatherUnit(unit: String)
        val weatherLocationLabel: String
        val iconPackLabel: String
        val notificationAccess: Boolean
        val calendarAccess: Boolean
        val gestureServiceEnabled: Boolean
        val hasBackgroundPicture: Boolean
    }

    fun show(scrollToColors: Boolean = false, scrollY: Int = 0, animate: Boolean = true) {
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, ui.dp(32))
        }
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(page)
        }
        val rerender = { show(scrollY = scroll.scrollY, animate = false) }
        fun applyAndRerender() {
            host.applyLookAndFeel()
            rerender()
        }

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
                setOnClickListener { metroOverlay.dismiss() }
            })
            addView(ui.text("SETTINGS", 13f, face = Typeface.create("sans-serif-medium", Typeface.NORMAL)).apply {
                letterSpacing = 0.1f
                setPadding(ui.dp(6), 0, 0, 0)
            })
        })
        page.addView(ui.pageTitle("start"))

        // ── Layout
        page.addView(ui.sectionTitle("Layout"))
        page.addView(ui.caption("Tiles per row"))
        page.addView(ui.chips(listOf("4 · Bigger tiles", "6 · More tiles"), if (prefs.columns <= 4) 0 else 1) { i ->
            prefs.columns = if (i == 0) 4 else 6
            host.applyLookAndFeel()
        })
        page.addView(chipSetting("Tile gap", listOf("None", "Thin", "Normal", "Wide"), listOf(0, 2, 4, 8), prefs.gutterDp) { prefs.gutterDp = it })
        page.addView(chipSetting("Corners", listOf("Square", "Soft", "Round"), listOf(0, 6, 14), prefs.cornerRadiusDp) { prefs.cornerRadiusDp = it })
        page.addView(chipSetting("Tile transparency", listOf("Solid", "Light", "Medium", "Glass"), listOf(100, 85, 70, 50), prefs.tileOpacity) { prefs.tileOpacity = it })
        page.addView(ui.toggleRow("Tile labels", "Show app names on medium and larger tiles", prefs.showLabels) {
            prefs.showLabels = it
            host.applyLookAndFeel()
        })

        // ── Background
        page.addView(ui.sectionTitle("Background"))
        page.addView(ui.chips(listOf("Wallpaper", "Picture in tiles"), if (prefs.backgroundMode == "picture") 1 else 0) { i ->
            if (i == 1 && !host.hasBackgroundPicture) {
                host.pickBackgroundPicture()
            } else {
                prefs.backgroundMode = if (i == 1) "picture" else "wallpaper"
                applyAndRerender()
            }
        })
        if (prefs.backgroundMode == "picture") {
            page.addView(ui.caption("The picture shows only through your tiles, like Windows 10 Mobile."))
            page.addView(ui.action(R.drawable.ic_m_photo, "Choose picture") { host.pickBackgroundPicture() })
        } else {
            page.addView(chipSetting("Wallpaper dim", listOf("Off", "Light", "Medium", "Dark"), listOf(0, 20, 35, 55), prefs.wallpaperDim) { prefs.wallpaperDim = it })
        }

        // ── Colours & icons
        val colorsTop = page.childCount
        page.addView(ui.sectionTitle("Colours & icons"))
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
                applyAndRerender()
            }
        })
        page.addView(ui.caption("Tile colour"))
        page.addView(ui.chips(listOf("Accent colour", "From app icon"), if (prefs.tileColorMode == "icon") 1 else 0) { i ->
            prefs.tileColorMode = if (i == 1) "icon" else "accent"
            host.applyLookAndFeel()
        })
        page.addView(ui.action(R.drawable.ic_m_apps, "Icon pack", host.iconPackLabel) { host.showIconPackPicker() })
        page.addView(ui.toggleRow("Themed icons", "White glyph icons for apps that support them (Android 13+)", prefs.themedIcons) {
            prefs.themedIcons = it
            host.applyLookAndFeel()
        })

        // ── Motion
        page.addView(ui.sectionTitle("Motion"))
        page.addView(ui.toggleRow("Animations", "Turnstile when opening apps and returning to Start", prefs.animationsEnabled) { prefs.animationsEnabled = it })
        page.addView(ui.toggleRow("Tilt effect", "Tiles tilt toward your finger", prefs.tiltEnabled) { prefs.tiltEnabled = it })
        page.addView(ui.toggleRow("Live tiles", "Flip to show notifications, alarms, photos and more", prefs.liveTilesEnabled) {
            prefs.liveTilesEnabled = it
            host.applyLookAndFeel()
        })
        val transitions = listOf("slide", "cube", "depth")
        page.addView(ui.caption("Swipe to All apps"))
        page.addView(ui.chips(listOf("Slide", "Cube", "Depth"), transitions.indexOf(prefs.pageTransition).coerceAtLeast(0)) { i ->
            prefs.pageTransition = transitions[i]
            host.applyPageTransition()
        })

        // ── Gestures
        page.addView(ui.sectionTitle("Gestures"))
        val swipeActions = listOf("notifications", "search", "none")
        page.addView(ui.caption("Swipe down on Start"))
        page.addView(ui.chips(listOf("Notifications", "Search", "Nothing"), swipeActions.indexOf(prefs.swipeDownAction).coerceAtLeast(0)) { i ->
            prefs.swipeDownAction = swipeActions[i]
        })
        page.addView(ui.caption("Double-tap empty space"))
        val tapActions = listOf("lock", "glance", "none")
        page.addView(ui.chips(listOf("Lock screen", "Glance", "Nothing"), tapActions.indexOf(prefs.doubleTapAction).coerceAtLeast(0)) { i ->
            prefs.doubleTapAction = tapActions[i]
        })
        page.addView(ui.caption("Glance also works as a screen saver: Settings › Display › Screen saver › Glance."))
        page.addView(ui.action(
            R.drawable.ic_m_lock,
            "Gesture helper",
            if (host.gestureServiceEnabled) "On · lock screen and notifications work everywhere" else "Off · needed for double-tap to lock"
        ) { host.openAccessibilitySettings() })

        // ── Weather
        page.addView(ui.sectionTitle("Weather"))
        page.addView(ui.action(R.drawable.ic_m_location, "Location", host.weatherLocationLabel) { host.showWeatherSetup() })
        page.addView(ui.caption("Units"))
        page.addView(ui.chips(listOf("Celsius", "Fahrenheit"), if (prefs.weatherUnit == "F") 1 else 0) { i ->
            host.setWeatherUnit(if (i == 1) "F" else "C")
        })

        // ── Live data
        page.addView(ui.sectionTitle("Live data"))
        page.addView(ui.action(
            R.drawable.ic_m_notifications,
            "Notification access",
            if (host.notificationAccess) "On · counts, message previews and music are live" else "Off · tap to allow unread counts and live previews"
        ) { host.requestNotificationAccess() })
        page.addView(ui.action(
            R.drawable.ic_m_calendar,
            "Calendar events",
            if (host.calendarAccess) "On · the Calendar tile shows what's next" else "Off · tap to show upcoming events on the Calendar tile"
        ) { host.requestCalendarAccess() })
        page.addView(ui.toggleRow("Smart auto-grow", "Tiles you open often grow (small → medium → wide); sizes you set stay", prefs.autoGrowEnabled) { prefs.autoGrowEnabled = it })
        page.addView(ui.toggleRow("Suggested now", "Apps you usually open at this hour, above your tiles", prefs.suggestionsEnabled) {
            prefs.suggestionsEnabled = it
            host.applyLookAndFeel()
        })
        page.addView(ui.action(R.drawable.ic_m_lock, "Private apps", "${host.privateAppCount} hidden · unlock to see them") { host.showPrivateApps() })

        // ── Launcher
        page.addView(ui.sectionTitle("Launcher"))
        page.addView(ui.action(R.drawable.ic_m_add, "Add tiles") { host.showAddTileSheet() })
        page.addView(ui.action(R.drawable.ic_m_widgets, "Add a widget") { host.showWidgetPicker() })
        page.addView(ui.action(R.drawable.ic_m_home, "Set as default home app", "Needed for app shortcuts and pinning") { host.openHomeSettings() })
        page.addView(ui.action(R.drawable.ic_m_keyboard, "Keyboard settings & themes") { host.openKeyboardSettings() })
        page.addView(ui.action(R.drawable.ic_m_backup, "Back up Start", "Save tiles and settings to a file") { host.exportBackup() })
        page.addView(ui.action(R.drawable.ic_m_restore, "Restore Start", "Load a backup file") { host.importBackup() })
        page.addView(ui.action(R.drawable.ic_m_reset, "Reset Start", "Put the default tiles back", danger = true) { host.confirmReset() })

        metroOverlay.show(scroll, MetroOverlay.Style.PANEL, animate = animate)
        if (animate) {
            MetroMotion.cascadeIn((0 until page.childCount).mapNotNull { page.getChildAt(it) }.take(14), ui.dp(48).toFloat())
        }
        scroll.post {
            when {
                scrollToColors -> scroll.smoothScrollTo(0, page.getChildAt(colorsTop)?.top ?: 0)
                scrollY > 0 -> scroll.scrollTo(0, scrollY)
            }
        }
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
