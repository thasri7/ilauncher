package com.custom.keyboard.launcher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.custom.keyboard.R

/** The two launcher pages: Start (tiles) and All apps. */
class LauncherPagerAdapter(
    private val onTilesPageReady: (RecyclerView) -> Unit,
    private val onDrawerPageReady: (View) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val PAGE_TILES = 0
        const val PAGE_DRAWER = 1
    }

    override fun getItemViewType(position: Int): Int = position

    override fun getItemCount(): Int = 2

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val layout = if (viewType == PAGE_TILES) R.layout.page_launcher_tiles else R.layout.page_launcher_drawer
        return PageViewHolder(inflater.inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (position == PAGE_TILES) {
            onTilesPageReady(holder.itemView.findViewById(R.id.rv_metro_tiles))
        } else {
            onDrawerPageReady(holder.itemView)
        }
    }

    class PageViewHolder(view: View) : RecyclerView.ViewHolder(view)
}
