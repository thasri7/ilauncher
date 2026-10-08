package com.custom.keyboard

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.custom.keyboard.launcher.AppDrawerAdapter
import com.custom.keyboard.launcher.CubePageTransformer
import com.custom.keyboard.launcher.LauncherKeyboardController
import com.custom.keyboard.launcher.LauncherPagerAdapter
import com.custom.keyboard.launcher.MetroTileAdapter
import com.custom.keyboard.launcher.TilePreferences
import com.custom.keyboard.models.TileItem
import com.custom.keyboard.models.TileType
import java.util.UUID

class LauncherActivity : AppCompatActivity() {

    private lateinit var tilePrefs: TilePreferences
    private lateinit var appLauncherHelper: AppLauncherHelper
    private lateinit var mathCalc: MathCalculator

    private lateinit var pagerLauncher: ViewPager2
    private var rvTiles: RecyclerView? = null
    private var rvDrawer: RecyclerView? = null
    private lateinit var rvSearchResults: RecyclerView

    private lateinit var metroTileAdapter: MetroTileAdapter
    private lateinit var appDrawerAdapter: AppDrawerAdapter
    private lateinit var searchResultsAdapter: AppDrawerAdapter

    private lateinit var tvMetroTitle: TextView
    private lateinit var tvPageIndicator: TextView
    private lateinit var tvBtnToggleIcon: TextView

    private lateinit var panelInbuiltKeyboardSearch: LinearLayout
    private lateinit var etExpressSearch: EditText
    private lateinit var tvMathCalcResult: TextView
    private lateinit var btnWebSearchAction: TextView
    private lateinit var llSearchActionStrip: LinearLayout

    private val tilesList = mutableListOf<TileItem>()
    private var allInstalledApps = listOf<AppLauncherHelper.AppEntry>()
    private var filteredSearchResults = listOf<AppLauncherHelper.AppEntry>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val clockTickRunnable = object : Runnable {
        override fun run() {
            metroTileAdapter.notifyDataSetChanged()
            mainHandler.postDelayed(this, 30_000L) // 30s — catches every minute change
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            metroTileAdapter.notifyDataSetChanged()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)

        tilePrefs = TilePreferences(this)
        appLauncherHelper = AppLauncherHelper(this)
        mathCalc = MathCalculator()

        initViews()
        setupEdgeToEdgeInsets()
        loadTilesAndApps()
        setupCubeViewPager()
        setupInbuiltCustomKeyboard()
        setupNavigationActions()
    }

    private fun initViews() {
        pagerLauncher = findViewById(R.id.pager_launcher)
        rvSearchResults = findViewById(R.id.rv_search_results)

        tvMetroTitle = findViewById(R.id.tv_metro_title)
        tvPageIndicator = findViewById(R.id.tv_page_indicator)
        tvBtnToggleIcon = findViewById(R.id.tv_btn_toggle_icon)

        panelInbuiltKeyboardSearch = findViewById(R.id.panel_inbuilt_keyboard_search)
        etExpressSearch = findViewById(R.id.et_express_search)
        tvMathCalcResult = findViewById(R.id.tv_math_calc_result)
        btnWebSearchAction = findViewById(R.id.btn_web_search_action)
        llSearchActionStrip = findViewById(R.id.ll_search_action_strip)

        etExpressSearch.showSoftInputOnFocus = false
    }

    private fun setupEdgeToEdgeInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.launcher_root)) { view, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(0, statusBar.top, 0, navBar.bottom)
            insets
        }
    }

    private fun loadTilesAndApps() {
        tilesList.clear()
        tilesList.addAll(tilePrefs.loadTiles())
        allInstalledApps = appLauncherHelper.getAllApps()

        ensureEssentialTiles()

        metroTileAdapter = MetroTileAdapter(
            context = this,
            tiles = tilesList,
            onTileClick = { tile -> handleTileClick(tile) },
            onTileLongClick = { _, _ -> /* flip handles edit mode */ },
            onTileResizeChanged = { tile ->
                tilePrefs.saveTiles(tilesList)
                metroTileAdapter.notifyDataSetChanged()
            },
            onTileRemoved = { tile ->
                tilesList.remove(tile)
                tilePrefs.saveTiles(tilesList)
                metroTileAdapter.notifyDataSetChanged()
                Toast.makeText(this, "Tile removed", Toast.LENGTH_SHORT).show()
            }
        )

        appDrawerAdapter = AppDrawerAdapter(
            context = this,
            appsList = allInstalledApps,
            onAppClick = { app ->
                launchAppWithUsageTracking(app.packageName)
            },
            onAppLongClick = { app, _ -> showAppPinMenu(app) }
        )

        rvSearchResults.layoutManager = LinearLayoutManager(this)
        searchResultsAdapter = AppDrawerAdapter(
            context = this,
            appsList = emptyList(),
            onAppClick = { app ->
                launchAppWithUsageTracking(app.packageName)
                closeKeyboardSearch()
            }
        )
        rvSearchResults.adapter = searchResultsAdapter
    }

    private fun setupCubeViewPager() {
        pagerLauncher.setPageTransformer(CubePageTransformer())

        val pagerAdapter = LauncherPagerAdapter(
            onTilesPageReady = { rv ->
                rvTiles = rv
                val gridLayoutManager = GridLayoutManager(this, 2)
                gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int {
                        return if (position < tilesList.size) tilesList[position].spanX else 1
                    }
                }
                rv.layoutManager = gridLayoutManager
                rv.adapter = metroTileAdapter

                // Enable Drag-to-Reorder via ItemTouchHelper!
                val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.START or ItemTouchHelper.END,
                    0
                ) {
                    override fun onMove(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                        target: RecyclerView.ViewHolder
                    ): Boolean {
                        val from = viewHolder.bindingAdapterPosition
                        val to = target.bindingAdapterPosition
                        if (from != RecyclerView.NO_POSITION && to != RecyclerView.NO_POSITION) {
                            metroTileAdapter.onItemMove(from, to)
                            tilePrefs.saveTiles(tilesList)
                        }
                        return true
                    }
                    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
                })
                touchHelper.attachToRecyclerView(rv)
            },
            onDrawerPageReady = { rv ->
                rvDrawer = rv
                rv.layoutManager = LinearLayoutManager(this)
                rv.adapter = appDrawerAdapter

                // Wire Square Home-style drawer search bar
                val drawerRoot = rv.parent as? android.view.ViewGroup
                val etDrawerSearch = drawerRoot?.rootView?.findViewById<EditText>(R.id.et_drawer_search)
                val btnDrawerClear = drawerRoot?.rootView?.findViewById<TextView>(R.id.btn_drawer_search_clear)
                etDrawerSearch?.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        val query = s?.toString()?.trim()?.lowercase() ?: ""
                        val filtered = if (query.isEmpty()) allInstalledApps
                        else allInstalledApps.filter {
                            it.name.lowercase().contains(query) || it.packageName.lowercase().contains(query)
                        }
                        appDrawerAdapter.updateData(filtered)
                        btnDrawerClear?.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })
                btnDrawerClear?.setOnClickListener {
                    etDrawerSearch?.setText("")
                    appDrawerAdapter.updateData(allInstalledApps)
                    it.visibility = View.GONE
                }
            }
        )
        pagerLauncher.adapter = pagerAdapter

        pagerLauncher.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (position == LauncherPagerAdapter.PAGE_TILES) {
                    tvMetroTitle.text = "Start"
                    tvPageIndicator.text = "Tiles"
                    tvBtnToggleIcon.text = "➔"
                } else {
                    tvMetroTitle.text = "Apps"
                    tvPageIndicator.text = "All Apps"
                    tvBtnToggleIcon.text = "◂"
                }
            }
        })
    }

    private fun ensureEssentialTiles() {
        val hasAppTiles = tilesList.any { it.type == TileType.APP_SHORTCUT }
        if (!hasAppTiles && allInstalledApps.isNotEmpty()) {
            val popularPatterns = listOf("chrome", "camera", "phone", "message", "whatsapp", "mail")
            val selected = mutableListOf<AppLauncherHelper.AppEntry>()
            for (pattern in popularPatterns) {
                val match = allInstalledApps.firstOrNull { it.name.lowercase().contains(pattern) || it.packageName.lowercase().contains(pattern) }
                if (match != null && !selected.contains(match)) {
                    selected.add(match)
                }
            }
            if (selected.isEmpty()) {
                selected.addAll(allInstalledApps.take(6))
            }
            val accentColors = listOf("#0078D7", "#00B7C3", "#107C41", "#D83B01", "#8764B8", "#E3008C")
            selected.forEachIndexed { index, app ->
                val badge = if (app.name.lowercase().contains("mail") || app.name.lowercase().contains("message")) "²" else ""
                tilesList.add(
                    TileItem(
                        id = UUID.randomUUID().toString(),
                        type = TileType.APP_SHORTCUT,
                        title = app.name,
                        packageName = app.packageName,
                        spanX = if (index == 0) 2 else 1,
                        spanY = 1,
                        accentColorHex = accentColors[index % accentColors.size],
                        badgeCount = badge
                    )
                )
            }
            tilePrefs.saveTiles(tilesList)
        }
    }

    private fun setupInbuiltCustomKeyboard() {
        val keyboardContainer = findViewById<View>(R.id.inbuilt_custom_keyboard_view)
        LauncherKeyboardController(
            context = this,
            keyboardView = keyboardContainer,
            searchEditText = etExpressSearch,
            onTextUpdated = { query -> performInstantFilter(query) },
            onActionSubmit = { executeTopSearchResult() }
        )

        etExpressSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                performInstantFilter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        findViewById<View>(R.id.btn_clear_search).setOnClickListener {
            etExpressSearch.setText("")
            performInstantFilter("")
        }

        findViewById<View>(R.id.btn_close_keyboard_search).setOnClickListener {
            closeKeyboardSearch()
        }

        btnWebSearchAction.setOnClickListener {
            val query = etExpressSearch.text.toString().trim()
            if (query.isNotEmpty()) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query)))
                startActivity(intent)
                closeKeyboardSearch()
            }
        }
    }

    private fun setupNavigationActions() {
        findViewById<View>(R.id.btn_add_tile).setOnClickListener {
            showAddTileDialog()
        }

        findViewById<View>(R.id.btn_toggle_page).setOnClickListener {
            togglePage()
        }

        findViewById<View>(R.id.btn_open_search).setOnClickListener {
            openKeyboardSearch()
        }

        findViewById<View>(R.id.btn_open_settings).setOnClickListener {
            showLauncherSettingsDialog()
        }
    }

    private fun showAddTileDialog() {
        val options = arrayOf(
            "📱 Add App Shortcut",
            "👤 Add Quick Contact Tile",
            "🎵 Add Media / Music Player Tile",
            "🏷️ Add Category Header (e.g. FAVORITES)",
            "📅 Add Live Calendar Tile",
            "☀️ Add Live Weather Tile"
        )

        AlertDialog.Builder(this)
            .setTitle("Add to Start")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showPickAppDialog()
                    1 -> showAddContactDialog()
                    2 -> {
                        tilesList.add(TileItem(UUID.randomUUID().toString(), TileType.MEDIA_PLAYER, "Media Player", spanX = 2, accentColorHex = "#E3008C"))
                        tilePrefs.saveTiles(tilesList)
                        metroTileAdapter.notifyDataSetChanged()
                        Toast.makeText(this, "Music player tile added!", Toast.LENGTH_SHORT).show()
                    }
                    3 -> showAddSectionHeaderDialog()
                    4 -> {
                        tilesList.add(TileItem(UUID.randomUUID().toString(), TileType.CALENDAR_BIG, "Calendar", spanX = 2))
                        tilePrefs.saveTiles(tilesList)
                        metroTileAdapter.notifyDataSetChanged()
                    }
                    5 -> {
                        tilesList.add(TileItem(UUID.randomUUID().toString(), TileType.WEATHER_LIVE, "The Weather Channel", spanX = 1, customSubtitle = "72° Sunny"))
                        tilePrefs.saveTiles(tilesList)
                        metroTileAdapter.notifyDataSetChanged()
                    }
                }
            }
            .show()
    }

    private fun showPickAppDialog() {
        val appNames = allInstalledApps.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select App to Pin")
            .setItems(appNames) { _, index ->
                val selected = allInstalledApps[index]
                showAppPinMenu(selected)
            }
            .show()
    }

    private fun showAddContactDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }
        val etName = EditText(this).apply { hint = "Contact Name (e.g. Alex)" }
        val etPhone = EditText(this).apply { hint = "Phone Number (optional)" }
        layout.addView(etName)
        layout.addView(etPhone)

        AlertDialog.Builder(this)
            .setTitle("New Quick Contact")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val name = etName.text.toString().trim()
                val phone = etPhone.text.toString().trim()
                if (name.isNotEmpty()) {
                    tilesList.add(
                        TileItem(
                            id = UUID.randomUUID().toString(),
                            type = TileType.QUICK_CONTACT,
                            title = name,
                            spanX = 1,
                            spanY = 1,
                            accentColorHex = "#0078D7",
                            contactPhone = phone
                        )
                    )
                    tilePrefs.saveTiles(tilesList)
                    metroTileAdapter.notifyDataSetChanged()
                    Toast.makeText(this, "Contact tile pinned!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddSectionHeaderDialog() {
        val input = EditText(this).apply {
            hint = "Header Title (e.g. FAVORITES, WORK)"
            setPadding(40, 30, 40, 30)
        }
        AlertDialog.Builder(this)
            .setTitle("Add Category Header")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val title = input.text.toString().trim()
                if (title.isNotEmpty()) {
                    tilesList.add(
                        TileItem(
                            id = UUID.randomUUID().toString(),
                            type = TileType.SECTION_HEADER,
                            title = title,
                            spanX = 2,
                            spanY = 1
                        )
                    )
                    tilePrefs.saveTiles(tilesList)
                    metroTileAdapter.notifyDataSetChanged()
                    Toast.makeText(this, "Header added!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun togglePage() {
        val current = pagerLauncher.currentItem
        if (current == LauncherPagerAdapter.PAGE_TILES) {
            pagerLauncher.setCurrentItem(LauncherPagerAdapter.PAGE_DRAWER, true)
        } else {
            pagerLauncher.setCurrentItem(LauncherPagerAdapter.PAGE_TILES, true)
        }
    }

    private fun openKeyboardSearch() {
        panelInbuiltKeyboardSearch.visibility = View.VISIBLE
        etExpressSearch.requestFocus()
        performInstantFilter(etExpressSearch.text.toString())
    }

    private fun closeKeyboardSearch() {
        panelInbuiltKeyboardSearch.visibility = View.GONE
        etExpressSearch.setText("")
    }

    private fun performInstantFilter(query: String) {
        val clean = query.trim().lowercase()

        val mathResult = mathCalc.evaluate(query)
        if (mathResult != null) {
            tvMathCalcResult.text = "= $mathResult"
            tvMathCalcResult.visibility = View.VISIBLE
            llSearchActionStrip.visibility = View.VISIBLE
        } else {
            tvMathCalcResult.visibility = View.GONE
        }

        if (clean.isNotEmpty()) {
            llSearchActionStrip.visibility = View.VISIBLE
            btnWebSearchAction.text = "🌐 Search '$query'"
        } else if (mathResult == null) {
            llSearchActionStrip.visibility = View.GONE
        }

        filteredSearchResults = if (clean.isEmpty()) {
            allInstalledApps.take(12)
        } else {
            allInstalledApps.filter {
                it.name.lowercase().contains(clean) || it.packageName.lowercase().contains(clean)
            }
        }
        searchResultsAdapter.updateData(filteredSearchResults)
    }

    private fun executeTopSearchResult() {
        if (filteredSearchResults.isNotEmpty()) {
            launchAppWithUsageTracking(filteredSearchResults[0].packageName)
            closeKeyboardSearch()
        } else {
            val query = etExpressSearch.text.toString().trim()
            if (query.isNotEmpty()) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query)))
                startActivity(intent)
                closeKeyboardSearch()
            }
        }
    }

    private fun launchAppWithUsageTracking(packageName: String) {
        val autoGrew = tilePrefs.recordAppLaunch(packageName, tilesList)
        if (autoGrew) {
            metroTileAdapter.notifyDataSetChanged()
            Toast.makeText(this, "Frequent app upgraded to Wide Tile! 🌟", Toast.LENGTH_SHORT).show()
        }
        appLauncherHelper.launchApp(packageName)
    }

    private fun handleTileClick(tile: TileItem) {
        when (tile.type) {
            TileType.CLOCK_WEATHER -> {
                val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS)
                try {
                    startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(this, "Clock Opened", Toast.LENGTH_SHORT).show()
                }
            }
            TileType.CALENDAR_BIG -> {
                try {
                    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR)
                    startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(this, "Calendar Opened", Toast.LENGTH_SHORT).show()
                }
            }
            TileType.WEATHER_LIVE -> {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://weather.com"))
                try {
                    startActivity(intent)
                } catch (_: Exception) {}
            }
            TileType.BATTERY_STATUS -> {
                try {
                    startActivity(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
            TileType.STORAGE_STATS -> {
                try {
                    startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
            TileType.EXPRESS_SEARCH -> {
                openKeyboardSearch()
            }
            TileType.KEYBOARD_SETTINGS -> {
                startActivity(Intent(this, MainActivity::class.java))
            }
            TileType.DEVICE_SETTINGS -> {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
            TileType.QUICK_CONTACT -> {
                if (tile.contactPhone.isNotEmpty()) {
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${tile.contactPhone}")))
                } else {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("content://contacts/people/")))
                }
            }
            TileType.APP_SHORTCUT -> {
                tile.packageName?.let { pkg ->
                    launchAppWithUsageTracking(pkg)
                }
            }
            TileType.MEDIA_PLAYER, TileType.SECTION_HEADER -> {}
        }
    }

    private fun showAppPinMenu(app: AppLauncherHelper.AppEntry) {
        val options = arrayOf(
            "Pin as Small Tile (1×1)",
            "Pin as Wide Tile (2×1)",
            "Pin as Big Tile (2×2)",
            "App Info"
        )

        AlertDialog.Builder(this)
            .setTitle(app.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> addAppTile(app, spanX = 1, spanY = 1)
                    1 -> addAppTile(app, spanX = 2, spanY = 1)
                    2 -> addAppTile(app, spanX = 2, spanY = 2)
                    3 -> {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", app.packageName, null)
                        }
                        startActivity(intent)
                    }
                }
            }
            .show()
    }

    private fun addAppTile(app: AppLauncherHelper.AppEntry, spanX: Int, spanY: Int) {
        val newTile = TileItem(
            id = UUID.randomUUID().toString(),
            type = TileType.APP_SHORTCUT,
            title = app.name,
            packageName = app.packageName,
            spanX = spanX,
            spanY = spanY,
            accentColorHex = "#0078D7"
        )
        tilesList.add(newTile)
        tilePrefs.saveTiles(tilesList)
        metroTileAdapter.notifyDataSetChanged()
        Toast.makeText(this, "Pinned ${app.name} to Start!", Toast.LENGTH_SHORT).show()
    }

    private fun showLauncherSettingsDialog() {
        val options = arrayOf(
            "Set as Default Launcher",
            "Smart Auto-Grow (Most Used Apps Get Bigger): ${if (tilePrefs.autoGrowEnabled) "ON" else "OFF"}",
            "Custom Keyboard Settings & Themes",
            "Reset to Authentic Square Home Layout",
            "➕ Add New Tile"
        )

        AlertDialog.Builder(this)
            .setTitle("Square Home Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        try {
                            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
                        } catch (_: Exception) {
                            startActivity(Intent(Settings.ACTION_SETTINGS))
                        }
                    }
                    1 -> {
                        tilePrefs.autoGrowEnabled = !tilePrefs.autoGrowEnabled
                        Toast.makeText(this, "Auto-grow set to ${if (tilePrefs.autoGrowEnabled) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        startActivity(Intent(this, MainActivity::class.java))
                    }
                    3 -> {
                        tilesList.clear()
                        tilesList.addAll(tilePrefs.resetToDefaults())
                        ensureEssentialTiles()
                        metroTileAdapter.notifyDataSetChanged()
                        Toast.makeText(this, "Square Home layout reset!", Toast.LENGTH_SHORT).show()
                    }
                    4 -> showAddTileDialog()
                }
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        mainHandler.post(clockTickRunnable)
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryReceiver, filter)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(clockTickRunnable)
        try {
            unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingSuperCall")
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (panelInbuiltKeyboardSearch.visibility == View.VISIBLE) {
            closeKeyboardSearch()
            return
        }
        if (pagerLauncher.currentItem != LauncherPagerAdapter.PAGE_TILES) {
            pagerLauncher.setCurrentItem(LauncherPagerAdapter.PAGE_TILES, true)
            return
        }
        rvTiles?.smoothScrollToPosition(0)
    }
}
