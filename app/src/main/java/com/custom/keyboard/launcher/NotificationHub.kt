package com.custom.keyboard.launcher

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat

/**
 * In-memory snapshot of the notifications on the device, grouped per app. It feeds the unread
 * counts on tiles, the text live tiles peek to, the per-tile notification list and Glance.
 * Nothing leaves the device.
 */
object NotificationHub {
    /** One notification, with what is needed to open or dismiss it. */
    data class Item(
        val key: String,
        val title: String,
        val text: String,
        val postTime: Long,
        val contentIntent: PendingIntent?,
        val clearable: Boolean
    )

    data class Entry(val count: Int, val title: String, val text: String, val items: List<Item>)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()

    @Volatile
    private var entries: Map<String, Entry> = emptyMap()

    @Volatile
    var isConnected = false
        internal set

    @Volatile
    internal var service: TileNotificationListener? = null

    /** Live system notifications by key, for the Hub's reply / snooze / mark-read actions. */
    @Volatile
    var active: Map<String, StatusBarNotification> = emptyMap()
        private set

    fun get(packageName: String?): Entry? = packageName?.let { entries[it] }

    /** Packages with notifications, most recent first (for Glance). */
    fun packagesByRecency(): List<String> =
        entries.entries.sortedByDescending { e -> e.value.items.maxOfOrNull { it.postTime } ?: 0L }.map { it.key }

    fun isAccessGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** Dismisses notifications, as swiping them away in the shade would. */
    fun dismiss(keys: Collection<String>) {
        val s = service ?: return
        try {
            s.cancelNotifications(keys.toTypedArray())
        } catch (_: Exception) {
        }
    }

    private fun CharSequence?.text() = this?.toString().orEmpty()

    internal fun publish(notifications: Array<StatusBarNotification>, keyOf: (StatusBarNotification) -> String = { it.packageName }) {
        active = notifications.associateBy { it.key }
        // Cloned and work-profile apps get their own key ("package#profile"), like their tiles.
        val grouped = notifications
            .filter { it.isClearable && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .groupBy(keyOf)
        entries = grouped.mapValues { (_, list) ->
            val items = list.sortedByDescending { it.postTime }.map { sbn ->
                val extras = sbn.notification.extras
                Item(
                    key = sbn.key,
                    title = extras.getCharSequence(Notification.EXTRA_TITLE).text(),
                    text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT)).text(),
                    postTime = sbn.postTime,
                    contentIntent = sbn.notification.contentIntent,
                    clearable = sbn.isClearable
                )
            }
            val latest = items.first()
            Entry(count = items.size, title = latest.title, text = latest.text, items = items)
        }
        mainHandler.post { listeners.toList().forEach { it() } }
    }

    internal fun clear() {
        entries = emptyMap()
        active = emptyMap()
        mainHandler.post { listeners.toList().forEach { it() } }
    }
}

/** Bound by the system once the user grants notification access in Settings. */
class TileNotificationListener : NotificationListenerService() {

    override fun onListenerDisconnected() {
        NotificationHub.isConnected = false
        NotificationHub.service = null
        NotificationHub.clear()
    }

    override fun onListenerConnected() {
        NotificationHub.isConnected = true
        NotificationHub.service = this
        // Anything that arrived while we weren't listening still goes into the Hub.
        runCatching { activeNotifications?.forEach { record(it) } }
        refresh()
    }

    private fun record(sbn: StatusBarNotification) {
        runCatching { HubStore.record(this, sbn, com.custom.keyboard.AppKeys.keyFor(this, sbn.packageName, sbn.user)) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let { record(it) }
        refresh()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.let { runCatching { HubStore.onRemoved(this, it.key) } }
        refresh()
    }

    private fun refresh() {
        try {
            NotificationHub.publish(activeNotifications ?: emptyArray()) { sbn ->
                com.custom.keyboard.AppKeys.keyFor(this, sbn.packageName, sbn.user)
            }
        } catch (_: Exception) {
            // The listener can be unbound between the callback and this call.
        }
    }
}
