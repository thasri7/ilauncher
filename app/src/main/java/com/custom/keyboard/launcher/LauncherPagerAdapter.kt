package com.custom.keyboard.launcher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.custom.keyboard.R

/** The launcher pages: Start (tiles), All apps, and the optional Text page. */
class LauncherPagerAdapter(
    private val onTilesPageReady: (RecyclerView) -> Unit,
    private val onDrawerPageReady: (View) -> Unit,
    /** Builds the Text page, or null when it is turned off. */
    private val textPage: () -> View?
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val PAGE_TILES = 0
        const val PAGE_DRAWER = 1
        const val PAGE_TEXT = 2
    }

    var textPageShown = true
        set(value) {
            if (field == value) return
            field = value
            if (value) notifyItemInserted(PAGE_TEXT) else notifyItemRemoved(PAGE_TEXT)
        }

    override fun getItemViewType(position: Int): Int = position

    override fun getItemCount(): Int = if (textPageShown) 3 else 2

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == PAGE_TEXT) {
            val frame = android.widget.FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            return PageViewHolder(frame)
        }
        val inflater = LayoutInflater.from(parent.context)
        val layout = if (viewType == PAGE_TILES) R.layout.page_launcher_tiles else R.layout.page_launcher_drawer
        return PageViewHolder(inflater.inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (position) {
            PAGE_TILES -> onTilesPageReady(holder.itemView.findViewById(R.id.rv_metro_tiles))
            PAGE_DRAWER -> onDrawerPageReady(holder.itemView)
            else -> {
                val frame = holder.itemView as android.widget.FrameLayout
                val page = textPage() ?: return
                (page.parent as? ViewGroup)?.removeView(page)
                frame.removeAllViews()
                frame.addView(page, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        }
    }

    class PageViewHolder(view: View) : RecyclerView.ViewHolder(view)
}
