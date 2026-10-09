package com.custom.keyboard.launcher

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Bundle

/**
 * App shortcuts ("New message", "Navigate home"…) through LauncherApps. Android only grants
 * this to the default home app, so everything degrades to "no shortcuts" otherwise.
 */
class AppShortcuts(private val context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)

    val isAvailable: Boolean
        get() = try {
            launcherApps?.hasShortcutHostPermission() == true
        } catch (_: Exception) {
            false
        }

    private fun query(packageName: String, ids: List<String>? = null): List<ShortcutInfo> {
        if (!isAvailable) return emptyList()
        val q = LauncherApps.ShortcutQuery()
            .setPackage(com.custom.keyboard.AppKeys.pkg(packageName))
            .setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
            )
        if (ids != null) q.setShortcutIds(ids)
        return try {
            launcherApps?.getShortcuts(q, com.custom.keyboard.AppKeys.user(context, packageName)).orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** The shortcuts an app offers in its long-press menu, best first. */
    fun forPackage(packageName: String, max: Int = 5): List<ShortcutInfo> =
        query(packageName)
            .filter { it.isEnabled && (it.isDynamic || it.isDeclaredInManifest) }
            .sortedWith(compareBy({ !it.isDeclaredInManifest }, { it.rank }))
            .take(max)

    fun find(packageName: String, id: String): ShortcutInfo? = query(packageName, listOf(id)).firstOrNull()

    fun icon(info: ShortcutInfo): Drawable? = try {
        launcherApps?.getShortcutIconDrawable(info, context.resources.displayMetrics.densityDpi)
    } catch (_: Exception) {
        null
    }

    fun start(packageName: String, id: String, sourceBounds: Rect?, options: Bundle?): Boolean = try {
        launcherApps?.startShortcut(com.custom.keyboard.AppKeys.pkg(packageName), id, sourceBounds, options, com.custom.keyboard.AppKeys.user(context, packageName))
        launcherApps != null
    } catch (_: Exception) {
        false
    }

    /**
     * Keeps the shortcuts pinned to Start alive even if the app later removes them from its
     * dynamic list. [ids] must be every shortcut of [packageName] that Start still shows.
     */
    fun syncPinned(packageName: String, ids: List<String>) {
        if (!isAvailable) return
        try {
            launcherApps?.pinShortcuts(com.custom.keyboard.AppKeys.pkg(packageName), ids, com.custom.keyboard.AppKeys.user(context, packageName))
        } catch (_: Exception) {
        }
    }
}
