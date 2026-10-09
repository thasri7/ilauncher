package com.custom.keyboard.launcher

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.custom.keyboard.AppLauncherHelper
import com.custom.keyboard.R
import java.util.Locale

/**
 * Windows 10 Mobile style "All apps" list: an optional "Most used" group followed by apps
 * grouped under accent-coloured letter tiles. Tapping a letter opens the jump list.
 * With [grouped] off it renders a flat list, which the search panel uses for results.
 */
class AppDrawerAdapter(
    private val icons: IconCache,
    private val accent: () -> Int,
    private val tiltEnabled: () -> Boolean,
    private val isPinned: (String) -> Boolean,
    private val onAppClick: (AppLauncherHelper.AppEntry, View) -> Unit,
    private val onAppLongClick: ((AppLauncherHelper.AppEntry, View) -> Unit)? = null,
    private val onHeaderClick: (() -> Unit)? = null,
    private val onPrivateClick: (() -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Row {
        data class Header(val letter: Char) : Row()
        data class Label(val text: String) : Row()
        data class App(val entry: AppLauncherHelper.AppEntry) : Row()
        data class Private(val count: Int) : Row()
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_LABEL = 1
        private const val TYPE_APP = 2
        private const val TYPE_PRIVATE = 3
        private const val TYPE_APP_GRID = 4
        const val OTHER_LETTER = '#'

        fun letterOf(name: String): Char {
            val c = name.trim().firstOrNull()?.uppercaseChar() ?: return OTHER_LETTER
            return if (c in 'A'..'Z') c else OTHER_LETTER
        }
    }

    private var rows: List<Row> = emptyList()

    /** Grid layout for All apps (icons in columns) instead of the W10M list. */
    var grid = false
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    /** Headers, labels and the private row span the whole grid. */
    fun isFullSpan(position: Int): Boolean = rows.getOrNull(position) !is Row.App
    /** Bumped when icons change (icon pack switch) so every row reloads its icon. */
    private var iconGeneration = 0

    /** Letters that currently have at least one app, for the jump list. */
    var lettersPresent: Set<Char> = emptySet()
        private set

    @SuppressLint("NotifyDataSetChanged")
    /**
     * @param grouped All apps (sections) rather than a flat result list
     * @param letterGroups A–Z letter headers; otherwise [apps] keep their order under [listLabel]
     */
    fun submit(
        apps: List<AppLauncherHelper.AppEntry>,
        grouped: Boolean,
        mostUsed: List<AppLauncherHelper.AppEntry> = emptyList(),
        privateCount: Int = 0,
        recentlyAdded: List<AppLauncherHelper.AppEntry> = emptyList(),
        letterGroups: Boolean = true,
        listLabel: String? = null
    ) {
        val out = ArrayList<Row>(apps.size + 32)
        if (grouped && privateCount > 0) out.add(Row.Private(privateCount))
        if (!grouped) {
            apps.mapTo(out) { Row.App(it) }
            lettersPresent = emptySet()
        } else if (!letterGroups) {
            if (recentlyAdded.isNotEmpty()) {
                out.add(Row.Label("Recently added"))
                recentlyAdded.mapTo(out) { Row.App(it) }
            }
            listLabel?.let { out.add(Row.Label(it)) }
            apps.mapTo(out) { Row.App(it) }
            lettersPresent = emptySet()
        } else {
            if (recentlyAdded.isNotEmpty()) {
                out.add(Row.Label("Recently added"))
                recentlyAdded.mapTo(out) { Row.App(it) }
            }
            if (mostUsed.isNotEmpty()) {
                out.add(Row.Label("Most used"))
                mostUsed.mapTo(out) { Row.App(it) }
            }
            val byLetter = apps.groupBy { letterOf(it.name) }
            val order = listOf(OTHER_LETTER) + ('A'..'Z')
            for (letter in order) {
                val group = byLetter[letter] ?: continue
                out.add(Row.Header(letter))
                group.mapTo(out) { Row.App(it) }
            }
            lettersPresent = byLetter.keys
        }
        rows = out
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun invalidateIcons() {
        iconGeneration++
        notifyDataSetChanged()
    }

    fun positionOf(letter: Char): Int = rows.indexOfFirst { it is Row.Header && it.letter == letter }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Header -> TYPE_HEADER
        is Row.Label -> TYPE_LABEL
        is Row.App -> if (grid) TYPE_APP_GRID else TYPE_APP
        is Row.Private -> TYPE_PRIVATE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(inflater.inflate(R.layout.item_app_header, parent, false))
            TYPE_LABEL -> LabelHolder(inflater.inflate(R.layout.item_drawer_label, parent, false))
            TYPE_PRIVATE -> PrivateHolder(inflater.inflate(R.layout.item_app_drawer, parent, false))
            TYPE_APP_GRID -> AppHolder(inflater.inflate(R.layout.item_app_grid, parent, false))
            else -> AppHolder(inflater.inflate(R.layout.item_app_drawer, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderHolder).bind(row.letter)
            is Row.Label -> (holder as LabelHolder).bind(row.text)
            is Row.App -> (holder as AppHolder).bind(row.entry)
            is Row.Private -> (holder as PrivateHolder).bind(row.count)
        }
    }

    inner class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val letter: TextView = view.findViewById(R.id.tv_header_letter)

        fun bind(value: Char) {
            letter.text = if (value == OTHER_LETTER) "#" else value.toString().lowercase(Locale.getDefault())
            letter.background = GradientDrawable().apply { setColor(accent()) }
            itemView.setOnClickListener { onHeaderClick?.invoke() }
        }
    }

    inner class LabelHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val label: TextView = view.findViewById(R.id.tv_drawer_label)

        fun bind(text: String) {
            label.text = text.lowercase(Locale.getDefault())
        }
    }

    /** "Private apps": hidden apps, opened only after the phone's fingerprint / PIN check. */
    inner class PrivateHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.iv_drawer_icon)
        private val name: TextView = view.findViewById(R.id.tv_drawer_name)

        fun bind(count: Int) {
            icon.setImageResource(R.drawable.ic_m_lock)
            icon.imageTintList = ColorStateList.valueOf(accent())
            val pad = (icon.resources.displayMetrics.density * 9).toInt()
            icon.setPadding(pad, pad, pad, pad)
            name.text = "Private apps · $count"
            itemView.setOnClickListener { onPrivateClick?.invoke() }
            itemView.setOnLongClickListener(null)
        }
    }

    inner class AppHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.iv_drawer_icon)
        private val name: TextView = view.findViewById(R.id.tv_drawer_name)
        private val pin: ImageView = view.findViewById(R.id.iv_drawer_pin)
        private var boundKey: String? = null

        @SuppressLint("ClickableViewAccessibility")
        fun bind(app: AppLauncherHelper.AppEntry) {
            name.text = app.name
            val key = "${app.packageName}#$iconGeneration"
            if (boundKey != key) {
                boundKey = key
                icon.setImageDrawable(null)
                icons.iconAsync(app.packageName, themed = false) { d, _ -> if (boundKey == key) icon.setImageDrawable(d) }
            }
            pin.visibility = if (isPinned(app.packageName)) View.VISIBLE else View.GONE
            pin.imageTintList = ColorStateList.valueOf(0x99FFFFFF.toInt())
            itemView.setOnTouchListener { v, e ->
                if (tiltEnabled()) when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> MetroMotion.tiltTo(v, e.x, e.y)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> MetroMotion.releaseTilt(v)
                }
                false
            }
            itemView.setOnClickListener { onAppClick(app, itemView) }
            itemView.setOnLongClickListener {
                val handler = onAppLongClick ?: return@setOnLongClickListener false
                handler(app, itemView)
                true
            }
        }
    }
}
