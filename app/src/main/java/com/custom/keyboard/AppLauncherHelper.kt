package com.custom.keyboard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.os.Bundle
import android.os.Process
import android.os.UserHandle

/**
 * Every launchable app on the phone, including copies made by "App clone" / "Dual apps" and work
 * profile apps (read through LauncherApps, like the phone's own launcher). Clones get a key of
 * "package#profile" (see [AppKeys]) and their name as the system badges it.
 */
class AppLauncherHelper(private val context: Context) {

    /** [packageName] is the app's key: the package, or "package#profile" for a clone. */
    data class AppEntry(
        val name: String,
        val packageName: String,
        val user: UserHandle? = null,
        val component: ComponentName? = null
    ) {
        val isClone: Boolean get() = AppKeys.isClone(packageName)
    }

    private val installedApps = mutableListOf<AppEntry>()
    private val launcherApps = context.getSystemService(LauncherApps::class.java)

    init {
        loadInstalledApps()
    }

    /** Re-reads the installed apps, e.g. after a package was installed or removed. */
    fun reload() {
        installedApps.clear()
        loadInstalledApps()
    }

    private fun loadInstalledApps() {
        val me = Process.myUserHandle()
        val seen = mutableSetOf<String>()
        val la = launcherApps
        if (la != null) {
            try {
                for (user in la.profiles) {
                    for (info in la.getActivityList(null, user)) {
                        val pkg = info.applicationInfo.packageName
                        if (pkg == context.packageName && user == me) continue
                        val key = AppKeys.keyFor(context, pkg, user)
                        if (!seen.add(key)) continue
                        var label = info.label?.toString()?.trim().orEmpty()
                        if (label.isEmpty()) continue
                        if (user != me) {
                            // The system's own badged name ("WhatsApp (Clone)", "Work Gmail"…).
                            label = context.packageManager.getUserBadgedLabel(label, user).toString()
                                .takeIf { it != label } ?: "$label (clone)"
                        }
                        installedApps.add(AppEntry(label, key, user, info.componentName))
                    }
                }
            } catch (_: Exception) {
            }
        }
        if (installedApps.isNotEmpty()) return
        // Fallback: this user's apps only.
        try {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            for (ri in pm.queryIntentActivities(intent, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == context.packageName || !seen.add(pkg)) continue
                val label = ri.loadLabel(pm).toString().trim()
                if (label.isNotEmpty()) installedApps.add(AppEntry(label, pkg))
            }
        } catch (_: Exception) {}
    }

    fun entry(key: String): AppEntry? = installedApps.firstOrNull { it.packageName == key }

    /**
     * Opens an app (a clone through its own profile). Returns false when it isn't there any more.
     */
    fun start(key: String, sourceBounds: Rect?, options: Bundle?): Boolean {
        if (AppKeys.isClone(key)) {
            val e = entry(key) ?: return false
            val component = e.component ?: return false
            return try {
                launcherApps?.startMainActivity(component, e.user ?: AppKeys.user(context, key), sourceBounds, options)
                true
            } catch (_: Exception) {
                false
            }
        }
        val intent = launchIntentFor(key) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.sourceBounds = sourceBounds
        return try {
            context.startActivity(intent, options)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** True when the app can still be opened (for clean-up of tiles). */
    fun isInstalled(key: String): Boolean = if (AppKeys.isClone(key)) entry(key) != null else launchIntentFor(key) != null

    /** App info page; clones open their own profile's page. */
    fun showDetails(key: String): Boolean {
        val e = entry(key)
        if (AppKeys.isClone(key) && e?.component != null) {
            return try {
                launcherApps?.startAppDetailsActivity(e.component, e.user ?: AppKeys.user(context, key), null, null)
                true
            } catch (_: Exception) {
                false
            }
        }
        return false
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
        if (AppKeys.isClone(packageName)) null else try {
            context.packageManager.getLaunchIntentForPackage(packageName)
        } catch (_: Exception) {
            null
        }

    fun launchApp(packageName: String): Boolean = start(packageName, null, null)
}
