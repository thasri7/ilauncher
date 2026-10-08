package com.custom.keyboard.launcher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.custom.keyboard.R

class LauncherPagerAdapter(
    private val onTilesPageReady: (RecyclerView) -> Unit,
    private val onDrawerPageReady: (RecyclerView) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val PAGE_TILES = 0
        const val PAGE_DRAWER = 1
    }

    override fun getItemViewType(position: Int): Int = position

    override fun getItemCount(): Int = 2

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == PAGE_TILES) {
            val view = inflater.inflate(R.layout.page_launcher_tiles, parent, false)
            TilesPageViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.page_launcher_drawer, parent, false)
            DrawerPageViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is TilesPageViewHolder) {
            val rv = holder.itemView.findViewById<RecyclerView>(R.id.rv_metro_tiles)
            onTilesPageReady(rv)
        } else if (holder is DrawerPageViewHolder) {
            val rv = holder.itemView.findViewById<RecyclerView>(R.id.rv_app_drawer)
            onDrawerPageReady(rv)
        }
    }

    class TilesPageViewHolder(view: View) : RecyclerView.ViewHolder(view)
    class DrawerPageViewHolder(view: View) : RecyclerView.ViewHolder(view)
}
