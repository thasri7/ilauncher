package com.custom.keyboard.launcher

import com.custom.keyboard.AppLauncherHelper
import java.util.Locale

/** App search ranking shared by the search panel, All apps and the app picker. */
object AppSearch {
    /**
     * Name prefix beats word prefix beats substring beats package match; [nickname] (e.g. "yt"
     * for YouTube) goes first. Ties sort alphabetically.
     */
    fun rank(
        apps: List<AppLauncherHelper.AppEntry>,
        query: String,
        nickname: AppLauncherHelper.AppEntry? = null
    ): List<AppLauncherHelper.AppEntry> {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return apps
        return apps.mapNotNull { app ->
            val name = app.name.lowercase(Locale.ROOT)
            val score = when {
                app == nickname -> 0
                name.startsWith(q) -> 1
                name.split(' ', '-', '.', '_').any { it.startsWith(q) } -> 2
                name.contains(q) -> 3
                app.packageName.lowercase(Locale.ROOT).contains(q) -> 4
                else -> return@mapNotNull null
            }
            score to app
        }.sortedWith(compareBy({ it.first }, { it.second.name.lowercase(Locale.ROOT) })).map { it.second }
    }
}
