package com.custom.keyboard.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Icon packs in the ADW / Nova format, which almost every pack on the Play Store ships: the pack
 * declares a theme intent filter and maps launcher components to drawables in `appfilter.xml`.
 */
object IconPacks {
    data class Pack(val packageName: String, val label: String)

    private val THEME_ACTIONS = listOf(
        "org.adw.launcher.THEMES",
        "com.novalauncher.THEME",
        "org.adw.ActivityStarter.THEMES",
        "com.gau.go.launcherex.theme"
    )

    fun installed(context: Context): List<Pack> {
        val pm = context.packageManager
        val seen = LinkedHashMap<String, Pack>()
        for (action in THEME_ACTIONS) {
            val matches = try {
                pm.queryIntentActivities(Intent(action), PackageManager.GET_META_DATA)
            } catch (_: Exception) {
                emptyList()
            }
            for (ri in matches) {
                val pkg = ri.activityInfo.packageName
                if (pkg !in seen) seen[pkg] = Pack(pkg, ri.loadLabel(pm).toString())
            }
        }
        return seen.values.sortedBy { it.label.lowercase() }
    }

    /** A loaded pack: resolves an app's launcher component to the pack's drawable. */
    class Loaded(private val resources: Resources, private val packPackage: String, private val byComponent: Map<String, String>) {
        private val byPackage: Map<String, String> = byComponent.entries
            .groupBy({ it.key.substringBefore('/') }, { it.value })
            .mapValues { it.value.first() }

        /** Every icon the pack maps, for picking one by hand. */
        fun drawableNames(): List<String> = byComponent.values.distinct().sorted()

        @SuppressLint("DiscouragedApi")
        fun drawableNamed(name: String): Drawable? {
            val id = resources.getIdentifier(name, "drawable", packPackage)
            if (id == 0) return null
            return runCatching { resources.getDrawable(id, null) }.getOrNull()
        }

        // Icon packs name their drawables in appfilter.xml, so they can only be looked up by name.
        @SuppressLint("DiscouragedApi")
        fun drawableFor(packageName: String, activityClass: String?): Drawable? {
            val name = activityClass?.let { byComponent["$packageName/$it"] } ?: byPackage[packageName] ?: return null
            val id = resources.getIdentifier(name, "drawable", packPackage)
            if (id == 0) return null
            return try {
                resources.getDrawable(id, null)
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Parses the pack's appfilter (res/xml first, then assets). Returns null if it isn't usable. */
    @SuppressLint("DiscouragedApi")
    fun load(context: Context, packPackage: String): Loaded? {
        val res = try {
            context.packageManager.getResourcesForApplication(packPackage)
        } catch (_: Exception) {
            return null
        }
        val map = HashMap<String, String>()
        val parser: XmlPullParser? = res.getIdentifier("appfilter", "xml", packPackage).takeIf { it != 0 }?.let { res.getXml(it) }
            ?: try {
                XmlPullParserFactory.newInstance().newPullParser().apply {
                    setInput(res.assets.open("appfilter.xml"), "UTF-8")
                }
            } catch (_: Exception) {
                null
            }
        parser ?: return null
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "item") {
                    val component = parser.getAttributeValue(null, "component")
                    val drawable = parser.getAttributeValue(null, "drawable")
                    // component="ComponentInfo{com.example/com.example.MainActivity}"
                    val flat = component?.substringAfter('{', "")?.substringBefore('}')
                    if (!flat.isNullOrEmpty() && !drawable.isNullOrEmpty() && '/' in flat) {
                        val pkg = flat.substringBefore('/')
                        val cls = flat.substringAfter('/').let { if (it.startsWith(".")) pkg + it else it }
                        map["$pkg/$cls"] = drawable
                    }
                }
                event = parser.next()
            }
        } catch (_: Exception) {
            if (map.isEmpty()) return null
        }
        return if (map.isEmpty()) null else Loaded(res, packPackage, map)
    }
}
