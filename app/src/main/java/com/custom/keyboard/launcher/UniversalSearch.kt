package com.custom.keyboard.launcher

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/** One search result row. */
data class SearchResult(
    val section: String,
    val title: String,
    val subtitle: String? = null,
    val icon: Drawable? = null,
    val iconRes: Int? = null,
    /** Small buttons on the right (call, message…). */
    val actions: List<Pair<Int, () -> Unit>> = emptyList(),
    val big: Boolean = false,
    val onLongClick: ((View) -> Unit)? = null,
    val onClick: (View) -> Unit
)

/** Results grouped under section headers. */
class SearchResultsAdapter(private val ui: MetroUi) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private sealed class Row {
        class Header(val title: String) : Row()
        class Item(val r: SearchResult) : Row()
    }

    private var rows: List<Row> = emptyList()
    var first: SearchResult? = null
        private set

    fun submit(results: List<SearchResult>) {
        val out = ArrayList<Row>()
        var last: String? = null
        results.forEach { r ->
            if (r.section != last && r.section.isNotEmpty()) out.add(Row.Header(r.section))
            last = r.section
            out.add(Row.Item(r))
        }
        rows = out
        first = results.firstOrNull()
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size
    override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1

    private class Holder(v: View) : RecyclerView.ViewHolder(v)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == 0) return Holder(ui.sectionTitle("").apply {
            setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(4))
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        val row = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ui.dp(52)
            setPadding(ui.dp(16), ui.dp(6), ui.dp(8), ui.dp(6))
            background = ui.ripple()
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        row.addView(ImageView(parent.context).apply { tag = "icon" }, LinearLayout.LayoutParams(ui.dp(30), ui.dp(30)))
        row.addView(LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(14), 0, ui.dp(6), 0)
            addView(ui.text("", 15f).apply { tag = "title"; maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            addView(ui.text("", 12f, 0x99FFFFFF.toInt()).apply { tag = "sub"; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(LinearLayout(parent.context).apply { tag = "actions"; orientation = LinearLayout.HORIZONTAL })
        return Holder(row)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder.itemView as TextView).text = row.title.uppercase()
            is Row.Item -> {
                val r = row.r
                val v = holder.itemView
                val icon = v.findViewWithTag<ImageView>("icon")
                icon.imageTintList = null
                when {
                    r.icon != null -> icon.setImageDrawable(r.icon)
                    r.iconRes != null -> {
                        icon.setImageResource(r.iconRes)
                        icon.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                    }
                    else -> icon.setImageDrawable(null)
                }
                icon.visibility = if (r.icon == null && r.iconRes == null) View.GONE else View.VISIBLE
                v.findViewWithTag<TextView>("title").apply {
                    text = r.title
                    textSize = if (r.big) 26f else 15f
                    typeface = if (r.big) android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL) else android.graphics.Typeface.DEFAULT
                }
                v.findViewWithTag<TextView>("sub").apply {
                    text = r.subtitle.orEmpty()
                    visibility = if (r.subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
                }
                val actions = v.findViewWithTag<LinearLayout>("actions")
                actions.removeAllViews()
                r.actions.forEach { (res, act) ->
                    actions.addView(ImageView(v.context).apply {
                        setImageResource(res)
                        imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                        setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
                        background = ui.ripple()
                        setOnClickListener { act() }
                    }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
                }
                v.setOnClickListener { r.onClick(v) }
                v.setOnLongClickListener(r.onLongClick?.let { l -> View.OnLongClickListener { l(v); true } })
            }
        }
    }
}

/** Android settings pages you can jump to from search, with words people use for them. */
object SettingsCatalog {
    data class Page(val title: String, val words: String, val action: String, val minSdk: Int = 0)

    val pages = listOf(
        Page("Wi-Fi", "wifi wireless internet network", Settings.ACTION_WIFI_SETTINGS),
        Page("Bluetooth", "bluetooth headphones pair", Settings.ACTION_BLUETOOTH_SETTINGS),
        Page("Mobile network", "sim mobile data cellular network roaming", Settings.ACTION_NETWORK_OPERATOR_SETTINGS),
        Page("Data usage", "data usage limit", Settings.ACTION_DATA_USAGE_SETTINGS, 28),
        Page("Hotspot & tethering", "hotspot tethering share internet", Settings.ACTION_WIRELESS_SETTINGS),
        Page("Aeroplane mode", "airplane aeroplane flight mode", Settings.ACTION_AIRPLANE_MODE_SETTINGS),
        Page("NFC", "nfc tap pay", Settings.ACTION_NFC_SETTINGS),
        Page("Display", "display brightness screen timeout dark", Settings.ACTION_DISPLAY_SETTINGS),
        Page("Wallpaper", "wallpaper background", Intent.ACTION_SET_WALLPAPER),
        Page("Sound & vibration", "sound volume ringtone vibration", Settings.ACTION_SOUND_SETTINGS),
        Page("Do Not Disturb", "do not disturb dnd silent quiet", Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS),
        Page("Notifications", "notifications alerts", "android.settings.NOTIFICATION_SETTINGS"),
        Page("Battery", "battery power saver usage", Intent.ACTION_POWER_USAGE_SUMMARY),
        Page("Battery saver", "battery saver power saving", Settings.ACTION_BATTERY_SAVER_SETTINGS),
        Page("Storage", "storage space memory free up", Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
        Page("Apps", "apps applications installed manage", Settings.ACTION_APPLICATION_SETTINGS),
        Page("Default apps", "default apps browser home launcher", Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        Page("Location", "location gps", Settings.ACTION_LOCATION_SOURCE_SETTINGS),
        Page("Security", "security lock screen fingerprint face unlock password pin", Settings.ACTION_SECURITY_SETTINGS),
        Page("Fingerprint", "fingerprint biometric", Settings.ACTION_FINGERPRINT_ENROLL, 28),
        Page("Privacy", "privacy permissions", Settings.ACTION_PRIVACY_SETTINGS),
        Page("Accounts", "accounts google sync users", Settings.ACTION_SYNC_SETTINGS),
        Page("Accessibility", "accessibility font size talkback", Settings.ACTION_ACCESSIBILITY_SETTINGS),
        Page("Date & time", "date time clock timezone", Settings.ACTION_DATE_SETTINGS),
        Page("Languages & input", "language keyboard input", Settings.ACTION_LOCALE_SETTINGS),
        Page("Keyboards", "keyboard input method", Settings.ACTION_INPUT_METHOD_SETTINGS),
        Page("Screen saver", "screen saver daydream", Settings.ACTION_DREAM_SETTINGS),
        Page("Cast", "cast screen mirror tv", Settings.ACTION_CAST_SETTINGS),
        Page("VPN", "vpn private network", Settings.ACTION_VPN_SETTINGS),
        Page("Usage access", "usage access screen time", Settings.ACTION_USAGE_ACCESS_SETTINGS),
        Page("Notification access", "notification access listener", Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
        Page("Developer options", "developer options usb debugging", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        Page("About phone", "about phone device info model android version", Settings.ACTION_DEVICE_INFO_SETTINGS),
        Page("System update", "update software system", "android.settings.SYSTEM_UPDATE_SETTINGS"),
        Page("Home app", "home launcher default", Settings.ACTION_HOME_SETTINGS)
    )

    fun search(query: String, max: Int = 4): List<Page> {
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()
        return pages.filter { Build.VERSION.SDK_INT >= it.minSdk }
            .filter { it.title.lowercase().contains(q) || it.words.split(' ').any { w -> w.startsWith(q) } || it.words.contains(q) }
            .sortedBy { if (it.title.lowercase().startsWith(q)) 0 else 1 }
            .take(max)
    }
}

/** Contacts and files for search; each needs its own permission, asked for from search. */
object DeviceSearch {
    data class Contact(val name: String, val number: String, val photo: Uri?, val lookup: Uri)
    data class MediaFile(val name: String, val uri: Uri, val mime: String, val kind: String)

    fun canReadContacts(c: Context) = ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun mediaPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 33)
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    fun canReadMedia(c: Context) = mediaPermissions().any { ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED }

    fun contacts(c: Context, query: String, max: Int = 4): List<Contact> {
        if (!canReadContacts(c) || query.trim().length < 2) return emptyList()
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(query.trim()))
        val out = LinkedHashMap<String, Contact>()
        runCatching {
            c.contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI, ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY
                ), null, null, null
            )?.use { cur ->
                while (cur.moveToNext() && out.size < max) {
                    val name = cur.getString(0) ?: continue
                    if (out.containsKey(name)) continue
                    val lookup = ContactsContract.Contacts.getLookupUri(cur.getLong(3), cur.getString(4))
                    out[name] = Contact(name, cur.getString(1).orEmpty(), cur.getString(2)?.let { Uri.parse(it) }, lookup)
                }
            }
        }
        return out.values.toList()
    }

    fun files(c: Context, query: String, max: Int = 5): List<MediaFile> {
        if (!canReadMedia(c) || query.trim().length < 2) return emptyList()
        val out = ArrayList<MediaFile>()
        val q = "%" + query.trim().replace("%", "") + "%"
        val sources = listOf(
            Triple(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Photo", "image/*"),
            Triple(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Video", "video/*"),
            Triple(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "Audio", "audio/*")
        )
        sources.forEach { (base, kind, mime) ->
            runCatching {
                c.contentResolver.query(
                    base, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                    "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf(q),
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                )?.use { cur ->
                    while (cur.moveToNext() && out.size < max) {
                        out.add(MediaFile(cur.getString(1) ?: "", ContentUris.withAppendedId(base, cur.getLong(0)), mime, kind))
                    }
                }
            }
        }
        return out.take(max)
    }
}
