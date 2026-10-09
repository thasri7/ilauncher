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
        fun applyTextPageSettings()
        fun resetTextSizes()
        fun applySystemBars()
        fun showOverview()
        fun showArrange()
        fun addSpace()
        fun saveCurrentTheme()
        fun exportTheme(theme: MetroThemes.Theme)
        fun importTheme()
        fun requestUsageAccess()
        fun requestActivityAccess()
        fun openScreenSaverSettings()
        fun backupNow()
        val spaceCount: Int
        val usageAccess: Boolean
        val activityAccess: Boolean
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
        Section("themes", R.drawable.ic_m_palette, "Themes", { MetroThemes.builtIns.plus(MetroThemes.saved(prefs)).firstOrNull { MetroThemes.matches(prefs, it) }?.name ?: "Your own look" }),
        Section("start", R.drawable.ic_m_apps, "Start", { "Tiles per row, gaps, corners, glass, lock layout" }),
        Section("spaces", R.drawable.ic_m_spaces, "Spaces & groups", { "${host.spaceCount} space${if (host.spaceCount == 1) "" else "s"} · arrange, fold groups" }),
        Section("background", R.drawable.ic_m_photo, "Background", { if (prefs.backgroundMode == "picture") "Picture in tiles" else "Wallpaper · parallax" }),
        Section("colours", R.drawable.ic_m_palette, "Colours & icons", { if (prefs.accentFromWallpaper) "Matching wallpaper · ${host.iconPackLabel}" else host.iconPackLabel }),
        Section("tiles", R.drawable.ic_m_live, "Live tiles & motion", { "Animations, tilt, live tiles, auto-grow" }),
        Section("apps", R.drawable.ic_m_search, "All apps & search", { "${if (prefs.drawerStyle == "grid") "Grid" else "List"} · private apps · ${engineName()}" }),
        Section("text", R.drawable.ic_m_title, "Text page", { if (prefs.textPageEnabled) "On · app names sized by use, swipe past All apps" else "Off" }),
        Section("gestures", R.drawable.ic_m_swipe, "Gestures", { "Swipe, double-tap, pinch, two fingers" }),
        Section("battery", R.drawable.ic_m_leaf, "Battery & Glance", { if (prefs.nightstand) "Nightstand on" else if (prefs.batterySaverPause) "Rests in battery saver" else "Always live" }),
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
                "themes" -> themes(page, rerender)
                "spaces" -> spaces(page)
                "battery" -> battery(page)
                "text" -> textPage(page, rerender)
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
        page.addView(ui.slider("Tile gap", 0, 12, prefs.gutterDp, format = { if (it == 0) "None" else "$it dp" }) { v ->
            if (v != prefs.gutterDp) {
                prefs.gutterDp = v
                host.applyLookAndFeel()
            }
        })
        page.addView(cornerSetting())
        page.addView(ui.slider("Tile colour strength", 20, 100, prefs.tileOpacity, format = { if (it >= 100) "Solid" else "$it%" }) { v ->
            if (v != prefs.tileOpacity) {
                prefs.tileOpacity = v
                host.applyLookAndFeel()
            }
        })
        page.addView(ui.caption("Tile finish"))
        page.addView(ui.chips(listOf("Flat", "Frosted glass"), if (prefs.tileFinish == "glass") 1 else 0) { i ->
            prefs.tileFinish = if (i == 1) "glass" else "flat"
            host.applyLookAndFeel()
        })
        page.addView(ui.toggleRow("Tile labels", "Show names on medium and larger tiles", prefs.showLabels) {
            prefs.showLabels = it
            host.applyLookAndFeel()
        })
        page.addView(ui.toggleRow("Lock Start", "Stop tiles being moved, resized or unpinned by accident", prefs.layoutLocked) { prefs.layoutLocked = it })
        page.addView(ui.toggleRow("Name folders for me", "New folders and stacks are named after their apps (Social, Games…)", prefs.folderAutoName) { prefs.folderAutoName = it })
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
        val speeds = listOf("slow", "normal", "fast")
        page.addView(ui.caption("Live tile pace"))
        page.addView(ui.chips(listOf("Calm", "Normal", "Lively"), speeds.indexOf(prefs.liveSpeed).coerceAtLeast(1)) { i -> prefs.liveSpeed = speeds[i] })
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
        val engines = listOf("google", "bing", "duckduckgo", "brave", "ecosia")
        page.addView(ui.caption("Web search"))
        page.addView(ui.chips(listOf("Google", "Bing", "DuckDuckGo", "Brave", "Ecosia"), engines.indexOf(prefs.searchEngine).coerceAtLeast(0)) { i ->
            prefs.searchEngine = engines[i]
        })
    }

    private fun engineName() = when (prefs.searchEngine) {
        "bing" -> "Bing"
        "duckduckgo" -> "DuckDuckGo"
        "brave" -> "Brave"
        "ecosia" -> "Ecosia"
        else -> "Google"
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
        val twoFinger = listOf("search", "notifications", "overview", "none")
        page.addView(ui.caption("Two fingers down"))
        page.addView(ui.chips(listOf("Search", "Notifications", "Overview", "Nothing"), twoFinger.indexOf(prefs.twoFingerAction).coerceAtLeast(0)) { i ->
            prefs.twoFingerAction = twoFinger[i]
        })
        val pinch = listOf("overview", "settings", "none")
        page.addView(ui.caption("Pinch in"))
        page.addView(ui.chips(listOf("Overview", "Settings", "Nothing"), pinch.indexOf(prefs.pinchAction).coerceAtLeast(0)) { i ->
            prefs.pinchAction = pinch[i]
        })
        val swipeRight = listOf("space", "search", "none")
        page.addView(ui.caption("Swipe right on Start"))
        page.addView(ui.chips(listOf("Next space", "Search", "Nothing"), swipeRight.indexOf(prefs.swipeRightAction).coerceAtLeast(0)) { i ->
            prefs.swipeRightAction = swipeRight[i]
        })
        page.addView(ui.action(
            R.drawable.ic_m_lock,
            "Gesture helper",
            if (host.gestureServiceEnabled) "On · lock screen and notifications work everywhere" else "Off · needed for double-tap to lock"
        ) { host.openAccessibilitySettings() })
        page.addView(ui.caption("Glance also works as a screen saver: Settings › Display › Screen saver › Glance."))
        page.addView(ui.caption("Swipe across a tile with unread messages to see them, or across an app stack to switch apps. Each app tile can have its own swipe action (tile menu)."))
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
        page.addView(ui.action(
            R.drawable.ic_m_phone_time,
            "Usage access",
            if (host.usageAccess) "On · Screen time and Data usage tiles are live" else "Off · needed for Screen time and Data usage tiles"
        ) { host.requestUsageAccess() })
        page.addView(ui.action(
            R.drawable.ic_m_walk,
            "Physical activity",
            if (host.activityAccess) "On · the Steps tile counts your steps" else "Off · needed for the Steps tile"
        ) { host.requestActivityAccess() })
        page.addView(ui.caption("Notifications, calendar, usage and steps stay on this phone. Only weather uses the internet."))
    }

    private fun backup(page: LinearLayout) {
        page.addView(ui.action(R.drawable.ic_m_backup, "Back up Start", "Every space, tile, setting and picture in one file · pick Drive to keep it in the cloud") { host.exportBackup() })
        page.addView(ui.action(R.drawable.ic_m_restore, "Restore Start", "Load a backup file") { host.importBackup() })
        page.addView(ui.action(R.drawable.ic_m_cloud, "Include in phone backup", "Android's own backup to your Google account restores Start on a new phone") { host.backupNow() })
        page.addView(ui.action(R.drawable.ic_m_reset, "Reset Start", "Put the default tiles back", danger = true) { host.confirmReset() })
    }

    private fun launcher(page: LinearLayout) {
        page.addView(ui.action(R.drawable.ic_m_home, "Set as default home app", "Needed for app shortcuts and pinning") { host.openHomeSettings() })
        page.addView(ui.action(R.drawable.ic_m_widgets, "Add a widget") { host.showWidgetPicker() })
        page.addView(ui.action(R.drawable.ic_m_keyboard, "Keyboard settings & themes") { host.openKeyboardSettings() })
    }

    private fun themes(page: LinearLayout, rerender: () -> Unit) {
        page.addView(ui.caption("A theme changes colours, glass, corners, gaps and labels. Your tiles stay where they are."))
        page.addView(ui.sectionTitle("Built in"))
        MetroThemes.builtIns.forEach { t -> page.addView(themeRow(t, rerender)) }
        val saved = MetroThemes.saved(prefs)
        page.addView(ui.sectionTitle("Yours"))
        if (saved.isEmpty()) page.addView(ui.caption("Save your current look to switch back to it any time."))
        saved.forEach { t -> page.addView(themeRow(t, rerender)) }
        page.addView(ui.action(R.drawable.ic_m_add, "Save current look") { host.saveCurrentTheme() })
        page.addView(ui.action(R.drawable.ic_m_restore, "Add a theme file", "Themes others shared with you") { host.importTheme() })
        if (saved.isNotEmpty()) page.addView(ui.caption("Share a theme with the arrow; long-press one of yours to delete it."))
    }

    private fun themeRow(t: MetroThemes.Theme, rerender: () -> Unit): View {
        val accent = runCatching { Color.parseColor(t.accent) }.getOrDefault(Color.GRAY)
        val swatch = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = ui.dp(minOf(t.corners, 10)).toFloat()
            setColor(Color.argb(t.opacity.coerceIn(30, 100) * 255 / 100, Color.red(accent), Color.green(accent), Color.blue(accent)))
            if (t.finish == "glass") setStroke(ui.dp(1), 0x66FFFFFF)
            setSize(ui.dp(28), ui.dp(28))
        }
        val inUse = MetroThemes.matches(prefs, t)
        val details = listOfNotNull(
            if (t.finish == "glass") "Glass" else "Flat",
            when {
                t.corners >= 12 -> "round"
                t.corners > 0 -> "soft"
                else -> "square"
            },
            "${t.columns} per row",
            if (t.colorMode == "icon") "app colours" else null
        ).joinToString(" · ")
        val row = ui.actionWithIcon(
            icon = swatch,
            title = t.name + if (inUse) "  ✓" else "",
            subtitle = details,
            trailingIcon = R.drawable.ic_m_forward,
            trailingDescription = "Share theme",
            onTrailing = { host.exportTheme(t) }
        ) {
            MetroThemes.apply(prefs, t)
            host.applyLookAndFeel()
            rerender()
        }
        if (!t.builtIn) row.setOnLongClickListener {
            MetroThemes.delete(prefs, t)
            rerender()
            true
        }
        return row
    }

    private fun spaces(page: LinearLayout) {
        page.addView(ui.caption("Spaces are separate Start screens, like Work and Home, each with its own tiles. Swipe right on Start to move between them."))
        page.addView(ui.action(R.drawable.ic_m_overview, "Overview", "See every space and group · or pinch Start") { host.showOverview() })
        page.addView(ui.action(R.drawable.ic_m_add, "New space") { host.addSpace() })
        page.addView(ui.action(R.drawable.ic_m_sort, "Arrange tiles", "Pack tightly, sort, group by kind, grow what you use") { host.showArrange() })
        page.addView(ui.caption("Groups: add a Group name tile, then tap a group's name on Start to fold it away."))
    }

    private fun textPage(page: LinearLayout, rerender: () -> Unit) {
        val apply = { host.applyTextPageSettings() }
        page.addView(ui.toggleRow("Text page", "A third page after All apps: every app as its name, big when you use it a lot", prefs.textPageEnabled) {
            prefs.textPageEnabled = it
            apply()
            rerender()
        })
        if (!prefs.textPageEnabled) return
        val orders = listOf("az", "use", "size")
        page.addView(ui.caption("Order"))
        page.addView(ui.chips(listOf("A to Z", "Most used", "Biggest first"), orders.indexOf(prefs.textOrder).coerceAtLeast(0)) { i ->
            prefs.textOrder = orders[i]
            apply()
        })
        page.addView(ui.slider("Smallest names", 10, 24, prefs.textMinSp, format = { "$it sp" }) { v ->
            prefs.textMinSp = v
            apply()
        })
        page.addView(ui.slider("Biggest names", 24, 64, prefs.textMaxSp, format = { "$it sp" }) { v ->
            prefs.textMaxSp = v
            apply()
        })
        val shrinks = listOf("slow", "normal", "fast")
        page.addView(ui.caption("Unused apps shrink"))
        page.addView(ui.chips(listOf("Slowly", "Normally", "Quickly"), shrinks.indexOf(prefs.textShrink).coerceAtLeast(1)) { i ->
            prefs.textShrink = shrinks[i]
            apply()
        })
        val colors = listOf("accent", "white", "app")
        page.addView(ui.caption("Colour"))
        page.addView(ui.chips(listOf("Accent for top apps", "White", "App colours"), colors.indexOf(prefs.textColor).coerceAtLeast(0)) { i ->
            prefs.textColor = colors[i]
            apply()
        })
        val fonts = listOf("light", "regular", "bold")
        page.addView(ui.caption("Font"))
        page.addView(ui.chips(listOf("Light", "Regular", "Bold"), fonts.indexOf(prefs.textFont).coerceAtLeast(0)) { i ->
            prefs.textFont = fonts[i]
            apply()
        })
        val cases = listOf("lower", "asis", "upper")
        page.addView(ui.caption("Letters"))
        page.addView(ui.chips(listOf("lowercase", "As named", "UPPERCASE"), cases.indexOf(prefs.textCase).coerceAtLeast(0)) { i ->
            prefs.textCase = cases[i]
            apply()
        })
        val aligns = listOf("start", "center", "end")
        page.addView(ui.caption("Line up"))
        page.addView(ui.chips(listOf("Left", "Centre", "Right"), aligns.indexOf(prefs.textAlign).coerceAtLeast(0)) { i ->
            prefs.textAlign = aligns[i]
            apply()
        })
        page.addView(ui.slider("Space between names", 2, 28, prefs.textSpacingDp, format = { "$it dp" }) { v ->
            prefs.textSpacingDp = v
            apply()
        })
        page.addView(ui.toggleRow("Letter strip", "Slide along A–Z on the right to light up names", prefs.textLetterStrip) {
            prefs.textLetterStrip = it
            apply()
        })
        page.addView(ui.toggleRow("Search box", "Type to find an app at the top of the page", prefs.textSearch) {
            prefs.textSearch = it
            apply()
        })
        page.addView(ui.action(R.drawable.ic_m_reset, "Reset name sizes", "Start counting use again; big tiles stay big") { host.resetTextSizes() })
        page.addView(ui.caption("Names grow as you open apps and fade a step at a time when you stop. Apps with big tiles on Start show big here too."))
    }

    private fun battery(page: LinearLayout) {
        page.addView(ui.toggleRow("Rest in battery saver", "When Android's battery saver is on, tiles stop flipping and the wallpaper stays still", prefs.batterySaverPause) {
            prefs.batterySaverPause = it
        })
        page.addView(ui.toggleRow("Nightstand", "While charging, Start turns into Glance after a minute untouched", prefs.nightstand) {
            prefs.nightstand = it
        })
        page.addView(ui.action(R.drawable.ic_m_moon, "Glance as screen saver", "Settings › Screen saver › Glance, when charging or docked") { host.openScreenSaverSettings() })
        page.addView(ui.caption("Live tiles only update while Start is on screen. Steps, switches and screen time stop listening when you leave Start."))
    }

    /** Corner roundness slider with a live preview of three tiles. */
    private fun cornerSetting(): View {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val preview = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ui.dp(20), ui.dp(6), ui.dp(20), 0)
        }
        val samples = listOf(56, 56, 116).mapIndexed { i, w ->
            View(context).also { v ->
                preview.addView(v, LinearLayout.LayoutParams(ui.dp(w), ui.dp(56)).apply { if (i > 0) marginStart = ui.dp(6) })
            }
        }
        fun paint(radiusDp: Int) = samples.forEachIndexed { i, v ->
            v.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = ui.dp(radiusDp).toFloat()
                val a = prefs.accentColorInt
                setColor(Color.argb(if (i == 1) 170 else 255, Color.red(a), Color.green(a), Color.blue(a)))
            }
        }
        paint(prefs.cornerRadiusDp)
        box.addView(ui.slider(
            "Tile corners", 0, 24, prefs.cornerRadiusDp,
            format = { if (it == 0) "Square" else "$it dp" },
            onMove = { paint(it) }
        ) { value ->
            if (value != prefs.cornerRadiusDp) {
                prefs.cornerRadiusDp = value
                host.applyLookAndFeel()
            }
        })
        box.addView(preview)
        return box
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
