package com.custom.keyboard.launcher

import android.app.Notification
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat

/**
 * In-memory snapshot of the notifications on the device, grouped per app. It feeds the unread
 * counts on tiles and the text that live app tiles peek to. Nothing leaves the device.
 */
object NotificationHub {
    data class Entry(val count: Int, val title: String, val text: String)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()

    @Volatile
    private var entries: Map<String, Entry> = emptyMap()

    @Volatile
    var isConnected = false
        internal set

    fun get(packageName: String?): Entry? = packageName?.let { entries[it] }

    fun isAccessGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    internal fun publish(notifications: Array<StatusBarNotification>) {
        val grouped = notifications
            .filter { it.isClearable && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .groupBy { it.packageName }
        entries = grouped.mapValues { (_, list) ->
            val latest = list.maxBy { it.postTime }.notification.extras
            Entry(
                count = list.size,
                title = latest.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                text = (latest.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: latest.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
            )
        }
        mainHandler.post { listeners.toList().forEach { it() } }
    }

    internal fun clear() {
        entries = emptyMap()
        mainHandler.post { listeners.toList().forEach { it() } }
    }
}

/** Bound by the system once the user grants notification access in Settings. */
class TileNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        NotificationHub.isConnected = true
        refresh()
    }

    override fun onListenerDisconnected() {
        NotificationHub.isConnected = false
        NotificationHub.clear()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()

    private fun refresh() {
        try {
            NotificationHub.publish(activeNotifications ?: emptyArray())
        } catch (_: Exception) {
            // The listener can be unbound between the callback and this call.
        }
    }
}
