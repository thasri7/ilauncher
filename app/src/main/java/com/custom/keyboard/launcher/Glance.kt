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
class GlanceView(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val icons = IconCache(context)
    private val main = Handler(Looper.getMainLooper())
    private val light = Typeface.create("sans-serif-light", Typeface.NORMAL)

    private val time = TextView(context).apply {
        setTextColor(0xE6FFFFFF.toInt())
        textSize = 84f
        typeface = light
        includeFontPadding = false
    }
    private val date = TextView(context).apply {
        setTextColor(0xB3FFFFFF.toInt())
        textSize = 18f
    }
    private val detail = TextView(context).apply {
        setTextColor(0x99FFFFFF.toInt())
        textSize = 14f
        setPadding(0, (6 * density).toInt(), 0, 0)
    }
    private val notifications = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, (18 * density).toInt(), 0, 0)
    }
    private val block = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(time)
        addView(date)
        addView(detail)
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
        main.post(ticker)
    }

    override fun onDetachedFromWindow() {
        NotificationHub.removeListener(onNotifications)
        main.removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    private fun refresh() {
        val now = Date()
        time.text = DateFormat.getTimeFormat(context).format(now)
        date.text = DateFormat.format("EEEE, d MMMM", now)
        val parts = ArrayList<String>()
        context.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.let {
            parts.add("Alarm " + DateFormat.getTimeFormat(context).format(Date(it.triggerTime)))
        }
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { b ->
            val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            if (level >= 0 && scale > 0) parts.add("${level * 100 / scale}%" + if (plugged) " · charging" else "")
        }
        detail.text = parts.joinToString("   ")

        notifications.removeAllViews()
        NotificationHub.packagesByRecency().take(6).forEach { pkg ->
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 0.02f }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        val view = GlanceView(this)
        view.setOnClickListener { finish() }
        setContentView(view)
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
