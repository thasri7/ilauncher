package com.custom.keyboard.launcher

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/** Upcoming calendar events for the Calendar tile. Needs READ_CALENDAR, asked for on demand. */
object AgendaProvider {
    data class Event(val title: String, val begin: Long, val end: Long, val allDay: Boolean, val location: String)

    private val executor = Executors.newSingleThreadExecutor()

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Next [max] events in the coming [days], loaded in the background. */
    fun loadAsync(context: Context, days: Int = 7, max: Int = 5, callback: (List<Event>) -> Unit) {
        if (!hasPermission(context)) {
            callback(emptyList())
            return
        }
        val app = context.applicationContext
        executor.execute {
            val events = query(app, days, max)
            Handler(Looper.getMainLooper()).post { callback(events) }
        }
    }

    private fun query(context: Context, days: Int, max: Int): List<Event> {
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now - 12 * 3_600_000L)
            ContentUris.appendId(it, now + days * 86_400_000L)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION
        )
        val out = ArrayList<Event>()
        try {
            context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext() && out.size < max) {
                    val end = c.getLong(2)
                    if (end < now) continue
                    out.add(Event(c.getString(0).orEmpty().ifEmpty { "(No title)" }, c.getLong(1), end, c.getInt(3) == 1, c.getString(4).orEmpty()))
                }
            }
        } catch (_: Exception) {
        }
        return out
    }
}
