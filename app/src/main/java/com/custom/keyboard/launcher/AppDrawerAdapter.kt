package com.custom.keyboard.launcher

import android.content.Context
import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.custom.keyboard.AppLauncherHelper
import com.custom.keyboard.R

class AppDrawerAdapter(
    private val context: Context,
    private var appsList: List<AppLauncherHelper.AppEntry>,
    private val onAppClick: (AppLauncherHelper.AppEntry) -> Unit,
    private val onAppLongClick: ((AppLauncherHelper.AppEntry, View) -> Unit)? = null
) : RecyclerView.Adapter<AppDrawerAdapter.AppViewHolder>() {

    private val packageManager: PackageManager = context.packageManager

    fun updateData(newApps: List<AppLauncherHelper.AppEntry>) {
        this.appsList = newApps
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_drawer, parent, false)
        return AppViewHolder(view)
    }

    override fun getItemCount(): Int = appsList.size

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val app = appsList[position]
        holder.bind(app)
    }

    inner class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val ivIcon: ImageView = view.findViewById(R.id.iv_drawer_icon)
        private val tvName: TextView = view.findViewById(R.id.tv_drawer_name)

        fun bind(app: AppLauncherHelper.AppEntry) {
            tvName.text = app.name
            try {
                val icon = packageManager.getApplicationIcon(app.packageName)
                ivIcon.setImageDrawable(icon)
            } catch (_: Exception) {
                ivIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            }

            itemView.setOnClickListener {
                onAppClick(app)
            }

            itemView.setOnLongClickListener {
                onAppLongClick?.invoke(app, itemView)
                true
            }
        }
    }
}
