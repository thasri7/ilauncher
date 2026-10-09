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
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.text.TextUtils
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
import com.custom.keyboard.models.TileSize

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

    /** The accent lifted towards white so it stays readable as text on the dark menus. */
    val accentText: Int
        get() {
            val c = accent
            val hsv = FloatArray(3)
            Color.colorToHSV(c, hsv)
            if (hsv[2] > 0.85f && hsv[1] < 0.7f) return c
            val mix = 0.45f
            return Color.rgb(
                (Color.red(c) + (255 - Color.red(c)) * mix).toInt(),
                (Color.green(c) + (255 - Color.green(c)) * mix).toInt(),
                (Color.blue(c) + (255 - Color.blue(c)) * mix).toInt()
            )
        }

    fun dp(value: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()
    fun dp(value: Int): Int = dp(value.toFloat())

    private val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun ripple(base: Int = Color.TRANSPARENT): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), ColorDrawable(base), ColorDrawable(Color.WHITE))

    /** Menu surface: dark acrylic with soft corners and a hairline edge. */
    fun card(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(0xF5202124.toInt())
            setStroke(dp(1), 0x1FFFFFFF)
            cornerRadius = dp(14).toFloat()
        }
        clipToOutline = true
        isClickable = true
        elevation = dp(16).toFloat()
        setPadding(0, dp(8), 0, dp(8))
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
        setPadding(dp(20), dp(10), dp(20), dp(10))
        addView(text(title, 21f, face = light).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        if (!subtitle.isNullOrEmpty()) addView(text(subtitle, 13f, 0x99FFFFFF.toInt()).apply { setPadding(0, dp(2), 0, 0) })
    }

    fun sectionTitle(value: String): TextView = text(value.uppercase(), 12f, accentText, medium).apply {
        letterSpacing = 0.1f
        setPadding(dp(20), dp(18), dp(20), dp(6))
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
            minimumHeight = dp(50)
            setPadding(dp(20), dp(8), dp(20), dp(8))
            background = ripple()
            val color = if (danger) 0xFFFF6B5E.toInt() else Color.WHITE
            if (iconRes != null) addView(icon(iconRes, 20, tint = if (danger) color else 0xE6FFFFFF.toInt()))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = if (iconRes != null) dp(18) else 0
                }
                addView(text(title, 15f, color))
                if (!subtitle.isNullOrEmpty()) addView(text(subtitle, 12f, 0x99FFFFFF.toInt()))
            })
            setOnClickListener { onClick() }
        }

    /**
     * A row with an arbitrary drawable icon (app shortcuts, widgets) and an optional trailing
     * glyph button, e.g. "pin this shortcut".
     */
    fun actionWithIcon(
        icon: Drawable?,
        title: String,
        subtitle: String? = null,
        trailingIcon: Int? = null,
        trailingDescription: String? = null,
        onTrailing: (() -> Unit)? = null,
        onClick: () -> Unit
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(52)
        setPadding(dp(18), dp(6), dp(8), dp(6))
        background = ripple()
        addView(ImageView(context).apply {
            setImageDrawable(icon)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14) }
            addView(text(title, 15f).apply { maxLines = 1 })
            if (!subtitle.isNullOrEmpty()) addView(text(subtitle, 12f, 0x99FFFFFF.toInt()).apply { maxLines = 1 })
        })
        if (trailingIcon != null && onTrailing != null) {
            addView(ImageView(context).apply {
                setImageDrawable(ContextCompat.getDrawable(context, trailingIcon))
                imageTintList = ColorStateList.valueOf(Color.WHITE)
                contentDescription = trailingDescription
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = ripple()
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                setOnClickListener { onTrailing() }
            })
        }
        setOnClickListener { onClick() }
    }

    fun divider(): View = View(context).apply {
        setBackgroundColor(0x1FFFFFFF)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        }
    }

    /**
     * Single-choice options; the selection updates in place before [onPick] runs. Up to four
     * short options render as a full-width segmented control, longer sets scroll sideways.
     */
    fun chips(options: List<String>, selected: Int, onPick: (Int) -> Unit): View {
        val segmented = options.size <= 4 && options.all { it.length <= 14 }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(4), dp(16), dp(8))
        }
        val chips = options.map { label ->
            text(label, 13f, face = medium).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(10), dp(9), dp(10), dp(9))
                layoutParams = if (segmented) {
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
                } else {
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
                }
                if (!segmented) minWidth = dp(56)
            }
        }
        fun paint(sel: Int) = chips.forEachIndexed { i, chip ->
            chip.background = RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF),
                GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    if (i == sel) setColor(accent) else setColor(0x1AFFFFFF)
                },
                null
            )
        }
        paint(selected)
        chips.forEachIndexed { i, chip ->
            chip.setOnClickListener {
                paint(i)
                onPick(i)
            }
            row.addView(chip)
        }
        if (segmented) {
            (chips.lastOrNull()?.layoutParams as? LinearLayout.LayoutParams)?.marginEnd = 0
            return row
        }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    /**
     * Tile size picker: a 4×4 grid where tapping cell (c, r) picks a c×r tile. The cells the size
     * covers light up in the accent colour; sizes not in [allowed] are dimmed and ignored.
     */
    fun sizeGrid(allowed: List<TileSize>, selected: TileSize, onPick: (TileSize) -> Unit): View {
        val max = TileSize.MAX
        var current = selected
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(10))
        }
        val label = text(selected.label, 13f, face = medium).apply { setPadding(0, 0, 0, dp(8)) }
        box.addView(label)
        val cells = Array(max) { arrayOfNulls<View>(max) }
        fun paint() {
            for (r in 0 until max) for (c in 0 until max) {
                val cell = cells[r][c] ?: continue
                val size = TileSize(c + 1, r + 1)
                val covered = c < current.cols && r < current.rows
                cell.background = GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    setColor(if (covered) accent else 0x1AFFFFFF)
                }
                cell.alpha = if (size in allowed) 1f else 0.3f
            }
            label.text = current.label
        }
        for (r in 0 until max) {
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until max) {
                val size = TileSize(c + 1, r + 1)
                val cell = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                        if (c < max - 1) marginEnd = dp(4)
                    }
                    contentDescription = size.label
                    setOnClickListener {
                        if (size in allowed && size != current) {
                            current = size
                            paint()
                            onPick(size)
                        }
                    }
                }
                cells[r][c] = cell
                line.addView(cell)
            }
            box.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (r < max - 1) bottomMargin = dp(4)
            })
        }
        paint()
        return box
    }

    /**
     * A Metro slider: title and value on one line, the track below. [onMove] runs live while
     * dragging (keep it cheap), [onDone] once the finger lifts.
     */
    fun slider(
        title: String,
        min: Int,
        max: Int,
        value: Int,
        format: (Int) -> String,
        onMove: (Int) -> Unit = {},
        onDone: (Int) -> Unit
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(10), dp(12), dp(4))
        val label = text(format(value), 13f, accentText, medium)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(title, 15f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(label)
        })
        addView(android.widget.SeekBar(context).apply {
            this.max = max - min
            progress = (value - min).coerceIn(0, max - min)
            progressTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(0x4DFFFFFF)
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                    label.text = format(progress + min)
                    if (fromUser) onMove(progress + min)
                }

                override fun onStartTrackingTouch(bar: android.widget.SeekBar) {}

                override fun onStopTrackingTouch(bar: android.widget.SeekBar) = onDone(bar.progress + min)
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = -dp(8)
        })
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
                        cornerRadius = dp(6).toFloat()
                        if (color == null) setStroke(dp(2), Color.WHITE)
                    }
                    cell.clipToOutline = true
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
            cornerRadius = dp(8).toFloat()
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
                cornerRadius = dp(8).toFloat()
                if (filled) setColor(accent) else {
                    setColor(0x14FFFFFF)
                    setStroke(dp(1), 0x4DFFFFFF)
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
