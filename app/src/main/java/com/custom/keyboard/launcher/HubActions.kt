package com.custom.keyboard.launcher

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification

/**
 * What the Hub can do with an entry while its notification is still on the phone: reply
 * through the app's own reply action, mark it read in the app, snooze it, dismiss it, or open
 * it. Once Android has cleared a notification only "open the app" is left.
 */
object HubActions {

    fun live(entry: HubStore.Entry): StatusBarNotification? = NotificationHub.active[entry.key]

    private fun replyAction(sbn: StatusBarNotification): Notification.Action? =
        sbn.notification.actions?.firstOrNull { a ->
            a.remoteInputs?.any { it.allowFreeFormInput } == true &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || a.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY || a.semanticAction == Notification.Action.SEMANTIC_ACTION_NONE)
        }

    private fun readAction(sbn: StatusBarNotification): Notification.Action? =
        sbn.notification.actions?.firstOrNull { a ->
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && a.semanticAction == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ) ||
                a.title?.toString()?.contains("read", ignoreCase = true) == true
        }

    fun canReply(entry: HubStore.Entry): Boolean = live(entry)?.let { replyAction(it) } != null

    fun canMarkReadInApp(entry: HubStore.Entry): Boolean = live(entry)?.let { readAction(it) } != null

    fun canReply(sbn: StatusBarNotification): Boolean = replyAction(sbn) != null

    /** Reply to a live notification (tile menus); returns false if the app doesn't allow it. */
    fun replyTo(context: Context, sbn: StatusBarNotification, text: String): Boolean {
        val action = replyAction(sbn) ?: return false
        val inputs = action.remoteInputs ?: return false
        val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        val results = Bundle()
        inputs.forEach { results.putCharSequence(it.resultKey, text) }
        RemoteInput.addResultsToIntent(inputs, intent, results)
        return runCatching { action.actionIntent.send(context, 0, intent) }.isSuccess
    }

    /** Sends [text] through the app's reply action. Returns false if it can't be sent. */
    fun reply(context: Context, entry: HubStore.Entry, text: String): Boolean {
        val sbn = live(entry) ?: return false
        val action = replyAction(sbn) ?: return false
        val inputs = action.remoteInputs ?: return false
        val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        val results = Bundle()
        inputs.forEach { results.putCharSequence(it.resultKey, text) }
        RemoteInput.addResultsToIntent(inputs, intent, results)
        return try {
            action.actionIntent.send(context, 0, intent)
            HubStore.addMine(context, entry, text)
            true
        } catch (_: PendingIntent.CanceledException) {
            false
        }
    }

    /** Marks read in the app too when it offers that; always marks read in the Hub. */
    fun markRead(context: Context, entries: List<HubStore.Entry>) {
        entries.mapNotNull { live(it) }.distinctBy { it.key }.forEach { sbn ->
            readAction(sbn)?.let { runCatching { it.actionIntent.send() } }
        }
        HubStore.markRead(context, entries.map { it.id })
    }

    fun snooze(entry: HubStore.Entry, minutes: Long): Boolean {
        val service = NotificationHub.service ?: return false
        if (live(entry) == null) return false
        return runCatching { service.snoozeNotification(entry.key, minutes * 60_000L) }.isSuccess
    }

    fun dismiss(context: Context, entries: List<HubStore.Entry>) {
        NotificationHub.dismiss(entries.map { it.key }.distinct())
        HubStore.markRead(context, entries.map { it.id })
    }

    /** Opens the notification's own target, or the app when that's gone. */
    fun open(context: Context, entry: HubStore.Entry, openApp: (String) -> Unit) {
        val pi = live(entry)?.notification?.contentIntent
        val sent = pi != null && runCatching { pi.send() }.isSuccess
        if (!sent) openApp(entry.app)
        HubStore.markRead(context, listOf(entry.id))
    }
}
