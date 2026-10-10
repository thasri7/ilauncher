package com.custom.keyboard.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Loads app icons once, off the main thread, and hands out independent copies so two views never
 * share drawable state. Applies the selected icon pack, offers the Android 13 white "themed"
 * glyph, derives a tile colour from an icon, and caches app-shortcut icons.
 */
class IconCache(private val context: Context) {
    private val packageManager = context.packageManager
    private val loaded = ConcurrentHashMap<String, Drawable>()
    private val fromPack = ConcurrentHashMap.newKeySet<String>()
    private val colors = ConcurrentHashMap<String, Int>()
    private val noColor = ConcurrentHashMap.newKeySet<String>()
    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    @Volatile
    private var pack: IconPacks.Loaded? = null
    /** Icons the user chose per app ("pack:<pack>:<drawable>" or "file"). */
    @Volatile
    var overrides: Map<String, String> = emptyMap()
    /** Shape for adaptive icons: "system", "circle", "squircle", "rounded", "teardrop". */
    @Volatile
    var shape: String = "system"
    private val otherPacks = ConcurrentHashMap<String, IconPacks.Loaded?>()

    fun customIconFile(key: String) = java.io.File(context.filesDir, "icons/" + key.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".png")

    private fun overrideIcon(key: String): Drawable? {
        val spec = overrides[key] ?: return null
        return when {
            spec == "file" -> customIconFile(key).takeIf { it.exists() }?.let {
                android.graphics.BitmapFactory.decodeFile(it.path)?.let { b -> android.graphics.drawable.BitmapDrawable(context.resources, b) }
            }
            spec.startsWith("pack:") -> {
                val packPkg = spec.removePrefix("pack:").substringBefore(':')
                val name = spec.substringAfterLast(':')
                otherPacks.getOrPut(packPkg) { IconPacks.load(context, packPkg) }?.drawableNamed(name)
            }
            else -> null
        }
    }

    /** Draws an adaptive icon's layers inside the chosen shape. */
    private fun shaped(d: Drawable): Drawable {
        val a = d as? AdaptiveIconDrawable ?: return d
        val size = 192
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val path = android.graphics.Path()
        val s = size.toFloat()
        when (shape) {
            "circle" -> path.addCircle(s / 2, s / 2, s / 2, android.graphics.Path.Direction.CW)
            "rounded" -> path.addRoundRect(0f, 0f, s, s, s * 0.18f, s * 0.18f, android.graphics.Path.Direction.CW)
            "teardrop" -> path.addRoundRect(android.graphics.RectF(0f, 0f, s, s), floatArrayOf(s / 2, s / 2, s / 2, s / 2, s * 0.12f, s * 0.12f, s / 2, s / 2), android.graphics.Path.Direction.CW)
            else -> {
                // Squircle: a superellipse, like many phones' default.
                val n = 4.0
                for (i in 0..360) {
                    val t = Math.toRadians(i.toDouble())
                    val c = Math.cos(t)
                    val sn = Math.sin(t)
                    val x = (s / 2) * (1 + Math.signum(c) * Math.pow(Math.abs(c), 2 / n))
                    val y = (s / 2) * (1 + Math.signum(sn) * Math.pow(Math.abs(sn), 2 / n))
                    if (i == 0) path.moveTo(x.toFloat(), y.toFloat()) else path.lineTo(x.toFloat(), y.toFloat())
                }
                path.close()
            }
        }
        canvas.clipPath(path)
        // Adaptive layers are 108 units with the visible 72 in the middle.
        val pad = size / 4
        a.background?.apply { setBounds(-pad, -pad, size + pad, size + pad); draw(canvas) }
        a.foreground?.apply { setBounds(-pad, -pad, size + pad, size + pad); draw(canvas) }
        return android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
    }

    /** Switches icon pack ("" = system icons) and drops cached icons. */
    fun setIconPack(packPackage: String, onReady: () -> Unit) {
        executor.execute {
            pack = packPackage.takeIf { it.isNotEmpty() }?.let { IconPacks.load(context, it) }
            clear()
            main.post(onReady)
        }
    }

    private fun load(packageName: String): Drawable? = loaded[packageName] ?: run {
        overrideIcon(packageName)?.let { custom ->
            loaded[packageName] = custom
            fromPack.add(packageName)
            return@run custom
        }
        if (packageName == com.custom.keyboard.AppKeys.HUB) {
            return@run androidx.core.content.ContextCompat.getDrawable(context, com.custom.keyboard.R.mipmap.ic_hub)?.also { loaded[packageName] = it }
        }
        if (com.custom.keyboard.AppKeys.isClone(packageName)) {
            // A cloned / work-profile app: the system icon with its profile badge.
            val pkg = com.custom.keyboard.AppKeys.pkg(packageName)
            val user = com.custom.keyboard.AppKeys.user(context, packageName)
            val icon = try {
                context.getSystemService(android.content.pm.LauncherApps::class.java)
                    ?.getActivityList(pkg, user)?.firstOrNull()?.getBadgedIcon(0)
            } catch (_: Exception) {
                null
            }
            return@run icon?.also { loaded[packageName] = it; fromPack.remove(packageName) }
        }
        val packIcon = pack?.let { p ->
            val activity = try {
                packageManager.getLaunchIntentForPackage(packageName)?.component?.className
            } catch (_: Exception) {
                null
            }
            p.drawableFor(packageName, activity)
        }
        val icon = packIcon ?: try {
            packageManager.getApplicationIcon(packageName)
        } catch (_: Exception) {
            null
        }
        val finalIcon = if (packIcon == null && icon != null && shape != "system") runCatching { shaped(icon) }.getOrDefault(icon) else icon
        finalIcon?.also {
            loaded[packageName] = it
            if (packIcon != null) fromPack.add(packageName) else fromPack.remove(packageName)
        }
    }

    private fun copyOf(d: Drawable): Drawable = d.constantState?.newDrawable(context.resources)?.mutate() ?: d

    fun isCached(packageName: String?): Boolean = packageName != null && loaded.containsKey(packageName)

    /** Synchronous icon; loads on the calling thread when it isn't cached yet. */
    fun icon(packageName: String?): Drawable {
        val d = packageName?.let { load(it) } ?: return packageManager.defaultActivityIcon
        return copyOf(d)
    }

    /**
     * Delivers the icon (themed when asked and available) on the main thread: immediately when
     * cached, otherwise after loading it in the background.
     */
    fun iconAsync(packageName: String?, themed: Boolean, callback: (Drawable, Boolean) -> Unit) {
        if (packageName == null) {
            callback(packageManager.defaultActivityIcon, false)
            return
        }
        if (isCached(packageName)) {
            val t = if (themed) themedIcon(packageName) else null
            callback(t ?: icon(packageName), t != null)
            return
        }
        executor.execute {
            load(packageName)
            main.post {
                val t = if (themed) themedIcon(packageName) else null
                callback(t ?: icon(packageName), t != null)
            }
        }
    }

    /** Warms the cache for these packages in the background. */
    fun prefetch(packages: Collection<String>) {
        val todo = packages.filter { !loaded.containsKey(it) }
        if (todo.isEmpty()) return
        executor.execute { todo.forEach { load(it) } }
    }

    /**
     * The adaptive icon's monochrome layer tinted white, or null when the app doesn't ship one
     * (or an icon pack supplies the icon, which wins).
     */
    fun themedIcon(packageName: String?): Drawable? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || packageName == null) return null
        val base = load(packageName) ?: return null
        if (packageName in fromPack || com.custom.keyboard.AppKeys.isClone(packageName)) return null
        // With an icon shape the cached icon is a picture; the themed glyph comes from the original.
        val adaptive = base as? AdaptiveIconDrawable
            ?: runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull() as? AdaptiveIconDrawable
            ?: return null
        val mono = (copyOf(adaptive) as? AdaptiveIconDrawable)?.monochrome ?: return null
        return mono.mutate().apply { setTint(Color.WHITE) }
    }

    /** A saturated, white-text-friendly colour sampled from the app icon, or null for grey icons. */
    fun tileColor(packageName: String?): Int? {
        if (packageName == null || packageName in noColor) return null
        colors[packageName]?.let { return it }
        val color = load(packageName)?.let { sampleColor(copyOf(it)) }
        if (color == null) noColor.add(packageName) else colors[packageName] = color
        return color
    }

    fun clear() {
        loaded.clear()
        fromPack.clear()
        colors.clear()
        noColor.clear()
    }

    private fun sampleColor(d: Drawable): Int? {
        val size = 32
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(bitmap))

        // Hue histogram weighted by saturation × brightness, so white and black pixels don't vote.
        val buckets = 24
        val weight = FloatArray(buckets)
        val sumS = FloatArray(buckets)
        val sumV = FloatArray(buckets)
        val sumH = FloatArray(buckets)
        val hsv = FloatArray(3)
        for (y in 0 until size) for (x in 0 until size) {
            val px = bitmap.getPixel(x, y)
            if (Color.alpha(px) < 128) continue
            Color.colorToHSV(px, hsv)
            val w = hsv[1] * hsv[2]
            if (w < 0.12f) continue
            val b = ((hsv[0] / 360f) * buckets).toInt().coerceIn(0, buckets - 1)
            weight[b] += w
            sumH[b] += hsv[0] * w
            sumS[b] += hsv[1] * w
            sumV[b] += hsv[2] * w
        }
        bitmap.recycle()

        val best = weight.indices.maxBy { weight[it] }
        if (weight[best] < 8f) return null
        val w = weight[best]
        return Color.HSVToColor(
            floatArrayOf(
                sumH[best] / w,
                (sumS[best] / w).coerceIn(0.45f, 0.9f),
                (sumV[best] / w).coerceIn(0.42f, 0.7f)
            )
        )
    }
}
