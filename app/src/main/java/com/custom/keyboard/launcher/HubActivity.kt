package com.custom.keyboard.launcher

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.custom.keyboard.AppLauncherHelper
import com.custom.keyboard.R
import java.util.Calendar
import java.util.Locale

/**
 * The Hub: every message and notification from every app in one list, newest first, like
 * BlackBerry Hub. Chats are grouped into threads you can read and reply to without opening the
 * app. Filter by kind or app, search, swipe to mark read or dismiss, hold for more.
 */
class HubActivity : AppCompatActivity() {

    private lateinit var prefs: TilePreferences
    private lateinit var ui: MetroUi
    private lateinit var icons: IconCache
    private lateinit var apps: AppLauncherHelper
    private lateinit var hubOverlay: MetroOverlay
    private val insets = Rect()

    private lateinit var list: RecyclerView
    private lateinit var adapter: Adapter
    private lateinit var chipsRow: LinearLayout
    private lateinit var countText: TextView
    private lateinit var search: EditText
    private lateinit var empty: LinearLayout

    /** "all", "unread", a kind name, or "app:<key>". */
    private var filter = "all"
    private var query = ""
    private val light = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private val onChange: () -> Unit = { refresh() }

    private sealed class Row {
        class Day(val label: String) : Row()
        class Thread(val latest: HubStore.Entry, val entries: List<HubStore.Entry>, val unread: Int) : Row()
    }

    private var rows: List<Row> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        prefs = TilePreferences(this)
        ui = MetroUi(this) { prefs.accentColorInt }
        icons = IconCache(this)
        apps = AppLauncherHelper(this)

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF0B0C0F.toInt()) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Header: title, unread count, search and more.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(18), ui.dp(6), ui.dp(6), 0)
        }
        header.addView(ui.text("hub", 30f, face = light))
        countText = ui.text("", 15f, ui.accentText, medium).apply { setPadding(ui.dp(10), ui.dp(6), 0, 0) }
        header.addView(countText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(iconButton(R.drawable.ic_m_search, "Search") { toggleSearch() })
        header.addView(iconButton(R.drawable.ic_m_more, "More") { showMenu() })
        column.addView(header)

        search = ui.input("", "Search messages").apply {
            visibility = View.GONE
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString()?.trim().orEmpty()
                    refresh()
                }
            })
        }
        column.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(4))
        })

        chipsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(8))
        }
        column.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipsRow)
        })

        val listBox = FrameLayout(this)
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@HubActivity)
            clipToPadding = false
        }
        adapter = Adapter()
        list.adapter = adapter
        listBox.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ui.dp(28), ui.dp(48), ui.dp(28), 0)
            visibility = View.GONE
        }
        listBox.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(listBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val hubOverlayHost = FrameLayout(this).apply { clipChildren = false }
        root.addView(hubOverlayHost, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        hubOverlay = MetroOverlay(hubOverlayHost) { insets }
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, wi ->
            val bars = wi.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            insets.set(bars.left, bars.top, bars.right, bars.bottom)
            column.setPadding(bars.left, bars.top, bars.right, 0)
            list.setPadding(0, 0, 0, bars.bottom + ui.dp(16))
            WindowInsetsCompat.CONSUMED
        }

        // Swipe right: read / unread. Swipe left: dismiss.
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun getSwipeDirs(rv: RecyclerView, holder: RecyclerView.ViewHolder): Int =
                if (rows.getOrNull(holder.bindingAdapterPosition) is Row.Thread) super.getSwipeDirs(rv, holder) else 0

            override fun onMove(rv: RecyclerView, a: RecyclerView.ViewHolder, b: RecyclerView.ViewHolder) = false

            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {
                val row = rows.getOrNull(holder.bindingAdapterPosition) as? Row.Thread ?: return
                if (direction == ItemTouchHelper.RIGHT) {
                    if (row.unread > 0) HubActions.markRead(this@HubActivity, row.entries)
                    else HubStore.markRead(this@HubActivity, listOf(row.latest.id), read = false)
                } else {
                    HubActions.dismiss(this@HubActivity, row.entries)
                    Toast.makeText(this@HubActivity, "Dismissed · still in the Hub", Toast.LENGTH_SHORT).show()
                }
                refresh()
            }

            override fun onChildDraw(c: Canvas, rv: RecyclerView, holder: RecyclerView.ViewHolder, dX: Float, dY: Float, state: Int, active: Boolean) {
                // Colour behind the row shows what letting go will do.
                val v = holder.itemView
                val paint = android.graphics.Paint().apply { color = if (dX > 0) prefs.accentColorInt else 0xFF8A2C2C.toInt() }
                if (dX > 0) c.drawRect(v.left.toFloat(), v.top.toFloat(), v.left + dX, v.bottom.toFloat(), paint)
                else if (dX < 0) c.drawRect(v.right + dX, v.top.toFloat(), v.right.toFloat(), v.bottom.toFloat(), paint)
                super.onChildDraw(c, rv, holder, dX, dY, state, active)
            }
        }).attachToRecyclerView(list)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    hubOverlay.handleBack() -> Unit
                    search.visibility == View.VISIBLE -> toggleSearch()
                    else -> finish()
                }
            }
        })
        intent?.getStringExtra(EXTRA_FILTER)?.let { filter = it }
    }

    override fun onStart() {
        super.onStart()
        HubStore.addListener(onChange)
        refresh()
    }

    override fun onStop() {
        super.onStop()
        HubStore.removeListener(onChange)
    }

    private fun iconButton(res: Int, description: String, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(Color.WHITE)
        contentDescription = description
        setPadding(ui.dp(11), ui.dp(11), ui.dp(11), ui.dp(11))
        background = ui.ripple()
        layoutParams = LinearLayout.LayoutParams(ui.dp(44), ui.dp(44))
        setOnClickListener { onClick() }
    }

    private fun toggleSearch() {
        val show = search.visibility != View.VISIBLE
        search.visibility = if (show) View.VISIBLE else View.GONE
        val imm = getSystemService(InputMethodManager::class.java)
        if (show) {
            search.requestFocus()
            imm?.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
        } else {
            search.setText("")
            imm?.hideSoftInputFromWindow(search.windowToken, 0)
        }
    }

    private fun appName(key: String): String = apps.entry(key)?.name ?: key.substringBefore('#').substringAfterLast('.')

    // ── Building the list ───────────────────────────────────────────────────────────────

    private fun matchesFilter(e: HubStore.Entry): Boolean = when {
        filter == "all" -> true
        filter == "unread" -> !e.read && !e.mine
        filter.startsWith("app:") -> e.app == filter.removePrefix("app:")
        else -> e.kind.name == filter
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun refresh() {
        val muted = prefs.hubMuted
        val all = HubStore.all(this).filter { it.app !in muted }
        val unread = all.count { !it.read && !it.mine }
        countText.text = if (unread > 0) "$unread new" else ""
        buildChips(all)

        val shown = all.filter { matchesFilter(it) }.filter { e ->
            query.isEmpty() || e.text.contains(query, true) || e.title.contains(query, true) ||
                e.conversation.contains(query, true) || appName(e.app).contains(query, true)
        }
        // One row per thread (a chat, or a single notification), placed at its newest message.
        val threads = shown.groupBy { it.thread }.values.map { entries ->
            val sorted = entries.sortedByDescending { it.time }
            Row.Thread(sorted.first(), sorted, sorted.count { !it.read && !it.mine })
        }.sortedByDescending { it.latest.time }
        val out = ArrayList<Row>()
        var lastDay = ""
        threads.forEach { t ->
            val day = dayLabel(t.latest.time)
            if (day != lastDay) {
                out.add(Row.Day(day))
                lastDay = day
            }
            out.add(t)
        }
        rows = out
        adapter.notifyDataSetChanged()
        showEmpty(out.isEmpty(), all.isEmpty())
    }

    private fun dayLabel(time: Long): String = when {
        DateUtils.isToday(time) -> "Today"
        DateUtils.isToday(time + 86_400_000L) -> "Yesterday"
        else -> DateFormat.format("EEEE, d MMMM", time).toString()
    }

    private fun showEmpty(show: Boolean, nothingAtAll: Boolean) {
        empty.removeAllViews()
        empty.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        val access = NotificationHub.isAccessGranted(this)
        empty.addView(ui.icon(R.drawable.ic_m_notifications, 40, 0x66FFFFFF))
        empty.addView(ui.text(
            when {
                !access -> "The Hub needs notification access"
                nothingAtAll -> "All caught up"
                else -> "Nothing here"
            }, 20f, face = light
        ).apply { setPadding(0, ui.dp(12), 0, ui.dp(6)); gravity = Gravity.CENTER })
        empty.addView(ui.caption(
            if (!access) "Allow it once and every message and notification from your apps collects here, even after you clear them."
            else if (nothingAtAll) "New messages and notifications from all your apps will show up here."
            else "Try another filter."
        ).apply { gravity = Gravity.CENTER })
        if (!access) empty.addView(ui.button("Allow notification access", filled = true) {
            runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }.apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = ui.dp(12) })
    }

    private fun buildChips(all: List<HubStore.Entry>) {
        chipsRow.removeAllViews()
        fun chip(id: String, label: String, count: Int, icon: android.graphics.drawable.Drawable? = null) {
            val selected = filter == id
            chipsRow.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.dp(12), 0, ui.dp(12), 0)
                background = GradientDrawable().apply {
                    cornerRadius = ui.dp(16).toFloat()
                    setColor(if (selected) prefs.accentColorInt else 0x1AFFFFFF)
                }
                if (icon != null) addView(ImageView(context).apply { setImageDrawable(icon) }, LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)).apply { marginEnd = ui.dp(6) })
                addView(ui.text(if (count > 0) "$label  $count" else label, 13f, face = medium))
                setOnClickListener {
                    filter = id
                    refresh()
                    list.scrollToPosition(0)
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(32)).apply { marginEnd = ui.dp(8) })
        }
        val unread = all.filter { !it.read && !it.mine }
        chip("all", "All", 0)
        chip("unread", "Unread", unread.size)
        listOf(
            HubStore.Kind.MESSAGE to "Messages", HubStore.Kind.EMAIL to "Email", HubStore.Kind.CALL to "Calls",
            HubStore.Kind.SOCIAL to "Social", HubStore.Kind.OTHER to "Other"
        ).forEach { (kind, label) ->
            if (all.any { it.kind == kind }) chip(kind.name, label, unread.count { it.kind == kind })
        }
        // One chip per app, busiest first.
        all.groupBy { it.app }.entries.sortedByDescending { it.value.size }.take(12).forEach { (app, entries) ->
            chip("app:$app", appName(app), entries.count { !it.read && !it.mine }, icons.icon(app))
        }
    }

    // ── Rows ────────────────────────────────────────────────────────────────────────────

    private inner class Holder(val view: View) : RecyclerView.ViewHolder(view)

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = if (rows[position] is Row.Day) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            if (viewType == 0) {
                return Holder(ui.sectionTitle("").apply {
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                })
            }
            val row = LinearLayout(this@HubActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10))
                background = ui.ripple(0xFF0B0C0F.toInt())
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val bar = View(this@HubActivity).apply { tag = "bar" }
            row.addView(bar, LinearLayout.LayoutParams(ui.dp(3), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = ui.dp(10) })
            row.addView(ImageView(this@HubActivity).apply { tag = "icon" }, LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)))
            val body = LinearLayout(this@HubActivity).apply { orientation = LinearLayout.VERTICAL }
            val top = LinearLayout(this@HubActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(ui.text("", 15f).apply { tag = "title"; maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(ui.text("", 12f, 0x99FFFFFF.toInt()).apply { tag = "time"; setPadding(ui.dp(8), 0, 0, 0) })
            body.addView(top)
            body.addView(ui.text("", 12f, ui.accentText).apply { tag = "where"; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            body.addView(ui.text("", 14f, 0xCCFFFFFF.toInt()).apply { tag = "text"; maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            row.addView(body, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ui.dp(12) })
            return Holder(row)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            when (val r = rows[position]) {
                is Row.Day -> (holder.view as TextView).text = r.label.uppercase(Locale.getDefault())
                is Row.Thread -> {
                    val v = holder.view
                    val e = r.latest
                    val unread = r.unread > 0
                    v.findViewWithTag<View>("bar").setBackgroundColor(if (unread) prefs.accentColorInt else Color.TRANSPARENT)
                    v.findViewWithTag<ImageView>("icon").setImageDrawable(icons.icon(e.app))
                    v.findViewWithTag<TextView>("title").apply {
                        text = (e.conversation.ifEmpty { e.title }) + if (r.entries.size > 1) "  (${r.entries.size})" else ""
                        typeface = if (unread) medium else Typeface.DEFAULT
                    }
                    v.findViewWithTag<TextView>("time").text = DateFormat.getTimeFormat(this@HubActivity).format(java.util.Date(e.time))
                    v.findViewWithTag<TextView>("where").text = listOfNotNull(
                        appName(e.app),
                        e.sender.takeIf { it.isNotEmpty() && it != e.conversation && e.conversation.isNotEmpty() }
                    ).joinToString(" · ")
                    v.findViewWithTag<TextView>("text").apply {
                        text = (if (e.mine) "You: " else "") + e.text
                        setTextColor(if (unread) Color.WHITE else 0x99FFFFFF.toInt())
                    }
                    v.setOnClickListener { showThread(r.entries) }
                    v.setOnLongClickListener {
                        showItemMenu(r)
                        true
                    }
                }
            }
        }
    }

    // ── Thread: read the chat and reply ─────────────────────────────────────────────────

    private fun showThread(entries: List<HubStore.Entry>) {
        val ordered = entries.sortedBy { it.time }
        val latest = ordered.last()
        HubActions.markRead(this, entries.filter { !it.read })
        val card = ui.card()
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(16), ui.dp(6), ui.dp(8), ui.dp(4))
            addView(ImageView(context).apply { setImageDrawable(icons.icon(latest.app)) }, LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(12), 0, 0, 0)
                addView(ui.text(latest.conversation.ifEmpty { latest.title }, 19f, face = light).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                addView(ui.text(appName(latest.app), 12f, 0x99FFFFFF.toInt()))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(iconButton(R.drawable.ic_m_forward, "Open in app") {
                hubOverlay.dismiss()
                HubActions.open(this@HubActivity, latest) { key -> openApp(key) }
            })
        })
        val bubbles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(8))
        }
        ordered.takeLast(40).forEach { e ->
            bubbles.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = ui.dp(12).toFloat()
                    setColor(if (e.mine) prefs.accentColorInt else 0x1FFFFFFF)
                }
                if (!e.mine && e.sender.isNotEmpty() && e.sender != e.conversation) addView(ui.text(e.sender, 12f, ui.accentText, medium))
                else if (!e.mine && e.conversation.isEmpty() && e.title.isNotEmpty()) addView(ui.text(e.title, 12f, ui.accentText, medium))
                addView(ui.text(e.text, 15f))
                addView(ui.text(DateUtils.getRelativeTimeSpanString(e.time, System.currentTimeMillis(), 60_000L).toString(), 11f, 0x99FFFFFF.toInt()).apply {
                    setPadding(0, ui.dp(2), 0, 0)
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ui.dp(6)
                gravity = if (e.mine) Gravity.END else Gravity.START
                if (e.mine) marginStart = ui.dp(48) else marginEnd = ui.dp(48)
            })
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(bubbles)
        }
        card.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        scroll.post {
            // Keep the sheet to most of the screen and start at the newest message.
            val max = (resources.displayMetrics.heightPixels * 0.55f).toInt()
            if (scroll.height > max) scroll.layoutParams = scroll.layoutParams.apply { height = max }
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
        // Reply, when the app allows replying from its notification.
        val replyTo = ordered.lastOrNull { !it.mine && HubActions.canReply(it) }
        if (replyTo != null) {
            val input = ui.input("", "Reply").apply {
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                isSingleLine = false
                maxLines = 4
            }
            val send = {
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    if (HubActions.reply(this, replyTo, text)) {
                        input.setText("")
                        hubOverlay.dismiss()
                        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
                        Toast.makeText(this, "Sent", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Couldn't send · open the app to reply", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.dp(12), ui.dp(4), ui.dp(6), ui.dp(8))
                addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(iconButton(R.drawable.ic_m_forward, "Send") { send() })
            })
        } else {
            card.addView(ui.caption(if (HubActions.live(latest) == null) "This message was cleared on the phone; open the app to reply." else "This app doesn't allow replies from notifications.").apply {
                setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(10))
            })
        }
        hubOverlay.show(card, MetroOverlay.Style.SHEET)
    }

    private fun openApp(key: String) {
        if (!apps.start(key, null, null)) Toast.makeText(this, "That app isn't available", Toast.LENGTH_SHORT).show()
    }

    private fun showItemMenu(row: Row.Thread) {
        val e = row.latest
        val card = ui.card()
        card.addView(ui.header(e.conversation.ifEmpty { e.title }, appName(e.app)))
        if (row.unread > 0) card.addView(ui.action(R.drawable.ic_m_check, "Mark as read") {
            hubOverlay.dismiss()
            HubActions.markRead(this, row.entries)
        }) else card.addView(ui.action(R.drawable.ic_m_notifications, "Mark as unread") {
            hubOverlay.dismiss()
            HubStore.markRead(this, listOf(e.id), read = false)
        })
        if (HubActions.live(e) != null) {
            card.addView(ui.sectionTitle("Snooze"))
            val now = Calendar.getInstance()
            val tonight = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 20); set(Calendar.MINUTE, 0) }
            val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 8); set(Calendar.MINUTE, 0) }
            val choices = listOfNotNull(
                "1 hour" to 60L,
                if (tonight.after(now)) "Tonight" to (tonight.timeInMillis - now.timeInMillis) / 60_000L else null,
                "Tomorrow" to (tomorrow.timeInMillis - now.timeInMillis) / 60_000L
            )
            card.addView(ui.chips(choices.map { it.first }, -1) { i ->
                hubOverlay.dismiss()
                val ok = HubActions.snooze(e, choices[i].second)
                Toast.makeText(this, if (ok) "Snoozed · it comes back ${choices[i].first.lowercase()}" else "Can't snooze that one", Toast.LENGTH_SHORT).show()
            })
            card.addView(ui.action(R.drawable.ic_m_close, "Dismiss notification", "Clears it from the phone; it stays in the Hub") {
                hubOverlay.dismiss()
                HubActions.dismiss(this, row.entries)
            })
        }
        card.addView(ui.action(R.drawable.ic_m_forward, "Open in ${appName(e.app)}") {
            hubOverlay.dismiss()
            HubActions.open(this, e) { key -> openApp(key) }
        })
        card.addView(ui.action(R.drawable.ic_m_delete, "Delete from the Hub") {
            hubOverlay.dismiss()
            HubStore.delete(this, row.entries.map { it.id })
        })
        card.addView(ui.action(R.drawable.ic_m_lock, "Leave ${appName(e.app)} out of the Hub", danger = true) {
            hubOverlay.dismiss()
            prefs.hubMuted = prefs.hubMuted + e.app
            refresh()
        })
        hubOverlay.show(scrollableSheet(card), MetroOverlay.Style.SHEET)
    }

    private fun showMenu() {
        val card = ui.card()
        card.addView(ui.header("Hub", "Every message and notification in one place"))
        card.addView(ui.action(R.drawable.ic_m_check, "Mark everything read") {
            hubOverlay.dismiss()
            HubStore.markAllRead(this)
        })
        val days = listOf(1, 3, 7, 30)
        card.addView(ui.sectionTitle("Keep messages for"))
        card.addView(ui.chips(listOf("1 day", "3 days", "7 days", "30 days"), days.indexOf(prefs.hubKeepDays).coerceAtLeast(0)) { i ->
            prefs.hubKeepDays = days[i]
        })
        val muted = prefs.hubMuted
        if (muted.isNotEmpty()) {
            card.addView(ui.sectionTitle("Left out"))
            muted.forEach { key ->
                card.addView(ui.actionWithIcon(icons.icon(key), appName(key), "Tap to bring back") {
                    prefs.hubMuted = prefs.hubMuted - key
                    hubOverlay.dismiss()
                    refresh()
                })
            }
        }
        card.addView(ui.action(R.drawable.ic_m_notifications, "Notification access", if (NotificationHub.isAccessGranted(this)) "On" else "Off · needed for the Hub") {
            runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        })
        card.addView(ui.action(R.drawable.ic_m_delete, "Clear the Hub", "Removes the saved history; your apps keep their messages", danger = true) {
            hubOverlay.dismiss()
            HubStore.clearAll(this)
        })
        card.addView(ui.caption("The Hub is saved only on this phone."))
        hubOverlay.show(scrollableSheet(card), MetroOverlay.Style.SHEET)
    }

    private fun scrollableSheet(card: LinearLayout): ScrollView = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        background = card.background
        elevation = card.elevation
        card.background = null
        card.elevation = 0f
        clipToOutline = true
        addView(card)
    }

    companion object {
        /** Optional filter to open with ("unread", "MESSAGE", "app:<key>"…). */
        const val EXTRA_FILTER = "filter"
    }
}
