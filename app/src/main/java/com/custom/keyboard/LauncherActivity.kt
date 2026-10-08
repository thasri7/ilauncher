package com.custom.keyboard

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
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
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.custom.keyboard.launcher.AppDrawerAdapter
import com.custom.keyboard.launcher.IconCache
import com.custom.keyboard.launcher.LauncherKeyboardController
import com.custom.keyboard.launcher.LauncherPagerAdapter
import com.custom.keyboard.launcher.METRO_ACCENTS
import com.custom.keyboard.launcher.MediaTileController
import com.custom.keyboard.launcher.MetroGridLayoutManager
import com.custom.keyboard.launcher.MetroMotion
import com.custom.keyboard.launcher.MetroOverlay
import com.custom.keyboard.launcher.MetroTileAdapter
import com.custom.keyboard.launcher.MetroUi
import com.custom.keyboard.launcher.NotificationHub
import com.custom.keyboard.launcher.PageTransformers
import com.custom.keyboard.launcher.TilePreferences
import com.custom.keyboard.models.TileItem
import com.custom.keyboard.models.TileSize
import com.custom.keyboard.models.TileType
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

class LauncherActivity : AppCompatActivity() {

    private lateinit var prefs: TilePreferences
    private lateinit var appHelper: AppLauncherHelper
    private lateinit var icons: IconCache
    private lateinit var media: MediaTileController
    private lateinit var metroOverlay: MetroOverlay
    private lateinit var ui: MetroUi
    private val mathCalc = MathCalculator()
    private val lightFace: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)

    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var pager: ViewPager2
    private lateinit var tvTitle: TextView
    private lateinit var btnTogglePage: ImageView
    private lateinit var editBar: View

    private lateinit var searchPanel: LinearLayout
    private lateinit var searchBox: View
    private lateinit var keyboardView: View
    private lateinit var etSearch: EditText
    private lateinit var tvMathResult: TextView
    private lateinit var btnWebSearch: View
    private lateinit var tvWebSearchLabel: TextView
    private lateinit var searchActionStrip: View

    private var rvTiles: RecyclerView? = null
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

    private val handler = Handler(Looper.getMainLooper())
    /** Play the turnstile entrance the next time Start becomes visible. */
    private var playEntrance = true
    /** Tiles were swung away for an app launch and still need to come back. */
    private var tilesTurnedOut = false
    /** Tiles that auto-grew while an app was launching; restyled once we are back. */
    private val pendingRestyles = mutableSetOf<String>()

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
        override fun onReceive(context: Context?, intent: Intent?) = tileAdapter.tick()
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = tileAdapter.onBatteryChanged()
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
            tileAdapter.notifyItemRangeChanged(0, tiles.size, MetroTileAdapter.PAYLOAD_ICONS)
        }
    }

    private val notificationsChanged: () -> Unit = { tileAdapter.onNotificationsChanged() }

    private val contactPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode != RESULT_OK || uri == null) return@registerForActivityResult
        var name: String? = null
        var number = ""
        try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    number = c.getString(1).orEmpty()
                }
            }
        } catch (_: Exception) {
        }
        val pickedName = name
        if (pickedName.isNullOrBlank()) {
            promptManualContact()
        } else {
            addTile(TileItem(UUID.randomUUID().toString(), TileType.QUICK_CONTACT, pickedName, contactPhone = number))
        }
    }

    // ── Setup ───────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_launcher)

        prefs = TilePreferences(this)
        appHelper = AppLauncherHelper(this)
        icons = IconCache(this)
        ui = MetroUi(this) { prefs.accentColorInt }

        bindViews()
        metroOverlay = MetroOverlay(findViewById(R.id.overlay_host)) { systemInsets }
        setupInsets()
        loadData()
        setupPager()
        setupSearchPanel()
        setupChrome()
        applyLookAndFeel()

        val packageFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageReceiver, packageFilter, ContextCompat.RECEIVER_EXPORTED)

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
            cellPitch = { gridLayoutManager.cellPitch },
            callbacks = tileCallbacks
        )
        gridLayoutManager = MetroGridLayoutManager { position -> tileAdapter.spanFor(position) }

        drawerAdapter = AppDrawerAdapter(
            icons = icons,
            accent = { prefs.accentColorInt },
            tiltEnabled = { prefs.tiltEnabled },
            isPinned = { pkg -> tiles.any { it.packageName == pkg } },
            onAppClick = { app, view -> launchApp(app.packageName, view) },
            onAppLongClick = { app, view -> showDrawerAppMenu(app, view) },
            onHeaderClick = { showJumpList() }
        )
        searchAdapter = AppDrawerAdapter(
            icons = icons,
            accent = { prefs.accentColorInt },
            tiltEnabled = { prefs.tiltEnabled },
            isPinned = { pkg -> tiles.any { it.packageName == pkg } },
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
        rv.itemAnimator = DefaultItemAnimator().apply {
            moveDuration = 260
            changeDuration = 160
            addDuration = 220
            removeDuration = 160
        }
        applyTilesPadding(rv)

        touchHelper = ItemTouchHelper(dragCallback).also { it.attachToRecyclerView(rv) }

        // Taps and long-presses on the empty space between and below tiles.
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (tileAdapter.editMode && rv.findChildViewUnder(e.x, e.y) == null) {
                    tileAdapter.exitEditMode()
                    return true
                }
                return false
            }

            override fun onLongPress(e: MotionEvent) {
                if (!tileAdapter.editMode && rv.findChildViewUnder(e.x, e.y) == null) {
                    rv.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    showStartMenu(null)
                }
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
        rv.setPadding(side + systemInsets.left, ui.dp(4), side + systemInsets.right, systemInsets.bottom + ui.dp(96))
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
            drawerAdapter.submit(allApps, grouped = true, mostUsed = mostUsedApps(4))
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

    // ── Tiles: callbacks, drag & drop ────────────────────────────────────────────────────

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
            if (editing) root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showEditBar(editing)
        }
    }

    private val dragCallback = object : ItemTouchHelper.Callback() {
        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
            makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0)

        override fun isLongPressDragEnabled(): Boolean = false

        override fun isItemViewSwipeEnabled(): Boolean = false

        override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            tileAdapter.moveTile(from, to)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) tileAdapter.lift(viewHolder)
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            tileAdapter.settle(viewHolder)
            prefs.saveTiles(tiles)
        }
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
        val i = tiles.indexOf(tile)
        if (i >= 0) tileAdapter.notifyItemChanged(i, MetroTileAdapter.PAYLOAD_RESTYLE)
        prefs.saveTiles(tiles)
    }

    private fun unpin(tile: TileItem) {
        val i = tiles.indexOf(tile)
        if (i < 0) return
        tiles.removeAt(i)
        tileAdapter.notifyItemRemoved(i)
        prefs.saveTiles(tiles)
        drawerAdapter.notifyDataSetChanged()
        if (tiles.isEmpty()) tileAdapter.exitEditMode()
    }

    private fun removeTilesFor(packageName: String) {
        for (i in tiles.indices.reversed()) {
            if (tiles[i].packageName == packageName && tiles[i].type == TileType.APP_SHORTCUT) {
                tiles.removeAt(i)
                tileAdapter.notifyItemRemoved(i)
            }
        }
        prefs.saveTiles(tiles)
    }

    private fun addTile(tile: TileItem) {
        tiles.add(tile)
        tileAdapter.notifyItemInserted(tiles.size - 1)
        prefs.saveTiles(tiles)
        drawerAdapter.notifyDataSetChanged()
        if (pager.currentItem != LauncherPagerAdapter.PAGE_TILES) pager.setCurrentItem(LauncherPagerAdapter.PAGE_TILES, true)
        rvTiles?.postDelayed({ rvTiles?.smoothScrollToPosition(tiles.size - 1) }, 250)
    }

    private fun pinApp(app: AppLauncherHelper.AppEntry, size: TileSize) {
        addTile(TileItem(UUID.randomUUID().toString(), TileType.APP_SHORTCUT, app.name, app.packageName, size))
        toast("Pinned ${app.name} to Start")
    }

    // ── Opening things ──────────────────────────────────────────────────────────────────

    private fun handleTileClick(tile: TileItem, view: View) {
        when (tile.type) {
            TileType.APP_SHORTCUT -> tile.packageName?.let { launchApp(it, view) }
            TileType.CLOCK_WEATHER -> launchIntent(Intent(AlarmClock.ACTION_SHOW_ALARMS), view)
            TileType.CALENDAR_BIG -> launchIntent(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR), view)
            TileType.WEATHER_LIVE -> {
                val weatherApp = allApps.firstOrNull { it.name.lowercase().contains("weather") || it.packageName.contains("weather") }
                if (weatherApp != null) launchApp(weatherApp.packageName, view) else webSearch("weather")
            }
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
            TileType.SECTION_HEADER -> Unit
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

    /**
     * Starts [intent] the Windows 10 Mobile way: the other tiles turnstile away, then the app
     * zooms out of the tile that was tapped. Falls back to [fallback] if nothing handles it.
     */
    private fun launchIntent(intent: Intent, source: View?, fallback: Intent? = null) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val start = {
            val options = source?.takeIf { it.isAttachedToWindow && it.width > 0 }
                ?.let { ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height).toBundle() }
            try {
                startActivity(intent, options)
            } catch (_: ActivityNotFoundException) {
                try {
                    if (fallback != null) startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) else toast("No app can open that")
                } catch (_: Exception) {
                    toast("No app can open that")
                }
                playEntranceIfNeeded()
            } catch (_: SecurityException) {
                toast("That app can't be opened from here")
                playEntranceIfNeeded()
            }
        }
        val rv = rvTiles
        if (prefs.animationsEnabled && rv != null && source != null && source.parent === rv) {
            tilesTurnedOut = true
            MetroMotion.turnstileOut(rv, source, { tileAdapter.motionView(rv, it) }) { start() }
            handler.removeCallbacks(turnstileSafety)
            handler.postDelayed(turnstileSafety, 1500)
        } else {
            start()
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
        rv.doOnLayout { MetroMotion.turnstileIn(rv, motionView) }
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
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        try {
            startActivity(intent)
            toast("Turn on access for ${getString(R.string.app_name)} to make tiles live")
        } catch (_: Exception) {
            toast("Open Settings › Notifications › Device & app notifications")
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

        searchResults = if (clean.isEmpty()) mostUsedApps(8).ifEmpty { allApps.take(8) } else rankApps(clean)
        searchAdapter.submit(searchResults, grouped = false)
    }

    /** Name prefix beats word prefix beats substring; nicknames like "yt" or "wa" come first. */
    private fun rankApps(query: String): List<AppLauncherHelper.AppEntry> {
        val q = query.trim().lowercase(Locale.getDefault())
        if (q.isEmpty()) return allApps
        val nickname = appHelper.findMatchingApp(q)
        val scored = allApps.mapNotNull { app ->
            val name = app.name.lowercase(Locale.getDefault())
            val score = when {
                app == nickname -> 0
                name.startsWith(q) -> 1
                name.split(' ', '-', '.').any { it.startsWith(q) } -> 2
                name.contains(q) -> 3
                app.packageName.lowercase().contains(q) -> 4
                else -> return@mapNotNull null
            }
            score to app
        }
        return scored.sortedWith(compareBy({ it.first }, { it.second.name.lowercase() })).map { it.second }
    }

    private fun mostUsedApps(count: Int): List<AppLauncherHelper.AppEntry> {
        val usage = prefs.usageCounts()
        return allApps.filter { (usage[it.packageName] ?: 0) > 0 }
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
        card.addView(ui.action(R.drawable.ic_m_resize, "Customise Start", "Move, resize and recolour tiles") {
            metroOverlay.dismiss()
            if (pager.currentItem != LauncherPagerAdapter.PAGE_TILES) pager.setCurrentItem(LauncherPagerAdapter.PAGE_TILES, true)
            tileAdapter.enterEditMode(tiles.firstOrNull()?.id)
        })
        card.addView(ui.action(R.drawable.ic_m_palette, "Accent colour") { showSettings(scrollToColors = true) })
        card.addView(ui.action(R.drawable.ic_m_settings, "Settings") { showSettings() })
        metroOverlay.show(card, if (anchor != null) MetroOverlay.Style.POPUP else MetroOverlay.Style.SHEET, anchor)
    }

    /** The per-tile customisation menu: size, colour, name, live on/off and app actions. */
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
                    restyle(tile)
                }
            })
            card.addView(ui.sectionTitle("Colour"))
            val swatches: List<Int?> = listOf<Int?>(null) + METRO_ACCENTS.map { Color.parseColor(it.second) }
            val current = tile.accentColorHex?.let { runCatching { Color.parseColor(it) }.getOrNull() }
            card.addView(ui.swatches(swatches, current) { picked ->
                tile.accentColorHex = picked?.let { String.format("#%06X", 0xFFFFFF and it) }
                restyle(tile)
            })
            card.addView(ui.divider())
        }
        card.addView(ui.action(R.drawable.ic_m_edit, if (isHeader) "Rename group" else "Rename") {
            prompt(if (isHeader) "Rename group" else "Rename tile", tile.title, "Name") { name ->
                tile.title = name
                restyle(tile)
            }
        })
        if (hasLiveFace(tile)) {
            card.addView(ui.toggleRow("Live tile", "Flip to show live info", tile.liveEnabled) { on ->
                tile.liveEnabled = on
                restyle(tile)
            })
        }
        if (tile.type == TileType.APP_SHORTCUT) {
            tile.packageName?.let { pkg ->
                card.addView(ui.action(R.drawable.ic_m_info, "App info") { metroOverlay.dismiss(); openAppInfo(pkg) })
                card.addView(ui.action(R.drawable.ic_m_delete, "Uninstall", danger = true) { metroOverlay.dismiss(); uninstall(pkg) })
            }
        }
        card.addView(ui.action(R.drawable.ic_m_unpin, "Unpin from Start", danger = true) {
            metroOverlay.dismiss()
            unpin(tile)
        })
        metroOverlay.show(scrollableSheet(card), MetroOverlay.Style.SHEET)
    }

    private fun hasLiveFace(tile: TileItem) = when (tile.type) {
        TileType.APP_SHORTCUT, TileType.CLOCK_WEATHER, TileType.CALENDAR_BIG, TileType.BATTERY_STATUS,
        TileType.STORAGE_STATS, TileType.QUICK_CONTACT -> true
        else -> false
    }

    private fun describe(tile: TileItem): String = when (tile.type) {
        TileType.APP_SHORTCUT -> "App · ${tile.size.label}"
        TileType.SECTION_HEADER -> "Group name"
        TileType.QUICK_CONTACT -> listOf("Person", tile.contactPhone).filter { it.isNotEmpty() }.joinToString(" · ")
        else -> "${tile.title} · ${tile.size.label}"
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
        val pinned = tiles.filter { it.packageName == app.packageName && it.type == TileType.APP_SHORTCUT }
        if (pinned.isNotEmpty()) {
            card.addView(ui.action(R.drawable.ic_m_unpin, "Unpin from Start") {
                metroOverlay.dismiss()
                pinned.forEach { unpin(it) }
            })
        }
        card.addView(ui.action(R.drawable.ic_m_info, "App info") { metroOverlay.dismiss(); openAppInfo(app.packageName) })
        card.addView(ui.action(R.drawable.ic_m_delete, "Uninstall", danger = true) { metroOverlay.dismiss(); uninstall(app.packageName) })
        metroOverlay.show(card, MetroOverlay.Style.POPUP, anchor)
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
        }
        add(R.drawable.ic_m_apps, "App", "Pin any installed app") { showAppPicker() }
        add(R.drawable.ic_m_person, "Person", "Call a contact in one tap") { metroOverlay.dismiss(); pickContact() }
        add(R.drawable.ic_m_music, "Music", "Now playing, with controls") { tile(TileType.MEDIA_PLAYER, "Music", TileSize.WIDE) }
        add(R.drawable.ic_m_clock, "Clock", "Time, date and next alarm") { tile(TileType.CLOCK_WEATHER, "Clock", TileSize.WIDE) }
        add(R.drawable.ic_m_calendar, "Calendar", "Today's date") { tile(TileType.CALENDAR_BIG, "Calendar", TileSize.MEDIUM) }
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
            isPinned = { pkg -> tiles.any { it.packageName == pkg } },
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
        picker.submit(allApps, grouped = false)
        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString().orEmpty()
                picker.submit(if (q.isBlank()) allApps else rankApps(q), grouped = false)
            }
        })
        metroOverlay.show(card, MetroOverlay.Style.SHEET) { hideKeyboard(filter) }
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

    private fun showSettings(scrollToColors: Boolean = false, scrollY: Int = 0, animate: Boolean = true) {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, ui.dp(32))
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(page)
        }
        val rerender = { showSettings(scrollY = scroll.scrollY, animate = false) }
        fun applyAndRefresh() {
            applyLookAndFeel()
            rerender()
        }

        page.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(6), ui.dp(4), ui.dp(6), 0)
            addView(ImageView(this@LauncherActivity).apply {
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

        page.addView(ui.sectionTitle("Layout"))
        page.addView(ui.caption("Tiles per row"))
        page.addView(ui.chips(listOf("4 · Bigger tiles", "6 · More tiles"), if (prefs.columns <= 4) 0 else 1) { i ->
            prefs.columns = if (i == 0) 4 else 6
            applyLookAndFeel()
        })
        page.addView(chipSetting("Tile gap", listOf("None", "Thin", "Normal", "Wide"), listOf(0, 2, 4, 8), prefs.gutterDp) { prefs.gutterDp = it })
        page.addView(chipSetting("Corners", listOf("Square", "Soft", "Round"), listOf(0, 6, 14), prefs.cornerRadiusDp) { prefs.cornerRadiusDp = it })
        page.addView(chipSetting("Tile transparency", listOf("Solid", "Light", "Medium", "Glass"), listOf(100, 85, 70, 50), prefs.tileOpacity) { prefs.tileOpacity = it })
        page.addView(chipSetting("Wallpaper dim", listOf("Off", "Light", "Medium", "Dark"), listOf(0, 20, 35, 55), prefs.wallpaperDim) { prefs.wallpaperDim = it })
        page.addView(ui.toggleRow("Tile labels", "Show app names on medium and larger tiles", prefs.showLabels) {
            prefs.showLabels = it
            applyLookAndFeel()
        })

        val colorsTop = page.childCount
        page.addView(ui.sectionTitle("Colours"))
        page.addView(ui.caption("Accent colour"))
        val accents = METRO_ACCENTS.map { Color.parseColor(it.second) }
        page.addView(ui.swatches(accents, prefs.accentColorInt.takeIf { it in accents }) { picked ->
            if (picked != null) {
                prefs.accentColor = String.format("#%06X", 0xFFFFFF and picked)
                applyAndRefresh()
            }
        })
        page.addView(ui.caption("Tile colour"))
        page.addView(ui.chips(listOf("Accent colour", "From app icon"), if (prefs.tileColorMode == "icon") 1 else 0) { i ->
            prefs.tileColorMode = if (i == 1) "icon" else "accent"
            applyLookAndFeel()
        })
        page.addView(ui.toggleRow("Themed icons", "White glyph icons for apps that support them (Android 13+)", prefs.themedIcons) {
            prefs.themedIcons = it
            applyLookAndFeel()
        })

        page.addView(ui.sectionTitle("Motion"))
        page.addView(ui.toggleRow("Animations", "Turnstile when opening apps and returning to Start", prefs.animationsEnabled) { prefs.animationsEnabled = it })
        page.addView(ui.toggleRow("Tilt effect", "Tiles tilt toward your finger", prefs.tiltEnabled) { prefs.tiltEnabled = it })
        page.addView(ui.toggleRow("Live tiles", "Flip to show notifications, alarms and more", prefs.liveTilesEnabled) {
            prefs.liveTilesEnabled = it
            applyLookAndFeel()
        })
        val transitions = listOf("slide", "cube", "depth")
        page.addView(ui.caption("Swipe to All apps"))
        page.addView(ui.chips(listOf("Slide", "Cube", "Depth"), transitions.indexOf(prefs.pageTransition).coerceAtLeast(0)) { i ->
            prefs.pageTransition = transitions[i]
            PageTransformers.apply(pager, prefs.pageTransition)
        })

        page.addView(ui.sectionTitle("Live data"))
        val granted = NotificationHub.isAccessGranted(this)
        page.addView(ui.action(
            R.drawable.ic_m_notifications,
            "Notification access",
            if (granted) "On · counts, message previews and music are live" else "Off · tap to allow unread counts and live previews"
        ) { requestNotificationAccess() })
        page.addView(ui.toggleRow("Smart auto-grow", "Small tiles you open often grow to medium", prefs.autoGrowEnabled) { prefs.autoGrowEnabled = it })

        page.addView(ui.sectionTitle("Launcher"))
        page.addView(ui.action(R.drawable.ic_m_add, "Add tiles") { showAddTileSheet() })
        page.addView(ui.action(R.drawable.ic_m_home, "Set as default home app") {
            try {
                startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        })
        page.addView(ui.action(R.drawable.ic_m_keyboard, "Keyboard settings & themes") {
            startActivity(Intent(this, MainActivity::class.java))
        })
        page.addView(ui.action(R.drawable.ic_m_reset, "Reset Start", "Put the default tiles back", danger = true) { confirmReset() })

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
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.caption(title))
            val selected = values.indexOf(current).takeIf { it >= 0 } ?: values.indices.minBy { abs(values[it] - current) }
            addView(ui.chips(labels, selected) { i ->
                save(values[i])
                applyLookAndFeel()
            })
        }

    private fun confirmReset() {
        val card = ui.card().apply { setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(16)) }
        card.addView(ui.text("Reset Start?", 22f, face = lightFace))
        card.addView(ui.text("Your tiles, sizes and colours go back to the defaults. Settings are kept.", 14f, 0xCCFFFFFF.toInt()).apply {
            setPadding(0, ui.dp(8), 0, ui.dp(16))
        })
        card.addView(buttonRow(
            ui.button("Cancel", filled = false) { metroOverlay.dismiss() },
            ui.button("Reset", filled = true) {
                metroOverlay.dismiss()
                tileAdapter.exitEditMode()
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
        root.setBackgroundColor(Color.argb(prefs.wallpaperDim.coerceIn(0, 90) * 255 / 100, 0, 0, 0))
        gridLayoutManager.columns = prefs.columns
        gridLayoutManager.gutterPx = ui.dp(prefs.gutterDp)
        PageTransformers.apply(pager, prefs.pageTransition)
        findViewById<ImageView>(R.id.iv_search_glyph).imageTintList = ColorStateList.valueOf(prefs.accentColorInt)
        tileAdapter.notifyDataSetChanged()
        drawerAdapter.notifyDataSetChanged()
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────────────────

    override fun onStart() {
        super.onStart()
        NotificationHub.addListener(notificationsChanged)
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
        if (pendingRestyles.isNotEmpty()) {
            tiles.forEachIndexed { i, t -> if (t.id in pendingRestyles) tileAdapter.notifyItemChanged(i, MetroTileAdapter.PAYLOAD_RESTYLE) }
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
        tileAdapter.exitEditMode()
        playEntrance = true
    }

    override fun onDestroy() {
        super.onDestroy()
        media.stop()
        try {
            unregisterReceiver(packageReceiver)
        } catch (_: Exception) {
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
            pager.currentItem != LauncherPagerAdapter.PAGE_TILES -> pager.setCurrentItem(LauncherPagerAdapter.PAGE_TILES, true)
            homePressed || rvTiles?.canScrollVertically(-1) == true -> rvTiles?.smoothScrollToPosition(0)
        }
    }

    private fun hideKeyboard(view: View?) {
        view ?: return
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
