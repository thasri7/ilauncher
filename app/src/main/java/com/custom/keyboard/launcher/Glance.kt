package com.custom.keyboard.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Date
import kotlin.random.Random

/**
 * Windows Phone "Glance": a dim clock on black with the date, next alarm, battery and an icon
 * for every app with notifications. It drifts a little every minute to protect OLED screens.
 */
@SuppressLint("ViewConstructor")
class GlanceView(context: Context, private val nightstand: Boolean = false) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val icons = IconCache(context)
    private val prefs = TilePreferences(context)
    private val weather = WeatherRepository(context, prefs)
    private val main = Handler(Looper.getMainLooper())
    private val light = Typeface.create("sans-serif-light", Typeface.NORMAL)
    /** Glance text is a dim accent tint, like the coloured Glance option on Lumias. */
    private val tint: Int = run {
        val a = prefs.accentColorInt
        Color.rgb((Color.red(a) + 255 * 2) / 3, (Color.green(a) + 255 * 2) / 3, (Color.blue(a) + 255 * 2) / 3)
    }
    private var agenda: List<AgendaProvider.Event> = emptyList()

    private val time = TextView(context).apply {
        setTextColor(0xE6FFFFFF.toInt())
        textSize = if (nightstand) 110f else 84f
        typeface = light
        includeFontPadding = false
    }
    private val date = TextView(context).apply {
        setTextColor(0xB3FFFFFF.toInt())
        textSize = 18f
        typeface = light
    }
    private val rows = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, (10 * density).toInt(), 0, 0)
    }
    private val notifications = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, (18 * density).toInt(), 0, 0)
    }
    private val block = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(time)
        addView(date)
        addView(rows)
        addView(notifications)
    }

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            drift()
            main.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L + 50)
        }
    }

    private val onNotifications: () -> Unit = { refresh() }

    init {
        setBackgroundColor(Color.BLACK)
        addView(block, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        NotificationHub.addListener(onNotifications)
        AgendaProvider.loadAsync(context, days = 1, max = 2) { events ->
            agenda = events
            refresh()
        }
        main.post(ticker)
    }

    override fun onDetachedFromWindow() {
        NotificationHub.removeListener(onNotifications)
        main.removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    private fun row(iconRes: Int, text: String) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
        addView(ImageView(context).apply {
            setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(tint)
            alpha = 0.8f
        }, LinearLayout.LayoutParams((18 * density).toInt(), (18 * density).toInt()))
        addView(TextView(context).apply {
            this.text = text
            setTextColor(0xB3FFFFFF.toInt())
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding((10 * density).toInt(), 0, 0, 0)
        })
    }

    private fun refresh() {
        val now = Date()
        time.text = DateFormat.getTimeFormat(context).format(now)
        date.text = DateFormat.format("EEEE, d MMMM", now)
        rows.removeAllViews()
        // Next event today, next alarm, weather and battery, each on its own line.
        agenda.firstOrNull { it.end > System.currentTimeMillis() }?.let { e ->
            val at = if (e.allDay) "All day" else DateFormat.getTimeFormat(context).format(Date(e.begin))
            rows.addView(row(com.custom.keyboard.R.drawable.ic_m_calendar, "$at  ${e.title}"))
        }
        context.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.let {
            val soon = it.triggerTime - System.currentTimeMillis() < 24 * 3_600_000L
            val fmt = if (soon) DateFormat.getTimeFormat(context).format(Date(it.triggerTime)) else DateFormat.format("EEE", it.triggerTime).toString() + " " + DateFormat.getTimeFormat(context).format(Date(it.triggerTime))
            rows.addView(row(com.custom.keyboard.R.drawable.ic_m_alarm, fmt))
        }
        weather.report?.takeIf { System.currentTimeMillis() - it.fetchedAt < 6 * 3_600_000L }?.let { r ->
            rows.addView(row(WeatherCodes.icon(r.code, r.isDay), "${Math.round(r.temperature)}°  ${WeatherCodes.describe(r.code)}"))
        }
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { b ->
            val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            if (level >= 0 && scale > 0) {
                rows.addView(row(if (plugged) com.custom.keyboard.R.drawable.ic_m_bolt else com.custom.keyboard.R.drawable.ic_m_battery, "${level * 100 / scale}%" + if (plugged) " · charging" else ""))
            }
        }

        notifications.removeAllViews()
        NotificationHub.packagesByRecency().filter { it !in prefs.hiddenApps }.take(6).forEach { pkg ->
            val count = NotificationHub.get(pkg)?.count ?: 0
            notifications.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, (14 * density).toInt(), 0)
                addView(ImageView(context).apply {
                    // Desaturated and dim, like the monochrome Glance icons.
                    setImageDrawable(icons.icon(pkg).mutate().apply {
                        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                    })
                    alpha = 0.75f
                    val s = (22 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(s, s)
                })
                addView(TextView(context).apply {
                    text = count.toString()
                    setTextColor(0xB3FFFFFF.toInt())
                    textSize = 14f
                    setPadding((5 * density).toInt(), 0, 0, 0)
                })
            })
        }
    }

    /** Moves the block to a new random spot so no pixel stays lit for long. */
    private fun drift() {
        post {
            val maxX = (width - block.width).coerceAtLeast(0)
            val maxY = (height - block.height).coerceAtLeast(0)
            val x = (maxX * (0.1f + Random.nextFloat() * 0.8f))
            val y = (maxY * (0.15f + Random.nextFloat() * 0.6f))
            block.animate().translationX(x).translationY(y).setDuration(1200).start()
        }
    }
}

/** Full-screen Glance opened by the double-tap gesture; any tap goes back to Start. */
class GlanceActivity : Activity() {
    companion object {
        /** Opened by Nightstand: closes by itself when the phone is unplugged. */
        const val EXTRA_NIGHTSTAND = "nightstand"
    }

    private val unplugged = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = finish()
    }
    private var nightstand = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nightstand = intent.getBooleanExtra(EXTRA_NIGHTSTAND, false)
        if (nightstand) {
            androidx.core.content.ContextCompat.registerReceiver(
                this, unplugged, IntentFilter(Intent.ACTION_POWER_DISCONNECTED), androidx.core.content.ContextCompat.RECEIVER_EXPORTED
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 0.02f }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        val view = GlanceView(this, nightstand)
        view.setOnClickListener { finish() }
        setContentView(view)
    }

    override fun onDestroy() {
        if (nightstand) runCatching { unregisterReceiver(unplugged) }
        super.onDestroy()
    }
}

/** Glance as an Android screen saver (Settings › Display › Screen saver), e.g. while charging. */
class GlanceDreamService : DreamService() {
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = false
        setContentView(GlanceView(this))
    }
}
