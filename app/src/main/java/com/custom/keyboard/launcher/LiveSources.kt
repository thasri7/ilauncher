package com.custom.keyboard.launcher

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Real data for the info tiles. Everything is read on demand while Start is on screen and
 * cached for a few minutes, so tiles cost nothing while the phone is in your pocket.
 */
object UsageReader {
    data class ScreenTime(val totalMs: Long, val top: List<Pair<String, Long>>, val unlocks: Int, val readAt: Long)
    data class DataUse(val mobileMonth: Long, val mobileToday: Long, val wifiMonth: Long, val wifiToday: Long, val readAt: Long)

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private const val MAX_AGE_MS = 3 * 60_000L

    @Volatile var screenTime: ScreenTime? = null
        private set
    @Volatile var dataUse: DataUse? = null
        private set
    private var loadingScreen = false
    private var loadingData = false

    fun hasAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun startOfDay(now: Long = System.currentTimeMillis()): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun startOfMonth(): Long = Calendar.getInstance().apply {
        timeInMillis = startOfDay()
        set(Calendar.DAY_OF_MONTH, 1)
    }.timeInMillis

    /** Refreshes screen time in the background if it is old; [onDone] runs on the main thread. */
    fun refreshScreenTime(context: Context, homePackages: Set<String>, onDone: () -> Unit) {
        val cached = screenTime
        if (loadingScreen || !hasAccess(context)) return
        if (cached != null && System.currentTimeMillis() - cached.readAt < MAX_AGE_MS && startOfDay(cached.readAt) == startOfDay()) return
        loadingScreen = true
        val app = context.applicationContext
        executor.execute {
            val result = runCatching { readScreenTime(app, homePackages) }.getOrNull()
            main.post {
                loadingScreen = false
                if (result != null) {
                    screenTime = result
                    onDone()
                }
            }
        }
    }

    /**
     * Foreground time per app since midnight, from the system's activity events (the same
     * source Digital Wellbeing uses). Home screens are left out, like Android does.
     */
    private fun readScreenTime(context: Context, homePackages: Set<String>): ScreenTime {
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val start = startOfDay(now)
        val events = usm.queryEvents(start, now)
        val resumedAt = HashMap<String, Long>()
        val totals = HashMap<String, Long>()
        var unlocks = 0
        val e = UsageEvents.Event()
        @Suppress("DEPRECATION")
        val resumed = UsageEvents.Event.MOVE_TO_FOREGROUND
        @Suppress("DEPRECATION")
        val paused = UsageEvents.Event.MOVE_TO_BACKGROUND
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            val key = pkg + "/" + e.className
            when (e.eventType) {
                resumed -> resumedAt[key] = e.timeStamp
                paused -> {
                    // An activity already open at midnight only reports its pause.
                    val from = resumedAt.remove(key) ?: if (totals[pkg] == null) start else null
                    if (from != null) totals[pkg] = (totals[pkg] ?: 0L) + (e.timeStamp - from)
                }
                else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && e.eventType == UsageEvents.Event.KEYGUARD_HIDDEN) unlocks++
            }
        }
        // Whatever is still in front counts up to now (only the most recent one).
        resumedAt.maxByOrNull { it.value }?.let { (key, from) ->
            val pkg = key.substringBefore('/')
            totals[pkg] = (totals[pkg] ?: 0L) + (now - from)
        }
        val counted = totals.filterKeys { it !in homePackages && it != context.packageName }
        val top = counted.entries.sortedByDescending { it.value }.take(5).map { it.key to it.value }
        return ScreenTime(counted.values.sum(), top, unlocks, now)
    }

    fun refreshData(context: Context, onDone: () -> Unit) {
        val cached = dataUse
        if (loadingData || !hasAccess(context)) return
        if (cached != null && System.currentTimeMillis() - cached.readAt < MAX_AGE_MS) return
        loadingData = true
        val app = context.applicationContext
        executor.execute {
            val result = runCatching { readData(app) }.getOrNull()
            main.post {
                loadingData = false
                if (result != null) {
                    dataUse = result
                    onDone()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun readData(context: Context): DataUse {
        val nsm = context.getSystemService(NetworkStatsManager::class.java)
        val now = System.currentTimeMillis()
        fun bytes(type: Int, from: Long): Long = try {
            // A null subscriber id asks for all SIMs; it is allowed from Android 10 on.
            @Suppress("DEPRECATION")
            nsm.querySummaryForDevice(type, null, from, now).let { it.rxBytes + it.txBytes }
        } catch (_: Exception) {
            -1L
        }
        @Suppress("DEPRECATION")
        val mobile = ConnectivityManager.TYPE_MOBILE
        @Suppress("DEPRECATION")
        val wifi = ConnectivityManager.TYPE_WIFI
        return DataUse(
            mobileMonth = bytes(mobile, startOfMonth()),
            mobileToday = bytes(mobile, startOfDay(now)),
            wifiMonth = bytes(wifi, startOfMonth()),
            wifiToday = bytes(wifi, startOfDay(now)),
            readAt = now
        )
    }

    fun formatDuration(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes < 1 -> "0 min"
            minutes < 60 -> "$minutes min"
            else -> "${minutes / 60} h ${minutes % 60} min"
        }
    }

    fun formatBytes(bytes: Long): String = when {
        bytes < 0 -> "—"
        bytes < 1_000_000 -> "${bytes / 1000} KB"
        bytes < 1_000_000_000 -> String.format(Locale.getDefault(), "%.0f MB", bytes / 1e6)
        else -> String.format(Locale.getDefault(), "%.1f GB", bytes / 1e9)
    }
}

/**
 * Today's steps from the hardware step counter. The sensor counts since the phone started, so
 * the count where today began is remembered (see [StepMath]). The listener is batched by the
 * sensor hub and registered only while Start is visible.
 */
class StepCounter(private val context: Context, private val prefs: TilePreferences, private val onChange: () -> Unit) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private var listening = false

    val available: Boolean get() = sensor != null

    /** Steps today, or null before the first reading. */
    var today: Int? = null
        private set

    fun start() {
        val s = sensor ?: return
        if (listening) return
        listening = runCatching { sensors?.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL, 10_000_000) == true }.getOrDefault(false)
    }

    fun stop() {
        if (!listening) return
        sensors?.unregisterListener(this)
        listening = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val value = event.values.firstOrNull()?.toInt() ?: return
        val state = StepMath.update(StepMath.State(prefs.stepsDay, prefs.stepsBaseline, prefs.stepsLast), StepMath.dayKey(), value)
        prefs.stepsDay = state.day
        prefs.stepsBaseline = state.baseline
        prefs.stepsLast = state.last
        val steps = state.last - state.baseline
        if (steps != today) {
            today = steps
            onChange()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

/** Pure step bookkeeping, unit-tested. */
object StepMath {
    data class State(val day: String, val baseline: Int, val last: Int)

    fun dayKey(now: Long = System.currentTimeMillis()): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))

    fun update(old: State, day: String, value: Int): State = when {
        // Very first reading: count from now.
        old.last < 0 -> State(day, value, value)
        // A new day: steps since the last reading (late yesterday) count for today, unless the
        // phone restarted, in which case the counter began again at zero.
        old.day != day -> State(day, if (value >= old.last) old.last else 0, value)
        // Restarted today: keep what was walked before the restart.
        value < old.last -> State(day, old.baseline - old.last, value)
        else -> State(day, old.baseline, value)
    }
}

/** Countdown tile maths, unit-tested. */
object Countdown {
    /** Whole days from [fromDay] to [target] (both at midnight); negative once it has passed. */
    fun daysBetween(fromDay: Calendar, target: Calendar): Int {
        val a = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(fromDay.get(Calendar.YEAR), fromDay.get(Calendar.MONTH), fromDay.get(Calendar.DAY_OF_MONTH)) }
        val b = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(target.get(Calendar.YEAR), target.get(Calendar.MONTH), target.get(Calendar.DAY_OF_MONTH)) }
        return ((b.timeInMillis - a.timeInMillis) / 86_400_000L).toInt()
    }

    /** For yearly events (birthdays), the next time the date comes round, today included. */
    fun nextYearly(today: Calendar, month: Int, day: Int): Calendar {
        val next = Calendar.getInstance().apply {
            clear()
            set(today.get(Calendar.YEAR), month, 1)
            set(Calendar.DAY_OF_MONTH, minOf(day, getActualMaximum(Calendar.DAY_OF_MONTH)))
        }
        if (daysBetween(today, next) < 0) {
            next.set(Calendar.DAY_OF_MONTH, 1)
            next.add(Calendar.YEAR, 1)
            next.set(Calendar.DAY_OF_MONTH, minOf(day, next.getActualMaximum(Calendar.DAY_OF_MONTH)))
        }
        return next
    }

    fun describe(days: Int): String = when {
        days == 0 -> "Today"
        days == 1 -> "Tomorrow"
        days == -1 -> "Yesterday"
        days > 0 -> "in $days days"
        else -> "${abs(days)} days ago"
    }

    /** Parses "yyyy-MM-dd". */
    fun parse(value: String?): Calendar? {
        val parts = value?.split("-")?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 3 } ?: return null
        return Calendar.getInstance().apply { clear(); set(parts[0], parts[1] - 1, parts[2]) }
    }

    fun format(c: Calendar): String = String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
}

/** World clock helpers. */
object WorldClocks {
    data class City(val zoneId: String, val name: String, val region: String)

    /** Every time zone as a city name ("Asia/Kolkata" → Kolkata, Asia), for the picker. */
    fun cities(): List<City> = TimeZone.getAvailableIDs()
        .filter { it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/") && it.first().isUpperCase() }
        .map { id -> City(id, id.substringAfterLast('/').replace('_', ' '), id.substringBefore('/').replace('_', ' ')) }
        .distinctBy { it.name + it.region }
        .sortedBy { it.name }

    /** "+5:30 h", "−3 h", "Same time". */
    fun offsetLabel(zoneId: String, now: Long = System.currentTimeMillis()): String {
        val diff = TimeZone.getTimeZone(zoneId).getOffset(now) - TimeZone.getDefault().getOffset(now)
        if (diff == 0) return "Same time"
        val sign = if (diff > 0) "+" else "−"
        val minutes = abs(diff) / 60_000
        return if (minutes % 60 == 0) "$sign${minutes / 60} h" else "$sign${minutes / 60}:${String.format(Locale.US, "%02d", minutes % 60)} h"
    }

    /** "Tomorrow" / "Yesterday" / "Today" relative to here. */
    fun dayLabel(zoneId: String, now: Long = System.currentTimeMillis()): String {
        val here = Calendar.getInstance().apply { timeInMillis = now }
        val there = Calendar.getInstance(TimeZone.getTimeZone(zoneId)).apply { timeInMillis = now }
        val d = (there.get(Calendar.YEAR) * 400 + there.get(Calendar.DAY_OF_YEAR)) - (here.get(Calendar.YEAR) * 400 + here.get(Calendar.DAY_OF_YEAR))
        return when {
            d > 0 -> "Tomorrow"
            d < 0 -> "Yesterday"
            else -> "Today"
        }
    }
}
