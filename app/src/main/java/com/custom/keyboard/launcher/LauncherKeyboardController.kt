package com.custom.keyboard.launcher

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.custom.keyboard.R

class LauncherKeyboardController(
    private val context: Context,
    private val keyboardView: View,
    private val searchEditText: EditText,
    private val onTextUpdated: (String) -> Unit,
    private val onActionSubmit: () -> Unit
) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vm?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    init {
        buildKeyboardRows()
    }

    private fun buildKeyboardRows() {
        val rowNums = keyboardView.findViewById<LinearLayout>(R.id.row_kb_nums)
        val rowQ = keyboardView.findViewById<LinearLayout>(R.id.row_kb_q)
        val rowA = keyboardView.findViewById<LinearLayout>(R.id.row_kb_a)
        val rowZ = keyboardView.findViewById<LinearLayout>(R.id.row_kb_z)
        val rowBottom = keyboardView.findViewById<LinearLayout>(R.id.row_kb_bottom)

        // Numbers row
        val nums = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        nums.forEach { num ->
            rowNums?.addView(createKeyButton(num, 1f) { appendText(num) })
        }

        // QWERTY row
        val qRow = listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P")
        qRow.forEach { char ->
            rowQ?.addView(createKeyButton(char, 1f) { appendText(char.lowercase()) })
        }

        // ASDF row
        val aRow = listOf("A", "S", "D", "F", "G", "H", "J", "K", "L")
        aRow.forEach { char ->
            rowA?.addView(createKeyButton(char, 1f) { appendText(char.lowercase()) })
        }

        // ZXCV row + Backspace
        val zRow = listOf("Z", "X", "C", "V", "B", "N", "M")
        zRow.forEach { char ->
            rowZ?.addView(createKeyButton(char, 1f) { appendText(char.lowercase()) })
        }
        // Backspace key
        rowZ?.addView(createKeyButton("⌫", 1.8f, isAction = true) {
            performBackspace()
        })

        // Bottom row: Math shortcuts + Space + Go
        rowBottom?.addView(createKeyButton("+", 1f, isAction = true) { appendText("+") })
        rowBottom?.addView(createKeyButton("-", 1f, isAction = true) { appendText("-") })
        rowBottom?.addView(createKeyButton("*", 1f, isAction = true) { appendText("*") })
        rowBottom?.addView(createKeyButton("/", 1f, isAction = true) { appendText("/") })
        rowBottom?.addView(createKeyButton("SPACE", 4.2f) { appendText(" ") })
        rowBottom?.addView(createKeyButton("GO ➔", 1.8f, isEnter = true) {
            triggerHaptic()
            onActionSubmit()
        })
    }

    private fun createKeyButton(
        label: String,
        weight: Float,
        isAction: Boolean = false,
        isEnter: Boolean = false,
        onClick: () -> Unit
    ): View {
        val btn = TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = if (label.length > 2) 12f else 16f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                setMargins(2, 0, 2, 0)
            }

            background = when {
                isEnter -> ContextCompat.getDrawable(context, R.drawable.bg_key_enter)
                isAction -> ContextCompat.getDrawable(context, R.drawable.bg_key_action)
                else -> ContextCompat.getDrawable(context, R.drawable.bg_key)
            }

            isClickable = true
            isFocusable = true

            setOnClickListener {
                triggerHaptic()
                onClick()
            }
        }
        return btn
    }

    private fun appendText(char: String) {
        val current = searchEditText.text.toString()
        val start = searchEditText.selectionStart.coerceAtLeast(0)
        val end = searchEditText.selectionEnd.coerceAtLeast(0)
        val min = Math.min(start, end)
        val max = Math.max(start, end)

        val updated = current.substring(0, min) + char + current.substring(max)
        searchEditText.setText(updated)
        searchEditText.setSelection(min + char.length)
        onTextUpdated(updated)
    }

    private fun performBackspace() {
        val current = searchEditText.text.toString()
        if (current.isEmpty()) return

        val start = searchEditText.selectionStart.coerceAtLeast(0)
        val end = searchEditText.selectionEnd.coerceAtLeast(0)
        val min = Math.min(start, end)
        val max = Math.max(start, end)

        val updated = if (min == max) {
            if (min > 0) {
                current.substring(0, min - 1) + current.substring(min)
            } else current
        } else {
            current.substring(0, min) + current.substring(max)
        }

        val newCursor = if (min == max) (min - 1).coerceAtLeast(0) else min
        searchEditText.setText(updated)
        searchEditText.setSelection(newCursor.coerceAtMost(updated.length))
        onTextUpdated(updated)
    }

    private fun triggerHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(18)
            }
        } catch (_: Exception) {}
    }
}
