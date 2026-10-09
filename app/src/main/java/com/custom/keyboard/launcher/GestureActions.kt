package com.custom.keyboard.launcher

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent

/** Start-screen gestures: pull down the notification shade and double-tap to lock. */
object GestureActions {

    /** Opens the notification shade; returns false if the device doesn't allow it. */
    @SuppressLint("WrongConstant")
    fun expandNotifications(context: Context): Boolean {
        // StatusBarManager.expandNotificationsPanel() is what launchers have always used; it
        // needs EXPAND_STATUS_BAR. Fall back to the accessibility service when it's blocked.
        try {
            val service = context.getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(service)
            return true
        } catch (_: Throwable) {
        }
        return LauncherGestureService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS) == true
    }

    /** Locks the screen through the accessibility service (Android 9+). */
    fun lockScreen(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return LauncherGestureService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) == true
    }

    val isServiceEnabled: Boolean get() = LauncherGestureService.instance != null
}

/**
 * Accessibility service whose only job is performing "lock screen" and "open notifications" for
 * launcher gestures. It reads no screen content.
 */
class LauncherGestureService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: LauncherGestureService? = null
            private set
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}
}
