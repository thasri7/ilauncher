package com.custom.keyboard

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.SearchManager
import android.app.WallpaperManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.text.format.DateFormat
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.custom.keyboard.launcher.AgendaProvider
import com.custom.keyboard.launcher.AppDrawerAdapter
import com.custom.keyboard.launcher.AppSearch
import com.custom.keyboard.launcher.AppShortcuts
import com.custom.keyboard.launcher.GestureActions
import com.custom.keyboard.launcher.GlanceActivity
import com.custom.keyboard.launcher.IconCache
import com.custom.keyboard.launcher.IconPacks
import com.custom.keyboard.launcher.LauncherKeyboardController
import com.custom.keyboard.launcher.LauncherPagerAdapter
import com.custom.keyboard.launcher.METRO_ACCENTS
import com.custom.keyboard.launcher.MediaTileController
import com.custom.keyboard.launcher.MetroGridLayoutManager
import com.custom.keyboard.launcher.MetroItemAnimator
import com.custom.keyboard.launcher.MetroMotion
import com.custom.keyboard.launcher.MetroOverlay
import com.custom.keyboard.launcher.MetroTileAdapter
import com.custom.keyboard.launcher.MetroUi
import com.custom.keyboard.launcher.NotificationHub
import com.custom.keyboard.launcher.PageTransformers
import com.custom.keyboard.launcher.SettingsPage
import com.custom.keyboard.launcher.StartBackdrop
import com.custom.keyboard.launcher.Suggestions
import com.custom.keyboard.launcher.TileMedia
import com.custom.keyboard.launcher.TilePreferences
import com.custom.keyboard.launcher.WeatherCodes
import com.custom.keyboard.launcher.WeatherParser
import com.custom.keyboard.launcher.WeatherReport
import com.custom.keyboard.launcher.WeatherRepository
import com.custom.keyboard.launcher.WidgetTiles
import com.custom.keyboard.models.TileItem
import com.custom.keyboard.models.TileSize
import com.custom.keyboard.models.TileType
import java.util.Calendar
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil

class LauncherActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CONFIGURE_WIDGET = 0x5701
    }

    private lateinit var prefs: TilePreferences
    private lateinit var appHelper: AppLauncherHelper
    private lateinit var icons: IconCache
    private lateinit var media: MediaTileController
    private lateinit var widgets: WidgetTiles
    private lateinit var shortcuts: AppShortcuts
    private lateinit var weather: WeatherRepository
    private lateinit var metroOverlay: MetroOverlay
    private lateinit var ui: MetroUi
    private lateinit var settingsPage: SettingsPage
    private val mathCalc = MathCalculator()
    private val lightFace: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)

    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var pager: ViewPager2
    private lateinit var tvTitle: TextView
    private lateinit var btnTogglePage: ImageView
    private lateinit var editBar: View
    private lateinit var undoBar: View
    /** Clean-up of the last unpinned tile (widget id, pictures), run once Undo is no longer offered. */
    private var pendingUndoExpiry: Runnable? = null

    private lateinit var searchPanel: LinearLayout
    private lateinit var searchBox: View
    private lateinit var keyboardView: View
    private lateinit var etSearch: EditText
    private lateinit var tvMathResult: TextView
    private lateinit var btnWebSearch: View
    private lateinit var tvWebSearchLabel: TextView
    private lateinit var searchActionStrip: View

    private var rvTiles: RecyclerView? = null
    private var suggestionsStrip: View? = null
    private var suggestionsShownFor = ""
    /** The phone was just unlocked: play the "your day" entrance. */
    private var dayEntrance = false
    private var rvDrawer: RecyclerView? = null
    private var etDrawerSearch: EditText? = null
    private var touchHelper: ItemTouchHelper? = null
    private lateinit var gridLayoutManager: MetroGridLayoutManager
    private lateinit var tileAdapter: MetroTileAdapter
    private lateinit var drawerAdapter: AppDrawerAdapter
    private lateinit var searchAdapter: AppDrawerAdapter

    private val tiles = mutableListOf<TileItem>()
    private var allApps = listOf<AppLauncherHelper.AppEntry>()
    private var searchResults = listOf<AppLauncherHelper.AppEntry>()
    private var drawerQuery = ""
    private val systemInsets = Rect()
    private var backdrop: StartBackdrop? = null

    private val handler = Handler(Looper.getMainLooper())
    /** Play the turnstile entrance the next time Start becomes visible. */
    private var playEntrance = true
    /** Tiles were swung away for an app launch and still need to come back. */
    private var tilesTurnedOut = false
    /** Tiles that auto-grew while an app was launching; restyled once we are back. */
    private val pendingRestyles = mutableSetOf<String>()

    /** A widget being added: allocated id, then bind permission, then its own setup screen. */
    private data class PendingWidget(val id: Int, val label: String, val size: TileSize)
    private var pendingWidget: PendingWidget? = null
    /** The Photos / People tile waiting for the picture picker, or null to create a new Photos tile. */
    private var pictureTarget: TileItem? = null

    private val liveTick = object : Runnable {
        override fun run() {
            val rv = rvTiles
            if (rv != null && !metroOverlay.isShowing && searchPanel.visibility != View.VISIBLE &&
                pager.currentItem == LauncherPagerAdapter.PAGE_TILES && rv.scrollState == RecyclerView.SCROLL_STATE_IDLE
            ) {
                tileAdapter.runLiveStep(rv)
            }
            handler.postDelayed(this, 2500)
        }
    }

    private val turnstileSafety = Runnable {
        // The launch didn't take us off screen (e.g. a translucent activity); bring the tiles back.
        if (tilesTurnedOut && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            playEntranceIfNeeded()
        }
    }

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            tileAdapter.tick()
            refreshAgenda()
            refreshWeather(force = false)
            refreshSuggestions()
        }
    }

    private var lastBatteryState = ""
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // The system sends this very often (voltage, temperature); only redraw on real changes.
            val state = intent?.let {
                "${it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)}/${it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)}/" +
                    "${it.getIntExtra(BatteryManager.EXTRA_STATUS, 0)}/${it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10}"
            } ?: return
            if (state == lastBatteryState) return
            lastBatteryState = state
            tileAdapter.onBatteryChanged()
        }
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val pkg = intent?.data?.schemeSpecificPart
            val replacing = intent?.getBooleanExtra(Intent.EXTRA_REPLACING, false) == true
            if (intent?.action == Intent.ACTION_PACKAGE_REMOVED && !replacing && pkg != null) {
                removeTilesFor(pkg)
            }
            icons.clear()
            appHelper.reload()
            allApps = appHelper.getAllApps()
            refreshDrawer()
            tileAdapter.refreshIcons()
        }
    }

    private val notificationsChanged: () -> Unit = { tileAdapter.onNotificationsChanged() }

    private val userPresentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            dayEntrance = true
            playEntrance = true
        }
    }

    private val wallpaperColorsListener: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        WallpaperManager.OnColorsChangedListener { _, which ->
            if (which and WallpaperManager.FLAG_SYSTEM != 0) applyWallpaperAccent()
        }
    } else null

    private val privateUnlock = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) showPrivateApps()
    }

    // ── Activity results ────────────────────────────────────────────────────────────────

    private val contactPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode != RESULT_OK || uri == null) return@registerForActivityResult
        var name: String? = null
        var number = ""
        var photo: String? = null
        try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.PHOTO_URI
            )
            contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    number = c.getString(1).orEmpty()
                    photo = c.getString(2)
                }
            }
        } catch (_: Exception) {
        }
        val pickedName = name
        if (pickedName.isNullOrBlank()) {
            promptManualContact()
            return@registerForActivityResult
        }
        val tile = TileItem(UUID.randomUUID().toString(), TileType.QUICK_CONTACT, pickedName, contactPhone = number)
        addTile(tile)
        // The picker's one-time grant usually covers the contact photo too; if not, initials stay.
        photo?.let { p -> TileMedia.savePortrait(this, tile.id, Uri.parse(p)) { ok -> if (ok) tileAdapter.refresh(tile) } }
    }

    private val photosPicker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        val tile = pictureTarget ?: TileItem(UUID.randomUUID().toString(), TileType.PHOTOS, "Photos", size = TileSize.WIDE).also { addTile(it) }
        pictureTarget = null
        TileMedia.savePhotos(this, tile.id, uris) { saved ->
            tileAdapter.refresh(tile, MetroTileAdapter.PAYLOAD_ICONS)
            toast(if (saved > 0) "$saved photos on your Photos tile" else "Couldn't read those photos")
        }
    }

    private val portraitPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val tile = pictureTarget
        pictureTarget = null
        if (uri == null || tile == null) return@registerForActivityResult
        TileMedia.savePortrait(this, tile.id, uri) { ok -> if (ok) tileAdapter.refresh(tile) else toast("Couldn't read that picture") }
    }

    private val backgroundPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        TileMedia.saveBackground(this, uri) { ok ->
            if (!ok) {
                toast("Couldn't read that picture")
                return@saveBackground
            }
            prefs.backgroundMode = "picture"
            loadBackdrop()
            applyLookAndFeel()
            if (metroOverlay.isShowing) settingsPage.show(animate = false)
        }
    }

    private val widgetBinder = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val pending = pendingWidget ?: return@registerForActivityResult
        if (result.resultCode == RESULT_OK) configureWidget(pending) else cancelWidget()
    }

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) useDeviceLocationForWeather() else toast("Search for your city instead")
    }

    private val calendarPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) refreshAgenda() else toast("Calendar events stay hidden")
        if (metroOverlay.isShowing) settingsPage.show(animate = false)
    }

    private val backupWriter = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        val ok = try {
            contentResolver.openOutputStream(uri)?.use { it.write(prefs.exportJson().toByteArray()) } != null
        } catch (_: Exception) {
            false
        }
        toast(if (ok) "Start backed up" else "Couldn't save the backup")
    }

    private val backupReader = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val text = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (_: Exception) {
            null
        }
        val oldWidgets = tiles.filter { it.type == TileType.WIDGET }.map { it.appWidgetId }
        if (text == null || !prefs.importJson(text)) {
            toast("That isn't a Start backup")
            return@registerForActivityResult
        }
        oldWidgets.forEach { widgets.delete(it) }
        metroOverlay.dismiss()
        tileAdapter.exitEditMode()
        tileAdapter.closeFolder()
        tiles.clear()
        tiles.addAll(prefs.loadTiles())
        loadBackdrop()
        icons.setIconPack(prefs.iconPack) { onIconsChanged() }
        applyLookAndFeel()
        toast("Start restored")
    }

    // ── Setup ───────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_launcher)

        prefs = TilePreferences(this)
        appHelper = AppLauncherHelper(this)
        icons = IconCache(this)
        widgets = WidgetTiles(this)
        shortcuts = AppShortcuts(this)
        weather = WeatherRepository(this, prefs)
        ui = MetroUi(this) { prefs.accentColorInt }

        bindViews()
        metroOverlay = MetroOverlay(findViewById(R.id.overlay_host)) { systemInsets }
        settingsPage = SettingsPage(this, ui, prefs, metroOverlay, settingsHost)
        setupInsets()
        loadData()
        setupPager()
        setupSearchPanel()
        setupChrome()
        loadBackdrop()
        applyLookAndFeel()
        if (prefs.iconPack.isNotEmpty()) icons.setIconPack(prefs.iconPack) { onIconsChanged() }
        icons.prefetch(allPinnedPackages() + allApps.map { it.packageName })

        val packageFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageReceiver, packageFilter, ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(this, userPresentReceiver, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_EXPORTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            (wallpaperColorsListener as? WallpaperManager.OnColorsChangedListener)?.let {
                WallpaperManager.getInstance(this).addOnColorsChangedListener(it, handler)
            }
        }
        applyWallpaperAccent()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack(homePressed = false)
        })
    }

    private fun bindViews() {
        root = findViewById(R.id.launcher_root)
        content = findViewById(R.id.launcher_content)
        pager = findViewById(R.id.pager_launcher)
        tvTitle = findViewById(R.id.tv_metro_title)
        btnTogglePage = findViewById(R.id.btn_toggle_page)
        editBar = findViewById(R.id.ll_edit_bar)
        undoBar = findViewById(R.id.ll_undo_bar)
        searchPanel = findViewById(R.id.panel_inbuilt_keyboard_search)
        searchBox = findViewById(R.id.ll_search_box)
        keyboardView = findViewById(R.id.inbuilt_custom_keyboard_view)
        etSearch = findViewById(R.id.et_express_search)
        tvMathResult = findViewById(R.id.tv_math_calc_result)
        btnWebSearch = findViewById(R.id.btn_web_search_action)
        tvWebSearchLabel = findViewById(R.id.tv_web_search_label)
        searchActionStrip = findViewById(R.id.ll_search_action_strip)
        etSearch.showSoftInputOnFocus = false
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            systemInsets.set(bars.left, bars.top, bars.right, bars.bottom)
            content.setPadding(bars.left, bars.top, bars.right, 0)
            searchPanel.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            (editBar.layoutParams as FrameLayout.LayoutParams).bottomMargin = bars.bottom + ui.dp(18)
            editBar.requestLayout()
            rvTiles?.let { applyTilesPadding(it) }
            rvDrawer?.let { it.setPadding(0, 0, 0, bars.bottom + ui.dp(24)) }
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun loadData() {
        tiles.clear()
        tiles.addAll(prefs.loadTiles())
        allApps = appHelper.getAllApps()
        seedAppTiles()

        media = MediaTileController(this) { if (::tileAdapter.isInitialized) tileAdapter.onMediaChanged() }
        tileAdapter = MetroTileAdapter(
            context = this,
            tiles = tiles,
            prefs = prefs,
            icons = icons,
            media = media,
            widgets = widgets,
            shortcuts = shortcuts,
            backdrop = { backdrop },
            weather = { weather.report },
            weatherConfigured = { weather.hasLocation },
            cellPitch = { gridLayoutManager.cellPitch },
            callbacks = tileCallbacks
        )
        gridLayoutManager = MetroGridLayoutManager { position -> tileAdapter.spanFor(position) }

        drawerAdapter = AppDrawerAdapter(
            icons = icons,
            accent = { prefs.accentColorInt },
            tiltEnabled = { prefs.tiltEnabled },
            isPinned = { pkg -> pkg in allPinnedPackages() },
            onAppClick = { app, view -> launchApp(app.packageName, view) },
            onAppLongClick = { app, view -> showDrawerAppMenu(app, view) },
            onHeaderClick = { showJumpList() },
            onPrivateClick = { unlockPrivateApps() }
        )
        searchAdapter = AppDrawerAdapter(
            icons = icons,
            accent = { prefs.accentColorInt },
            tiltEnabled = { prefs.tiltEnabled },
            isPinned = { pkg -> pkg in allPinnedPackages() },
            onAppClick = { app, view ->
                launchApp(app.packageName, view)
                closeSearch()
            },
            onAppLongClick = { app, view -> showDrawerAppMenu(app, view) }
        )
        val rvSearch = findViewById<RecyclerView>(R.id.rv_search_results)
        rvSearch.layoutManager = LinearLayoutManager(this)
        rvSearch.adapter = searchAdapter
    }

    /** Apps shown in All apps, search and pickers: everything except Private apps. */
    private fun visibleApps(): List<AppLauncherHelper.AppEntry> {
        val hidden = prefs.hiddenApps
        return if (hidden.isEmpty()) allApps else allApps.filter { it.packageName !in hidden }
    }

    private fun allPinnedPackages(): Set<String> =
        (tiles + tiles.flatMap { it.children }).filter { it.type == TileType.APP_SHORTCUT }.mapNotNull { it.packageName }.toSet()

    /** First run only: pin a few everyday apps in a W10M-like mix of sizes. */
    private fun seedAppTiles() {
        if (prefs.appsSeeded) return
        prefs.appsSeeded = true
        if (tiles.any { it.type == TileType.APP_SHORTCUT } || allApps.isEmpty()) return
        val patterns = listOf("chrome", "camera", "dialer", "phone", "messag", "whatsapp", "gmail", "mail", "photos", "gallery", "maps", "youtube")
        val picked = LinkedHashSet<AppLauncherHelper.AppEntry>()
        for (p in patterns) {
            allApps.firstOrNull { it.name.lowercase().contains(p) || it.packageName.lowercase().contains(p) }?.let { picked.add(it) }
            if (picked.size == 8) break
        }
        if (picked.isEmpty()) picked.addAll(allApps.take(8))
        val sizes = listOf(TileSize.MEDIUM, TileSize.MEDIUM, TileSize.SMALL, TileSize.SMALL, TileSize.SMALL, TileSize.SMALL, TileSize.WIDE, TileSize.MEDIUM)
        picked.forEachIndexed { i, app ->
            tiles.add(TileItem(UUID.randomUUID().toString(), TileType.APP_SHORTCUT, app.name, app.packageName, sizes.getOrElse(i) { TileSize.MEDIUM }))
        }
        prefs.saveTiles(tiles)
    }

    private fun setupPager() {
        pager.offscreenPageLimit = 1
        pager.adapter = LauncherPagerAdapter(
            onTilesPageReady = { rv -> setupTilesPage(rv) },
            onDrawerPageReady = { page -> setupDrawerPage(page) }
        )
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val onTiles = position == LauncherPagerAdapter.PAGE_TILES
                swapTitle(if (onTiles) "start" else "all apps")
                btnTogglePage.setImageResource(if (onTiles) R.drawable.ic_m_apps else R.drawable.ic_m_back)
                btnTogglePage.contentDescription = if (onTiles) "All apps" else "Back to Start"
                if (onTiles) {
                    hideKeyboard(etDrawerSearch)
                    if (drawerQuery.isNotEmpty()) etDrawerSearch?.setText("")
                }
            }
        })
    }

    private fun swapTitle(text: String) {
        if (tvTitle.text.toString() == text) return
        tvTitle.animate().alpha(0f).translationX(-ui.dp(12).toFloat()).setStartDelay(0).setDuration(110).withEndAction {
            tvTitle.text = text
            tvTitle.translationX = ui.dp(16).toFloat()
            tvTitle.animate().alpha(1f).translationX(0f).setDuration(220).setInterpolator(DecelerateInterpolator(2f)).start()
        }.start()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTilesPage(rv: RecyclerView) {
        if (rvTiles === rv) return
        rvTiles = rv
        rv.layoutManager = gridLayoutManager
        rv.adapter = tileAdapter
        rv.itemAnimator = MetroItemAnimator { holder -> tileAdapter.motionView(rv, holder.itemView) }.apply {
            moveDuration = 280
            changeDuration = 160
            addDuration = 220
            removeDuration = 160
        }
        // Keep off-screen tiles around so scrolling back doesn't rebind them.
        rv.setItemViewCacheSize(24)
        suggestionsStrip = (rv.parent as? View)?.findViewById(R.id.ll_suggestions)
        applyTilesPadding(rv)
        // The suggestions strip scrolls away with the first rows of tiles.
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                suggestionsStrip?.translationY = -recyclerView.computeVerticalScrollOffset().toFloat()
            }
        })
        rv.post { refreshSuggestions() }

        touchHelper = ItemTouchHelper(dragCallback).also { it.attachToRecyclerView(rv) }

        // Gestures on the empty space between and below tiles, plus swipe down anywhere at the top.
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (tileAdapter.editMode && rv.findChildViewUnder(e.x, e.y) == null) {
                    tileAdapter.exitEditMode()
                    return true
                }
                return false
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (tileAdapter.editMode || rv.findChildViewUnder(e.x, e.y) != null) return false
                runDoubleTapGesture()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!tileAdapter.editMode && rv.findChildViewUnder(e.x, e.y) == null) {
                    rv.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    showStartMenu(null)
                }
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val start = e1 ?: return false
                val pulledDown = e2.y - start.y > ui.dp(80) && velocityY > 1500 && abs(velocityY) > 2 * abs(velocityX)
                if (pulledDown && !tileAdapter.editMode && !rv.canScrollVertically(-1)) {
                    runSwipeDownGesture()
                    return true
                }
                return false
            }
        })
        rv.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                detector.onTouchEvent(e)
                return false
            }
        })
        rv.doOnLayout { playEntranceIfNeeded() }
    }

    private fun applyTilesPadding(rv: RecyclerView) {
        val side = ui.dp(8)
        val strip = suggestionsStrip?.takeIf { it.visibility == View.VISIBLE }
        val top = if (strip != null) strip.measuredHeight.takeIf { it > 0 } ?: ui.dp(88) else ui.dp(4)
        rv.setPadding(side + systemInsets.left, top, side + systemInsets.right, systemInsets.bottom + ui.dp(96))
    }

    /** "Suggested now": apps you usually open around this hour, as small tiles above Start. */
    private fun refreshSuggestions(force: Boolean = false) {
        val strip = suggestionsStrip ?: return
        val rv = rvTiles ?: return
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val columns = prefs.columns
        val apps = if (prefs.suggestionsEnabled && !tileAdapter.editMode) {
            Suggestions.forHour(prefs.hourlyUsage(), hour, columns, prefs.hiddenApps)
                .mapNotNull { pkg -> allApps.firstOrNull { it.packageName == pkg } }
        } else emptyList()
        val key = "$hour/${apps.joinToString { it.packageName }}/${prefs.accentColor}/${prefs.gutterDp}/$columns/${prefs.cornerRadiusDp}"
        if (!force && key == suggestionsShownFor) return
        suggestionsShownFor = key
        val row = strip.findViewById<LinearLayout>(R.id.ll_suggestions_row)
        row.removeAllViews()
        if (apps.size < 2) {
            strip.visibility = View.GONE
            applyTilesPadding(rv)
            return
        }
        strip.findViewById<TextView>(R.id.tv_suggestions_title).text = "suggested · ${Suggestions.partOfDay(hour)}"
        val pitch = gridLayoutManager.cellPitch.takeIf { it > 0f } ?: (rv.width - ui.dp(16)) / columns.toFloat()
        val gutter = ui.dp(prefs.gutterDp)
        val size = (pitch - gutter).toInt()
        apps.forEachIndexed { i, app ->
            val cell = FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    setColor(Color.argb(prefs.tileOpacity.coerceIn(20, 100) * 255 / 100, Color.red(prefs.accentColorInt), Color.green(prefs.accentColorInt), Color.blue(prefs.accentColorInt)))
                    cornerRadius = prefs.cornerRadiusDp * resources.displayMetrics.density
                }
                clipToOutline = prefs.cornerRadiusDp > 0
                contentDescription = app.name
            }
            val icon = ImageView(this)
            cell.addView(icon, FrameLayout.LayoutParams((size * 0.56f).toInt(), (size * 0.56f).toInt(), Gravity.CENTER))
            icons.iconAsync(app.packageName, prefs.themedIcons) { d, _ -> icon.setImageDrawable(d) }
            cell.setOnTouchListener { v, e ->
                if (prefs.tiltEnabled) when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> MetroMotion.tiltTo(v, e.x, e.y)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> MetroMotion.releaseTilt(v)
                }
                false
            }
            cell.setOnClickListener { launchApp(app.packageName, null) }
            cell.setOnLongClickListener {
                showDrawerAppMenu(app, cell)
                true
            }
            row.addView(cell, LinearLayout.LayoutParams(size, size).apply { if (i > 0) marginStart = gutter })
        }
        val wasHidden = strip.visibility != View.VISIBLE
        strip.visibility = View.VISIBLE
        strip.translationY = -rv.computeVerticalScrollOffset().toFloat()
        strip.post { applyTilesPadding(rv) }
        if (wasHidden && prefs.animationsEnabled) MetroMotion.cascadeIn((0 until row.childCount).map { row.getChildAt(it) }, ui.dp(32).toFloat())
    }

    private fun setupDrawerPage(page: View) {
        val rv = page.findViewById<RecyclerView>(R.id.rv_app_drawer)
        if (rvDrawer === rv) return
        rvDrawer = rv
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = drawerAdapter
        rv.setPadding(0, 0, 0, systemInsets.bottom + ui.dp(24))

        val search = page.findViewById<EditText>(R.id.et_drawer_search)
        val clear = page.findViewById<View>(R.id.btn_drawer_search_clear)
        etDrawerSearch = search
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                drawerQuery = s?.toString()?.trim().orEmpty()
                clear.visibility = if (drawerQuery.isEmpty()) View.GONE else View.VISIBLE
                refreshDrawer()
            }
        })
        clear.setOnClickListener { search.setText("") }
        refreshDrawer()
    }

    private fun refreshDrawer() {
        if (drawerQuery.isEmpty()) {
            val hiddenCount = prefs.hiddenApps.count { pkg -> allApps.any { it.packageName == pkg } }
            drawerAdapter.submit(visibleApps(), grouped = true, mostUsed = mostUsedApps(4), privateCount = hiddenCount)
        } else {
            drawerAdapter.submit(rankApps(drawerQuery), grouped = false)
        }
    }

    private fun setupChrome() {
        findViewById<View>(R.id.btn_open_search).setOnClickListener { openSearch() }
        btnTogglePage.setOnClickListener { togglePage() }
        findViewById<View>(R.id.btn_open_settings).setOnClickListener { v -> showStartMenu(v) }
        findViewById<View>(R.id.btn_edit_add).setOnClickListener { showAddTileSheet() }
        findViewById<View>(R.id.btn_edit_done).setOnClickListener { tileAdapter.exitEditMode() }
    }

    // ── Tiles: callbacks, drag & drop, folders ──────────────────────────────────────────

    private val tileCallbacks = object : MetroTileAdapter.Callbacks {
        override fun onTileClick(tile: TileItem, view: View) = handleTileClick(tile, view)

        override fun onStartDrag(holder: RecyclerView.ViewHolder) {
            touchHelper?.startDrag(holder)
        }

        override fun onTileResized(tile: TileItem) = restyle(tile)

        override fun onTileUnpinned(tile: TileItem) = unpin(tile)

        override fun onTileMenu(tile: TileItem, anchor: View) = showTileMenu(tile)

        override fun onEditModeChanged(editing: Boolean) {
            pager.isUserInputEnabled = !editing
            refreshSuggestions()
            if (editing) root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showEditBar(editing)
        }

        override fun onFolderAppClick(app: TileItem, view: View) = openAppTile(app, view)

        override fun onFolderAppMenu(folder: TileItem, app: TileItem, anchor: View) = showFolderAppMenu(folder, app, anchor)

        override fun onTileSwipe(tile: TileItem) = showTileNotifications(tile)
    }

    private val dragCallback = object : ItemTouchHelper.Callback() {
        /** Tile the dragged app is hovering over, to become a folder on drop. */
        private var mergeTarget: RecyclerView.ViewHolder? = null

        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
            makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0)

        override fun isLongPressDragEnabled(): Boolean = false

        override fun isItemViewSwipeEnabled(): Boolean = false

        private fun canMerge(dragged: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val a = tileAdapter.tileAt(dragged.bindingAdapterPosition) ?: return false
            val b = tileAdapter.tileAt(target.bindingAdapterPosition) ?: return false
            return a !== b && a.type == TileType.APP_SHORTCUT && (b.type == TileType.APP_SHORTCUT || b.type == TileType.FOLDER)
        }

        /** Dropping in the middle of a tile makes a folder; near its edges just moves past it. */
        private fun centredOver(dragged: View, target: View): Boolean {
            val dx = dragged.left + dragged.translationX + dragged.width / 2f - (target.left + target.width / 2f)
            val dy = dragged.top + dragged.translationY + dragged.height / 2f - (target.top + target.height / 2f)
            return abs(dx) < target.width * 0.25f && abs(dy) < target.height * 0.25f
        }

        private fun setMergeTarget(target: RecyclerView.ViewHolder?) {
            if (mergeTarget === target) return
            tileAdapter.setMergeHighlight(mergeTarget, on = false)
            mergeTarget = target
            tileAdapter.setMergeHighlight(target, on = true)
        }

        override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            if (canMerge(viewHolder, target) && centredOver(viewHolder.itemView, target.itemView)) {
                setMergeTarget(target)
                return false
            }
            setMergeTarget(null)
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            tileAdapter.moveTile(from, to)
            return true
        }

        override fun onChildDraw(
            c: Canvas,
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean
        ) {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            val target = mergeTarget ?: return
            if (!centredOver(viewHolder.itemView, target.itemView)) setMergeTarget(null)
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) tileAdapter.lift(viewHolder)
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            val target = mergeTarget
            mergeTarget = null
            val dragged = tileAdapter.tileAt(viewHolder.bindingAdapterPosition)
            val into = target?.let { tileAdapter.tileAt(it.bindingAdapterPosition) }
            tileAdapter.settle(viewHolder)
            target?.let { tileAdapter.setMergeHighlight(it, on = false) }
            if (dragged != null && into != null) {
                // Let the drop settle before the list changes under ItemTouchHelper.
                recyclerView.post { mergeIntoFolder(dragged, into) }
            } else {
                prefs.saveTiles(tiles)
            }
        }
    }

    private fun mergeIntoFolder(app: TileItem, target: TileItem) {
        if (app.type != TileType.APP_SHORTCUT || app === target) return
        if (target.type == TileType.FOLDER) {
            tileAdapter.removeTile(app)
            target.children.add(app)
            tileAdapter.refresh(target)
        } else if (target.type == TileType.APP_SHORTCUT) {
            val folder = TileItem(
                UUID.randomUUID().toString(),
                TileType.FOLDER,
                "Folder",
                size = if (target.size == TileSize.SMALL) TileSize.MEDIUM else target.size
            )
            folder.children.add(target)
            folder.children.add(app)
            tileAdapter.replaceTile(target, folder)
            tileAdapter.removeTile(app)
            toast("Folder made · tap ⋯ to name it")
        }
        prefs.saveTiles(tiles)
    }

    private fun removeFromFolder(folder: TileItem, app: TileItem) {
        if (!folder.children.remove(app)) return
        val index = tiles.indexOf(folder)
        if (folder.children.size <= 1) {
            // A folder with one app left dissolves back into that app's tile.
            val last = folder.children.firstOrNull()
            if (last != null) tileAdapter.replaceTile(folder, last) else tileAdapter.removeTile(folder)
        } else {
            tileAdapter.refresh(folder)
        }
        tileAdapter.insertTile(app, index + 1)
        prefs.saveTiles(tiles)
        drawerAdapter.notifyDataSetChanged()
    }

    private fun ungroup(folder: TileItem) {
        val index = tiles.indexOf(folder)
        val apps = folder.children.toList()
        tileAdapter.removeTile(folder)
        apps.forEachIndexed { i, app -> tileAdapter.insertTile(app, index + i) }
        prefs.saveTiles(tiles)
    }

    private fun showEditBar(show: Boolean) {
        editBar.animate().cancel()
        if (show) {
            editBar.visibility = View.VISIBLE
            editBar.translationY = ui.dp(90).toFloat()
            editBar.alpha = 0f
            editBar.animate().translationY(0f).alpha(1f).setStartDelay(0).setDuration(260)
                .setInterpolator(DecelerateInterpolator(2f)).start()
        } else {
            editBar.animate().translationY(ui.dp(90).toFloat()).alpha(0f).setStartDelay(0).setDuration(180)
                .withEndAction { editBar.visibility = View.GONE }.start()
        }
    }

    private fun restyle(tile: TileItem) {
        tileAdapter.refresh(tile)
        prefs.saveTiles(tiles)
    }

    private fun unpin(tile: TileItem) {
        val index = tiles.indexOf(tile)
        tileAdapter.removeTile(tile)
        prefs.saveTiles(tiles)
        syncPinnedShortcuts(tile.packageName)
        drawerAdapter.notifyDataSetChanged()
        if (tiles.isEmpty()) tileAdapter.exitEditMode()
        root.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        offerUndo("Unpinned ${tile.title.ifEmpty { "tile" }}", onUndo = {
            tileAdapter.insertTile(tile, index)
            prefs.saveTiles(tiles)
            syncPinnedShortcuts(tile.packageName)
            drawerAdapter.notifyDataSetChanged()
        }, onExpire = {
            when (tile.type) {
                TileType.WIDGET -> widgets.delete(tile.appWidgetId)
                TileType.PHOTOS, TileType.QUICK_CONTACT -> TileMedia.deleteTile(this, tile.id)
                else -> Unit
            }
        })
    }

    /** Shows the Undo bar for 4 s; [onExpire] runs when Undo is no longer possible. */
    private fun offerUndo(message: String, onUndo: () -> Unit, onExpire: () -> Unit) {
        pendingUndoExpiry?.let {
            handler.removeCallbacks(it)
            it.run()
        }
        val expire = Runnable {
            pendingUndoExpiry = null
            onExpire()
            undoBar.animate().alpha(0f).translationY(ui.dp(24).toFloat()).setStartDelay(0).setDuration(180)
                .withEndAction { undoBar.visibility = View.GONE }.start()
        }
        pendingUndoExpiry = expire
        findViewById<TextView>(R.id.tv_undo_message).text = message
        findViewById<View>(R.id.btn_undo).setOnClickListener {
            if (pendingUndoExpiry !== expire) return@setOnClickListener
            handler.removeCallbacks(expire)
            pendingUndoExpiry = null
            onUndo()
            undoBar.animate().alpha(0f).setStartDelay(0).setDuration(150).withEndAction { undoBar.visibility = View.GONE }.start()
        }
        (undoBar.layoutParams as FrameLayout.LayoutParams).bottomMargin = systemInsets.bottom + ui.dp(84)
        undoBar.visibility = View.VISIBLE
        undoBar.alpha = 0f
        undoBar.translationY = ui.dp(24).toFloat()
        undoBar.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(220).setInterpolator(DecelerateInterpolator(2f)).start()
        handler.postDelayed(expire, 4000)
    }

    private fun removeTilesFor(packageName: String) {
        tiles.filter { it.packageName == packageName && it.type == TileType.APP_SHORTCUT }.forEach { tileAdapter.removeTile(it) }
        tiles.filter { it.type == TileType.FOLDER }.forEach { folder ->
            folder.children.filter { it.packageName == packageName }.forEach { app ->
                folder.children.remove(app)
            }
            when (folder.children.size) {
                0 -> tileAdapter.removeTile(folder)
                1 -> tileAdapter.replaceTile(folder, folder.children.first())
                else -> tileAdapter.refresh(folder)
            }
        }
        prefs.saveTiles(tiles)
    }

    private fun addTile(tile: TileItem) {
        tileAdapter.insertTile(tile)
        prefs.saveTiles(tiles)
        drawerAdapter.notifyDataSetChanged()
        if (pager.currentItem != LauncherPagerAdapter.PAGE_TILES) pager.setCurrentItem(LauncherPagerAdapter.PAGE_TILES, true)
        rvTiles?.postDelayed({ rvTiles?.smoothScrollToPosition(tileAdapter.positionOf(tile)) }, 250)
    }

    private fun pinApp(app: AppLauncherHelper.AppEntry, size: TileSize) {
        addTile(TileItem(UUID.randomUUID().toString(), TileType.APP_SHORTCUT, app.name, app.packageName, size))
        toast("Pinned ${app.name} to Start")
    }

    private fun pinShortcut(packageName: String, shortcutId: String, label: String) {
        addTile(TileItem(UUID.randomUUID().toString(), TileType.APP_SHORTCUT, label, packageName, TileSize.MEDIUM, shortcutId = shortcutId))
        syncPinnedShortcuts(packageName)
        toast("Pinned \"$label\" to Start")
    }

    /** Keeps every shortcut that Start shows for [packageName] pinned with the system. */
    private fun syncPinnedShortcuts(packageName: String?) {
        packageName ?: return
        val ids = (tiles + tiles.flatMap { it.children })
            .filter { it.packageName == packageName && it.shortcutId != null }
            .mapNotNull { it.shortcutId }
        shortcuts.syncPinned(packageName, ids)
    }

    // ── Opening things ──────────────────────────────────────────────────────────────────

    private fun handleTileClick(tile: TileItem, view: View) {
        when (tile.type) {
            TileType.APP_SHORTCUT -> openAppTile(tile, view)
            TileType.FOLDER -> {
                val panel = tileAdapter.toggleFolder(tile)
                if (panel >= 0) rvTiles?.postDelayed({ rvTiles?.smoothScrollToPosition(panel) }, 120)
            }
            TileType.CLOCK_WEATHER -> launchIntent(Intent(AlarmClock.ACTION_SHOW_ALARMS), view)
            TileType.CALENDAR_BIG -> launchIntent(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR), view)
            TileType.WEATHER_LIVE -> if (weather.hasLocation) showWeatherPanel() else showWeatherSetup()
            TileType.BATTERY_STATUS -> launchIntent(Intent(Intent.ACTION_POWER_USAGE_SUMMARY), view, Intent(Settings.ACTION_SETTINGS))
            TileType.STORAGE_STATS -> launchIntent(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS), view, Intent(Settings.ACTION_SETTINGS))
            TileType.EXPRESS_SEARCH -> openSearch()
            TileType.KEYBOARD_SETTINGS -> launchIntent(Intent(this, MainActivity::class.java), view)
            TileType.DEVICE_SETTINGS -> launchIntent(Intent(Settings.ACTION_SETTINGS), view)
            TileType.QUICK_CONTACT -> {
                val intent = if (tile.contactPhone.isNotEmpty()) Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(tile.contactPhone)}"))
                else Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CONTACTS)
                launchIntent(intent, view)
            }
            TileType.MEDIA_PLAYER -> {
                val playingApp = media.packageName
                when {
                    !NotificationHub.isAccessGranted(this) -> requestNotificationAccess()
                    playingApp != null -> launchApp(playingApp, view)
                    else -> launchIntent(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC), view)
                }
            }
            TileType.PHOTOS -> {
                if (TileMedia.photos(this, tile.id).isEmpty()) choosePhotos(tile)
                else launchIntent(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY), view)
            }
            TileType.WIDGET, TileType.SECTION_HEADER -> Unit
        }
    }

    /** App tiles open the app, or their pinned shortcut when they have one. */
    private fun openAppTile(tile: TileItem, view: View) {
        val pkg = tile.packageName ?: return
        val shortcutId = tile.shortcutId
        if (shortcutId == null) {
            launchApp(pkg, view)
            return
        }
        launchWithMotion(view) { options ->
            val bounds = Rect().also { view.getGlobalVisibleRect(it) }
            shortcuts.start(pkg, shortcutId, bounds, options) || run {
                toast("That shortcut is no longer available")
                false
            }
        }
    }

    private fun launchApp(packageName: String, source: View?) {
        val intent = appHelper.launchIntentFor(packageName)
        if (intent == null) {
            toast("That app isn't available")
            return
        }
        prefs.recordAppLaunch(packageName, tiles)?.let { pendingRestyles.add(it.id) }
        launchIntent(intent, source)
    }

    /** Starts [intent] with Metro motion; falls back to [fallback] if nothing handles it. */
    private fun launchIntent(intent: Intent, source: View?, fallback: Intent? = null) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launchWithMotion(source) { options ->
            try {
                startActivity(intent, options)
                true
            } catch (_: ActivityNotFoundException) {
                try {
                    if (fallback != null) {
                        startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        true
                    } else {
                        toast("No app can open that")
                        false
                    }
                } catch (_: Exception) {
                    toast("No app can open that")
                    false
                }
            } catch (_: SecurityException) {
                toast("That app can't be opened from here")
                false
            }
        }
    }

    /**
     * The Windows 10 Mobile launch: the other tiles turnstile away, then [start] opens the app,
     * which the system zooms out of the tapped tile. [start] returns false if nothing opened.
     */
    private fun launchWithMotion(source: View?, start: (Bundle?) -> Boolean) {
        val run = {
            val options = source?.takeIf { it.isAttachedToWindow && it.width > 0 }
                ?.let { ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height).toBundle() }
            if (!start(options)) playEntranceIfNeeded()
        }
        val rv = rvTiles
        if (prefs.animationsEnabled && rv != null && source != null && source.parent === rv) {
            tilesTurnedOut = true
            MetroMotion.turnstileOut(rv, source, { tileAdapter.motionView(rv, it) }) { run() }
            handler.removeCallbacks(turnstileSafety)
            handler.postDelayed(turnstileSafety, 1500)
        } else {
            run()
        }
    }

    private fun playEntranceIfNeeded() {
        val rv = rvTiles ?: return
        if (!playEntrance && !tilesTurnedOut) return
        playEntrance = false
        tilesTurnedOut = false
        handler.removeCallbacks(turnstileSafety)
        val motionView = { child: View -> tileAdapter.motionView(rv, child) }
        if (!prefs.animationsEnabled || pager.currentItem != LauncherPagerAdapter.PAGE_TILES || metroOverlay.isShowing) {
            MetroMotion.resetTiles(rv, motionView)
            return
        }
        val yourDay = dayEntrance
        dayEntrance = false
        rv.doOnLayout {
            if (yourDay) {
                // Unlocked: today's info and unread tiles arrive first, then unread tiles flip.
                MetroMotion.turnstileIn(rv, motionView) { child -> tileAdapter.priorityOf(rv, child) }
                if (prefs.liveTilesEnabled) rv.postDelayed({ tileAdapter.pulseUnread() }, 650)
            } else {
                MetroMotion.turnstileIn(rv, motionView)
            }
        }
    }

    private fun webSearch(query: String) {
        val intent = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
        val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query)))
        launchIntent(intent, null, fallback)
    }

    private fun togglePage() {
        val target = if (pager.currentItem == LauncherPagerAdapter.PAGE_TILES) LauncherPagerAdapter.PAGE_DRAWER else LauncherPagerAdapter.PAGE_TILES
        pager.setCurrentItem(target, true)
    }

    private fun requestNotificationAccess() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            toast("Turn on access for ${getString(R.string.app_name)} to make tiles live")
        } catch (_: Exception) {
            toast("Open Settings › Notifications › Device & app notifications")
        }
    }

    private fun requestCalendarAccess() {
        if (AgendaProvider.hasPermission(this)) {
            openAppInfo(packageName)
        } else {
            calendarPermission.launch(Manifest.permission.READ_CALENDAR)
        }
    }

    private fun refreshAgenda() {
        if (tiles.none { it.type == TileType.CALENDAR_BIG }) return
        AgendaProvider.loadAsync(this) { events -> tileAdapter.agenda = events }
    }

    // ── Weather ─────────────────────────────────────────────────────────────────────────

    /** Refreshes when a Weather tile is pinned and the report is old (or [force]). */
    private fun refreshWeather(force: Boolean, onDone: (() -> Unit)? = null) {
        if (!weather.hasLocation || tiles.none { it.type == TileType.WEATHER_LIVE } && !force) return
        if (!force && !weather.isStale) return
        weather.refresh { _, error ->
            tileAdapter.onWeatherChanged()
            if (error != null && force) toast(error)
            onDone?.invoke()
        }
    }

    private fun deg(v: Double) = "${Math.round(v)}°"

    /** Forecast panel opened from the Weather tile. */
    private fun showWeatherPanel() {
        val report = weather.report
        val card = ui.card()
        card.addView(ui.header(report?.place?.ifEmpty { "Weather" } ?: prefs.weatherPlace.ifEmpty { "Weather" }, report?.let { updatedText(it) } ?: "Updating…"))
        if (report != null) {
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.dp(18), ui.dp(4), ui.dp(18), ui.dp(8))
                addView(ui.icon(WeatherCodes.icon(report.code, report.isDay), 56))
                addView(ui.text(deg(report.temperature), 54f, face = lightFace).apply { setPadding(ui.dp(14), 0, ui.dp(14), 0) })
                addView(LinearLayout(this@LauncherActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(ui.text(WeatherCodes.describe(report.code), 17f))
                    addView(ui.text("Feels like ${deg(report.feelsLike)}", 13f, 0xCCFFFFFF.toInt()))
                    val wind = if (report.unit == "F") "mph" else "km/h"
                    addView(ui.text("Humidity ${report.humidity}% · Wind ${Math.round(report.windSpeed)} $wind", 13f, 0xCCFFFFFF.toInt()))
                })
            })
            card.addView(ui.sectionTitle("Next days"))
            report.days.forEachIndexed { i, d ->
                val name = if (i == 0) "Today" else runCatching {
                    val p = d.date.split("-").map { it.toInt() }
                    DateFormat.format("EEEE", Calendar.getInstance().apply { set(p[0], p[1] - 1, p[2]) }).toString()
                }.getOrDefault(d.date)
                card.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = ui.dp(44)
                    setPadding(ui.dp(20), 0, ui.dp(20), 0)
                    addView(ui.text(name, 15f).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
                    addView(ui.icon(WeatherCodes.icon(d.code, true), 22))
                    addView(ui.text("${deg(d.max)}  ${deg(d.min)}", 15f).apply {
                        gravity = Gravity.END
                        layoutParams = LinearLayout.LayoutParams(ui.dp(84), ViewGroup.LayoutParams.WRAP_CONTENT)
                    })
                })
            }
        }
        card.addView(ui.divider())
        card.addView(ui.chips(listOf("°C", "°F"), if (prefs.weatherUnit == "F") 1 else 0) { i ->
            prefs.weatherUnit = if (i == 1) "F" else "C"
            refreshWeather(force = true) { if (metroOverlay.isShowing) showWeatherPanel() }
        })
        card.addView(ui.action(R.drawable.ic_m_reset, "Refresh") {
            refreshWeather(force = true) { if (metroOverlay.isShowing) showWeatherPanel() }
        })
        card.addView(ui.action(R.drawable.ic_m_location, "Change location", if (prefs.weatherUseDevice) "Following your location" else prefs.weatherPlace) {
            showWeatherSetup()
        })
        val weatherApp = allApps.firstOrNull { it.name.lowercase().contains("weather") || it.packageName.contains("weather") }
        if (weatherApp != null) {
            card.addView(ui.action(R.drawable.ic_m_apps, "Open ${weatherApp.name}") {
                metroOverlay.dismiss()
                launchApp(weatherApp.packageName, null)
            })
        }
        card.addView(ui.caption("Weather data by Open-Meteo.com"))
        metroOverlay.show(scrollableSheet(card), MetroOverlay.Style.SHEET)
    }

    private fun updatedText(r: WeatherReport): String {
        val minutes = (System.currentTimeMillis() - r.fetchedAt) / 60_000
        return when {
            minutes < 1 -> "Updated just now"
            minutes < 60 -> "Updated $minutes min ago"
            minutes < 48 * 60 -> "Updated ${minutes / 60} h ago"
            else -> "Updated ${minutes / 1440} days ago"
        }
    }

    /** Choose where the weather is for: the phone's location, or a searched city. */
    private fun showWeatherSetup() {
        val card = ui.card()
        card.addView(ui.header("Weather location"))
        card.addView(ui.action(R.drawable.ic_m_location, "Use my location", "Approximate location, updated as you move") {
            if (weather.hasLocationPermission()) useDeviceLocationForWeather()
            else locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        })
        card.addView(ui.sectionTitle("Or search for a city"))
        val input = ui.input("", "City name")
        card.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(ui.dp(16), 0, ui.dp(16), ui.dp(8))
        })
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(results)
        var pending: Runnable? = null
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString().orEmpty()
                pending?.let { handler.removeCallbacks(it) }
                // Wait for a pause in typing before asking the server.
                val search = Runnable {
                    weather.searchPlaces(q) { places ->
                        if (input.text.toString() != q) return@searchPlaces
                        results.removeAllViews()
                        if (places.isEmpty() && q.trim().length >= 2) results.addView(ui.caption("No places found (or no connection)."))
                        places.forEach { place ->
                            results.addView(ui.action(R.drawable.ic_m_location, place.name, place.detail) {
                                hideKeyboard(input)
                                weather.setPlace(place, useDevice = false)
                                metroOverlay.dismiss()
                                refreshWeather(force = true)
                                tileAdapter.onWeatherChanged()
                            })
                        }
                    }
                }
                pending = search
                handler.postDelayed(search, 400)
            }
        })
        metroOverlay.show(scrollableSheet(card), MetroOverlay.Style.SHEET) { hideKeyboard(input) }
        input.post {
            input.requestFocus()
            getSystemService(InputMethodManager::class.java)?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun useDeviceLocationForWeather() {
        toast("Finding your location…")
        weather.locateDevice { place ->
            if (place == null) {
                toast("Couldn't get your location. Turn on Location, or search for a city.")
                return@locateDevice
            }
            weather.setPlace(place, useDevice = true)
            metroOverlay.dismiss()
            refreshWeather(force = true)
            tileAdapter.onWeatherChanged()
        }
    }

    // ── Gestures ────────────────────────────────────────────────────────────────────────

    private fun runSwipeDownGesture() {
        when (prefs.swipeDownAction) {
            "notifications" -> if (!GestureActions.expandNotifications(this)) promptGestureHelper("open the notification shade")
            "search" -> openSearch()
        }
    }

    private fun runDoubleTapGesture() {
        when (prefs.doubleTapAction) {
            "lock" -> if (!GestureActions.lockScreen()) promptGestureHelper("lock the screen")
            "glance" -> {
                startActivity(Intent(this, GlanceActivity::class.java))
                @Suppress("DEPRECATION")
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
        }
    }

    // ── Accent from wallpaper ───────────────────────────────────────────────────────────

    /** Picks the accent from the wallpaper's main colour, adjusted so white text stays readable. */
    private fun applyWallpaperAccent() {
        if (!prefs.accentFromWallpaper || Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return
        val colors = try {
            WallpaperManager.getInstance(this).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        } catch (_: Exception) {
            null
        } ?: return
        val candidates = listOfNotNull(colors.primaryColor, colors.secondaryColor, colors.tertiaryColor).map { it.toArgb() }
        val hsv = FloatArray(3)
        // Prefer the most colourful of the wallpaper's main colours.
        val best = candidates.maxByOrNull { Color.colorToHSV(it, hsv); hsv[1] * (0.3f + hsv[2]) } ?: return
        Color.colorToHSV(best, hsv)
        hsv[1] = if (hsv[1] < 0.15f) hsv[1] else hsv[1].coerceIn(0.45f, 0.95f)
        hsv[2] = hsv[2].coerceIn(0.35f, 0.72f)
        val hex = String.format("#%06X", 0xFFFFFF and Color.HSVToColor(hsv))
        if (hex != prefs.accentColor) {
            prefs.accentColor = hex
            if (::tileAdapter.isInitialized) applyLookAndFeel()
        }
    }

    // ── Notifications for a tile ────────────────────────────────────────────────────────

    private fun showTileNotifications(tile: TileItem) {
        val packages = if (tile.type == TileType.FOLDER) tile.children.mapNotNull { it.packageName } else listOfNotNull(tile.packageName)
        val items = packages.flatMap { pkg -> NotificationHub.get(pkg)?.items.orEmpty().map { pkg to it } }
            .sortedByDescending { it.second.postTime }
        if (items.isEmpty()) {
            toast("No notifications")
            return
        }
        val card = ui.card()
        card.addView(ui.header(tile.title, "${items.size} notification${if (items.size == 1) "" else "s"}"))
        items.forEach { (pkg, item) ->
            val ago = DateUtils.getRelativeTimeSpanString(item.postTime, System.currentTimeMillis(), 60_000L).toString()
            card.addView(ui.actionWithIcon(
                icon = icons.icon(pkg),
                title = item.title.ifEmpty { tile.title },
                subtitle = listOf(item.text, ago).filter { it.isNotEmpty() }.joinToString(" · "),
                trailingIcon = if (item.clearable) R.drawable.ic_m_close else null,
                trailingDescription = "Dismiss",
                onTrailing = {
                    NotificationHub.dismiss(listOf(item.key))
                    metroOverlay.dismiss()
                }
            ) {
                metroOverlay.dismiss()
                try {
                    item.contentIntent?.send() ?: launchApp(pkg, null)
                } catch (_: Exception) {
                    launchApp(pkg, null)
                }
            })
        }
        if (items.any { it.second.clearable }) {
            card.addView(ui.divider())
            card.addView(ui.action(R.drawable.ic_m_delete, "Clear all") {
                NotificationHub.dismiss(items.filter { it.second.clearable }.map { it.second.key })
                metroOverlay.dismiss()
            })
        }
        metroOverlay.show(scrollableSheet(card), MetroOverlay.Style.SHEET)
    }

    // ── Private apps ────────────────────────────────────────────────────────────────────

    private fun setHidden(packageName: String, hidden: Boolean) {
        prefs.hiddenApps = if (hidden) prefs.hiddenApps + packageName else prefs.hiddenApps - packageName
        refreshDrawer()
        refreshSuggestions(force = true)
        toast(if (hidden) "Moved to Private apps" else "Back in All apps")
    }

    /** Asks for the phone's fingerprint, face or PIN before showing Private apps. */
    private fun unlockPrivateApps() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isDeviceSecure) {
            showPrivateApps()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val prompt = BiometricPrompt.Builder(this)
                .setTitle("Private apps")
                .setSubtitle("Confirm it's you")
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()
            prompt.authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = showPrivateApps()
            })
        } else {
            @Suppress("DEPRECATION")
            val intent = keyguard.createConfirmDeviceCredentialIntent("Private apps", "Confirm it's you")
            if (intent != null) privateUnlock.launch(intent) else showPrivateApps()
        }
    }

    private fun showPrivateApps() {
        val hidden = prefs.hiddenApps
        val apps = allApps.filter { it.packageName in hidden }
        val card = ui.card()
        card.addView(ui.header("Private apps", "Hidden from All apps and search"))
        if (apps.isEmpty()) card.addView(ui.caption("Nothing here. Long-press an app in All apps and choose Move to Private apps."))
        apps.forEach { app ->
            card.addView(ui.actionWithIcon(
                icon = icons.icon(app.packageName),
                title = app.name,
                trailingIcon = R.drawable.ic_m_move_out,
                trailingDescription = "Show in All apps",
                onTrailing = {
                    metroOverlay.dismiss()
                    setHidden(app.packageName, hidden = false)
                }
            ) {
                metroOverlay.dismiss()
                launchApp(app.packageName, null)
            })
        }
        metroOverlay.show(scrollableSheet(card), MetroOverlay.Style.SHEET)
    }

    private fun promptGestureHelper(what: String) {
        val card = ui.card().apply { setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(16)) }
        card.addView(ui.text("Turn on the gesture helper", 22f, face = lightFace))
        card.addView(ui.text(
            "To $what, Android needs the launcher's gesture helper enabled in Accessibility settings. It only performs the gesture and reads nothing on screen.",
            14f, 0xCCFFFFFF.toInt()
        ).apply { setPadding(0, ui.dp(8), 0, ui.dp(16)) })
        card.addView(buttonRow(
            ui.button("Not now", filled = false) { metroOverlay.dismiss() },
            ui.button("Open settings", filled = true) {
                metroOverlay.dismiss()
                openAccessibilitySettings()
            }
        ))
        metroOverlay.show(card, MetroOverlay.Style.DIALOG)
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    // ── Search panel ────────────────────────────────────────────────────────────────────

    private fun setupSearchPanel() {
        LauncherKeyboardController(
            context = this,
            keyboardView = keyboardView,
            searchEditText = etSearch,
            onTextUpdated = { filterSearch(it) },
            onActionSubmit = { runTopSearchResult() }
        )
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterSearch(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        findViewById<View>(R.id.btn_clear_search).setOnClickListener { etSearch.setText("") }
        findViewById<View>(R.id.btn_close_keyboard_search).setOnClickListener { closeSearch() }
        btnWebSearch.setOnClickListener {
            val query = etSearch.text.toString().trim()
            if (query.isNotEmpty()) {
                webSearch(query)
                closeSearch()
            }
        }
    }

    private fun openSearch() {
        if (searchPanel.visibility == View.VISIBLE) return
        tileAdapter.exitEditMode()
        metroOverlay.dismiss()
        searchBox.background = GradientDrawable().apply {
            setColor(0x1AFFFFFF)
            setStroke(ui.dp(2), prefs.accentColorInt)
        }
        searchPanel.visibility = View.VISIBLE
        searchPanel.alpha = 0f
        searchPanel.animate().alpha(1f).setStartDelay(0).setDuration(160).start()
        keyboardView.translationY = ui.dp(320).toFloat()
        keyboardView.animate().translationY(0f).setStartDelay(0).setDuration(320).setInterpolator(DecelerateInterpolator(2.2f)).start()
        etSearch.requestFocus()
        filterSearch(etSearch.text.toString())
    }

    private fun closeSearch() {
        if (searchPanel.visibility != View.VISIBLE) return
        keyboardView.animate().translationY(keyboardView.height.toFloat()).setStartDelay(0).setDuration(200).start()
        searchPanel.animate().alpha(0f).setStartDelay(0).setDuration(220).withEndAction {
            searchPanel.visibility = View.GONE
            etSearch.setText("")
        }.start()
    }

    private fun filterSearch(query: String) {
        val clean = query.trim()
        val math = mathCalc.evaluate(clean)
        tvMathResult.visibility = if (math != null) View.VISIBLE else View.GONE
        if (math != null) tvMathResult.text = "= $math"
        btnWebSearch.visibility = if (clean.isNotEmpty()) View.VISIBLE else View.GONE
        tvWebSearchLabel.text = "Search the web for “$clean”"
        searchActionStrip.visibility = if (math != null || clean.isNotEmpty()) View.VISIBLE else View.GONE

        searchResults = if (clean.isEmpty()) mostUsedApps(8).ifEmpty { visibleApps().take(8) } else rankApps(clean)
        searchAdapter.submit(searchResults, grouped = false)
    }

    private fun rankApps(query: String): List<AppLauncherHelper.AppEntry> =
        AppSearch.rank(visibleApps(), query, appHelper.findMatchingApp(query.trim().lowercase())?.takeIf { it.packageName !in prefs.hiddenApps })

    private fun mostUsedApps(count: Int): List<AppLauncherHelper.AppEntry> {
        val usage = prefs.usageCounts()
        return visibleApps().filter { (usage[it.packageName] ?: 0) > 0 }
            .sortedByDescending { usage[it.packageName] ?: 0 }
            .take(count)
    }

    private fun runTopSearchResult() {
        val first = searchResults.firstOrNull()
        val query = etSearch.text.toString().trim()
        if (first != null && query.isNotEmpty()) {
            launchApp(first.packageName, null)
            closeSearch()
        } else if (query.isNotEmpty()) {
            webSearch(query)
            closeSearch()
        }
    }

    // ── Menus ───────────────────────────────────────────────────────────────────────────

    private fun scrollableSheet(card: LinearLayout): ScrollView = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        background = card.background
        elevation = card.elevation
        card.background = null
        card.elevation = 0f
        addView(card)
    }

    /** "…" menu on the header, and long-press on empty Start space. */
    private fun showStartMenu(anchor: View?) {
        val card = ui.card()
        card.addView(ui.action(R.drawable.ic_m_add, "Add tiles") { showAddTileSheet() })
        card.addView(ui.action(R.drawable.ic_m_widgets, "Add a widget") { showWidgetPicker() })
        card.addView(ui.action(R.drawable.ic_m_resize, "Customise Start", "Move, resize, recolour, make folders") {
            metroOverlay.dismiss()
            if (pager.currentItem != LauncherPagerAdapter.PAGE_TILES) pager.setCurrentItem(LauncherPagerAdapter.PAGE_TILES, true)
            tileAdapter.enterEditMode(tiles.firstOrNull()?.id)
        })
        card.addView(ui.action(R.drawable.ic_m_palette, "Accent colour") { settingsPage.show(scrollToColors = true) })
        card.addView(ui.action(R.drawable.ic_m_settings, "Settings") { settingsPage.show() })
        metroOverlay.show(card, if (anchor != null) MetroOverlay.Style.POPUP else MetroOverlay.Style.SHEET, anchor)
    }

    /** Shortcut rows for an app, each with a pin button. */
    private fun addShortcutRows(card: LinearLayout, packageName: String) {
        val list = shortcuts.forPackage(packageName)
        if (list.isEmpty()) return
        card.addView(ui.sectionTitle("Shortcuts"))
        list.forEach { info ->
            val label = info.shortLabel?.toString() ?: info.longLabel?.toString() ?: return@forEach
            card.addView(ui.actionWithIcon(
                icon = shortcuts.icon(info) ?: icons.icon(packageName),
                title = label,
                trailingIcon = R.drawable.ic_m_pin,
                trailingDescription = "Pin to Start",
                onTrailing = {
                    metroOverlay.dismiss()
                    pinShortcut(packageName, info.id, label)
                }
            ) {
                metroOverlay.dismiss()
                if (!shortcuts.start(packageName, info.id, null, null)) toast("That shortcut is no longer available")
            })
        }
        card.addView(ui.divider())
    }

    /** The per-tile customisation menu: size, colour, name, live on/off and type-specific actions. */
    private fun showTileMenu(tile: TileItem) {
        val card = ui.card()
        val isHeader = tile.type == TileType.SECTION_HEADER
        card.addView(ui.header(tile.title.ifEmpty { "Tile" }, describe(tile)))
        if (!isHeader) {
            card.addView(ui.sectionTitle("Size"))
            val sizes = TileSize.entries
            card.addView(ui.chips(sizes.map { it.label }, sizes.indexOf(tile.size)) { i ->
                if (tile.size != sizes[i]) {
                    tile.size = sizes[i]
                    tile.sizeLocked = true
                    restyle(tile)
                }
            })
            if (tile.type != TileType.WIDGET) {
                card.addView(ui.sectionTitle("Colour"))
                val swatches: List<Int?> = listOf<Int?>(null) + METRO_ACCENTS.map { Color.parseColor(it.second) }
                val current = tile.accentColorHex?.let { runCatching { Color.parseColor(it) }.getOrNull() }
                card.addView(ui.swatches(swatches, current) { picked ->
                    tile.accentColorHex = picked?.let { String.format("#%06X", 0xFFFFFF and it) }
                    restyle(tile)
                })
            }
            card.addView(ui.divider())
        }
        if (tile.type == TileType.APP_SHORTCUT && tile.shortcutId == null) tile.packageName?.let { addShortcutRows(card, it) }

        if (tile.type != TileType.WIDGET) {
            card.addView(ui.action(R.drawable.ic_m_edit, when (tile.type) {
                TileType.SECTION_HEADER -> "Rename group"
                TileType.FOLDER -> "Name folder"
                else -> "Rename"
            }) {
                prompt(if (isHeader) "Rename group" else "Rename tile", tile.title, "Name") { name ->
                    tile.title = name
                    restyle(tile)
                }
            })
        }
        if (hasLiveFace(tile)) {
            card.addView(ui.toggleRow("Live tile", "Flip to show live info", tile.liveEnabled) { on ->
                tile.liveEnabled = on
                restyle(tile)
            })
        }
        val unread = (if (tile.type == TileType.FOLDER) tile.children.mapNotNull { it.packageName } else listOfNotNull(tile.packageName))
            .sumOf { NotificationHub.get(it)?.count ?: 0 }
        if (unread > 0) {
            card.addView(ui.action(R.drawable.ic_m_notifications, "Notifications ($unread)", "Tip: swipe across the tile") {
                metroOverlay.dismiss()
                showTileNotifications(tile)
            })
        }
        when (tile.type) {
            TileType.APP_SHORTCUT -> {
                card.addView(ui.action(R.drawable.ic_m_folder, "Add to a folder") { showFolderPicker(tile) })
                tile.packageName?.let { pkg ->
                    card.addView(ui.action(R.drawable.ic_m_info, "App info") { metroOverlay.dismiss(); openAppInfo(pkg) })
                    card.addView(ui.action(R.drawable.ic_m_delete, "Uninstall", danger = true) { metroOverlay.dismiss(); uninstall(pkg) })
                }
            }
            TileType.FOLDER -> card.addView(ui.action(R.drawable.ic_m_apps, "Ungroup", "Put the apps back on Start") {
                metroOverlay.dismiss()
                ungroup(tile)
            })
            TileType.PHOTOS -> card.addView(ui.action(R.drawable.ic_m_photo, "Choose photos") { metroOverlay.dismiss(); choosePhotos(tile) })
            TileType.QUICK_CONTACT -> card.addView(ui.action(R.drawable.ic_m_photo, "Choose photo") {
                metroOverlay.dismiss()
                pictureTarget = tile
                portraitPicker.launch("image/*")
            })
            TileType.CALENDAR_BIG -> if (!AgendaProvider.hasPermission(this)) {
                card.addView(ui.action(R.drawable.ic_m_calendar, "Show my events") { metroOverlay.dismiss(); requestCalendarAccess() })
            }
            else -> Unit
        }
        card.addView(ui.action(R.drawable.ic_m_unpin, if (tile.type == TileType.FOLDER) "Unpin folder and its apps" else "Unpin from Start", danger = true) {
            metroOverlay.dismiss()
            unpin(tile)
        })
        metroOverlay.show(scrollableSheet(card), MetroOverlay.Style.SHEET)
    }

    private fun hasLiveFace(tile: TileItem) = when (tile.type) {
        TileType.APP_SHORTCUT -> tile.shortcutId == null
        TileType.CLOCK_WEATHER, TileType.CALENDAR_BIG, TileType.BATTERY_STATUS,
        TileType.STORAGE_STATS, TileType.QUICK_CONTACT, TileType.PHOTOS -> true
        else -> false
    }

    private fun describe(tile: TileItem): String = when (tile.type) {
        TileType.APP_SHORTCUT -> if (tile.shortcutId != null) "Shortcut · ${tile.size.label}" else "App · ${tile.size.label}"
        TileType.SECTION_HEADER -> "Group name"
        TileType.FOLDER -> "Folder · ${tile.children.size} apps"
        TileType.WIDGET -> "Widget · ${tile.size.label}"
        TileType.QUICK_CONTACT -> listOf("Person", tile.contactPhone).filter { it.isNotEmpty() }.joinToString(" · ")
        else -> "${tile.title} · ${tile.size.label}"
    }

    /** "Add to a folder": existing folders, or a new folder together with another app tile. */
    private fun showFolderPicker(app: TileItem) {
        val card = ui.card()
        card.addView(ui.header("Add ${app.title} to…"))
        val folders = tiles.filter { it.type == TileType.FOLDER }
        folders.forEach { folder ->
            card.addView(ui.action(R.drawable.ic_m_folder, folder.title, "${folder.children.size} apps") {
                metroOverlay.dismiss()
                mergeIntoFolder(app, folder)
            })
        }
        val partners = tiles.filter { it.type == TileType.APP_SHORTCUT && it !== app }
        if (partners.isNotEmpty()) {
            card.addView(ui.sectionTitle("New folder with"))
            partners.forEach { other ->
                card.addView(ui.actionWithIcon(icons.icon(other.packageName), other.title) {
                    metroOverlay.dismiss()
                    mergeIntoFolder(app, other)
                })
            }
        }
        if (folders.isEmpty() && partners.isEmpty()) card.addView(ui.caption("Pin another app first, then group them."))
        metroOverlay.show(scrollableSheet(card), MetroOverlay.Style.SHEET)
    }

    private fun showFolderAppMenu(folder: TileItem, app: TileItem, anchor: View) {
        val card = ui.card()
        card.addView(ui.header(app.title, "In ${folder.title}"))
        if (app.shortcutId == null) app.packageName?.let { addShortcutRows(card, it) }
        card.addView(ui.action(R.drawable.ic_m_move_out, "Move out of folder") {
            metroOverlay.dismiss()
            removeFromFolder(folder, app)
        })
        app.packageName?.let { pkg ->
            card.addView(ui.action(R.drawable.ic_m_info, "App info") { metroOverlay.dismiss(); openAppInfo(pkg) })
        }
        card.addView(ui.action(R.drawable.ic_m_unpin, "Unpin", danger = true) {
            metroOverlay.dismiss()
            folder.children.remove(app)
            when (folder.children.size) {
                0 -> tileAdapter.removeTile(folder)
                1 -> tileAdapter.replaceTile(folder, folder.children.first())
                else -> tileAdapter.refresh(folder)
            }
            prefs.saveTiles(tiles)
            drawerAdapter.notifyDataSetChanged()
        })
        metroOverlay.show(card, MetroOverlay.Style.POPUP, anchor)
    }

    private fun showDrawerAppMenu(app: AppLauncherHelper.AppEntry, anchor: View) {
        val card = ui.card()
        card.addView(ui.header(app.name))
        card.addView(ui.sectionTitle("Pin to Start"))
        val sizes = TileSize.entries
        card.addView(ui.chips(sizes.map { it.label }, -1) { i ->
            metroOverlay.dismiss()
            pinApp(app, sizes[i])
        })
        addShortcutRows(card, app.packageName)
        val pinned = tiles.filter { it.packageName == app.packageName && it.type == TileType.APP_SHORTCUT && it.shortcutId == null }
        if (pinned.isNotEmpty()) {
            card.addView(ui.action(R.drawable.ic_m_unpin, "Unpin from Start") {
                metroOverlay.dismiss()
                pinned.forEach { unpin(it) }
            })
        }
        val isHidden = app.packageName in prefs.hiddenApps
        card.addView(ui.action(R.drawable.ic_m_lock, if (isHidden) "Show in All apps" else "Move to Private apps") {
            metroOverlay.dismiss()
            setHidden(app.packageName, hidden = !isHidden)
        })
        card.addView(ui.action(R.drawable.ic_m_info, "App info") { metroOverlay.dismiss(); openAppInfo(app.packageName) })
        card.addView(ui.action(R.drawable.ic_m_delete, "Uninstall", danger = true) { metroOverlay.dismiss(); uninstall(app.packageName) })
        metroOverlay.show(scrollableSheet(card), if (shortcuts.isAvailable) MetroOverlay.Style.SHEET else MetroOverlay.Style.POPUP, anchor)
    }

    private fun showAddTileSheet() {
        val card = ui.card()
        card.addView(ui.header("Add to Start", "New tiles go to the end of Start"))
        fun add(icon: Int, title: String, subtitle: String, make: () -> Unit) {
            card.addView(ui.action(icon, title, subtitle) { make() })
        }
        fun tile(type: TileType, title: String, size: TileSize, subtitle: String = "") {
            metroOverlay.dismiss()
            addTile(TileItem(UUID.randomUUID().toString(), type, title, size = size, customSubtitle = subtitle))
            if (type == TileType.CALENDAR_BIG) refreshAgenda()
        }
        add(R.drawable.ic_m_apps, "App", "Pin any installed app") { showAppPicker() }
        add(R.drawable.ic_m_widgets, "Widget", "Any Android widget, inside a tile") { showWidgetPicker() }
        add(R.drawable.ic_m_person, "Person", "Call a contact in one tap") { metroOverlay.dismiss(); pickContact() }
        add(R.drawable.ic_m_photo, "Photos", "A live slideshow of photos you pick") {
            metroOverlay.dismiss()
            choosePhotos(null)
        }
        add(R.drawable.ic_m_sun, "Weather", "Live conditions and forecast") {
            tile(TileType.WEATHER_LIVE, "Weather", TileSize.WIDE)
            if (!weather.hasLocation) showWeatherSetup() else refreshWeather(force = true)
        }
        add(R.drawable.ic_m_music, "Music", "Now playing, with controls") { tile(TileType.MEDIA_PLAYER, "Music", TileSize.WIDE) }
        add(R.drawable.ic_m_clock, "Clock", "Time, date and next alarm") { tile(TileType.CLOCK_WEATHER, "Clock", TileSize.WIDE) }
        add(R.drawable.ic_m_calendar, "Calendar", "Today's date and upcoming events") { tile(TileType.CALENDAR_BIG, "Calendar", TileSize.WIDE) }
        add(R.drawable.ic_m_battery, "Battery", "Level, temperature, time to full") { tile(TileType.BATTERY_STATUS, "Battery", TileSize.MEDIUM) }
        add(R.drawable.ic_m_storage, "Device", "Storage and memory") { tile(TileType.STORAGE_STATS, "Device", TileSize.MEDIUM) }
        add(R.drawable.ic_m_search, "Search", "Apps, maths and web") { tile(TileType.EXPRESS_SEARCH, "Search", TileSize.WIDE) }
        add(R.drawable.ic_m_keyboard, "Keyboard settings", "Themes and layouts") { tile(TileType.KEYBOARD_SETTINGS, "Keyboard", TileSize.SMALL, "Themes & layouts") }
        add(R.drawable.ic_m_settings, "Phone settings", "Android settings") { tile(TileType.DEVICE_SETTINGS, "Settings", TileSize.SMALL) }
        add(R.drawable.ic_m_title, "Group name", "Start a new named group of tiles") {
            prompt("Name this group", "", "e.g. Work, Games") { name ->
                addTile(TileItem(UUID.randomUUID().toString(), TileType.SECTION_HEADER, name, size = TileSize.WIDE))
            }
        }
        metroOverlay.show(scrollableSheet(card), MetroOverlay.Style.SHEET)
    }

    private fun choosePhotos(tile: TileItem?) {
        pictureTarget = tile
        try {
            photosPicker.launch("image/*")
        } catch (_: ActivityNotFoundException) {
            toast("No photo picker on this phone")
        }
    }

    private fun showAppPicker() {
        val card = ui.card()
        card.addView(ui.header("Pin an app"))
        val filter = ui.input("", "Search apps")
        card.addView(filter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(ui.dp(16), 0, ui.dp(16), ui.dp(8))
        })
        val picker = AppDrawerAdapter(
            icons = icons,
            accent = { prefs.accentColorInt },
            tiltEnabled = { false },
            isPinned = { pkg -> pkg in allPinnedPackages() },
            onAppClick = { app, _ ->
                hideKeyboard(filter)
                metroOverlay.dismiss()
                pinApp(app, TileSize.MEDIUM)
            }
        )
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@LauncherActivity)
            adapter = picker
        }
        card.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.55f).toInt()))
        picker.submit(visibleApps(), grouped = false)
        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString().orEmpty()
                picker.submit(if (q.isBlank()) visibleApps() else rankApps(q), grouped = false)
            }
        })
        metroOverlay.show(card, MetroOverlay.Style.SHEET) { hideKeyboard(filter) }
    }

    // ── Widgets ─────────────────────────────────────────────────────────────────────────

    private fun showWidgetPicker() {
        val card = ui.card()
        card.addView(ui.header("Add a widget", "It goes in a tile you can resize like any other"))
        val providers = widgets.providers()
        if (providers.isEmpty()) card.addView(ui.caption("No apps on this phone offer widgets."))
        val pm = packageManager
        providers.forEach { info ->
            val app = try {
                pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString()
            } catch (_: Exception) {
                info.provider.packageName
            }
            val size = widgetTileSize(info)
            card.addView(ui.actionWithIcon(widgets.icon(this, info) ?: icons.icon(info.provider.packageName), widgets.label(info), "$app · ${size.label}") {
                metroOverlay.dismiss()
                addWidget(info, size)
            })
        }
        metroOverlay.show(scrollableSheet(card), MetroOverlay.Style.SHEET)
    }

    /** The smallest tile that fits the widget's minimum size. */
    private fun widgetTileSize(info: AppWidgetProviderInfo): TileSize {
        val pitch = gridLayoutManager.cellPitch.takeIf { it > 0f } ?: (resources.displayMetrics.widthPixels / prefs.columns.toFloat())
        val cols = ceil(info.minWidth / pitch).toInt()
        val rows = ceil(info.minHeight / pitch).toInt()
        return when {
            rows > 2 -> TileSize.LARGE
            cols > 2 -> TileSize.WIDE
            else -> TileSize.MEDIUM
        }
    }

    private fun addWidget(info: AppWidgetProviderInfo, size: TileSize) {
        val pending = PendingWidget(widgets.allocate(), widgets.label(info), size)
        pendingWidget = pending
        if (widgets.bindIfAllowed(pending.id, info)) {
            configureWidget(pending)
        } else {
            try {
                widgetBinder.launch(widgets.bindRequest(pending.id, info))
            } catch (_: ActivityNotFoundException) {
                cancelWidget()
                toast("This phone doesn't allow adding widgets")
            }
        }
    }

    private fun configureWidget(pending: PendingWidget) {
        if (widgets.needsConfiguration(pending.id) && widgets.startConfiguration(this, pending.id, REQUEST_CONFIGURE_WIDGET)) return
        finishWidget(pending)
    }

    private fun finishWidget(pending: PendingWidget) {
        pendingWidget = null
        addTile(TileItem(UUID.randomUUID().toString(), TileType.WIDGET, pending.label, size = pending.size, appWidgetId = pending.id))
    }

    private fun cancelWidget() {
        pendingWidget?.let { widgets.delete(it.id) }
        pendingWidget = null
    }

    @Deprecated("Widget configuration screens only report back through onActivityResult")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CONFIGURE_WIDGET) return
        val pending = pendingWidget ?: return
        if (resultCode == RESULT_OK) finishWidget(pending) else cancelWidget()
    }

    // ── Icon packs, backdrop, backup ────────────────────────────────────────────────────

    private fun showIconPackPicker() {
        val card = ui.card()
        card.addView(ui.header("Icon pack", "Packs made for Nova, ADW and similar launchers work here"))
        fun choose(pkg: String) {
            metroOverlay.dismiss()
            prefs.iconPack = pkg
            icons.setIconPack(pkg) { onIconsChanged() }
        }
        card.addView(ui.action(if (prefs.iconPack.isEmpty()) R.drawable.ic_m_check else R.drawable.ic_m_apps, "System icons") { choose("") })
        val packs = IconPacks.installed(this)
        packs.forEach { pack ->
            card.addView(ui.actionWithIcon(icons.icon(pack.packageName), pack.label, if (pack.packageName == prefs.iconPack) "In use" else null) {
                choose(pack.packageName)
            })
        }
        if (packs.isEmpty()) card.addView(ui.caption("No icon packs installed. Get one from the Play Store, then pick it here."))
        metroOverlay.show(scrollableSheet(card), MetroOverlay.Style.SHEET)
    }

    private fun onIconsChanged() {
        tileAdapter.refreshIcons()
        drawerAdapter.invalidateIcons()
        searchAdapter.invalidateIcons()
    }

    private fun currentIconPackLabel(): String {
        val pkg = prefs.iconPack.takeIf { it.isNotEmpty() } ?: return "System icons"
        return IconPacks.installed(this).firstOrNull { it.packageName == pkg }?.label ?: "System icons"
    }

    private fun loadBackdrop() {
        backdrop = null
        if (prefs.backgroundMode != "picture") return
        val dm = resources.displayMetrics
        val picture = TileMedia.loadNow(TileMedia.backgroundFile(this), maxOf(dm.widthPixels, dm.heightPixels)) ?: return
        backdrop = StartBackdrop(picture, dm.widthPixels, dm.heightPixels)
    }

    private val settingsHost = object : SettingsPage.Host {
        override fun applyLookAndFeel() = this@LauncherActivity.applyLookAndFeel()
        override fun applyPageTransition() = PageTransformers.apply(pager, prefs.pageTransition)
        override fun showAddTileSheet() = this@LauncherActivity.showAddTileSheet()
        override fun showWidgetPicker() = this@LauncherActivity.showWidgetPicker()
        override fun showIconPackPicker() = this@LauncherActivity.showIconPackPicker()
        override fun requestNotificationAccess() = this@LauncherActivity.requestNotificationAccess()
        override fun requestCalendarAccess() = this@LauncherActivity.requestCalendarAccess()
        override fun pickBackgroundPicture() = backgroundPicker.launch("image/*")
        override fun openAccessibilitySettings() = this@LauncherActivity.openAccessibilitySettings()
        override fun openHomeSettings() {
            try {
                startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
        override fun openKeyboardSettings() = startActivity(Intent(this@LauncherActivity, MainActivity::class.java))
        override fun exportBackup() = backupWriter.launch("start-backup.json")
        override fun importBackup() = backupReader.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        override fun confirmReset() = this@LauncherActivity.confirmReset()
        override fun showWeatherSetup() = this@LauncherActivity.showWeatherSetup()
        override fun applyWallpaperAccent() = this@LauncherActivity.applyWallpaperAccent()
        override fun showPrivateApps() = unlockPrivateApps()
        override val privateAppCount: Int get() = prefs.hiddenApps.size
        override fun setWeatherUnit(unit: String) {
            prefs.weatherUnit = unit
            refreshWeather(force = true)
        }
        override val weatherLocationLabel: String
            get() = when {
                !weather.hasLocation -> "Not set"
                prefs.weatherUseDevice -> "My location · ${prefs.weatherPlace}"
                else -> prefs.weatherPlace
            }
        override val iconPackLabel: String get() = currentIconPackLabel()
        override val notificationAccess: Boolean get() = NotificationHub.isAccessGranted(this@LauncherActivity)
        override val calendarAccess: Boolean get() = AgendaProvider.hasPermission(this@LauncherActivity)
        override val gestureServiceEnabled: Boolean get() = GestureActions.isServiceEnabled
        override val hasBackgroundPicture: Boolean get() = TileMedia.backgroundFile(this@LauncherActivity).exists()
    }

    /** Semantic-zoom jump list: tap a letter in All apps to jump straight to it. */
    private fun showJumpList() {
        val present = drawerAdapter.lettersPresent
        val letters = listOf(AppDrawerAdapter.OTHER_LETTER) + ('A'..'Z')
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(14), ui.dp(16), ui.dp(14), ui.dp(16))
        }
        val cells = ArrayList<View>()
        letters.chunked(4).forEach { rowLetters ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until 4) {
                val letter = rowLetters.getOrNull(i)
                val cell = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ui.dp(76), 1f).apply { setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) }
                    if (letter != null) {
                        val has = letter in present
                        text = if (letter == AppDrawerAdapter.OTHER_LETTER) "#" else letter.lowercaseChar().toString()
                        textSize = 30f
                        typeface = lightFace
                        setTextColor(if (has) Color.WHITE else 0x40FFFFFF)
                        gravity = Gravity.BOTTOM or Gravity.START
                        setPadding(ui.dp(10), 0, 0, ui.dp(4))
                        background = GradientDrawable().apply { setColor(if (has) prefs.accentColorInt else 0x14FFFFFF) }
                        if (has) setOnClickListener {
                            metroOverlay.dismiss()
                            val pos = drawerAdapter.positionOf(letter)
                            if (pos >= 0) (rvDrawer?.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(pos, 0)
                        }
                        cells.add(this)
                    }
                }
                row.addView(cell)
            }
            grid.addView(row)
        }
        metroOverlay.show(ScrollView(this).apply { addView(grid) }, MetroOverlay.Style.PANEL)
        // Zoom-out entrance: letters settle from slightly larger, one after another.
        cells.forEachIndexed { i, c ->
            c.alpha = 0f
            c.scaleX = 1.3f
            c.scaleY = 1.3f
            c.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(60L + i * 12L).setDuration(260)
                .setInterpolator(DecelerateInterpolator(2f)).start()
        }
    }

    private fun confirmReset() {
        val card = ui.card().apply { setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(16)) }
        card.addView(ui.text("Reset Start?", 22f, face = lightFace))
        card.addView(ui.text("Your tiles, folders, sizes and colours go back to the defaults. Settings are kept.", 14f, 0xCCFFFFFF.toInt()).apply {
            setPadding(0, ui.dp(8), 0, ui.dp(16))
        })
        card.addView(buttonRow(
            ui.button("Cancel", filled = false) { metroOverlay.dismiss() },
            ui.button("Reset", filled = true) {
                metroOverlay.dismiss()
                tileAdapter.exitEditMode()
                tileAdapter.closeFolder()
                tiles.filter { it.type == TileType.WIDGET }.forEach { widgets.delete(it.appWidgetId) }
                tiles.clear()
                tiles.addAll(prefs.resetToDefaults())
                prefs.appsSeeded = false
                seedAppTiles()
                tileAdapter.notifyDataSetChanged()
                drawerAdapter.notifyDataSetChanged()
                toast("Start has been reset")
            }
        ))
        metroOverlay.show(card, MetroOverlay.Style.DIALOG)
    }

    private fun prompt(title: String, initial: String, hint: String, onSave: (String) -> Unit) {
        val card = ui.card().apply { setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(16)) }
        card.addView(ui.text(title, 22f, face = lightFace))
        val input = ui.input(initial, hint)
        card.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ui.dp(12)
            bottomMargin = ui.dp(16)
        })
        val save = {
            val value = input.text.toString().trim()
            if (value.isNotEmpty()) {
                hideKeyboard(input)
                metroOverlay.dismiss()
                onSave(value)
            }
        }
        input.setOnEditorActionListener { _, _, _ ->
            save()
            true
        }
        card.addView(buttonRow(
            ui.button("Cancel", filled = false) { hideKeyboard(input); metroOverlay.dismiss() },
            ui.button("Save", filled = true) { save() }
        ))
        metroOverlay.show(card, MetroOverlay.Style.DIALOG) { hideKeyboard(input) }
        input.post {
            input.requestFocus()
            getSystemService(InputMethodManager::class.java)?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun promptManualContact() {
        prompt("New person tile", "", "Name") { name ->
            addTile(TileItem(UUID.randomUUID().toString(), TileType.QUICK_CONTACT, name))
        }
    }

    private fun buttonRow(vararg buttons: View): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        buttons.forEachIndexed { i, b ->
            addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = ui.dp(10)
            })
        }
    }

    private fun pickContact() {
        try {
            contactPicker.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
        } catch (_: ActivityNotFoundException) {
            promptManualContact()
        }
    }

    private fun openAppInfo(packageName: String) {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } catch (_: Exception) {
            toast("Couldn't open app info")
        }
    }

    private fun uninstall(packageName: String) {
        try {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", packageName, null)))
        } catch (_: Exception) {
            openAppInfo(packageName)
        }
    }

    // ── Look & feel ─────────────────────────────────────────────────────────────────────

    @SuppressLint("NotifyDataSetChanged")
    private fun applyLookAndFeel() {
        val pictureMode = prefs.backgroundMode == "picture" && backdrop != null
        // In picture mode the screen is solid and the picture only shows through the tiles.
        root.setBackgroundColor(if (pictureMode) Color.BLACK else Color.argb(prefs.wallpaperDim.coerceIn(0, 90) * 255 / 100, 0, 0, 0))
        gridLayoutManager.columns = prefs.columns
        gridLayoutManager.gutterPx = ui.dp(prefs.gutterDp)
        PageTransformers.apply(pager, prefs.pageTransition)
        findViewById<ImageView>(R.id.iv_search_glyph).imageTintList = ColorStateList.valueOf(prefs.accentColorInt)
        tileAdapter.notifyDataSetChanged()
        drawerAdapter.notifyDataSetChanged()
        refreshSuggestions(force = true)
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────────────────

    override fun onStart() {
        super.onStart()
        NotificationHub.addListener(notificationsChanged)
        widgets.startListening()
    }

    override fun onResume() {
        super.onResume()
        val timeFilter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(this, timeReceiver, timeFilter, ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        media.start()
        tileAdapter.tick()
        tileAdapter.onNotificationsChanged()
        tileAdapter.onMediaChanged()
        refreshAgenda()
        refreshWeather(force = false)
        refreshSuggestions()
        applyWallpaperAccent()
        prefs.takePendingPins().forEach { pinShortcut(it.packageName, it.shortcutId, it.label) }
        if (pendingRestyles.isNotEmpty()) {
            tiles.filter { it.id in pendingRestyles }.forEach { tileAdapter.refresh(it) }
            pendingRestyles.clear()
        }
        handler.removeCallbacks(liveTick)
        handler.postDelayed(liveTick, 2500)
        playEntranceIfNeeded()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(liveTick)
        try {
            unregisterReceiver(timeReceiver)
            unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {
        }
    }

    override fun onStop() {
        super.onStop()
        NotificationHub.removeListener(notificationsChanged)
        widgets.stopListening()
        tileAdapter.exitEditMode()
        tileAdapter.closeFolder()
        playEntrance = true
    }

    override fun onDestroy() {
        super.onDestroy()
        media.stop()
        try {
            unregisterReceiver(packageReceiver)
            unregisterReceiver(userPresentReceiver)
        } catch (_: Exception) {
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            (wallpaperColorsListener as? WallpaperManager.OnColorsChangedListener)?.let {
                WallpaperManager.getInstance(this).removeOnColorsChangedListener(it)
            }
        }
    }

    /** Pressing Home while already on the launcher walks back to the top of Start. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) goBack(homePressed = true)
    }

    private fun goBack(homePressed: Boolean) {
        when {
            metroOverlay.dismiss() -> Unit
            searchPanel.visibility == View.VISIBLE -> closeSearch()
            tileAdapter.editMode -> tileAdapter.exitEditMode()
            closeOpenFolder() -> Unit
            pager.currentItem != LauncherPagerAdapter.PAGE_TILES -> pager.setCurrentItem(LauncherPagerAdapter.PAGE_TILES, true)
            homePressed || rvTiles?.canScrollVertically(-1) == true -> rvTiles?.smoothScrollToPosition(0)
        }
    }

    /** Closes the open folder; returns false when none was open. */
    private fun closeOpenFolder(): Boolean {
        val before = tileAdapter.itemCount
        tileAdapter.closeFolder()
        return tileAdapter.itemCount != before
    }

    private fun hideKeyboard(view: View?) {
        view ?: return
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
