package com.custom.keyboard.launcher

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.custom.keyboard.R
import com.custom.keyboard.models.TileItem
import com.custom.keyboard.models.TileSize
import com.custom.keyboard.models.TileType
import java.io.File
import java.util.Calendar
import java.util.Collections
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Start-screen tiles. Adapter positions equal indexes into [tiles], except while a folder is open:
 * its panel is an extra row right after the folder. Use [positionOf] / [tileAt] to convert.
 */
class MetroTileAdapter(
    private val context: Context,
    private val tiles: MutableList<TileItem>,
    private val prefs: TilePreferences,
    private val icons: IconCache,
    private val media: MediaTileController,
    private val widgets: WidgetTiles,
    private val shortcuts: AppShortcuts,
    private val backdrop: () -> StartBackdrop?,
    private val weather: () -> WeatherReport?,
    private val weatherConfigured: () -> Boolean,
    private val cellPitch: () -> Float,
    private val callbacks: Callbacks
) : RecyclerView.Adapter<MetroTileAdapter.TileHolder>() {

    interface Callbacks {
        fun onTileClick(tile: TileItem, view: View)
        fun onStartDrag(holder: RecyclerView.ViewHolder)
        fun onTileResized(tile: TileItem)
        fun onTileUnpinned(tile: TileItem)
        fun onTileMenu(tile: TileItem, anchor: View)
        fun onEditModeChanged(editing: Boolean)
        fun onFolderAppClick(app: TileItem, view: View)
        fun onFolderAppMenu(folder: TileItem, app: TileItem, anchor: View)
        /** Horizontal swipe across a tile that has notifications. */
        fun onTileSwipe(tile: TileItem)
        /** False while Start is locked against edits. */
        fun canCustomise(): Boolean
        fun onLockedLongPress(tile: TileItem)
    }

    companion object {
        private const val TYPE_APP = 1
        private const val TYPE_CLOCK = 2
        private const val TYPE_CALENDAR = 3
        private const val TYPE_BATTERY = 4
        private const val TYPE_STORAGE = 5
        private const val TYPE_MEDIA = 6
        private const val TYPE_CONTACT = 7
        private const val TYPE_SEARCH = 8
        private const val TYPE_ACTION = 9
        private const val TYPE_HEADER = 10
        private const val TYPE_FOLDER = 11
        private const val TYPE_FOLDER_PANEL = 12
        private const val TYPE_PHOTOS = 13
        private const val TYPE_WIDGET = 14
        private const val TYPE_WEATHER = 15

        /** Re-read time/battery/storage values without re-binding anything else. */
        const val PAYLOAD_TICK = "tick"
        const val PAYLOAD_EDIT = "edit"
        const val PAYLOAD_NOTIFICATIONS = "notifications"
        const val PAYLOAD_MEDIA = "media"
        /** Size, colour or content changed: full re-bind on the same holder, so the grid animates the move. */
        const val PAYLOAD_RESTYLE = "restyle"
        /** App icons changed (install, update, icon pack): reload them quietly. */
        const val PAYLOAD_ICONS = "icons"

        private const val BACK_FACE_HOLD_MS = 4500L
        private const val FRONT_FACE_HOLD_MS = 7000L
    }

    private val density = context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val headerHeightPx = (52 * density).toInt()
    private val shortcutIcons = HashMap<String, Drawable?>()
    private var recyclerView: RecyclerView? = null
    private val windowLocation = IntArray(2)

    var editMode = false
        private set
    private var selectedId: String? = null
    private var openFolderId: String? = null
    private var panelJustOpened = false

    /** Upcoming events for Calendar tiles (empty without calendar permission). */
    var agenda: List<AgendaProvider.Event> = emptyList()
        set(value) {
            field = value
            notifyTypes(PAYLOAD_TICK) { it.type == TileType.CALENDAR_BIG }
        }

    // ── Positions ───────────────────────────────────────────────────────────────────────

    private fun panelPosition(): Int {
        val id = openFolderId ?: return -1
        val i = tiles.indexOfFirst { it.id == id }
        return if (i < 0) -1 else i + 1
    }

    private fun isPanel(position: Int) = position == panelPosition()

    /** The tile shown at [position]; for the open folder's panel this is the folder itself. */
    private fun itemAt(position: Int): TileItem {
        val panel = panelPosition()
        return if (panel < 0 || position < panel) tiles[position] else tiles[position - 1]
    }

    /** The tile at an adapter position, or null for a folder panel. */
    fun tileAt(position: Int): TileItem? = if (position < 0 || position >= itemCount || isPanel(position)) null else itemAt(position)

    fun positionOf(tile: TileItem): Int {
        val i = tiles.indexOf(tile)
        if (i < 0) return -1
        val panel = panelPosition()
        return if (panel in 0..i) i + 1 else i
    }

    fun folderContaining(app: TileItem): TileItem? = tiles.firstOrNull { f -> f.type == TileType.FOLDER && f.children.any { it === app } }

    // ── Structure changes (all list edits go through here) ─────────────────────────────

    fun refresh(tile: TileItem, payload: String = PAYLOAD_RESTYLE) {
        val pos = positionOf(tile)
        if (pos >= 0) {
            notifyItemChanged(pos, payload)
            if (tile.id == openFolderId) notifyItemChanged(panelPosition(), PAYLOAD_RESTYLE)
            return
        }
        folderContaining(tile)?.let { refresh(it) }
    }

    fun insertTile(tile: TileItem, index: Int = tiles.size) {
        closeFolder()
        tiles.add(index.coerceIn(0, tiles.size), tile)
        notifyItemInserted(positionOf(tile))
    }

    fun removeTile(tile: TileItem) {
        if (tile.id == openFolderId) closeFolder()
        val pos = positionOf(tile)
        if (pos < 0) return
        tiles.remove(tile)
        notifyItemRemoved(pos)
    }

    /** Swaps a tile for another, e.g. an app tile turning into a folder. */
    fun replaceTile(old: TileItem, new: TileItem) {
        closeFolder()
        val i = tiles.indexOf(old)
        if (i < 0) return
        tiles[i] = new
        notifyItemChanged(i)
    }

    fun moveTile(from: Int, to: Int) {
        if (from < to) for (i in from until to) Collections.swap(tiles, i, i + 1)
        else for (i in from downTo to + 1) Collections.swap(tiles, i, i - 1)
        notifyItemMoved(from, to)
    }

    /** Opens or closes a folder inline. Returns the panel position when it opened, else -1. */
    fun toggleFolder(folder: TileItem): Int {
        if (openFolderId == folder.id) {
            closeFolder()
            return -1
        }
        closeFolder()
        openFolderId = folder.id
        panelJustOpened = true
        val pos = panelPosition()
        notifyItemInserted(pos)
        notifyItemChanged(pos - 1, PAYLOAD_EDIT)
        return pos
    }

    fun closeFolder() {
        val pos = panelPosition()
        if (pos < 0) {
            openFolderId = null
            return
        }
        openFolderId = null
        notifyItemRemoved(pos)
        notifyItemChanged(pos - 1, PAYLOAD_EDIT)
    }

    // ── Edit mode ───────────────────────────────────────────────────────────────────────

    fun enterEditMode(selectId: String?) {
        closeFolder()
        val wasEditing = editMode
        editMode = true
        selectedId = selectId
        notifyItemRangeChanged(0, itemCount, PAYLOAD_EDIT)
        if (!wasEditing) callbacks.onEditModeChanged(true)
    }

    fun exitEditMode() {
        if (!editMode) return
        editMode = false
        selectedId = null
        notifyItemRangeChanged(0, itemCount, PAYLOAD_EDIT)
        callbacks.onEditModeChanged(false)
    }

    private fun select(id: String) {
        if (selectedId == id) return
        selectedId = id
        notifyItemRangeChanged(0, itemCount, PAYLOAD_EDIT)
    }

    /** Called when a drag ends, to settle the dragged tile back into its edit-mode look. */
    fun settle(holder: RecyclerView.ViewHolder) {
        val h = holder as? TileHolder ?: return
        val tile = h.tile ?: return
        applyEditState(h, tile, animate = true)
    }

    /** Highlights the tile a dragged app would be dropped into to make a folder. */
    fun setMergeHighlight(holder: RecyclerView.ViewHolder?, on: Boolean) {
        val h = holder as? TileHolder ?: return
        val tile = h.tile ?: return
        if (!on) {
            applyEditState(h, tile, animate = true)
            return
        }
        MetroMotion.centerPivot(h.frame)
        h.frame.animate().setStartDelay(0).scaleX(1.08f).scaleY(1.08f).alpha(1f).setDuration(160).start()
    }

    // ── Live updates ────────────────────────────────────────────────────────────────────

    private fun notifyTypes(payload: String, predicate: (TileItem) -> Boolean) {
        tiles.forEach { tile -> if (predicate(tile)) notifyItemChanged(positionOf(tile), payload) }
    }

    fun tick() = notifyTypes(PAYLOAD_TICK) {
        it.type == TileType.CLOCK_WEATHER || it.type == TileType.CALENDAR_BIG ||
            it.type == TileType.BATTERY_STATUS || it.type == TileType.STORAGE_STATS
    }

    fun onBatteryChanged() = notifyTypes(PAYLOAD_TICK) { it.type == TileType.BATTERY_STATUS }

    private val lastCounts = HashMap<String, Int>()
    /** Tiles that just got a new notification flip straight away instead of waiting their turn. */
    private val pulsePending = HashSet<String>()

    fun onNotificationsChanged() {
        tiles.filter { it.type == TileType.APP_SHORTCUT && it.shortcutId == null }.forEach { tile ->
            val pkg = tile.packageName ?: return@forEach
            val count = NotificationHub.get(pkg)?.count ?: 0
            if (count > (lastCounts[pkg] ?: count)) pulsePending.add(tile.id)
            lastCounts[pkg] = count
        }
        notifyTypes(PAYLOAD_NOTIFICATIONS) { it.type == TileType.APP_SHORTCUT || it.type == TileType.FOLDER }
    }

    fun onMediaChanged() = notifyTypes(PAYLOAD_MEDIA) { it.type == TileType.MEDIA_PLAYER }

    fun onWeatherChanged() = notifyTypes(PAYLOAD_TICK) { it.type == TileType.WEATHER_LIVE }

    /** "Your day" entrance order: today's info first, then tiles with unread messages. */
    fun priorityOf(rv: RecyclerView, child: View): Int {
        val tile = (rv.getChildViewHolder(child) as? TileHolder)?.tile ?: return 3
        return when {
            tile.type == TileType.CLOCK_WEATHER || tile.type == TileType.CALENDAR_BIG || tile.type == TileType.WEATHER_LIVE -> 0
            (NotificationHub.get(tile.packageName)?.count ?: 0) > 0 -> 1
            tile.type == TileType.FOLDER && tile.children.any { (NotificationHub.get(it.packageName)?.count ?: 0) > 0 } -> 1
            else -> 2
        }
    }

    /** Makes every tile with unread messages flip to show them (after unlocking). */
    fun pulseUnread() {
        tiles.filter { it.type == TileType.APP_SHORTCUT && (NotificationHub.get(it.packageName)?.count ?: 0) > 0 }
            .forEach { pulsePending.add(it.id) }
        notifyTypes(PAYLOAD_NOTIFICATIONS) { it.id in pulsePending }
    }

    fun refreshIcons() {
        shortcutIcons.clear()
        notifyItemRangeChanged(0, itemCount, PAYLOAD_ICONS)
    }

    /** One beat of the live-tile clock: flip a tile back to its front, or show a back face. */
    fun runLiveStep(rv: RecyclerView) {
        if (editMode || !prefs.liveTilesEnabled) return
        val now = SystemClock.uptimeMillis()
        val live = (0 until rv.childCount)
            .mapNotNull { rv.getChildAt(it)?.let(rv::getChildViewHolder) as? TileHolder }
            .filter { h ->
                val tile = h.tile
                h !is FolderPanelHolder && tile != null && tile.liveEnabled && h.hasBack(tile) &&
                    h.itemView.bottom > 0 && h.itemView.top < rv.height
            }
        val target = live.filter { it.showingBack && now - it.lastLiveAt > BACK_FACE_HOLD_MS }.randomOrNull()
            ?: live.filter { !it.showingBack && now - it.lastLiveAt > FRONT_FACE_HOLD_MS }.randomOrNull()
            ?: return
        target.toggleFace(now)
    }

    /** Picture-in-tiles tiles show a slice of the picture that depends on where they are. */
    fun invalidateBackdrops() {
        val rv = recyclerView ?: return
        if (prefs.backgroundMode != "picture") return
        for (i in 0 until rv.childCount) {
            (rv.getChildAt(i)?.let(rv::getChildViewHolder) as? TileHolder)?.surface?.invalidate()
        }
    }

    // ── Adapter ─────────────────────────────────────────────────────────────────────────

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = recyclerView
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = invalidateBackdrops()
        })
    }

    fun spanFor(position: Int): MetroGridPacker.Spec {
        if (position < 0 || position >= itemCount) return MetroGridPacker.Spec(2, 2)
        if (isPanel(position)) return MetroGridPacker.Spec(0, 0, panelHeight(itemAt(position)))
        val tile = itemAt(position)
        return if (tile.type == TileType.SECTION_HEADER) {
            MetroGridPacker.Spec(0, 0, headerHeightPx)
        } else {
            MetroGridPacker.Spec(tile.size.cols, tile.size.rows)
        }
    }

    private val panelPadding get() = (8 * density).toInt()

    private fun panelPerRow() = (prefs.columns / 2).coerceAtLeast(2)

    private fun panelHeight(folder: TileItem): Int {
        val rows = ceil(folder.children.size.coerceAtLeast(1) / panelPerRow().toFloat()).toInt()
        return (panelPadding * 2 + rows * 2 * cellPitch() - prefs.gutterDp * density).toInt().coerceAtLeast(1)
    }

    override fun getItemCount(): Int = tiles.size + if (panelPosition() >= 0) 1 else 0

    override fun getItemViewType(position: Int): Int {
        if (isPanel(position)) return TYPE_FOLDER_PANEL
        return when (itemAt(position).type) {
            TileType.APP_SHORTCUT -> TYPE_APP
            TileType.CLOCK_WEATHER -> TYPE_CLOCK
            TileType.CALENDAR_BIG -> TYPE_CALENDAR
            TileType.BATTERY_STATUS -> TYPE_BATTERY
            TileType.STORAGE_STATS -> TYPE_STORAGE
            TileType.MEDIA_PLAYER -> TYPE_MEDIA
            TileType.QUICK_CONTACT -> TYPE_CONTACT
            TileType.EXPRESS_SEARCH -> TYPE_SEARCH
            TileType.SECTION_HEADER -> TYPE_HEADER
            TileType.FOLDER -> TYPE_FOLDER
            TileType.PHOTOS -> TYPE_PHOTOS
            TileType.WIDGET -> TYPE_WIDGET
            TileType.WEATHER_LIVE -> TYPE_WEATHER
            TileType.KEYBOARD_SETTINGS, TileType.DEVICE_SETTINGS -> TYPE_ACTION
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TileHolder {
        val layout = when (viewType) {
            TYPE_APP -> R.layout.item_tile_app
            TYPE_CLOCK -> R.layout.item_tile_clock
            TYPE_CALENDAR -> R.layout.item_tile_calendar
            TYPE_BATTERY -> R.layout.item_tile_battery
            TYPE_STORAGE -> R.layout.item_tile_storage
            TYPE_MEDIA -> R.layout.item_tile_media
            TYPE_CONTACT -> R.layout.item_tile_contact
            TYPE_SEARCH -> R.layout.item_tile_search
            TYPE_HEADER -> R.layout.item_tile_section
            TYPE_FOLDER -> R.layout.item_tile_folder
            TYPE_PHOTOS -> R.layout.item_tile_photos
            TYPE_WIDGET -> R.layout.item_tile_widget
            TYPE_FOLDER_PANEL -> R.layout.item_tile_folder_panel
            TYPE_WEATHER -> R.layout.item_tile_weather
            else -> R.layout.item_tile_action
        }
        // itemView belongs to RecyclerView's item animator and ItemTouchHelper (they move it and
        // cancel its animations on every change), so all Metro motion runs on the inner frame.
        val root = TileRootLayout(parent.context) { editMode }.apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            clipChildren = false
        }
        // The frame must keep clipChildren on: Android only clips a view's content (here the tile
        // surface with its sliding live faces) when the view's parent clips children. The frame
        // itself may still overflow the root, which lets the edit buttons sit on the corners.
        val frame = FrameLayout(parent.context)
        root.addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val surface = LayoutInflater.from(parent.context).inflate(layout, frame, false) as FrameLayout
        frame.addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val holder = when (viewType) {
            TYPE_APP -> AppHolder(root, frame, surface)
            TYPE_CLOCK -> ClockHolder(root, frame, surface)
            TYPE_CALENDAR -> CalendarHolder(root, frame, surface)
            TYPE_BATTERY -> BatteryHolder(root, frame, surface)
            TYPE_STORAGE -> DeviceHolder(root, frame, surface)
            TYPE_MEDIA -> MediaHolder(root, frame, surface)
            TYPE_CONTACT -> ContactHolder(root, frame, surface)
            TYPE_SEARCH -> SearchHolder(root, frame, surface)
            TYPE_HEADER -> HeaderHolder(root, frame, surface)
            TYPE_FOLDER -> FolderHolder(root, frame, surface)
            TYPE_PHOTOS -> PhotosHolder(root, frame, surface)
            TYPE_WIDGET -> WidgetHolder(root, frame, surface)
            TYPE_FOLDER_PANEL -> FolderPanelHolder(root, frame, surface)
            TYPE_WEATHER -> WeatherHolder(root, frame, surface)
            else -> ActionHolder(root, frame, surface)
        }
        if (holder !is FolderPanelHolder) wireTouches(holder)
        return holder
    }

    override fun onBindViewHolder(holder: TileHolder, position: Int) {
        val tile = itemAt(position)
        if (holder.tile?.id != tile.id) {
            holder.resetFaces()
            // Desynchronise live tiles so they don't all flip on the same beat.
            holder.lastLiveAt = SystemClock.uptimeMillis() - Random.nextLong(0, FRONT_FACE_HOLD_MS)
        }
        holder.tile = tile
        holder.frame.rotationX = 0f
        holder.frame.rotationY = 0f
        styleSurface(holder, tile)
        holder.bindContent(tile)
        if (holder.showingBack && !(prefs.liveTilesEnabled && tile.liveEnabled && holder.hasBack(tile))) holder.resetFaces()
        if (holder !is FolderPanelHolder) applyEditState(holder, tile, animate = false)
    }

    override fun onBindViewHolder(holder: TileHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty() || holder is FolderPanelHolder) {
            onBindViewHolder(holder, position)
            return
        }
        val tile = itemAt(position)
        holder.tile = tile
        if (PAYLOAD_RESTYLE in payloads) {
            // MetroItemAnimator animates the change (smooth resize, or a pop for recolours).
            onBindViewHolder(holder, position)
            return
        }
        if (PAYLOAD_ICONS in payloads) {
            holder.invalidateIcons()
            onBindViewHolder(holder, position)
            return
        }
        if (PAYLOAD_EDIT in payloads) applyEditState(holder, tile, animate = true)
        if (payloads.any { it == PAYLOAD_TICK || it == PAYLOAD_NOTIFICATIONS || it == PAYLOAD_MEDIA }) {
            holder.bindContent(tile)
            if (holder.showingBack && !holder.hasBack(tile)) holder.resetFaces()
            if (pulsePending.remove(tile.id) && !editMode && prefs.liveTilesEnabled && tile.liveEnabled && holder.hasBack(tile)) {
                val now = SystemClock.uptimeMillis()
                if (!holder.showingBack) holder.root.post { holder.toggleFace(now) } else holder.lastLiveAt = now
            }
        }
    }

    // ── Look ────────────────────────────────────────────────────────────────────────────

    fun colorFor(tile: TileItem): Int {
        tile.accentColorHex?.let { hex ->
            runCatching { Color.parseColor(hex) }.getOrNull()?.let { return it }
        }
        if (prefs.tileColorMode == "icon" && tile.type == TileType.APP_SHORTCUT) {
            icons.tileColor(tile.packageName)?.let { return it }
        }
        return prefs.accentColorInt
    }

    private fun withAlpha(color: Int, percent: Int): Int =
        Color.argb(percent.coerceIn(0, 100) * 255 / 100, Color.red(color), Color.green(color), Color.blue(color))

    private fun darker(color: Int, factor: Float): Int =
        Color.rgb((Color.red(color) * factor).toInt(), (Color.green(color) * factor).toInt(), (Color.blue(color) * factor).toInt())

    /** Where the tile sits in the window, ignoring animation transforms. */
    private fun screenOffset(holder: TileHolder): Pair<Float, Float> {
        val rv = recyclerView ?: return 0f to 0f
        rv.getLocationInWindow(windowLocation)
        return (windowLocation[0] + holder.itemView.left).toFloat() to (windowLocation[1] + holder.itemView.top).toFloat()
    }

    private fun tileBackground(holder: TileHolder, color: Int, radius: Float): Drawable {
        val picture = if (prefs.backgroundMode == "picture") backdrop() else null
        if (picture != null) {
            // The picture shows through the tile with a light wash of the tile colour.
            val tint = withAlpha(color, (prefs.tileOpacity - 40).coerceIn(0, 60))
            return picture.tileDrawable(tint, radius) { screenOffset(holder) }
        }
        return GradientDrawable().apply {
            setColor(withAlpha(color, prefs.tileOpacity.coerceIn(20, 100)))
            cornerRadius = radius
        }
    }

    private fun styleSurface(holder: TileHolder, tile: TileItem) {
        val radius = prefs.cornerRadiusDp * density
        holder.surface.background = when {
            tile.type == TileType.SECTION_HEADER -> null
            holder is FolderPanelHolder -> GradientDrawable().apply {
                setColor(withAlpha(darker(colorFor(tile), 0.55f), prefs.tileOpacity.coerceIn(40, 100)))
                cornerRadius = radius
            }
            else -> tileBackground(holder, colorFor(tile), radius)
        }
        holder.surface.clipToOutline = prefs.cornerRadiusDp > 0 || tile.type == TileType.WIDGET
    }

    private fun tileWidthPx(tile: TileItem): Float = tile.size.cols * cellPitch() - prefs.gutterDp * density

    private fun tileHeightPx(tile: TileItem): Float = tile.size.rows * cellPitch() - prefs.gutterDp * density

    private fun TextView.sizePx(px: Float) = setTextSize(TypedValue.COMPLEX_UNIT_PX, px)

    private fun View.show(visible: Boolean) {
        visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun ImageView.square(px: Int) {
        val lp = layoutParams
        if (lp.width != px || lp.height != px) {
            lp.width = px
            lp.height = px
            layoutParams = lp
        }
    }

    private fun applyEditState(h: TileHolder, tile: TileItem, animate: Boolean) {
        val selected = editMode && tile.id == selectedId
        val scale = if (!editMode || selected) 1f else 0.9f
        val alpha = if (!editMode || selected) 1f else 0.7f
        MetroMotion.centerPivot(h.frame)
        if (animate) {
            h.frame.animate().setStartDelay(0).scaleX(scale).scaleY(scale).alpha(alpha)
                .rotationX(0f).rotationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator(2f)).start()
        } else {
            h.frame.animate().cancel()
            h.frame.scaleX = scale
            h.frame.scaleY = scale
            h.frame.alpha = alpha
        }
        val isHeader = tile.type == TileType.SECTION_HEADER
        h.btnUnpin.show(selected)
        h.btnResize.show(selected && allowedSizes(tile).size > 1)
        h.btnMore.show(selected)
        val button = ((if (tile.size == TileSize.SMALL && !isHeader) 22 else 30) * density).toInt()
        listOf(h.btnUnpin, h.btnResize, h.btnMore).forEach { b ->
            val lp = b.layoutParams
            if (lp.width != button) {
                lp.width = button
                lp.height = button
                b.layoutParams = lp
            }
        }
        if (h is FolderHolder) h.setOpen(tile.id == openFolderId)
    }

    // ── Touch: tilt, tap, long-press to customise, drag to move ─────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun wireTouches(holder: TileHolder) {
        val root = holder.root
        val frame = holder.frame
        var downX = 0f
        var downY = 0f
        var dragging = false
        var swiping = false
        val swipeDistance = 56 * density
        root.setOnTouchListener { v, e ->
            val tile = holder.tile ?: return@setOnTouchListener false
            val tilts = !editMode && prefs.tiltEnabled && tile.type != TileType.SECTION_HEADER && tile.type != TileType.WIDGET
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    dragging = false
                    swiping = false
                    if (tilts) MetroMotion.tiltTo(frame, e.x, e.y)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (editMode && !dragging && hypot(dx, dy) > touchSlop) {
                        dragging = true
                        select(tile.id)
                        callbacks.onStartDrag(holder)
                    } else if (!editMode && !swiping && abs(dx) > touchSlop * 0.6f && abs(dx) > 2 * abs(dy) && hasUnread(tile)) {
                        // Claim the horizontal swipe before the pager turns it into a page change.
                        swiping = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        MetroMotion.releaseTilt(frame)
                    } else if (swiping) {
                        frame.translationX = dx * 0.35f
                    } else if (tilts) {
                        MetroMotion.tiltTo(frame, e.x, e.y)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (swiping) {
                        frame.animate().setStartDelay(0).translationX(0f).setDuration(180).start()
                        if (e.actionMasked == MotionEvent.ACTION_UP && abs(e.rawX - downX) > swipeDistance) callbacks.onTileSwipe(tile)
                        swiping = false
                        // Swallow the tap that would otherwise open the app.
                        return@setOnTouchListener true
                    }
                    if (!editMode) MetroMotion.releaseTilt(frame)
                }
            }
            swiping
        }
        root.setOnClickListener {
            val tile = holder.tile ?: return@setOnClickListener
            if (editMode) select(tile.id) else callbacks.onTileClick(tile, root)
        }
        (root as TileRootLayout).touchTargets = { listOf(holder.btnUnpin, holder.btnResize, holder.btnMore) }
        root.onLongPress = {
            holder.tile?.let { tile ->
                MetroMotion.releaseTilt(frame)
                if (!callbacks.canCustomise()) {
                    callbacks.onLockedLongPress(tile)
                    return@let
                }
                if (editMode) select(tile.id) else enterEditMode(tile.id)
                // The finger is still down, so the same gesture can carry straight on into a drag.
                callbacks.onStartDrag(holder)
            }
        }
        holder.btnUnpin.setOnClickListener { holder.tile?.let { callbacks.onTileUnpinned(it) } }
        // Resize handle. A tap steps through the sizes (W10M). Dragging stretches the tile smoothly
        // under your finger, with a faint outline of the size it will take; the size is only
        // chosen when you let go, and the tile then glides into its new place.
        var startX = 0f
        var startY = 0f
        var resizing = false
        var target: TileSize? = null
        val origin = IntArray(2)
        val gridRight = IntArray(2)
        holder.btnResize.setOnTouchListener { v, e ->
            val tile = holder.tile ?: return@setOnTouchListener false
            val pitch = cellPitch()
            val gutter = prefs.gutterDp * density
            val allowed = allowedSizes(tile)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX
                    startY = e.rawY
                    resizing = false
                    target = null
                    holder.root.getLocationOnScreen(origin)
                    (holder.root.parent as? View)?.let { rv ->
                        rv.getLocationOnScreen(gridRight)
                        gridRight[0] += rv.width - rv.paddingRight
                    }
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!resizing && hypot(e.rawX - startX, e.rawY - startY) > touchSlop && pitch > 0f) {
                        resizing = true
                        holder.root.translationZ = 12 * density
                        holder.ghost.visibility = View.VISIBLE
                    }
                    if (resizing) {
                        val minW = (allowed.minOf { it.cols } * pitch - gutter)
                        val minH = (allowed.minOf { it.rows } * pitch - gutter)
                        val maxW = minOf(allowed.maxOf { it.cols } * pitch - gutter, (gridRight[0] - origin[0]).toFloat())
                        val maxH = allowed.maxOf { it.rows } * pitch - gutter
                        val w = (e.rawX - origin[0]).coerceIn(minW, maxOf(minW, maxW))
                        val h = (e.rawY - origin[1]).coerceIn(minH, maxOf(minH, maxH))
                        // Size just this tile directly; a layout request would re-lay the whole grid each frame.
                        sizeDirectly(holder.frame, w.toInt(), h.toInt())
                        val snapped = snapSize(Math.round((w + gutter) / pitch), Math.round((h + gutter) / pitch), allowed)
                        if (snapped != target) {
                            target = snapped
                            sizeDirectly(holder.ghost, (snapped.cols * pitch - gutter).toInt(), (snapped.rows * pitch - gutter).toInt())
                            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (resizing) {
                        // Hand the stretched bounds to the item animator so it morphs from there.
                        dragResizeHint = tile.id to Rect(
                            holder.root.left, holder.root.top,
                            holder.root.left + holder.frame.width, holder.root.top + holder.frame.height
                        )
                        holder.frame.requestLayout()
                        holder.ghost.visibility = View.GONE
                        holder.root.translationZ = 0f
                        val chosen = target
                        if (chosen != null && chosen != tile.size && e.actionMasked == MotionEvent.ACTION_UP) {
                            tile.size = chosen
                            tile.sizeLocked = true
                            callbacks.onTileResized(tile)
                        } else {
                            // Same size: let the tile settle back from its stretched shape.
                            notifyItemChanged(bindingAdapterPositionOf(holder), PAYLOAD_RESTYLE)
                        }
                        resizing = false
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                        var next = tile.size.nextInCycle()
                        repeat(4) { if (next !in allowed) next = next.nextInCycle() }
                        if (next != tile.size) {
                            tile.size = next
                            // The user chose this size; auto-grow leaves it alone from now on.
                            tile.sizeLocked = true
                            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            callbacks.onTileResized(tile)
                        }
                    }
                }
            }
            true
        }
        holder.btnMore.setOnClickListener { holder.tile?.let { callbacks.onTileMenu(it, root) } }
    }

    /** Bounds a drag-resize ended at (tile id → rect in RecyclerView coordinates), consumed by the animator. */
    private var dragResizeHint: Pair<String, Rect>? = null

    fun takeDragResizeHint(holder: RecyclerView.ViewHolder): Rect? {
        val id = (holder as? TileHolder)?.tile?.id ?: return null
        val hint = dragResizeHint?.takeIf { it.first == id } ?: return null
        dragResizeHint = null
        return hint.second
    }

    private fun sizeDirectly(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    private fun bindingAdapterPositionOf(holder: RecyclerView.ViewHolder) = holder.bindingAdapterPosition.coerceAtLeast(0)

    /** Sizes a tile may take: widgets are limited to what they support. */
    fun allowedSizes(tile: TileItem): List<TileSize> = when (tile.type) {
        TileType.SECTION_HEADER -> emptyList()
        TileType.WIDGET -> widgets.allowedSizes(tile.appWidgetId, cellPitch())
        else -> TileSize.entries
    }

    /** The allowed size closest to a footprint of [cols] × [rows] cells. */
    private fun snapSize(cols: Int, rows: Int, allowed: List<TileSize>): TileSize {
        val wanted = when {
            cols <= 1 && rows <= 1 -> TileSize.SMALL
            cols <= 2 && rows <= 2 -> TileSize.MEDIUM
            rows <= 2 -> TileSize.WIDE
            else -> TileSize.LARGE
        }
        if (wanted in allowed || allowed.isEmpty()) return wanted
        return allowed.minBy { abs(it.cols - cols) + abs(it.rows - rows) }
    }

    private fun hasUnread(tile: TileItem) = when (tile.type) {
        TileType.APP_SHORTCUT -> tile.shortcutId == null && (NotificationHub.get(tile.packageName)?.count ?: 0) > 0
        TileType.FOLDER -> tile.children.any { (NotificationHub.get(it.packageName)?.count ?: 0) > 0 }
        else -> false
    }

    /** The view that Metro motion (tilt, turnstile, edit-mode shrink) animates for a tile. */
    fun motionView(rv: RecyclerView, child: View): View =
        (rv.getChildViewHolder(child) as? TileHolder)?.frame ?: child

    /** Lift effect while a tile is being dragged. */
    fun lift(holder: RecyclerView.ViewHolder) {
        val frame = (holder as? TileHolder)?.frame ?: return
        MetroMotion.centerPivot(frame)
        frame.animate().setStartDelay(0).scaleX(1.06f).scaleY(1.06f).alpha(0.95f).setDuration(140).start()
    }

    private fun editButton(parent: FrameLayout, iconRes: Int, gravity: Int, description: String): ImageView =
        ImageView(context).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(Color.BLACK)
            contentDescription = description
            val pad = (5 * density).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            elevation = 4 * density
            visibility = View.GONE
            val size = (30 * density).toInt()
            // Inside the corners, so every tap on them lands on the tile (never on empty Start).
            parent.addView(this, FrameLayout.LayoutParams(size, size, gravity).apply {
                val m = (2 * density).toInt()
                setMargins(m, m, m, m)
            })
        }

    private fun appIconInto(view: ImageView, tile: TileItem, themed: Boolean, stillBound: () -> Boolean, onThemed: (Boolean) -> Unit = {}) {
        val pkg = tile.packageName
        val shortcutId = tile.shortcutId
        if (pkg != null && shortcutId != null) {
            val key = "$pkg/$shortcutId"
            val icon = if (shortcutIcons.containsKey(key)) shortcutIcons[key] else shortcuts.find(pkg, shortcutId)?.let(shortcuts::icon).also { shortcutIcons[key] = it }
            if (icon != null) {
                view.setImageDrawable(icon.constantState?.newDrawable(context.resources) ?: icon)
                onThemed(false)
                return
            }
        }
        icons.iconAsync(pkg, themed) { drawable, isThemed ->
            if (stillBound()) {
                view.setImageDrawable(drawable)
                onThemed(isThemed)
            }
        }
    }

    // ── Holders ─────────────────────────────────────────────────────────────────────────

    abstract inner class TileHolder(
        val root: FrameLayout,
        val frame: FrameLayout,
        val surface: FrameLayout
    ) : RecyclerView.ViewHolder(root) {
        var tile: TileItem? = null
        val front: View? = surface.findViewById(R.id.face_front)
        val back: View? = surface.findViewById(R.id.face_back)
        var showingBack = false
        var lastLiveAt = 0L
        /** Outline of the size a drag-resize will end at. */
        val ghost: View = View(context).apply {
            background = GradientDrawable().apply {
                setColor(0x1AFFFFFF)
                setStroke((2 * density).toInt(), 0xB3FFFFFF.toInt(), 8 * density, 5 * density)
                cornerRadius = prefs.cornerRadiusDp * density
            }
            visibility = View.GONE
            root.addView(this, FrameLayout.LayoutParams(0, 0))
        }
        val btnUnpin = editButton(frame, R.drawable.ic_m_unpin, Gravity.TOP or Gravity.END, "Unpin")
        val btnResize = editButton(frame, R.drawable.ic_m_resize, Gravity.BOTTOM or Gravity.END, "Resize")
        // Top-left, so it never covers the label in the bottom-left corner.
        val btnMore = editButton(frame, R.drawable.ic_m_more, Gravity.TOP or Gravity.START, "More options")

        /** Peek tiles slide their back face up; the rest flip like WP7 live tiles. */
        open val peeks = false

        abstract fun bindContent(tile: TileItem)

        open fun hasBack(tile: TileItem): Boolean = false

        open fun invalidateIcons() {}

        /** Called just before a live animation reveals a face, to load what it should show. */
        open fun beforeReveal(showBack: Boolean) {}

        fun toggleFace(now: Long) {
            val f = front ?: return
            val b = back ?: return
            showingBack = !showingBack
            lastLiveAt = now
            beforeReveal(showingBack)
            if (!prefs.animationsEnabled) MetroMotion.showFace(f, b, showingBack)
            else if (peeks) MetroMotion.peek(f, b, showingBack)
            else MetroMotion.flip(surface, f, b, showingBack)
        }

        fun resetFaces() {
            showingBack = false
            surface.animate().cancel()
            surface.rotationX = 0f
            val f = front ?: return
            val b = back ?: return
            MetroMotion.showFace(f, b, showBack = false)
        }
    }

    inner class AppHolder(root: FrameLayout, frame: FrameLayout, surface: FrameLayout) : TileHolder(root, frame, surface) {
        private val icon: ImageView = surface.findViewById(R.id.iv_tile_app_icon)
        private val label: TextView = surface.findViewById(R.id.tv_tile_app_name)
        private val count: TextView = surface.findViewById(R.id.tv_tile_count)
        private val backTitle: TextView = surface.findViewById(R.id.tv_tile_back_title)
        private val backText: TextView = surface.findViewById(R.id.tv_tile_back_text)
        private val backIcon: ImageView = surface.findViewById(R.id.iv_tile_back_icon)
        private val backName: TextView = surface.findViewById(R.id.tv_tile_back_name)
        private val backCount: TextView = surface.findViewById(R.id.tv_tile_back_count)
        private var boundKey: String? = null
        private var themed = false

        override val peeks = true

        override fun invalidateIcons() {
            boundKey = null
        }

        override fun hasBack(tile: TileItem): Boolean {
            if (tile.size == TileSize.SMALL || tile.shortcutId != null) return false
            val entry = NotificationHub.get(tile.packageName) ?: return false
            return entry.title.isNotEmpty() || entry.text.isNotEmpty()
        }

        private fun sizeIcon(tile: TileItem) {
            val h = tileHeightPx(tile)
            // Monochrome layers carry adaptive-icon padding, so they get a bigger box.
            val factor = when (tile.size) {
                TileSize.SMALL -> 0.5f
                TileSize.LARGE -> 0.3f
                else -> 0.36f
            } * (if (themed) 1.9f else 1f)
            icon.square(minOf(h * factor, h * 0.9f).toInt())
        }

        override fun bindContent(tile: TileItem) {
            val key = "${tile.packageName}/${tile.shortcutId}/${prefs.themedIcons}"
            if (boundKey != key) {
                boundKey = key
                icon.setImageDrawable(null)
                appIconInto(icon, tile, prefs.themedIcons, { boundKey == key }) { isThemed ->
                    themed = isThemed
                    this.tile?.let { sizeIcon(it) }
                }
                icons.iconAsync(tile.packageName, themed = false) { d, _ -> if (boundKey == key) backIcon.setImageDrawable(d) }
            }
            sizeIcon(tile)

            label.text = tile.title
            label.show(prefs.showLabels && tile.size != TileSize.SMALL)

            val entry = if (tile.shortcutId == null) NotificationHub.get(tile.packageName) else null
            val countText = entry?.count?.takeIf { it > 0 }?.let { if (it > 99) "99+" else it.toString() }.orEmpty()
            count.text = countText
            count.show(countText.isNotEmpty())
            backTitle.text = entry?.title.orEmpty()
            backText.text = entry?.text.orEmpty()
            backText.maxLines = if (tile.size == TileSize.LARGE) 9 else 3
            backName.text = tile.title
            backCount.text = countText
        }
    }

    inner class FolderHolder(root: FrameLayout, frame: FrameLayout, surface: FrameLayout) : TileHolder(root, frame, surface) {
        private val grid: LinearLayout = surface.findViewById(R.id.ll_folder_grid)
        private val label: TextView = surface.findViewById(R.id.tv_folder_name)
        private val count: TextView = surface.findViewById(R.id.tv_folder_count)
        private val chevron: ImageView = surface.findViewById(R.id.iv_folder_chevron)
        private var boundKey: String? = null

        override fun invalidateIcons() {
            boundKey = null
        }

        fun setOpen(open: Boolean) {
            chevron.animate().setStartDelay(0).rotation(if (open) 180f else 0f).setDuration(200).start()
        }

        override fun bindContent(tile: TileItem) {
            val (rows, cols) = when (tile.size) {
                TileSize.SMALL -> 2 to 2
                TileSize.MEDIUM -> 2 to 2
                TileSize.WIDE -> 2 to 4
                TileSize.LARGE -> 3 to 3
            }
            val key = tile.children.joinToString(",") { it.id } + "/$rows/$cols/${cellPitch()}"
            if (boundKey != key) {
                boundKey = key
                grid.removeAllViews()
                val h = tileHeightPx(tile)
                val iconPx = (h * if (tile.size == TileSize.SMALL) 0.34f else 0.22f).toInt()
                val gap = (4 * density).toInt()
                var index = 0
                for (r in 0 until rows) {
                    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    for (c in 0 until cols) {
                        val child = tile.children.getOrNull(index++)
                        val iv = ImageView(context)
                        row.addView(iv, LinearLayout.LayoutParams(iconPx, iconPx).apply { setMargins(gap / 2, gap / 2, gap / 2, gap / 2) })
                        if (child != null) appIconInto(iv, child, themed = false, stillBound = { boundKey == key })
                    }
                    grid.addView(row)
                }
            }
            label.text = tile.title
            label.show(prefs.showLabels && tile.size != TileSize.SMALL)
            val unread = tile.children.sumOf { NotificationHub.get(it.packageName)?.count ?: 0 }
            count.text = if (unread > 0) unread.toString() else ""
            count.show(unread > 0)
            chevron.show(tile.size != TileSize.SMALL)
        }
    }

    /** The open folder's contents, laid out inline across the full width (W10M style). */
    inner class FolderPanelHolder(root: FrameLayout, frame: FrameLayout, surface: FrameLayout) : TileHolder(root, frame, surface) {
        private val container: FrameLayout = surface.findViewById(R.id.fl_folder_panel)

        @SuppressLint("ClickableViewAccessibility")
        override fun bindContent(tile: TileItem) {
            container.removeAllViews()
            val pitch = cellPitch()
            val gutter = prefs.gutterDp * density
            val size = (2 * pitch - gutter).toInt()
            val perRow = panelPerRow()
            val color = colorFor(tile)
            val cells = ArrayList<View>()
            tile.children.forEachIndexed { i, app ->
                val cell = FrameLayout(context).apply {
                    background = GradientDrawable().apply {
                        setColor(withAlpha(color, prefs.tileOpacity.coerceIn(20, 100)))
                        cornerRadius = prefs.cornerRadiusDp * density
                    }
                    clipToOutline = prefs.cornerRadiusDp > 0
                }
                val iv = ImageView(context)
                val iconPx = (size * 0.36f).toInt()
                cell.addView(iv, FrameLayout.LayoutParams(iconPx, iconPx, Gravity.CENTER))
                appIconInto(iv, app, themed = false, stillBound = { this.tile === tile })
                cell.addView(TextView(context).apply {
                    text = app.title
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    val pad = (7 * density).toInt()
                    setPadding(pad, 0, pad, pad)
                    visibility = if (prefs.showLabels) View.VISIBLE else View.GONE
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
                NotificationHub.get(app.packageName)?.count?.takeIf { it > 0 }?.let { n ->
                    cell.addView(TextView(context).apply {
                        text = n.toString()
                        setTextColor(Color.WHITE)
                        textSize = 13f
                        val pad = (7 * density).toInt()
                        setPadding(pad, pad, pad, pad)
                    }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
                }
                cell.setOnTouchListener { v, e ->
                    if (prefs.tiltEnabled) when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> MetroMotion.tiltTo(v, e.x, e.y)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> MetroMotion.releaseTilt(v)
                    }
                    false
                }
                cell.setOnClickListener { callbacks.onFolderAppClick(app, cell) }
                cell.setOnLongClickListener {
                    callbacks.onFolderAppMenu(tile, app, cell)
                    true
                }
                val lp = FrameLayout.LayoutParams(size, size).apply {
                    leftMargin = panelPadding + ((i % perRow) * 2 * pitch).toInt()
                    topMargin = panelPadding + ((i / perRow) * 2 * pitch).toInt()
                }
                container.addView(cell, lp)
                cells.add(cell)
            }
            if (panelJustOpened && prefs.animationsEnabled) {
                panelJustOpened = false
                surface.pivotY = 0f
                surface.scaleY = 0.3f
                surface.alpha = 0f
                surface.animate().setStartDelay(0).scaleY(1f).alpha(1f).setDuration(240).setInterpolator(DecelerateInterpolator(2f)).start()
                cells.forEachIndexed { i, c ->
                    c.alpha = 0f
                    c.translationY = -12 * density
                    c.animate().setStartDelay(60L + i * 25L).alpha(1f).translationY(0f).setDuration(260)
                        .setInterpolator(DecelerateInterpolator(2f)).start()
                }
            }
            panelJustOpened = false
        }
    }

    inner class ClockHolder(root: FrameLayout, frame: FrameLayout, surface: FrameLayout) : TileHolder(root, frame, surface) {
        private val time: TextView = surface.findViewById(R.id.tv_clock_time)
        private val ampm: TextView = surface.findViewById(R.id.tv_clock_ampm)
        private val date: TextView = surface.findViewById(R.id.tv_clock_date)
        private val backValue: TextView = surface.findViewById(R.id.tv_clock_back_value)
        private val backTitle: TextView = surface.findViewById(R.id.tv_clock_back_title)

        override fun hasBack(tile: TileItem) = tile.size != TileSize.SMALL

        override fun bindContent(tile: TileItem) {
            val now = Date()
            val is24h = DateFormat.is24HourFormat(context)
            time.text = DateFormat.format(if (is24h) "H:mm" else "h:mm", now)
            ampm.text = if (is24h) "" else DateFormat.format("a", now).toString().lowercase(Locale.getDefault())
            date.text = DateFormat.format(if (tile.size == TileSize.MEDIUM) "EEE d MMM" else "EEEE, d MMMM", now)
            val h = tileHeightPx(tile)
            // Square tiles are width-bound, wide ones height-bound.
            time.sizePx(
                h * when (tile.size) {
                    TileSize.SMALL -> 0.3f
                    TileSize.MEDIUM -> 0.26f
                    TileSize.WIDE -> 0.34f
                    TileSize.LARGE -> 0.24f
                }
            )
            ampm.show(tile.size != TileSize.SMALL && !is24h)
            date.show(tile.size != TileSize.SMALL)

            val next = context.getSystemService(AlarmManager::class.java)?.nextAlarmClock
            if (next != null) {
                backValue.text = DateFormat.format(if (is24h) "EEE H:mm" else "EEE h:mm a", next.triggerTime)
                backTitle.text = "Next alarm"
            } else {
                backValue.text = "No alarms"
                backTitle.text = "Alarms"
            }
        }
    }

    inner class CalendarHolder(root: FrameLayout, frame: FrameLayout, surface: FrameLayout) : TileHolder(root, frame, surface) {
        private val weekday: TextView = surface.findViewById(R.id.tv_cal_weekday)
        private val day: TextView = surface.findViewById(R.id.tv_cal_day)
        private val events: TextView = surface.findViewById(R.id.tv_cal_events)
        private val month: TextView = surface.findViewById(R.id.tv_cal_month)
        private val detail: TextView = surface.findViewById(R.id.tv_cal_detail)

        override fun hasBack(tile: TileItem) = tile.size != TileSize.SMALL

        private fun whenText(e: AgendaProvider.Event): String {
            val today = Calendar.getInstance()
            val at = Calendar.getInstance().apply { timeInMillis = e.begin }
            val sameDay = today.get(Calendar.YEAR) == at.get(Calendar.YEAR) && today.get(Calendar.DAY_OF_YEAR) == at.get(Calendar.DAY_OF_YEAR)
            val time = if (e.allDay) "All day" else DateFormat.getTimeFormat(context).format(Date(e.begin))
            return if (sameDay) time else "${DateFormat.format("EEE", at)} $time"
        }

        override fun bindContent(tile: TileItem) {
            val cal = Calendar.getInstance()
            val now = cal.time
            weekday.text = DateFormat.format(if (tile.size == TileSize.SMALL) "EEE" else "EEEE", now)
            day.text = cal.get(Calendar.DAY_OF_MONTH).toString()
            day.sizePx(tileHeightPx(tile) * if (tile.size == TileSize.SMALL) 0.45f else 0.42f)
            weekday.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (tile.size == TileSize.SMALL) 11f else 14f)

            val upcoming = agenda
            val roomy = tile.size == TileSize.WIDE || tile.size == TileSize.LARGE
            events.show(roomy && upcoming.isNotEmpty())
            events.text = upcoming.take(if (tile.size == TileSize.LARGE) 5 else 2)
                .joinToString("\n") { "${whenText(it)}  ${it.title}" }

            val next = upcoming.firstOrNull()
            if (next != null) {
                month.text = next.title
                detail.text = listOf(whenText(next), next.location).filter { it.isNotEmpty() }.joinToString("\n")
            } else {
                month.text = DateFormat.format("MMMM yyyy", now)
                val daysInYear = cal.getActualMaximum(Calendar.DAY_OF_YEAR)
                detail.text = "Week ${cal.get(Calendar.WEEK_OF_YEAR)}\nDay ${cal.get(Calendar.DAY_OF_YEAR)} of $daysInYear"
            }
        }
    }

    inner class BatteryHolder(root: FrameLayout, frame: FrameLayout, surface: FrameLayout) : TileHolder(root, frame, surface) {
        private val glyph: BatteryGlyph = surface.findViewById(R.id.v_battery_glyph)
        private val percent: TextView = surface.findViewById(R.id.tv_battery_percent)
        private val label: TextView = surface.findViewById(R.id.tv_battery_label)
        private val backTitle: TextView = surface.findViewById(R.id.tv_battery_back_title)
        private val backDetail: TextView = surface.findViewById(R.id.tv_battery_back_detail)

        override fun hasBack(tile: TileItem) = tile.size != TileSize.SMALL

        override fun bindContent(tile: TileItem) {
            val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val bm = context.getSystemService(BatteryManager::class.java)
            val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
                ?: status?.let { s ->
                    val l = s.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = s.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    if (l >= 0 && scale > 0) l * 100 / scale else null
                } ?: 0
            val plugged = (status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            val state = status?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val small = tile.size == TileSize.SMALL
            val h = tileHeightPx(tile)

            glyph.charging = plugged
            glyph.setLevel(level / 100f)
            val glyphH = (h * if (small) 0.24f else 0.15f).toInt()
            glyph.layoutParams = glyph.layoutParams.apply {
                height = glyphH
                width = (glyphH * 2.1f).toInt()
            }
            percent.text = "$level%"
            percent.sizePx(h * if (small) 0.28f else 0.26f)
            val stateText = when {
                state == BatteryManager.BATTERY_STATUS_FULL -> "Fully charged"
                plugged -> "Charging"
                level <= 15 -> "Battery low"
                else -> "Battery"
            }
            label.text = stateText
            label.show(!small && prefs.showLabels)

            backTitle.text = if (stateText == "Battery") "On battery" else stateText
            val lines = ArrayList<String>()
            if (plugged && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val remaining = bm?.computeChargeTimeRemaining() ?: -1L
                if (remaining > 0) {
                    val minutes = remaining / 60_000
                    lines.add(if (minutes >= 60) "Full in ${minutes / 60} h ${minutes % 60} min" else "Full in $minutes min")
                }
            }
            status?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let {
                lines.add(String.format(Locale.getDefault(), "%.1f °C", it / 10f))
            }
            status?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)?.takeIf { it > 0 }?.let {
                lines.add(String.format(Locale.getDefault(), "%.2f V", it / 1000f))
            }
            backDetail.text = lines.joinToString("\n")
        }
    }

    inner class DeviceHolder(root: FrameLayout, frame: FrameLayout, surface: FrameLayout) : TileHolder(root, frame, surface) {
        private val storageRing: RingGauge = surface.findViewById(R.id.v_storage_ring)
        private val storageMain: TextView = surface.findViewById(R.id.tv_storage_main)
        private val storageDetail: TextView = surface.findViewById(R.id.tv_storage_detail)
        private val memoryRing: RingGauge = surface.findViewById(R.id.v_memory_ring)
        private val memoryMain: TextView = surface.findViewById(R.id.tv_memory_main)
        private val memoryDetail: TextView = surface.findViewById(R.id.tv_memory_detail)

        override val peeks = true

        override fun hasBack(tile: TileItem) = tile.size != TileSize.SMALL

        private fun gb(bytes: Long) = String.format(Locale.getDefault(), "%.1f GB", bytes / 1_073_741_824.0)

        override fun bindContent(tile: TileItem) {
            val small = tile.size == TileSize.SMALL
            val textPx = tileHeightPx(tile) * if (small) 0.2f else 0.13f
            try {
                val stat = StatFs(Environment.getDataDirectory().path)
                val total = stat.totalBytes
                val free = stat.availableBytes
                val used = if (total > 0) (total - free).toFloat() / total else 0f
                storageRing.setLevel(used)
                storageMain.text = "${(used * 100).toInt()}%"
                storageDetail.text = if (small) "" else "Storage · ${gb(free)} free"
            } catch (_: Exception) {
                storageMain.text = "—"
                storageDetail.text = "Storage"
            }
            val am = context.getSystemService(ActivityManager::class.java)
            val mem = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mem)
            val memUsed = if (mem.totalMem > 0) (mem.totalMem - mem.availMem).toFloat() / mem.totalMem else 0f
            memoryRing.setLevel(memUsed)
            memoryMain.text = "${(memUsed * 100).toInt()}%"
            memoryDetail.text = "Memory · ${gb(mem.availMem)} free"

            storageMain.sizePx(textPx)
            memoryMain.sizePx(textPx)
            storageDetail.show(!small && prefs.showLabels)
            memoryDetail.show(!small && prefs.showLabels)
        }
    }

    inner class MediaHolder(root: FrameLayout, frame: FrameLayout, surface: FrameLayout) : TileHolder(root, frame, surface) {
        private val art: ImageView = surface.findViewById(R.id.iv_media_art)
        private val scrim: View = surface.findViewById(R.id.v_media_scrim)
        private val iconView: ImageView = surface.findViewById(R.id.iv_media_icon)
        private val title: TextView = surface.findViewById(R.id.tv_media_title)
        private val artist: TextView = surface.findViewById(R.id.tv_media_artist)
        private val controls: LinearLayout = surface.findViewById(R.id.ll_media_controls)
        private val playPause: ImageView = surface.findViewById(R.id.btn_media_play_pause)

        init {
            surface.findViewById<View>(R.id.btn_media_prev).setOnClickListener { if (!editMode) media.previous() }
            surface.findViewById<View>(R.id.btn_media_next).setOnClickListener { if (!editMode) media.next() }
            playPause.setOnClickListener { if (!editMode) media.playPause() }
        }

        override fun bindContent(tile: TileItem) {
            val access = NotificationHub.isAccessGranted(context)
            val cover = media.art
            art.setImageBitmap(cover)
            art.show(cover != null && tile.size != TileSize.SMALL)
            scrim.show(cover != null && tile.size != TileSize.SMALL)
            when {
                media.hasSession -> {
                    title.text = media.title.ifEmpty { "Now playing" }
                    artist.text = media.artist
                }
                access -> {
                    title.text = "Music"
                    artist.text = "Nothing playing"
                }
                else -> {
                    title.text = "Music"
                    artist.text = "Tap to show what's playing"
                }
            }
            playPause.setImageResource(if (media.isPlaying) R.drawable.ic_m_pause else R.drawable.ic_m_play)
            val small = tile.size == TileSize.SMALL
            title.show(!small)
            artist.show(!small)
            artist.maxLines = if (tile.size == TileSize.MEDIUM) 1 else 2
            iconView.show(!small || !media.hasSession)
            controls.show(!small || media.hasSession)
            controls.findViewById<View>(R.id.btn_media_prev).show(!small)
            controls.findViewById<View>(R.id.btn_media_next).show(!small)
        }
    }

    inner class ContactHolder(root: FrameLayout, frame: FrameLayout, surface: FrameLayout) : TileHolder(root, frame, surface) {
        private val avatar: TextView = surface.findViewById(R.id.tv_contact_avatar)
        private val photo: ImageView = surface.findViewById(R.id.iv_contact_photo)
        private val photoScrim: View = surface.findViewById(R.id.v_contact_scrim)
        private val name: TextView = surface.findViewById(R.id.tv_contact_name)
        private val backName: TextView = surface.findViewById(R.id.tv_contact_back_name)
        private val backPhone: TextView = surface.findViewById(R.id.tv_contact_back_phone)

        override fun hasBack(tile: TileItem) = tile.size != TileSize.SMALL && tile.contactPhone.isNotEmpty()

        override fun bindContent(tile: TileItem) {
            val initials = tile.title.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                .take(2).joinToString("") { it.take(1).uppercase() }
            avatar.text = initials.ifEmpty { "?" }
            avatar.sizePx(tileHeightPx(tile) * if (tile.size == TileSize.SMALL) 0.4f else 0.3f)
            name.text = tile.title
            name.show(prefs.showLabels && tile.size != TileSize.SMALL)
            backName.text = tile.title
            backPhone.text = tile.contactPhone

            val file = TileMedia.portrait(context, tile.id)
            photo.show(file != null)
            photoScrim.show(file != null)
            avatar.show(file == null)
            if (file != null) {
                val id = tile.id
                TileMedia.loadAsync(file, tileWidthPx(tile).toInt().coerceAtLeast(64)) { bmp -> if (this.tile?.id == id) photo.setImageBitmap(bmp) }
            }
        }
    }

    inner class PhotosHolder(root: FrameLayout, frame: FrameLayout, surface: FrameLayout) : TileHolder(root, frame, surface) {
        private val frontImage: ImageView = surface.findViewById(R.id.face_front)
        private val backImage: ImageView = surface.findViewById(R.id.face_back)
        private val label: TextView = surface.findViewById(R.id.tv_photos_label)
        private val empty: View = surface.findViewById(R.id.ll_photos_empty)
        private var files: List<File> = emptyList()
        private var index = 0
        private var boundFor: String? = null

        override val peeks = true

        override fun hasBack(tile: TileItem) = files.size >= 2

        override fun invalidateIcons() {
            boundFor = null
        }

        private fun px(tile: TileItem) = maxOf(tileWidthPx(tile), tileHeightPx(tile)).toInt().coerceAtLeast(64)

        private fun show(target: ImageView, i: Int) {
            val tile = tile ?: return
            val file = files.getOrNull(i) ?: return
            val id = tile.id
            TileMedia.loadAsync(file, px(tile)) { bmp -> if (this.tile?.id == id) target.setImageBitmap(bmp) }
            // Warm the cache for the photo after this one.
            files.getOrNull((i + 1) % files.size)?.let { TileMedia.loadAsync(it, px(tile)) {} }
        }

        override fun beforeReveal(showBack: Boolean) {
            if (files.isEmpty()) return
            index = (index + 1) % files.size
            show(if (showBack) backImage else frontImage, index)
        }

        override fun bindContent(tile: TileItem) {
            if (boundFor != tile.id + tile.size) {
                boundFor = tile.id + tile.size
                files = TileMedia.photos(context, tile.id)
                index = 0
                frontImage.setImageDrawable(null)
                show(frontImage, 0)
            }
            empty.show(files.isEmpty())
            label.text = tile.title
            label.show(prefs.showLabels && tile.size != TileSize.SMALL && files.isNotEmpty())
        }
    }

    inner class WidgetHolder(root: FrameLayout, frame: FrameLayout, surface: FrameLayout) : TileHolder(root, frame, surface) {
        private val container: FrameLayout = surface.findViewById(R.id.fl_widget_container)
        private val missing: TextView = surface.findViewById(R.id.tv_widget_missing)
        private var boundWidget = -1

        override fun bindContent(tile: TileItem) {
            if (boundWidget != tile.appWidgetId) {
                boundWidget = tile.appWidgetId
                container.removeAllViews()
                val view = widgets.createView(context, tile.appWidgetId)
                if (view != null) {
                    container.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
                missing.show(view == null)
            }
            widgets.updateSize(tile.appWidgetId, (tileWidthPx(tile) / density).toInt(), (tileHeightPx(tile) / density).toInt())
        }
    }

    inner class WeatherHolder(root: FrameLayout, frame: FrameLayout, surface: FrameLayout) : TileHolder(root, frame, surface) {
        private val icon: ImageView = surface.findViewById(R.id.iv_weather_icon)
        private val temp: TextView = surface.findViewById(R.id.tv_weather_temp)
        private val detail: View = surface.findViewById(R.id.ll_weather_detail)
        private val condition: TextView = surface.findViewById(R.id.tv_weather_condition)
        private val range: TextView = surface.findViewById(R.id.tv_weather_range)
        private val place: TextView = surface.findViewById(R.id.tv_weather_place)
        private val days: LinearLayout = surface.findViewById(R.id.ll_weather_days)
        private val backPlace: TextView = surface.findViewById(R.id.tv_weather_back_place)

        override fun hasBack(tile: TileItem) = tile.size != TileSize.SMALL && (weather()?.days?.size ?: 0) >= 2

        private fun deg(v: Double) = "${Math.round(v)}°"

        override fun bindContent(tile: TileItem) {
            val report = weather()
            val h = tileHeightPx(tile)
            val small = tile.size == TileSize.SMALL
            val roomy = tile.size == TileSize.WIDE || tile.size == TileSize.LARGE
            icon.square((h * if (small) 0.42f else 0.3f).toInt())
            temp.sizePx(h * if (small) 0.26f else if (tile.size == TileSize.MEDIUM) 0.24f else 0.3f)
            if (report == null) {
                icon.setImageResource(R.drawable.ic_m_sun)
                temp.text = if (small) "" else "—"
                condition.text = if (weatherConfigured()) "Updating…" else "Tap to set up"
                range.text = ""
                place.text = tile.title
            } else {
                icon.setImageResource(WeatherCodes.icon(report.code, report.isDay))
                temp.text = deg(report.temperature)
                condition.text = WeatherCodes.describe(report.code)
                range.text = report.today?.let { "${deg(it.max)} / ${deg(it.min)}" }.orEmpty()
                place.text = report.place.ifEmpty { tile.title }
            }
            temp.show(!small || report != null)
            detail.show(roomy || report == null && !small)
            place.show(!small && prefs.showLabels)
            backPlace.text = place.text

            days.removeAllViews()
            report?.days?.drop(1)?.take(if (roomy) 4 else 2)?.forEach { d ->
                days.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    val label = runCatching {
                        val parts = d.date.split("-").map { it.toInt() }
                        val c = Calendar.getInstance().apply { set(parts[0], parts[1] - 1, parts[2]) }
                        DateFormat.format("EEE", c).toString()
                    }.getOrDefault(d.date.takeLast(5))
                    addView(TextView(context).apply {
                        text = label
                        setTextColor(Color.WHITE)
                        textSize = 12f
                    })
                    addView(ImageView(context).apply {
                        setImageResource(WeatherCodes.icon(d.code, true))
                        val s = (22 * density).toInt()
                        layoutParams = LinearLayout.LayoutParams(s, s).apply { setMargins(0, (3 * density).toInt(), 0, (3 * density).toInt()) }
                    })
                    addView(TextView(context).apply {
                        text = "${deg(d.max)} ${deg(d.min)}"
                        setTextColor(Color.WHITE)
                        textSize = 12f
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
    }

    inner class SearchHolder(root: FrameLayout, frame: FrameLayout, surface: FrameLayout) : TileHolder(root, frame, surface) {
        private val hint: TextView = surface.findViewById(R.id.tv_search_hint)
        private val label: TextView = surface.findViewById(R.id.tv_search_label)

        override fun bindContent(tile: TileItem) {
            hint.show(tile.size == TileSize.WIDE || tile.size == TileSize.LARGE)
            label.show(tile.size != TileSize.SMALL && prefs.showLabels)
        }
    }

    inner class ActionHolder(root: FrameLayout, frame: FrameLayout, surface: FrameLayout) : TileHolder(root, frame, surface) {
        private val iconView: ImageView = surface.findViewById(R.id.iv_action_icon)
        private val title: TextView = surface.findViewById(R.id.tv_action_title)
        private val subtitle: TextView = surface.findViewById(R.id.tv_action_subtitle)

        override fun bindContent(tile: TileItem) {
            iconView.setImageDrawable(
                ContextCompat.getDrawable(
                    context,
                    if (tile.type == TileType.KEYBOARD_SETTINGS) R.drawable.ic_m_keyboard else R.drawable.ic_m_settings
                )
            )
            iconView.square((tileHeightPx(tile) * if (tile.size == TileSize.SMALL) 0.45f else 0.3f).toInt())
            title.text = tile.title
            title.show(tile.size != TileSize.SMALL && prefs.showLabels)
            subtitle.text = tile.customSubtitle
            subtitle.show(tile.customSubtitle.isNotEmpty() && (tile.size == TileSize.WIDE || tile.size == TileSize.LARGE))
        }
    }

    inner class HeaderHolder(root: FrameLayout, frame: FrameLayout, surface: FrameLayout) : TileHolder(root, frame, surface) {
        private val title: TextView = surface.findViewById(R.id.tv_section_title)

        override fun bindContent(tile: TileItem) {
            title.text = tile.title.lowercase(Locale.getDefault())
        }
    }
}
