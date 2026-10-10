package com.custom.keyboard.launcher

import android.app.Notification
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * The Hub's memory (like BlackBerry Hub): every message and notification that arrives is kept
 * here, so they stay in the Hub after Android clears them. Chat apps that post a conversation
 * (MessagingStyle) are split into one entry per message, so threads can be read in order.
 * Saved in the app's private storage only, and trimmed to the days the user chose.
 */
object HubStore {

    /** What kind of item it is, for the Hub's filters. */
    enum class Kind { MESSAGE, EMAIL, CALL, SOCIAL, OTHER }

    data class Entry(
        val id: String,
        /** System notification key, for reply / snooze / dismiss while it is still live. */
        val key: String,
        /** App key ("package" or "package#profile" for clones). */
        val app: String,
        val title: String,
        val text: String,
        /** Conversation name for chats (group or person), empty otherwise. */
        val conversation: String,
        val sender: String,
        val time: Long,
        val kind: Kind,
        val read: Boolean,
        /** True for replies sent from the Hub. */
        val mine: Boolean = false
    ) {
        /** Entries of one chat share this; other notifications are their own thread. */
        val thread: String get() = if (conversation.isNotEmpty()) "$app|$conversation" else "$app|$key"
    }

    private const val FILE = "hub.json"
    private const val MAX_ENTRIES = 1500
    private val io = Executors.newSingleThreadExecutor()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val listeners = LinkedHashSet<() -> Unit>()
    private val lock = Any()
    private var entries: MutableList<Entry>? = null
    private var appContext: Context? = null

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    private fun changed() {
        main.post { listeners.toList().forEach { it() } }
        save()
    }

    private fun list(context: Context): MutableList<Entry> = synchronized(lock) {
        appContext = context.applicationContext
        entries ?: load(context).also { entries = it }
    }

    /** Newest first. */
    fun all(context: Context): List<Entry> = synchronized(lock) { list(context).sortedByDescending { it.time } }

    fun unreadCount(context: Context): Int = synchronized(lock) { list(context).count { !it.read && !it.mine } }

    // ── Capture ─────────────────────────────────────────────────────────────────────────

    /** Called by the notification listener for every notification posted. */
    fun record(context: Context, sbn: StatusBarNotification, appKey: String) {
        val n = sbn.notification ?: return
        if (sbn.packageName == context.packageName) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        // Ongoing things (music, navigation, downloads) aren't messages.
        if (!sbn.isClearable || n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty()
        val kind = kindOf(n.category, extras.containsKey(Notification.EXTRA_MESSAGES), AppCategories.guess(appKey, title))
        val added = ArrayList<Entry>()
        val messages = messagesOf(extras)
        if (messages.isNotEmpty()) {
            // A chat: one entry per message, named by the conversation (or the person for 1:1).
            val convo = conversation.ifEmpty { title }
            messages.forEach { m ->
                added.add(Entry(
                    id = "${sbn.key}|${m.time}|${m.text.hashCode()}",
                    key = sbn.key, app = appKey, title = m.sender.ifEmpty { title }, text = m.text,
                    conversation = convo, sender = m.sender, time = m.time.takeIf { it > 0 } ?: sbn.postTime,
                    kind = if (kind == Kind.OTHER) Kind.MESSAGE else kind, read = false
                ))
            }
        } else {
            added.add(Entry(
                id = sbn.key, key = sbn.key, app = appKey, title = title, text = text,
                conversation = conversation, sender = "", time = sbn.postTime, kind = kind, read = false
            ))
        }
        synchronized(lock) {
            val all = list(context)
            var changedAny = false
            added.forEach { e ->
                val i = all.indexOfFirst { it.id == e.id }
                if (i >= 0) {
                    // The same notification updated in place: keep its read state.
                    val old = all[i]
                    if (old.text != e.text || old.title != e.title) {
                        all[i] = e.copy(read = old.read && old.text == e.text)
                        changedAny = true
                    }
                } else {
                    all.add(e)
                    changedAny = true
                }
            }
            if (!changedAny) return
            trim(all, context)
        }
        changed()
    }

    private data class Message(val sender: String, val text: String, val time: Long)

    private fun messagesOf(extras: Bundle): List<Message> {
        val raw = runCatching {
            @Suppress("DEPRECATION")
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        }.getOrNull() ?: return emptyList()
        return raw.mapNotNull { p: Parcelable? ->
            val b = p as? Bundle ?: return@mapNotNull null
            val text = b.getCharSequence("text")?.toString() ?: return@mapNotNull null
            val sender = b.getCharSequence("sender")?.toString()
                ?: runCatching {
                    @Suppress("DEPRECATION")
                    (b.getParcelable<android.app.Person>("sender_person"))?.name?.toString()
                }.getOrNull().orEmpty()
            Message(sender, text, b.getLong("time"))
        }.takeLast(25)
    }

    /** Pure, so it is unit-tested. */
    fun kindOf(category: String?, isChat: Boolean, appKind: String?): Kind = when {
        category == Notification.CATEGORY_EMAIL -> Kind.EMAIL
        category == Notification.CATEGORY_CALL || category == Notification.CATEGORY_MISSED_CALL -> Kind.CALL
        category == Notification.CATEGORY_MESSAGE || isChat -> Kind.MESSAGE
        category == Notification.CATEGORY_SOCIAL -> Kind.SOCIAL
        appKind == AppCategories.SOCIAL -> Kind.MESSAGE
        appKind == AppCategories.WORK -> Kind.EMAIL
        else -> Kind.OTHER
    }

    /** The system cleared a notification (opened, swiped, answered elsewhere): it counts as read. */
    fun onRemoved(context: Context, key: String) {
        synchronized(lock) {
            val all = list(context)
            var any = false
            all.forEachIndexed { i, e -> if (e.key == key && !e.read) { all[i] = e.copy(read = true); any = true } }
            if (!any) return
        }
        changed()
    }

    // ── Changes from the Hub ────────────────────────────────────────────────────────────

    fun markRead(context: Context, ids: Collection<String>, read: Boolean = true) {
        synchronized(lock) {
            val all = list(context)
            all.forEachIndexed { i, e -> if (e.id in ids) all[i] = e.copy(read = read) }
        }
        changed()
    }

    fun markAllRead(context: Context) {
        synchronized(lock) {
            val all = list(context)
            all.forEachIndexed { i, e -> if (!e.read) all[i] = e.copy(read = true) }
        }
        changed()
    }

    fun delete(context: Context, ids: Collection<String>) {
        synchronized(lock) { list(context).removeAll { it.id in ids } }
        changed()
    }

    fun clearAll(context: Context) {
        synchronized(lock) { list(context).clear() }
        changed()
    }

    /** A reply sent from the Hub, shown in the thread. */
    fun addMine(context: Context, of: Entry, text: String) {
        synchronized(lock) {
            list(context).add(of.copy(id = "me|${System.currentTimeMillis()}", title = "You", sender = "You", text = text,
                time = System.currentTimeMillis(), read = true, mine = true))
        }
        changed()
    }

    // ── Storage ─────────────────────────────────────────────────────────────────────────

    private fun trim(all: MutableList<Entry>, context: Context) {
        val days = TilePreferences(context).hubKeepDays.coerceIn(1, 90)
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        all.removeAll { it.time < cutoff }
        if (all.size > MAX_ENTRIES) {
            all.sortByDescending { it.time }
            while (all.size > MAX_ENTRIES) all.removeAt(all.size - 1)
        }
    }

    private val saveRunnable = Runnable {
        val ctx = appContext ?: return@Runnable
        val snapshot = synchronized(lock) { entries?.toList() } ?: return@Runnable
        io.execute {
            val arr = JSONArray()
            snapshot.forEach { e ->
                arr.put(JSONObject()
                    .put("id", e.id).put("key", e.key).put("app", e.app).put("title", e.title).put("text", e.text)
                    .put("c", e.conversation).put("s", e.sender).put("t", e.time).put("k", e.kind.name)
                    .put("r", e.read).put("m", e.mine))
            }
            runCatching {
                val f = File(ctx.filesDir, FILE)
                val tmp = File(ctx.filesDir, "$FILE.tmp")
                tmp.writeText(arr.toString())
                tmp.renameTo(f)
            }
        }
    }

    /** Saves a moment after the last change, not on every single notification. */
    private fun save() {
        main.removeCallbacks(saveRunnable)
        main.postDelayed(saveRunnable, 1500)
    }

    private fun load(context: Context): MutableList<Entry> {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return ArrayList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapTo(ArrayList()) { i ->
                val o = arr.getJSONObject(i)
                Entry(
                    o.getString("id"), o.optString("key"), o.optString("app"), o.optString("title"), o.optString("text"),
                    o.optString("c"), o.optString("s"), o.optLong("t"),
                    runCatching { Kind.valueOf(o.optString("k")) }.getOrDefault(Kind.OTHER),
                    o.optBoolean("r"), o.optBoolean("m")
                )
            }
        }.getOrDefault(ArrayList())
    }
}
