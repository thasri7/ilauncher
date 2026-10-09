package com.custom.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/**
 * Every emoji the phone can show, by category, with recently used ones first. Emoji the phone's
 * font can't draw are left out so there are no empty boxes. The list loads once in the
 * background and is kept for the life of the keyboard.
 */
@SuppressLint("ViewConstructor")
class EmojiPanel(context: Context, private val recents: () -> List<String>, private val onEmoji: (String) -> Unit) : LinearLayout(context) {

    private data class Section(val name: String, val icon: String, val emojis: List<String>)

    private sealed class Row {
        class Header(val title: String) : Row()
        class Emoji(val value: String) : Row()
    }

    companion object {
        private const val COLUMNS = 8
        private val ICONS = mapOf(
            "Smileys & Emotion" to "😀", "People & Body" to "👋", "Animals & Nature" to "🐻", "Food & Drink" to "🍔",
            "Activities" to "⚽", "Travel & Places" to "🚗", "Objects" to "💡", "Symbols" to "🔣", "Flags" to "🏁"
        )
        @Volatile
        private var cached: List<Section>? = null
        private val executor = Executors.newSingleThreadExecutor()
    }

    private val density = resources.displayMetrics.density
    private val rows = ArrayList<Row>()
    private val sectionStart = LinkedHashMap<String, Int>()
    private val tabs = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val list = RecyclerView(context)
    private val textColor = ContextCompat.getColor(context, R.color.kb_text_primary)
    private val dimColor = ContextCompat.getColor(context, R.color.kb_text_secondary)

    init {
        orientation = VERTICAL
        addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabs)
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (40 * density).toInt()))
        val grid = GridLayoutManager(context, COLUMNS).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (rows.getOrNull(position) is Row.Header) COLUMNS else 1
            }
        }
        list.layoutManager = grid
        list.adapter = Adapter()
        list.setHasFixedSize(true)
        addView(list, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        load()
    }

    private fun load() {
        cached?.let {
            show(it)
            return
        }
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        executor.execute {
            val paint = Paint()
            val sections = ArrayList<Section>()
            var name: String? = null
            var current = ArrayList<String>()
            runCatching {
                app.assets.open("emoji.txt").bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.startsWith("#")) {
                            name?.let { sections.add(Section(it, ICONS[it] ?: "•", current)) }
                            name = line.substring(1)
                            current = ArrayList()
                        } else {
                            val emoji = line.substringBefore('\t')
                            // Only emoji this phone's font really draws.
                            if (emoji.isNotEmpty() && paint.hasGlyph(emoji)) current.add(emoji)
                        }
                    }
                }
                name?.let { sections.add(Section(it, ICONS[it] ?: "•", current)) }
            }
            val result = sections.filter { it.emojis.isNotEmpty() }
            cached = result
            main.post { show(result) }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun show(sections: List<Section>) {
        rows.clear()
        sectionStart.clear()
        tabs.removeAllViews()
        val all = ArrayList<Section>()
        val recent = recents().take(COLUMNS * 3)
        if (recent.isNotEmpty()) all.add(Section("Recent", "🕘", recent))
        all.addAll(sections)
        all.forEach { s ->
            sectionStart[s.name] = rows.size
            rows.add(Row.Header(s.name))
            s.emojis.forEach { rows.add(Row.Emoji(it)) }
            tabs.addView(TextView(context).apply {
                text = s.icon
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                gravity = Gravity.CENTER
                contentDescription = s.name
                setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
                setOnClickListener {
                    (list.layoutManager as GridLayoutManager).scrollToPositionWithOffset(sectionStart[s.name] ?: 0, 0)
                }
            }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        list.adapter?.notifyDataSetChanged()
    }

    private inner class Holder(val view: TextView) : RecyclerView.ViewHolder(view)

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val tv = TextView(parent.context).apply {
                if (viewType == 0) {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(dimColor)
                    setPadding((10 * density).toInt(), (8 * density).toInt(), 0, (4 * density).toInt())
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                } else {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                    setTextColor(textColor)
                    gravity = Gravity.CENTER
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (46 * density).toInt())
                    background = ContextCompat.getDrawable(parent.context, R.drawable.bg_toolbar_icon)
                }
            }
            return Holder(tv)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> {
                    holder.view.text = row.title.uppercase()
                    holder.view.setOnClickListener(null)
                }
                is Row.Emoji -> {
                    holder.view.text = row.value
                    holder.view.setOnClickListener { onEmoji(row.value) }
                }
            }
        }
    }
}
