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
    private val onHeaderClick: (() -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Row {
        data class Header(val letter: Char) : Row()
        data class Label(val text: String) : Row()
        data class App(val entry: AppLauncherHelper.AppEntry) : Row()
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_LABEL = 1
        private const val TYPE_APP = 2
        const val OTHER_LETTER = '#'

        fun letterOf(name: String): Char {
            val c = name.trim().firstOrNull()?.uppercaseChar() ?: return OTHER_LETTER
            return if (c in 'A'..'Z') c else OTHER_LETTER
        }
    }

    private var rows: List<Row> = emptyList()

    /** Letters that currently have at least one app, for the jump list. */
    var lettersPresent: Set<Char> = emptySet()
        private set

    @SuppressLint("NotifyDataSetChanged")
    fun submit(apps: List<AppLauncherHelper.AppEntry>, grouped: Boolean, mostUsed: List<AppLauncherHelper.AppEntry> = emptyList()) {
        val out = ArrayList<Row>(apps.size + 32)
        if (!grouped) {
            apps.mapTo(out) { Row.App(it) }
            lettersPresent = emptySet()
        } else {
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

    fun positionOf(letter: Char): Int = rows.indexOfFirst { it is Row.Header && it.letter == letter }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Header -> TYPE_HEADER
        is Row.Label -> TYPE_LABEL
        is Row.App -> TYPE_APP
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(inflater.inflate(R.layout.item_app_header, parent, false))
            TYPE_LABEL -> LabelHolder(inflater.inflate(R.layout.item_drawer_label, parent, false))
            else -> AppHolder(inflater.inflate(R.layout.item_app_drawer, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderHolder).bind(row.letter)
            is Row.Label -> (holder as LabelHolder).bind(row.text)
            is Row.App -> (holder as AppHolder).bind(row.entry)
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

    inner class AppHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.iv_drawer_icon)
        private val name: TextView = view.findViewById(R.id.tv_drawer_name)
        private val pin: ImageView = view.findViewById(R.id.iv_drawer_pin)
        private var boundPackage: String? = null

        @SuppressLint("ClickableViewAccessibility")
        fun bind(app: AppLauncherHelper.AppEntry) {
            name.text = app.name
            if (boundPackage != app.packageName) {
                icon.setImageDrawable(icons.icon(app.packageName))
                boundPackage = app.packageName
            }
            pin.visibility = if (isPinned(app.packageName)) View.VISIBLE else View.GONE
            pin.imageTintList = ColorStateList.valueOf(accent())
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
