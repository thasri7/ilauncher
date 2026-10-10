package com.custom.keyboard.launcher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.custom.keyboard.R

/**
 * The launcher pages, left to right: Today (optional), Start (tiles), All apps, Text page
 * (optional). Positions depend on which optional pages are on, so callers use [tiles],
 * [drawer], [text] and [today] instead of fixed numbers.
 */
class LauncherPagerAdapter(
    private val onTilesPageReady: (RecyclerView) -> Unit,
    private val onDrawerPageReady: (View) -> Unit,
    /** Builds the Text page, or null when it is turned off. */
    private val textPage: () -> View?,
    /** Builds the Today page, or null when it is turned off. */
    private val todayPage: () -> View? = { null }
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    enum class Kind { TODAY, TILES, DRAWER, TEXT }

    private var kinds: List<Kind> = listOf(Kind.TILES, Kind.DRAWER, Kind.TEXT)

    fun configure(today: Boolean, text: Boolean) {
        val next = listOfNotNull(if (today) Kind.TODAY else null, Kind.TILES, Kind.DRAWER, if (text) Kind.TEXT else null)
        if (next == kinds) return
        kinds = next
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    fun positionOf(kind: Kind): Int = kinds.indexOf(kind)
    fun kindAt(position: Int): Kind? = kinds.getOrNull(position)

    val tiles: Int get() = positionOf(Kind.TILES)
    val drawer: Int get() = positionOf(Kind.DRAWER)
    /** -1 when the Text page is off. */
    val text: Int get() = positionOf(Kind.TEXT)
    /** -1 when the Today page is off. */
    val today: Int get() = positionOf(Kind.TODAY)

    override fun getItemViewType(position: Int): Int = kinds[position].ordinal

    override fun getItemId(position: Int): Long = kinds[position].ordinal.toLong()

    override fun getItemCount(): Int = kinds.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (Kind.entries[viewType]) {
            Kind.TODAY, Kind.TEXT -> PageViewHolder(FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            })
            Kind.TILES -> PageViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.page_launcher_tiles, parent, false))
            Kind.DRAWER -> PageViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.page_launcher_drawer, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (kinds[position]) {
            Kind.TILES -> onTilesPageReady(holder.itemView.findViewById(R.id.rv_metro_tiles))
            Kind.DRAWER -> onDrawerPageReady(holder.itemView)
            Kind.TEXT -> host(holder, textPage())
            Kind.TODAY -> host(holder, todayPage())
        }
    }

    private fun host(holder: RecyclerView.ViewHolder, page: View?) {
        val frame = holder.itemView as FrameLayout
        page ?: return
        if (page.parent === frame) return
        (page.parent as? ViewGroup)?.removeView(page)
        frame.removeAllViews()
        frame.addView(page, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    class PageViewHolder(view: View) : RecyclerView.ViewHolder(view)
}
