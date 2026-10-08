package com.custom.keyboard.launcher

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.custom.keyboard.R

/** The 20 Windows Phone / Windows 10 Mobile accent colours. */
val METRO_ACCENTS: List<Pair<String, String>> = listOf(
    "Lime" to "#A4C400", "Green" to "#60A917", "Emerald" to "#008A00", "Teal" to "#00ABA9",
    "Cyan" to "#1BA1E2", "Cobalt" to "#0050EF", "Indigo" to "#6A00FF", "Violet" to "#AA00FF",
    "Pink" to "#F472D0", "Magenta" to "#D80073", "Crimson" to "#A20025", "Red" to "#E51400",
    "Orange" to "#FA6800", "Amber" to "#F0A30A", "Yellow" to "#E3C800", "Brown" to "#825A2C",
    "Olive" to "#6D8764", "Steel" to "#647687", "Mauve" to "#76608A", "Taupe" to "#87794E"
)

/** Small builder kit so every menu, sheet and settings page shares one Metro look. */
class MetroUi(val context: Context, private val accentProvider: () -> Int) {
    val accent: Int get() = accentProvider()

    fun dp(value: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()
    fun dp(value: Int): Int = dp(value.toFloat())

    private val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun ripple(base: Int = Color.TRANSPARENT): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), ColorDrawable(base), ColorDrawable(Color.WHITE))

    /** Flat flyout surface with a hairline border, as used by W10M menus. */
    fun card(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(0xFA1C1C1E.toInt())
            setStroke(dp(1), 0x26FFFFFF)
        }
        isClickable = true
        elevation = dp(12).toFloat()
        setPadding(0, dp(6), 0, dp(6))
    }

    fun text(value: CharSequence, sizeSp: Float, color: Int = Color.WHITE, face: Typeface = regular): TextView =
        TextView(context).apply {
            text = value
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            typeface = face
        }

    fun pageTitle(value: String): TextView = text(value, 34f, face = light).apply {
        setPadding(dp(20), dp(8), dp(20), dp(4))
    }

    fun header(title: String, subtitle: String? = null): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(10), dp(18), dp(8))
        addView(text(title, 20f, face = light).apply { maxLines = 1 })
        if (!subtitle.isNullOrEmpty()) addView(text(subtitle, 12f, 0x99FFFFFF.toInt()))
    }

    fun sectionTitle(value: String): TextView = text(value.uppercase(), 12f, accent, medium).apply {
        letterSpacing = 0.12f
        setPadding(dp(20), dp(22), dp(20), dp(6))
    }

    fun caption(value: String): TextView = text(value, 12f, 0x99FFFFFF.toInt()).apply {
        setPadding(dp(20), 0, dp(20), dp(4))
    }

    fun icon(res: Int, sizeDp: Int = 22, tint: Int = Color.WHITE): ImageView = ImageView(context).apply {
        setImageDrawable(ContextCompat.getDrawable(context, res))
        imageTintList = ColorStateList.valueOf(tint)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** A menu / settings row: icon, title, optional subtitle. */
    fun action(iconRes: Int?, title: String, subtitle: String? = null, danger: Boolean = false, onClick: () -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(18), dp(8), dp(18), dp(8))
            background = ripple()
            val color = if (danger) 0xFFFF6B5E.toInt() else Color.WHITE
            if (iconRes != null) addView(icon(iconRes, tint = color))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = if (iconRes != null) dp(16) else 0
                }
                addView(text(title, 15f, color))
                if (!subtitle.isNullOrEmpty()) addView(text(subtitle, 12f, 0x99FFFFFF.toInt()))
            })
            setOnClickListener { onClick() }
        }

    fun divider(): View = View(context).apply {
        setBackgroundColor(0x1FFFFFFF)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        }
    }

    /** Single-choice chips; the selection updates in place before [onPick] runs. */
    fun chips(options: List<String>, selected: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(4), dp(16), dp(8))
        }
        val chips = options.map { label ->
            text(label, 13f, face = medium).apply {
                gravity = Gravity.CENTER
                minWidth = dp(56)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(8)
                }
            }
        }
        fun paint(sel: Int) = chips.forEachIndexed { i, chip ->
            chip.background = GradientDrawable().apply {
                if (i == sel) setColor(accent) else {
                    setColor(0x14FFFFFF)
                    setStroke(dp(1), 0x40FFFFFF)
                }
            }
        }
        paint(selected)
        chips.forEachIndexed { i, chip ->
            chip.setOnClickListener {
                paint(i)
                onPick(i)
            }
            row.addView(chip)
        }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    /** Colour grid, five per row. A null entry is the "follow accent" swatch. */
    fun swatches(colors: List<Int?>, selected: Int?, onPick: (Int?) -> Unit): View {
        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(8))
        }
        val cells = ArrayList<Pair<FrameLayout, Int?>>()
        colors.chunked(5).forEach { chunk ->
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until 5) {
                val color = chunk.getOrNull(i)
                val cell = FrameLayout(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                        setMargins(dp(3), dp(3), dp(3), dp(3))
                    }
                }
                if (i < chunk.size) {
                    cell.background = GradientDrawable().apply {
                        setColor(color ?: accent)
                        if (color == null) setStroke(dp(2), Color.WHITE)
                    }
                    if (color == null) {
                        cell.addView(text("A", 15f, face = medium).apply {
                            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
                        })
                    }
                    cells.add(cell to color)
                }
                line.addView(cell)
            }
            grid.addView(line)
        }
        fun mark(sel: Int?) = cells.forEach { (cell, color) ->
            cell.foreground = if (color == sel) ContextCompat.getDrawable(context, R.drawable.ic_m_check)?.mutate()?.let {
                InsetDrawable(it, dp(10))
            } else null
        }
        mark(selected)
        cells.forEach { (cell, color) ->
            cell.setOnClickListener {
                mark(color)
                onPick(color)
            }
        }
        return grid
    }

    fun toggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val toggle = MetroToggle(context, accent).apply { setOn(checked, animate = false) }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(20), dp(8), dp(20), dp(8))
            background = ripple()
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(text(title, 15f))
                if (!subtitle.isNullOrEmpty()) addView(text(subtitle, 12f, 0x99FFFFFF.toInt()))
            })
            addView(toggle)
        }
        toggle.onChange = onChange
        row.setOnClickListener { toggle.performClick() }
        return row
    }

    fun input(initial: String, hint: String): EditText = EditText(context).apply {
        setText(initial)
        setSelection(initial.length)
        this.hint = hint
        setTextColor(Color.WHITE)
        setHintTextColor(0x66FFFFFF)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        isSingleLine = true
        background = GradientDrawable().apply {
            setColor(0x1AFFFFFF)
            setStroke(dp(2), accent)
        }
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    /** A flat Metro button; [filled] buttons use the accent. */
    fun button(label: String, filled: Boolean, onClick: () -> Unit): TextView = text(label, 14f, face = medium).apply {
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(10), dp(18), dp(10))
        background = RippleDrawable(
            ColorStateList.valueOf(0x33FFFFFF),
            GradientDrawable().apply {
                if (filled) setColor(accent) else {
                    setColor(0x14FFFFFF)
                    setStroke(dp(2), 0x66FFFFFF)
                }
            },
            null
        )
        setOnClickListener { onClick() }
    }
}

/** Windows 10 Mobile toggle switch: outlined track when off, accent-filled track when on. */
@SuppressLint("ViewConstructor")
class MetroToggle(context: Context, private val accent: Int) : View(context) {
    var isOn = false
        private set
    var onChange: ((Boolean) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = RectF()
    private var knob = 0f
    private var animator: ValueAnimator? = null
    private val density = context.resources.displayMetrics.density

    init {
        isClickable = true
        setOnClickListener {
            setOn(!isOn, animate = true)
            onChange?.invoke(isOn)
        }
    }

    fun setOn(on: Boolean, animate: Boolean) {
        isOn = on
        animator?.cancel()
        val target = if (on) 1f else 0f
        if (!animate) {
            knob = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(knob, target).apply {
            duration = 160
            addUpdateListener {
                knob = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((46 * density).toInt(), (24 * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val stroke = 2f * density
        track.set(stroke / 2, stroke / 2, width - stroke / 2, height - stroke / 2)
        val radius = track.height() / 2
        if (knob > 0.5f) {
            paint.style = Paint.Style.FILL
            paint.color = accent
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke
            paint.color = Color.WHITE
        }
        canvas.drawRoundRect(track, radius, radius, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        val knobRadius = radius - 3.5f * density
        val cx = track.left + radius + (track.width() - 2 * radius) * knob
        canvas.drawCircle(cx, track.centerY(), knobRadius, paint)
    }
}
