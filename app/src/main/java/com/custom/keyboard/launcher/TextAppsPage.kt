package com.custom.keyboard.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.custom.keyboard.AppLauncherHelper
import java.util.Locale

/**
 * The Text page (AP15 style): every app as its name only, flowing line after line. Names are
 * sized like tiles: the apps you use most are biggest and unused ones shrink over time. A
 * letter strip on the right lights up the names that start with the letter under your finger.
 */
@SuppressLint("ViewConstructor")
class TextAppsPage(
    context: Context,
    private val prefs: TilePreferences,
    private val onOpen: (AppLauncherHelper.AppEntry, View) -> Unit,
    private val onMenu: (AppLauncherHelper.AppEntry, View) -> Unit
) : FrameLayout(context) {

    /**
     * One name: [label] is the name shown (the user may rename it here), [badge] the number of
     * notifications, and [hidden] names only appear when they match a search.
     */
    data class Item(
        val app: AppLauncherHelper.AppEntry,
        val level: Int,
        val color: Int,
        val label: String = app.name,
        val badge: Int = 0,
        val hidden: Boolean = false
    )

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val flow = FlowLayout(context)
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        isFillViewport = true
        clipToPadding = false
        addView(flow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private val search = EditText(context).apply {
        hint = "Type to find an app"
        setTextColor(Color.WHITE)
        setHintTextColor(0x80FFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_GO
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0x1AFFFFFF)
            cornerRadius = dp(10).toFloat()
        }
        setPadding(dp(14), dp(10), dp(14), dp(10))
    }
    private val strip = LetterStrip(context)
    private var items: List<Item> = emptyList()
    private var views: List<Pair<Item, TextView>> = emptyList()
    private var query = ""
    private var bottomInset = 0

    init {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(16), dp(4), dp(16), dp(6))
            })
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        addView(column, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(strip, LayoutParams(dp(30), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim().orEmpty()
                applyFilter(null)
            }
        })
        search.setOnEditorActionListener { v, _, _ ->
            visibleItems().firstOrNull()?.let { (item, tv) -> onOpen(item.app, tv) }
            v.clearFocus()
            true
        }
        strip.onLetter = { letter, active ->
            applyFilter(if (active) letter else null)
            if (letter != null) jumpTo(letter)
        }
    }

    fun setBottomInset(px: Int) {
        bottomInset = px
        scroll.setPadding(0, 0, 0, px + dp(24))
        strip.setPadding(0, dp(8), 0, px + dp(8))
    }

    fun setAccent(color: Int) {
        strip.accent = color
    }

    fun clearSearch() {
        if (search.text.isNotEmpty()) search.setText("")
    }

    /** Shows [list]; sizes and colours were worked out by the caller. */
    fun submit(list: List<Item>) {
        items = list
        search.visibility = if (prefs.textSearch) View.VISIBLE else View.GONE
        strip.visibility = if (prefs.textLetterStrip) View.VISIBLE else View.GONE
        flow.gap = dp(prefs.textSpacingDp)
        flow.align = prefs.textAlign
        flow.setPadding(dp(18), dp(4), if (prefs.textLetterStrip) dp(34) else dp(18), 0)
        val face = when (prefs.textFont) {
            "custom" -> TextFonts.custom(context) ?: Typeface.create("sans-serif-light", Typeface.NORMAL)
            "bold" -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            "regular" -> Typeface.create("sans-serif", Typeface.NORMAL)
            else -> Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        setBackgroundColor(when (prefs.textBackground) {
            "dim" -> 0x99000000.toInt()
            "black" -> Color.BLACK
            else -> Color.TRANSPARENT
        })
        val opacity = prefs.textOpacity.coerceIn(20, 100) / 100f
        val badgeColor = strip.accent
        flow.removeAllViews()
        views = list.map { item ->
            val tv = TextView(context).apply {
                val name = when (prefs.textCase) {
                    "upper" -> item.label.uppercase(Locale.getDefault())
                    "asis" -> item.label
                    else -> item.label.lowercase(Locale.getDefault())
                }
                text = if (item.badge > 0 && prefs.textNotify) {
                    // A small raised count, like a footnote, marks apps with notifications.
                    android.text.SpannableStringBuilder(name).apply {
                        val start = length
                        append(if (item.badge > 99) "99+" else item.badge.toString())
                        setSpan(android.text.style.SuperscriptSpan(), start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(android.text.style.RelativeSizeSpan(0.45f), start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(android.text.style.ForegroundColorSpan(badgeColor), start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                } else name
                typeface = face
                setTextColor(item.color)
                tag = opacity
                setTextSize(TypedValue.COMPLEX_UNIT_SP, TextCloud.sizeSp(item.level, prefs.textMinSp, prefs.textMaxSp))
                includeFontPadding = false
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                if (prefs.textShadow) setShadowLayer(4f, 0f, 1f, 0x99000000.toInt())
                isClickable = true
                isFocusable = true
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x33FFFFFF), null,
                    android.graphics.drawable.ColorDrawable(Color.WHITE)
                )
                setPadding(dp(2), dp(3), dp(2), dp(3))
                setOnClickListener { onOpen(item.app, this) }
                setOnLongClickListener {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onMenu(item.app, this)
                    true
                }
                contentDescription = item.label + if (item.badge > 0) ", ${item.badge} notifications" else ""
            }
            flow.addView(tv)
            item to tv
        }
        strip.letters = list.filter { !it.hidden }.mapNotNull { it.label.firstOrNull()?.uppercaseChar()?.let { c -> if (c in 'A'..'Z') c else '#' } }.toSet()
        applyFilter(null)
    }

    private fun matches(item: Item, letter: Char?): Boolean {
        val name = item.label
        // Hidden names show up only when searched for, like in AP15.
        if (item.hidden && query.isEmpty()) return false
        val okQuery = query.isEmpty() || name.contains(query, ignoreCase = true) || item.app.name.contains(query, ignoreCase = true) ||
            name.split(' ').any { it.startsWith(query, ignoreCase = true) }
        val first = name.firstOrNull()?.uppercaseChar()?.let { if (it in 'A'..'Z') it else '#' }
        val okLetter = letter == null || first == letter
        return okQuery && okLetter
    }

    private fun visibleItems() = views.filter { matches(it.first, null) }

    /** Search hides what doesn't match; the letter strip dims everything else. */
    private fun applyFilter(letter: Char?) {
        views.forEach { (item, tv) ->
            val queryOk = matches(item, null)
            tv.visibility = if (queryOk) View.VISIBLE else View.GONE
            tv.animate().cancel()
            val rest = tv.tag as? Float ?: 1f
            tv.alpha = if (letter == null || matches(item, letter)) rest else 0.18f * rest
        }
    }

    private fun jumpTo(letter: Char) {
        val target = views.firstOrNull { (item, tv) -> tv.visibility == View.VISIBLE && matches(item, letter) }?.second ?: return
        scroll.smoothScrollTo(0, (target.top - dp(24)).coerceAtLeast(0))
    }

    /** Lays children out in lines, wrapping like text. */
    private class FlowLayout(context: Context) : ViewGroup(context) {
        var gap = 0
        var align = "start"

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val inner = width - paddingLeft - paddingRight
            var x = 0
            var lineH = 0
            var total = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (c.visibility == GONE) continue
                c.measure(MeasureSpec.makeMeasureSpec(inner, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                if (x > 0 && x + c.measuredWidth > inner) {
                    total += lineH + gap / 2
                    x = 0
                    lineH = 0
                }
                x += c.measuredWidth + gap
                lineH = maxOf(lineH, c.measuredHeight)
            }
            total += lineH
            setMeasuredDimension(width, total + paddingTop + paddingBottom)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val inner = r - l - paddingLeft - paddingRight
            var y = paddingTop
            val line = ArrayList<View>()
            var lineW = 0
            fun flush() {
                if (line.isEmpty()) return
                val h = line.maxOf { it.measuredHeight }
                val used = lineW - gap
                var x = paddingLeft + when (align) {
                    "center" -> (inner - used) / 2
                    "end" -> inner - used
                    else -> 0
                }
                // Names on one line share a baseline, so big and small words line up.
                line.forEach { v ->
                    val top = y + h - v.measuredHeight
                    v.layout(x, top, x + v.measuredWidth, top + v.measuredHeight)
                    x += v.measuredWidth + gap
                }
                y += h + gap / 2
                line.clear()
                lineW = 0
            }
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (c.visibility == GONE) continue
                if (line.isNotEmpty() && lineW + c.measuredWidth > inner) flush()
                line.add(c)
                lineW += c.measuredWidth + gap
            }
            flush()
        }
    }

    /** A–Z strip; slide a finger along it to light up names with that first letter. */
    private class LetterStrip(context: Context) : View(context) {
        var letters: Set<Char> = emptySet()
            set(value) {
                field = value
                invalidate()
            }
        var onLetter: ((Char?, Boolean) -> Unit)? = null
        var accent: Int = Color.WHITE
        private val all = listOf('#') + ('A'..'Z')
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 11 * resources.displayMetrics.density
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private var current: Char? = null

        override fun onDraw(canvas: Canvas) {
            val h = (height - paddingTop - paddingBottom).toFloat()
            val step = h / all.size
            all.forEachIndexed { i, c ->
                paint.color = when {
                    c == current -> Color.WHITE
                    c in letters -> 0xB3FFFFFF.toInt()
                    else -> 0x33FFFFFF
                }
                paint.textSize = (if (c == current) 16 else 11) * resources.displayMetrics.density
                canvas.drawText(c.toString(), width / 2f, paddingTop + step * i + step * 0.7f, paint)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val h = (height - paddingTop - paddingBottom).toFloat()
            val i = (((event.y - paddingTop) / h) * all.size).toInt().coerceIn(0, all.size - 1)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val c = all[i]
                    if (c != current) {
                        current = c
                        if (c in letters) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onLetter?.invoke(c, true)
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val c = current
                    current = null
                    invalidate()
                    // Let go: everything lights up again, and the list stays at that letter.
                    onLetter?.invoke(c, false)
                }
            }
            return true
        }
    }
}
