package com.custom.keyboard.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build

/**
 * Loads app icons once and hands out independent copies, so two views never share drawable
 * state. Also derives a tile colour from an icon and, on Android 13+, the white "themed" glyph
 * that makes tiles look like real Windows 10 Mobile tiles.
 */
class IconCache(private val context: Context) {
    private val packageManager = context.packageManager
    private val loaded = HashMap<String, Drawable>()
    private val colors = HashMap<String, Int?>()

    private fun load(packageName: String): Drawable? = loaded[packageName] ?: try {
        packageManager.getApplicationIcon(packageName).also { loaded[packageName] = it }
    } catch (_: Exception) {
        null
    }

    private fun copyOf(d: Drawable): Drawable = d.constantState?.newDrawable(context.resources)?.mutate() ?: d

    fun icon(packageName: String?): Drawable {
        val d = packageName?.let { load(it) } ?: return packageManager.defaultActivityIcon
        return copyOf(d)
    }

    /** The adaptive icon's monochrome layer tinted white, or null when the app doesn't ship one. */
    fun themedIcon(packageName: String?): Drawable? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val adaptive = packageName?.let { load(it) } as? AdaptiveIconDrawable ?: return null
        val mono = (copyOf(adaptive) as? AdaptiveIconDrawable)?.monochrome ?: return null
        return mono.mutate().apply { setTint(Color.WHITE) }
    }

    /** A saturated, white-text-friendly colour sampled from the app icon, or null for grey icons. */
    fun tileColor(packageName: String?): Int? {
        if (packageName == null) return null
        if (colors.containsKey(packageName)) return colors[packageName]
        val color = load(packageName)?.let { sampleColor(copyOf(it)) }
        colors[packageName] = color
        return color
    }

    fun clear() {
        loaded.clear()
        colors.clear()
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
