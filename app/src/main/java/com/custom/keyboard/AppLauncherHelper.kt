package com.custom.keyboard

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

class AppLauncherHelper(private val context: Context) {

    data class AppEntry(val name: String, val packageName: String)

    private val installedApps = mutableListOf<AppEntry>()

    init {
        loadInstalledApps()
    }

    /** Re-reads the installed apps, e.g. after a package was installed or removed. */
    fun reload() {
        installedApps.clear()
        loadInstalledApps()
    }

    private fun loadInstalledApps() {
        try {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(intent, 0)
            val seen = mutableSetOf<String>()
            for (ri in resolveInfos) {
                val pkg = ri.activityInfo.packageName
                if (pkg == context.packageName || seen.contains(pkg)) continue
                seen.add(pkg)
                val label = ri.loadLabel(pm).toString().trim()
                if (label.isNotEmpty()) {
                    installedApps.add(AppEntry(label, pkg))
                }
            }
        } catch (_: Exception) {}
    }

    // Check if query matches any app (e.g. "whatsapp", "yt", "camera", "calc")
    fun findMatchingApp(query: String): AppEntry? {
        val clean = query.trim().lowercase()
        if (clean.length < 2) return null

        // 1. Exact or startsWith match on app label
        val directMatch = installedApps.firstOrNull {
            it.name.lowercase() == clean || it.name.lowercase().startsWith(clean)
        }
        if (directMatch != null) return directMatch

        // 2. Common nickname matches
        return when (clean) {
            "yt" -> installedApps.firstOrNull { it.packageName.contains("youtube") }
            "ig", "insta" -> installedApps.firstOrNull { it.packageName.contains("instagram") }
            "wa" -> installedApps.firstOrNull { it.packageName.contains("whatsapp") }
            "fb" -> installedApps.firstOrNull { it.packageName.contains("katana") || it.packageName.contains("facebook") }
            "calc" -> installedApps.firstOrNull { it.name.lowercase().contains("calc") }
            else -> null
        }
    }

    fun getAllApps(): List<AppEntry> = installedApps.sortedBy { it.name.lowercase() }

    fun launchIntentFor(packageName: String): Intent? =
        try {
            context.packageManager.getLaunchIntentForPackage(packageName)
        } catch (_: Exception) {
            null
        }

    fun launchApp(packageName: String): Boolean {
        return try {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }
}
