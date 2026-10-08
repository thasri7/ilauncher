package com.custom.keyboard.launcher

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.custom.keyboard.R
import com.custom.keyboard.models.TileItem
import com.custom.keyboard.models.TileType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Collections
import java.util.Date
import java.util.Locale

class MetroTileAdapter(
    private val context: Context,
    val tiles: MutableList<TileItem>,
    private val onTileClick: (TileItem) -> Unit,
    private val onTileLongClick: (TileItem, View) -> Unit,
    private val onTileResizeChanged: (TileItem) -> Unit,
    private val onTileRemoved: (TileItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val packageManager: PackageManager = context.packageManager
    private val audioManager: AudioManager? = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val timeFormat = SimpleDateFormat("h:mm", Locale.getDefault())
    private val ampmFormat = SimpleDateFormat("a", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())
    private val monthFormat = SimpleDateFormat("MMMM", Locale.getDefault())
    private val weekdayFormat = SimpleDateFormat("EEEE", Locale.getDefault())

    private var isPlayingMedia = false

    companion object {
        private const val VIEW_TYPE_CLOCK = 1
        private const val VIEW_TYPE_BATTERY = 2
        private const val VIEW_TYPE_STORAGE = 3
        private const val VIEW_TYPE_SEARCH = 4
        private const val VIEW_TYPE_APP = 5
        private const val VIEW_TYPE_CALENDAR = 6
        private const val VIEW_TYPE_WEATHER = 7
        private const val VIEW_TYPE_MEDIA = 8
        private const val VIEW_TYPE_CONTACT = 9
        private const val VIEW_TYPE_SECTION = 10
        private const val VIEW_TYPE_ACTION = 11
    }

    fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                Collections.swap(tiles, i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                Collections.swap(tiles, i, i - 1)
            }
        }
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    override fun getItemViewType(position: Int): Int {
        return when (tiles[position].type) {
            TileType.CLOCK_WEATHER -> VIEW_TYPE_CLOCK
            TileType.CALENDAR_BIG -> VIEW_TYPE_CALENDAR
            TileType.WEATHER_LIVE -> VIEW_TYPE_WEATHER
            TileType.BATTERY_STATUS -> VIEW_TYPE_BATTERY
            TileType.STORAGE_STATS -> VIEW_TYPE_STORAGE
            TileType.MEDIA_PLAYER -> VIEW_TYPE_MEDIA
            TileType.QUICK_CONTACT -> VIEW_TYPE_CONTACT
            TileType.SECTION_HEADER -> VIEW_TYPE_SECTION
            TileType.EXPRESS_SEARCH -> VIEW_TYPE_SEARCH
            TileType.APP_SHORTCUT -> VIEW_TYPE_APP
            TileType.KEYBOARD_SETTINGS, TileType.DEVICE_SETTINGS -> VIEW_TYPE_ACTION
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_CALENDAR -> CalendarBigViewHolder(inflater.inflate(R.layout.item_tile_calendar_big, parent, false))
            VIEW_TYPE_WEATHER -> WeatherViewHolder(inflater.inflate(R.layout.item_tile_weather, parent, false))
            VIEW_TYPE_CLOCK -> ClockViewHolder(inflater.inflate(R.layout.item_tile_clock, parent, false))
            VIEW_TYPE_BATTERY -> BatteryViewHolder(inflater.inflate(R.layout.item_tile_battery, parent, false))
            VIEW_TYPE_STORAGE -> StorageViewHolder(inflater.inflate(R.layout.item_tile_storage, parent, false))
            VIEW_TYPE_MEDIA -> MediaViewHolder(inflater.inflate(R.layout.item_tile_media, parent, false))
            VIEW_TYPE_CONTACT -> ContactViewHolder(inflater.inflate(R.layout.item_tile_contact, parent, false))
            VIEW_TYPE_SECTION -> SectionViewHolder(inflater.inflate(R.layout.item_tile_section, parent, false))
            VIEW_TYPE_SEARCH -> SearchViewHolder(inflater.inflate(R.layout.item_tile_search, parent, false))
            VIEW_TYPE_APP -> AppViewHolder(inflater.inflate(R.layout.item_tile_app, parent, false))
            else -> ActionViewHolder(inflater.inflate(R.layout.item_tile_action, parent, false))
        }
    }

    override fun getItemCount(): Int = tiles.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val tile = tiles[position]
        if (tile.type != TileType.SECTION_HEADER) {
            attachMetroTouchAnimation(holder.itemView, tile)
        }

        when (holder) {
            is CalendarBigViewHolder -> holder.bind(tile)
            is WeatherViewHolder -> holder.bind(tile)
            is ClockViewHolder -> holder.bind(tile)
            is BatteryViewHolder -> holder.bind(tile)
            is StorageViewHolder -> holder.bind(tile)
            is MediaViewHolder -> holder.bind(tile)
            is ContactViewHolder -> holder.bind(tile)
            is SectionViewHolder -> holder.bind(tile)
            is SearchViewHolder -> holder.bind(tile)
            is AppViewHolder -> holder.bind(tile)
            is ActionViewHolder -> holder.bind(tile)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachMetroTouchAnimation(view: View, tile: TileItem) {
        view.setOnTouchListener { v, event ->
            if (tile.isEditMode) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val scaleDownX = ObjectAnimator.ofFloat(v, "scaleX", 0.94f)
                    val scaleDownY = ObjectAnimator.ofFloat(v, "scaleY", 0.94f)
                    AnimatorSet().apply {
                        duration = 80
                        playTogether(scaleDownX, scaleDownY)
                        start()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val scaleUpX = ObjectAnimator.ofFloat(v, "scaleX", 1f)
                    val scaleUpY = ObjectAnimator.ofFloat(v, "scaleY", 1f)
                    AnimatorSet().apply {
                        duration = 140
                        interpolator = OvershootInterpolator(1.3f)
                        playTogether(scaleUpX, scaleUpY)
                        start()
                    }
                }
            }
            false
        }

        view.setOnClickListener {
            if (!tile.isEditMode) {
                onTileClick(tile)
            }
        }
        view.setOnLongClickListener {
            perform3DFlipAnimation(view) {
                tile.isEditMode = !tile.isEditMode
                notifyItemChanged(tiles.indexOf(tile))
            }
            onTileLongClick(tile, view)
            true
        }
    }

    private fun perform3DFlipAnimation(view: View, onHalfway: () -> Unit) {
        view.cameraDistance = view.width * 25f
        val flip1 = ObjectAnimator.ofFloat(view, "rotationY", 0f, 90f).apply {
            duration = 160
            interpolator = AccelerateDecelerateInterpolator()
        }
        val flip2 = ObjectAnimator.ofFloat(view, "rotationY", -90f, 0f).apply {
            duration = 160
            interpolator = AccelerateDecelerateInterpolator()
        }
        flip1.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                onHalfway()
                flip2.start()
            }
        })
        flip1.start()
    }

    inner class CalendarBigViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvTime: TextView = view.findViewById(R.id.tv_cal_small_time)
        private val tvMonth: TextView = view.findViewById(R.id.tv_cal_month)
        private val tvWeekday: TextView = view.findViewById(R.id.tv_cal_weekday)
        private val tvHugeDay: TextView = view.findViewById(R.id.tv_cal_huge_day)
        private val container: LinearLayout = view.findViewById(R.id.tile_calendar_container)

        fun bind(tile: TileItem) {
            val now = Date()
            val cal = Calendar.getInstance()
            tvTime.text = "${timeFormat.format(now)} ${ampmFormat.format(now)}"
            tvMonth.text = monthFormat.format(now)
            tvWeekday.text = weekdayFormat.format(now)
            tvHugeDay.text = cal.get(Calendar.DAY_OF_MONTH).toString()
            applyTileAccent(container, tile.accentColorHex)
        }
    }

    inner class WeatherViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvTemp: TextView = view.findViewById(R.id.tv_weather_temp)
        private val tvCondition: TextView = view.findViewById(R.id.tv_weather_condition)
        private val container: LinearLayout = view.findViewById(R.id.tile_weather_container)

        fun bind(tile: TileItem) {
            if (tile.customSubtitle.isNotEmpty()) {
                val parts = tile.customSubtitle.split(" ")
                if (parts.isNotEmpty()) tvTemp.text = parts[0]
                tvCondition.text = tile.customSubtitle
            }
            applyTileAccent(container, tile.accentColorHex)
        }
    }

    inner class ClockViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvTime: TextView = view.findViewById(R.id.tv_clock_time)
        private val tvAmPm: TextView = view.findViewById(R.id.tv_clock_ampm)
        private val tvDate: TextView = view.findViewById(R.id.tv_clock_date)
        private val container: LinearLayout = view.findViewById(R.id.tile_clock_container)

        fun bind(tile: TileItem) {
            val now = Date()
            tvTime.text = timeFormat.format(now)
            tvAmPm.text = ampmFormat.format(now)
            tvDate.text = dateFormat.format(now)
            applyTileAccent(container, tile.accentColorHex)
        }
    }

    inner class BatteryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvPercent: TextView = view.findViewById(R.id.tv_battery_percent)
        private val tvStatus: TextView = view.findViewById(R.id.tv_battery_status)
        private val pbLevel: ProgressBar = view.findViewById(R.id.pb_battery_level)
        private val tvChargingIcon: TextView = view.findViewById(R.id.tv_battery_charging_icon)
        private val container: LinearLayout = view.findViewById(R.id.tile_battery_container)

        fun bind(tile: TileItem) {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 80
            val isCharging = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) == BatteryManager.BATTERY_STATUS_CHARGING
            } else false

            tvPercent.text = "$level%"
            pbLevel.progress = level
            if (isCharging) {
                tvChargingIcon.visibility = View.VISIBLE
                tvStatus.text = "Charging ⚡"
            } else {
                tvChargingIcon.visibility = View.GONE
                tvStatus.text = if (level > 20) "Healthy" else "Battery Low"
            }
            applyTileAccent(container, tile.accentColorHex)
        }
    }

    inner class StorageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvMain: TextView = view.findViewById(R.id.tv_storage_main)
        private val tvDetail: TextView = view.findViewById(R.id.tv_storage_detail)
        private val pbBar: ProgressBar = view.findViewById(R.id.pb_storage_bar)
        private val container: LinearLayout = view.findViewById(R.id.tile_storage_container)

        fun bind(tile: TileItem) {
            try {
                val stat = StatFs(Environment.getDataDirectory().path)
                val totalBytes = stat.blockCountLong * stat.blockSizeLong
                val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
                val usedBytes = totalBytes - freeBytes
                val percentUsed = if (totalBytes > 0) ((usedBytes * 100) / totalBytes).toInt() else 0
                val freeGb = freeBytes / (1024 * 1024 * 1024)

                tvMain.text = "$percentUsed%"
                pbBar.progress = percentUsed
                tvDetail.text = "Free: ${freeGb} GB"
            } catch (_: Exception) {
                tvMain.text = "OK"
                tvDetail.text = "Storage Optimal"
            }
            applyTileAccent(container, tile.accentColorHex)
        }
    }

    inner class MediaViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvTrack: TextView = view.findViewById(R.id.tv_media_track)
        private val btnPlayPause: TextView = view.findViewById(R.id.btn_media_play_pause)
        private val btnPrev: TextView = view.findViewById(R.id.btn_media_prev)
        private val btnNext: TextView = view.findViewById(R.id.btn_media_next)
        private val container: LinearLayout = view.findViewById(R.id.tile_media_container)

        fun bind(tile: TileItem) {
            tvTrack.text = if (isPlayingMedia) "Playing Track 🎶" else "Music Paused"
            btnPlayPause.text = if (isPlayingMedia) "⏸" else "▶"

            btnPlayPause.setOnClickListener {
                isPlayingMedia = !isPlayingMedia
                btnPlayPause.text = if (isPlayingMedia) "⏸" else "▶"
                tvTrack.text = if (isPlayingMedia) "Playing Track 🎶" else "Music Paused"
                sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            }
            btnPrev.setOnClickListener {
                sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            }
            btnNext.setOnClickListener {
                sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_NEXT)
            }
            applyTileAccent(container, tile.accentColorHex)
        }

        private fun sendMediaKeyEvent(keyCode: Int) {
            try {
                audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            } catch (_: Exception) {}
        }
    }

    inner class ContactViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView = view.findViewById(R.id.tv_contact_name)
        private val tvAvatar: TextView = view.findViewById(R.id.tv_contact_avatar)
        private val container: RelativeLayout = view.findViewById(R.id.tile_contact_container)

        fun bind(tile: TileItem) {
            tvName.text = tile.title
            val initial = tile.title.trim().take(1).uppercase()
            tvAvatar.text = if (initial.isNotEmpty()) initial else "👤"

            container.setOnClickListener {
                if (tile.contactPhone.isNotEmpty()) {
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${tile.contactPhone}"))
                    context.startActivity(intent)
                } else {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("content://contacts/people/"))
                    context.startActivity(intent)
                }
            }
            applyTileAccent(container, tile.accentColorHex)
        }
    }

    inner class SectionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvTitle: TextView = view.findViewById(R.id.tv_section_title)
        fun bind(tile: TileItem) {
            tvTitle.text = tile.title.uppercase()
        }
    }

    inner class SearchViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val container: LinearLayout = view.findViewById(R.id.tile_search_container)
        fun bind(tile: TileItem) {
            applyTileAccent(container, tile.accentColorHex)
        }
    }

    inner class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val rootFrame: FrameLayout = view.findViewById(R.id.tile_app_root)
        private val ivIcon: ImageView = view.findViewById(R.id.iv_tile_app_icon)
        private val tvName: TextView = view.findViewById(R.id.tv_tile_app_name)
        private val tvBadge: TextView = view.findViewById(R.id.tv_tile_badge)
        private val tvUsageBadge: TextView = view.findViewById(R.id.tv_tile_usage_badge)
        private val container: RelativeLayout = view.findViewById(R.id.tile_app_container)

        private val editOverlay: LinearLayout = view.findViewById(R.id.ll_tile_edit_overlay)
        private val btn1x1: TextView = view.findViewById(R.id.btn_size_1x1)
        private val btn2x1: TextView = view.findViewById(R.id.btn_size_2x1)
        private val btn2x2: TextView = view.findViewById(R.id.btn_size_2x2)
        private val btn1x2: TextView = view.findViewById(R.id.btn_size_1x2)
        private val btnDone: TextView = view.findViewById(R.id.btn_tile_done)
        private val btnDelete: TextView = view.findViewById(R.id.btn_tile_delete)


        fun bind(tile: TileItem) {
            tvName.text = tile.title

            val density = context.resources.displayMetrics.density
            // Square Home tile height grid:
            // 1×1 compact square = 90dp, 2×1 wide = 125dp, 2×2 large = 260dp
            val targetHeightDp = when {
                tile.spanY == 2 -> 260
                tile.spanX == 1 -> 90
                else -> 125
            }
            rootFrame.layoutParams.height = (targetHeightDp * density).toInt()
            // Icon size scales with tile
            val iconSizeDp = when {
                tile.spanY == 2 -> 64
                tile.spanX == 1 -> 36
                else -> 48
            }
            ivIcon.layoutParams.width = (iconSizeDp * density).toInt()
            ivIcon.layoutParams.height = (iconSizeDp * density).toInt()
            ivIcon.requestLayout()


            if (tile.launchCount >= 2) {
                tvUsageBadge.visibility = View.VISIBLE
                tvUsageBadge.text = "🔥 ${tile.launchCount}"
            } else {
                tvUsageBadge.visibility = View.GONE
            }

            if (tile.badgeCount.isNotEmpty()) {
                tvBadge.text = tile.badgeCount
                tvBadge.visibility = View.VISIBLE
            } else {
                tvBadge.visibility = View.GONE
            }

            tile.packageName?.let { pkg ->
                try {
                    val icon = packageManager.getApplicationIcon(pkg)
                    ivIcon.setImageDrawable(icon)
                } catch (_: Exception) {
                    ivIcon.setImageResource(android.R.drawable.sym_def_app_icon)
                }
            } ?: run {
                ivIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            }

            if (tile.isEditMode) {
                editOverlay.visibility = View.VISIBLE

                btn1x1.setOnClickListener {
                    tile.spanX = 1
                    tile.spanY = 1
                    onTileResizeChanged(tile)
                }
                btn2x1.setOnClickListener {
                    tile.spanX = 2
                    tile.spanY = 1
                    onTileResizeChanged(tile)
                }
                btn2x2.setOnClickListener {
                    tile.spanX = 2
                    tile.spanY = 2
                    onTileResizeChanged(tile)
                }
                btn1x2.setOnClickListener {
                    tile.spanX = 1
                    tile.spanY = 2
                    onTileResizeChanged(tile)
                }
                btnDone.setOnClickListener {
                    tile.isEditMode = false
                    editOverlay.visibility = View.GONE
                    onTileResizeChanged(tile)
                }
                btnDelete.setOnClickListener {
                    tile.isEditMode = false
                    onTileRemoved(tile)
                }
            } else {
                editOverlay.visibility = View.GONE
            }

            applyTileAccent(container, tile.accentColorHex)
        }
    }

    inner class ActionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvTitle: TextView = view.findViewById(R.id.tv_action_title)
        private val tvSubtitle: TextView = view.findViewById(R.id.tv_action_subtitle)
        private val tvIcon: TextView = view.findViewById(R.id.tv_action_icon)
        private val container: LinearLayout = view.findViewById(R.id.tile_action_container)

        fun bind(tile: TileItem) {
            tvTitle.text = tile.title
            tvSubtitle.text = tile.customSubtitle
            tvIcon.text = when (tile.type) {
                TileType.KEYBOARD_SETTINGS -> "⌨️"
                TileType.QUICK_CONTACT -> "👤"
                else -> "⚙️"
            }
            applyTileAccent(container, tile.accentColorHex)
        }
    }

    private fun applyTileAccent(view: View, colorHex: String) {
        try {
            val parsedColor = Color.parseColor(colorHex)
            // Square Home uses ~55% opaque fill + subtle edge glow
            val fillColor = Color.argb(140, Color.red(parsedColor), Color.green(parsedColor), Color.blue(parsedColor))
            val strokeColor = Color.argb(200, Color.red(parsedColor), Color.green(parsedColor), Color.blue(parsedColor))
            val bgDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f
                setColor(fillColor)
                setStroke(3, strokeColor)
            }
            view.background = bgDrawable
        } catch (_: Exception) {}
    }
}
