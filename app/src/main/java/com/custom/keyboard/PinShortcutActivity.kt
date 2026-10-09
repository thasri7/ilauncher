package com.custom.keyboard

import android.app.Activity
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import com.custom.keyboard.launcher.TilePreferences

/**
 * Receives "Add to Home screen" requests from other apps (browser bookmarks, contacts, maps
 * directions…). The shortcut is accepted and queued; Start pins it as a tile when it resumes.
 */
class PinShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launcherApps = getSystemService(LauncherApps::class.java)
        val request = try {
            launcherApps?.getPinItemRequest(intent)
        } catch (_: Exception) {
            null
        }
        val info = request?.shortcutInfo
        if (request != null && request.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT &&
            info != null && request.isValid && request.accept()
        ) {
            val label = info.shortLabel?.toString() ?: info.longLabel?.toString() ?: info.`package`
            TilePreferences(this).queuePinnedShortcut(TilePreferences.PendingPin(info.`package`, info.id, label))
            Toast.makeText(this, "Pinned \"$label\" to Start", Toast.LENGTH_SHORT).show()
        }
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
