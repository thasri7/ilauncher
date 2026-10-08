package com.custom.keyboard.launcher

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.custom.keyboard.R
import com.custom.keyboard.models.TileItem
import com.custom.keyboard.models.TileSize
import com.custom.keyboard.models.TileType
import java.util.Calendar
import java.util.Collections
import java.util.Date
import java.util.Locale
import kotlin.math.hypot
import kotlin.random.Random

class MetroTileAdapter(
    private val context: Context,
    private val tiles: MutableList<TileItem>,
    private val prefs: TilePreferences,
    private val icons: IconCache,
    private val media: MediaTileController,
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

        /** Re-read time/battery/storage values without re-binding anything else. */
        const val PAYLOAD_TICK = "tick"
        const val PAYLOAD_EDIT = "edit"
        const val PAYLOAD_NOTIFICATIONS = "notifications"
        const val PAYLOAD_MEDIA = "media"
        /** Size or colour changed: full re-bind on the same holder, so the grid animates the move. */
        const val PAYLOAD_RESTYLE = "restyle"
        /** App icons changed (install/update): reload them quietly. */
        const val PAYLOAD_ICONS = "icons"

        private const val BACK_FACE_HOLD_MS = 4500L
        private const val FRONT_FACE_HOLD_MS = 7000L
    }

    private val density = context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val headerHeightPx = (52 * density).toInt()

    var editMode = false
        private set
    private var selectedId: String? = null

    val headerHeight: Int get() = headerHeightPx

    // ── Edit mode ───────────────────────────────────────────────────────────────────────

    fun enterEditMode(selectId: String?) {
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

    fun moveTile(from: Int, to: Int) {
        if (from < to) for (i in from until to) Collections.swap(tiles, i, i + 1)
        else for (i in from downTo to + 1) Collections.swap(tiles, i, i - 1)
        notifyItemMoved(from, to)
    }

    /** Called when a drag ends, to settle the dragged tile back into its edit-mode look. */
    fun settle(holder: RecyclerView.ViewHolder) {
        val h = holder as? TileHolder ?: return
        val tile = h.tile ?: return
        applyEditState(h, tile, animate = true)
    }

    // ── Live updates ────────────────────────────────────────────────────────────────────

    private fun notifyTypes(payload: String, predicate: (TileItem) -> Boolean) {
        tiles.forEachIndexed { i, tile -> if (predicate(tile)) notifyItemChanged(i, payload) }
    }

    fun tick() = notifyTypes(PAYLOAD_TICK) {
        it.type == TileType.CLOCK_WEATHER || it.type == TileType.CALENDAR_BIG ||
            it.type == TileType.BATTERY_STATUS || it.type == TileType.STORAGE_STATS
    }

    fun onBatteryChanged() = notifyTypes(PAYLOAD_TICK) { it.type == TileType.BATTERY_STATUS }

    fun onNotificationsChanged() = notifyTypes(PAYLOAD_NOTIFICATIONS) { it.type == TileType.APP_SHORTCUT }

    fun onMediaChanged() = notifyTypes(PAYLOAD_MEDIA) { it.type == TileType.MEDIA_PLAYER }

    /** One beat of the live-tile clock: flip a tile back to its front, or show a back face. */
    fun runLiveStep(rv: RecyclerView) {
        if (editMode || !prefs.liveTilesEnabled) return
        val now = SystemClock.uptimeMillis()
        val live = (0 until rv.childCount)
            .mapNotNull { rv.getChildAt(it)?.let(rv::getChildViewHolder) as? TileHolder }
            .filter { h ->
                val tile = h.tile
                tile != null && tile.liveEnabled && h.hasBack(tile) &&
                    h.itemView.bottom > 0 && h.itemView.top < rv.height
            }
        val target = live.filter { it.showingBack && now - it.lastLiveAt > BACK_FACE_HOLD_MS }.randomOrNull()
            ?: live.filter { !it.showingBack && now - it.lastLiveAt > FRONT_FACE_HOLD_MS }.randomOrNull()
            ?: return
        target.toggleFace(now)
    }

    // ── Adapter ─────────────────────────────────────────────────────────────────────────

    fun spanFor(position: Int): MetroGridLayoutManager.Spec {
        val tile = tiles.getOrNull(position) ?: return MetroGridLayoutManager.Spec(2, 2)
        return if (tile.type == TileType.SECTION_HEADER) {
            MetroGridLayoutManager.Spec(0, 0, headerHeightPx)
        } else {
            MetroGridLayoutManager.Spec(tile.size.cols, tile.size.rows)
        }
    }

    override fun getItemCount(): Int = tiles.size

    override fun getItemViewType(position: Int): Int = when (tiles[position].type) {
        TileType.APP_SHORTCUT -> TYPE_APP
        TileType.CLOCK_WEATHER -> TYPE_CLOCK
        TileType.CALENDAR_BIG -> TYPE_CALENDAR
        TileType.BATTERY_STATUS -> TYPE_BATTERY
        TileType.STORAGE_STATS -> TYPE_STORAGE
        TileType.MEDIA_PLAYER -> TYPE_MEDIA
        TileType.QUICK_CONTACT -> TYPE_CONTACT
        TileType.EXPRESS_SEARCH -> TYPE_SEARCH
        TileType.SECTION_HEADER -> TYPE_HEADER
        TileType.WEATHER_LIVE, TileType.KEYBOARD_SETTINGS, TileType.DEVICE_SETTINGS -> TYPE_ACTION
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
            else -> R.layout.item_tile_action
        }
        // itemView belongs to RecyclerView's item animator and ItemTouchHelper (they move it and
        // cancel its animations on every change), so all Metro motion runs on the inner frame.
        val root = FrameLayout(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            clipChildren = false
        }
        val frame = FrameLayout(parent.context).apply { clipChildren = false }
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
            else -> ActionHolder(root, frame, surface)
        }
        wireTouches(holder)
        return holder
    }

    override fun onBindViewHolder(holder: TileHolder, position: Int) {
        val tile = tiles[position]
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
        applyEditState(holder, tile, animate = false)
    }

    override fun onBindViewHolder(holder: TileHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
            return
        }
        val tile = tiles[position]
        holder.tile = tile
        if (PAYLOAD_RESTYLE in payloads) {
            onBindViewHolder(holder, position)
            MetroMotion.pop(holder.surface)
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

    private fun styleSurface(holder: TileHolder, tile: TileItem) {
        if (tile.type == TileType.SECTION_HEADER) {
            holder.surface.background = null
            return
        }
        val color = colorFor(tile)
        val alpha = (prefs.tileOpacity.coerceIn(20, 100) * 255) / 100
        holder.surface.background = GradientDrawable().apply {
            setColor(Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)))
            cornerRadius = prefs.cornerRadiusDp * density
        }
        holder.surface.clipToOutline = prefs.cornerRadiusDp > 0
    }

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
        h.btnResize.show(selected && !isHeader)
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
    }

    // ── Touch: tilt, tap, long-press to customise, drag to move ─────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun wireTouches(holder: TileHolder) {
        val root = holder.root
        val frame = holder.frame
        var downX = 0f
        var downY = 0f
        var dragging = false
        root.setOnTouchListener { _, e ->
            val tile = holder.tile ?: return@setOnTouchListener false
            val tilts = !editMode && prefs.tiltEnabled && tile.type != TileType.SECTION_HEADER
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    dragging = false
                    if (tilts) MetroMotion.tiltTo(frame, e.x, e.y)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (editMode && !dragging && hypot(e.rawX - downX, e.rawY - downY) > touchSlop) {
                        dragging = true
                        select(tile.id)
                        callbacks.onStartDrag(holder)
                    } else if (tilts) {
                        MetroMotion.tiltTo(frame, e.x, e.y)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (!editMode) MetroMotion.releaseTilt(frame)
            }
            false
        }
        root.setOnClickListener {
            val tile = holder.tile ?: return@setOnClickListener
            if (editMode) select(tile.id) else callbacks.onTileClick(tile, root)
        }
        root.setOnLongClickListener {
            val tile = holder.tile ?: return@setOnLongClickListener false
            if (editMode) select(tile.id) else enterEditMode(tile.id)
            // The finger is still down, so the same gesture can carry straight on into a drag.
            callbacks.onStartDrag(holder)
            true
        }
        holder.btnUnpin.setOnClickListener { holder.tile?.let { callbacks.onTileUnpinned(it) } }
        holder.btnResize.setOnClickListener {
            holder.tile?.let {
                it.size = it.size.nextInCycle()
                callbacks.onTileResized(it)
            }
        }
        holder.btnMore.setOnClickListener { holder.tile?.let { callbacks.onTileMenu(it, root) } }
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
            parent.addView(this, FrameLayout.LayoutParams(size, size, gravity).apply {
                val m = (3 * density).toInt()
                setMargins(m, m, m, m)
            })
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
        val btnUnpin = editButton(frame, R.drawable.ic_m_unpin, Gravity.TOP or Gravity.END, "Unpin")
        val btnResize = editButton(frame, R.drawable.ic_m_resize, Gravity.BOTTOM or Gravity.END, "Resize")
        val btnMore = editButton(frame, R.drawable.ic_m_more, Gravity.BOTTOM or Gravity.START, "More options")

        /** Peek tiles slide their back face up; the rest flip like WP7 live tiles. */
        open val peeks = false

        abstract fun bindContent(tile: TileItem)

        open fun hasBack(tile: TileItem): Boolean = false

        open fun invalidateIcons() {}

        fun toggleFace(now: Long) {
            val f = front ?: return
            val b = back ?: return
            showingBack = !showingBack
            lastLiveAt = now
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
        private var boundPackage: String? = null
        private var boundThemed = false

        override val peeks = true

        override fun invalidateIcons() {
            boundPackage = null
        }

        override fun hasBack(tile: TileItem): Boolean {
            if (tile.size == TileSize.SMALL) return false
            val entry = NotificationHub.get(tile.packageName) ?: return false
            return entry.title.isNotEmpty() || entry.text.isNotEmpty()
        }

        override fun bindContent(tile: TileItem) {
            val h = tileHeightPx(tile)
            val themedDrawable = if (prefs.themedIcons) icons.themedIcon(tile.packageName) else null
            val themed = themedDrawable != null
            // Monochrome layers carry adaptive-icon padding, so they get a bigger box.
            val factor = when (tile.size) {
                TileSize.SMALL -> 0.5f
                TileSize.LARGE -> 0.3f
                else -> 0.36f
            } * (if (themed) 1.9f else 1f)
            icon.square(minOf(h * factor, h * 0.9f).toInt())
            if (boundPackage != tile.packageName || boundThemed != themed) {
                icon.setImageDrawable(themedDrawable ?: icons.icon(tile.packageName))
                backIcon.setImageDrawable(icons.icon(tile.packageName))
                boundPackage = tile.packageName
                boundThemed = themed
            }

            label.text = tile.title
            label.show(prefs.showLabels && tile.size != TileSize.SMALL)

            val entry = NotificationHub.get(tile.packageName)
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
        private val month: TextView = surface.findViewById(R.id.tv_cal_month)
        private val detail: TextView = surface.findViewById(R.id.tv_cal_detail)

        override fun hasBack(tile: TileItem) = tile.size != TileSize.SMALL

        override fun bindContent(tile: TileItem) {
            val cal = Calendar.getInstance()
            val now = cal.time
            weekday.text = DateFormat.format(if (tile.size == TileSize.SMALL) "EEE" else "EEEE", now)
            day.text = cal.get(Calendar.DAY_OF_MONTH).toString()
            day.sizePx(tileHeightPx(tile) * if (tile.size == TileSize.SMALL) 0.45f else 0.42f)
            weekday.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (tile.size == TileSize.SMALL) 11f else 14f)
            month.text = DateFormat.format("MMMM yyyy", now)
            val daysInYear = cal.getActualMaximum(Calendar.DAY_OF_YEAR)
            detail.text = "Week ${cal.get(Calendar.WEEK_OF_YEAR)}\nDay ${cal.get(Calendar.DAY_OF_YEAR)} of $daysInYear"
        }
    }

    inner class BatteryHolder(root: FrameLayout, frame: FrameLayout, surface: FrameLayout) : TileHolder(root, frame, surface) {
        private val percent: TextView = surface.findViewById(R.id.tv_battery_percent)
        private val bar: ProgressBar = surface.findViewById(R.id.pb_battery_level)
        private val label: TextView = surface.findViewById(R.id.tv_battery_label)
        private val iconView: ImageView = surface.findViewById(R.id.iv_battery_icon)
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

            percent.text = "$level%"
            percent.sizePx(tileHeightPx(tile) * if (tile.size == TileSize.SMALL) 0.3f else 0.26f)
            bar.progress = level
            bar.show(tile.size != TileSize.SMALL)
            label.show(tile.size != TileSize.SMALL && prefs.showLabels)
            iconView.setImageResource(if (plugged) R.drawable.ic_m_bolt else R.drawable.ic_m_battery)
            iconView.show(tile.size != TileSize.SMALL)

            backTitle.text = when {
                state == BatteryManager.BATTERY_STATUS_FULL -> "Fully charged"
                plugged -> "Charging"
                level <= 15 -> "Battery low"
                else -> "On battery"
            }
            val lines = ArrayList<String>()
            status?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let {
                lines.add(String.format(Locale.getDefault(), "%.1f °C", it / 10f))
            }
            status?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)?.takeIf { it > 0 }?.let {
                lines.add(String.format(Locale.getDefault(), "%.2f V", it / 1000f))
            }
            if (plugged && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val remaining = bm?.computeChargeTimeRemaining() ?: -1L
                if (remaining > 0) {
                    val minutes = remaining / 60_000
                    lines.add(if (minutes >= 60) "Full in ${minutes / 60} h ${minutes % 60} min" else "Full in $minutes min")
                }
            }
            backDetail.text = lines.joinToString("\n")
        }
    }

    inner class DeviceHolder(root: FrameLayout, frame: FrameLayout, surface: FrameLayout) : TileHolder(root, frame, surface) {
        private val storageMain: TextView = surface.findViewById(R.id.tv_storage_main)
        private val storageBar: ProgressBar = surface.findViewById(R.id.pb_storage_bar)
        private val storageDetail: TextView = surface.findViewById(R.id.tv_storage_detail)
        private val memoryMain: TextView = surface.findViewById(R.id.tv_memory_main)
        private val memoryBar: ProgressBar = surface.findViewById(R.id.pb_memory_bar)
        private val memoryDetail: TextView = surface.findViewById(R.id.tv_memory_detail)

        override val peeks = true

        override fun hasBack(tile: TileItem) = tile.size != TileSize.SMALL

        private fun gb(bytes: Long) = String.format(Locale.getDefault(), "%.1f GB", bytes / 1_073_741_824.0)

        override fun bindContent(tile: TileItem) {
            val big = tile.size != TileSize.SMALL
            val textPx = tileHeightPx(tile) * if (big) 0.24f else 0.3f
            try {
                val stat = StatFs(Environment.getDataDirectory().path)
                val total = stat.totalBytes
                val free = stat.availableBytes
                val used = if (total > 0) ((total - free) * 100 / total).toInt() else 0
                storageMain.text = "$used%"
                storageBar.progress = used
                storageDetail.text = if (big) "Storage · ${gb(free)} free" else "Storage"
            } catch (_: Exception) {
                storageMain.text = "—"
                storageDetail.text = "Storage"
            }
            val am = context.getSystemService(ActivityManager::class.java)
            val mem = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mem)
            val memUsed = if (mem.totalMem > 0) ((mem.totalMem - mem.availMem) * 100 / mem.totalMem).toInt() else 0
            memoryMain.text = "$memUsed%"
            memoryBar.progress = memUsed
            memoryDetail.text = "Memory · ${gb(mem.availMem)} free"

            storageMain.sizePx(textPx)
            memoryMain.sizePx(textPx)
            storageBar.show(big)
            storageDetail.show(prefs.showLabels || !big)
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
                    when (tile.type) {
                        TileType.KEYBOARD_SETTINGS -> R.drawable.ic_m_keyboard
                        TileType.WEATHER_LIVE -> R.drawable.ic_m_sun
                        else -> R.drawable.ic_m_settings
                    }
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
