package com.custom.keyboard

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: KeyboardPreferences
    private lateinit var shortcutsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = KeyboardPreferences(this)

        val btnEnable = findViewById<Button>(R.id.btn_enable_ime)
        val btnSelect = findViewById<Button>(R.id.btn_select_ime)

        btnEnable.setOnClickListener {
            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }

        btnSelect.setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showInputMethodPicker()
        }

        shortcutsContainer = findViewById(R.id.shortcuts_list_container)

        setupPreferencesUI()
        setupShortcutsManagerUI()
    }

    private fun setupPreferencesUI() {
        // 1. Font Size
        val rgFontSize = findViewById<RadioGroup>(R.id.rg_font_size)
        when (prefs.fontSizeSp) {
            20f -> rgFontSize.check(R.id.rb_font_normal)
            25f -> rgFontSize.check(R.id.rb_font_xlarge)
            else -> rgFontSize.check(R.id.rb_font_large)
        }
        rgFontSize.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rb_font_normal -> prefs.fontSizeSp = 20f
                R.id.rb_font_large -> prefs.fontSizeSp = 22f
                R.id.rb_font_xlarge -> prefs.fontSizeSp = 25f
            }
        }

        // 1b. Font Typeface
        val rgTypeface = findViewById<RadioGroup>(R.id.rg_font_typeface)
        when (prefs.typefaceStyle) {
            "mono" -> rgTypeface.check(R.id.rb_typeface_mono)
            "serif" -> rgTypeface.check(R.id.rb_typeface_serif)
            else -> rgTypeface.check(R.id.rb_typeface_sans)
        }
        rgTypeface.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rb_typeface_mono -> prefs.typefaceStyle = "mono"
                R.id.rb_typeface_serif -> prefs.typefaceStyle = "serif"
                else -> prefs.typefaceStyle = "sans"
            }
        }

        // 1c. Secondary Language
        val rgSecondaryLang = findViewById<RadioGroup>(R.id.rg_secondary_lang)
        when (prefs.secondaryLanguage) {
            "fr" -> rgSecondaryLang.check(R.id.rb_lang_french)
            "es" -> rgSecondaryLang.check(R.id.rb_lang_spanish)
            else -> rgSecondaryLang.check(R.id.rb_lang_arabic)
        }
        rgSecondaryLang.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rb_lang_french -> prefs.secondaryLanguage = "fr"
                R.id.rb_lang_spanish -> prefs.secondaryLanguage = "es"
                else -> prefs.secondaryLanguage = "ar"
            }
        }

        // 2. Keyboard Height
        val rgHeight = findViewById<RadioGroup>(R.id.rg_height)
        when (prefs.rowHeightDp) {
            48 -> rgHeight.check(R.id.rb_height_compact)
            60 -> rgHeight.check(R.id.rb_height_tall)
            else -> rgHeight.check(R.id.rb_height_normal)
        }
        rgHeight.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rb_height_compact -> prefs.rowHeightDp = 48
                R.id.rb_height_normal -> prefs.rowHeightDp = 54
                R.id.rb_height_tall -> prefs.rowHeightDp = 60
            }
        }

        // 3. Theme
        val rgTheme = findViewById<RadioGroup>(R.id.rg_theme)
        when (prefs.theme) {
            "amoled" -> rgTheme.check(R.id.rb_theme_amoled)
            "neon" -> rgTheme.check(R.id.rb_theme_neon)
            else -> rgTheme.check(R.id.rb_theme_slate)
        }
        rgTheme.setOnCheckedChangeListener { _, checkedId ->
            prefs.theme = when (checkedId) {
                R.id.rb_theme_amoled -> "amoled"
                R.id.rb_theme_neon -> "neon"
                else -> "slate"
            }
        }

        // 4. Checkboxes
        val cbNumbers = findViewById<CheckBox>(R.id.cb_show_numbers)
        cbNumbers.isChecked = prefs.showNumberRow
        cbNumbers.setOnCheckedChangeListener { _, isChecked ->
            prefs.showNumberRow = isChecked
        }

        val cbHaptic = findViewById<CheckBox>(R.id.cb_haptic)
        cbHaptic.isChecked = prefs.hapticFeedback
        cbHaptic.setOnCheckedChangeListener { _, isChecked ->
            prefs.hapticFeedback = isChecked
        }

        val cbSound = findViewById<CheckBox>(R.id.cb_sound)
        cbSound.isChecked = prefs.soundEnabled
        cbSound.setOnCheckedChangeListener { _, isChecked ->
            prefs.soundEnabled = isChecked
        }

        val cbDoubleTapSpace = findViewById<CheckBox>(R.id.cb_double_tap_space)
        cbDoubleTapSpace.isChecked = prefs.doubleTapPeriod
        cbDoubleTapSpace.setOnCheckedChangeListener { _, isChecked ->
            prefs.doubleTapPeriod = isChecked
        }

        val cbAutoCorrect = findViewById<CheckBox>(R.id.cb_auto_correct)
        cbAutoCorrect.isChecked = prefs.autoCorrect
        cbAutoCorrect.setOnCheckedChangeListener { _, isChecked ->
            prefs.autoCorrect = isChecked
        }

        listOf(
            R.id.cb_glide to (prefs.glideTyping to { on: Boolean -> prefs.glideTyping = on }),
            R.id.cb_glide_trail to (prefs.glideTrail to { on: Boolean -> prefs.glideTrail = on }),
            R.id.cb_next_word to (prefs.nextWordHints to { on: Boolean -> prefs.nextWordHints = on })
        ).forEach { (id, state) ->
            findViewById<CheckBox>(id).apply {
                isChecked = state.first
                setOnCheckedChangeListener { _, isChecked -> state.second(isChecked) }
            }
        }

        val cbAutoCap = findViewById<CheckBox>(R.id.cb_auto_cap)
        cbAutoCap.isChecked = prefs.autoCapitalize
        cbAutoCap.setOnCheckedChangeListener { _, isChecked ->
            prefs.autoCapitalize = isChecked
        }

        val cbKeyPopups = findViewById<CheckBox>(R.id.cb_key_popups)
        cbKeyPopups.isChecked = prefs.keyPopupsEnabled
        cbKeyPopups.setOnCheckedChangeListener { _, isChecked ->
            prefs.keyPopupsEnabled = isChecked
        }

        val rgLongPress = findViewById<RadioGroup>(R.id.rg_long_press)
        when (prefs.longPressDelayMs) {
            250L -> rgLongPress.check(R.id.rb_delay_fast)
            450L -> rgLongPress.check(R.id.rb_delay_relaxed)
            else -> rgLongPress.check(R.id.rb_delay_normal)
        }
        rgLongPress.setOnCheckedChangeListener { _, checkedId ->
            prefs.longPressDelayMs = when (checkedId) {
                R.id.rb_delay_fast -> 250L
                R.id.rb_delay_relaxed -> 450L
                else -> 320L
            }
        }
    }

    private fun setupShortcutsManagerUI() {
        renderShortcutsList()

        val etTrigger = findViewById<EditText>(R.id.et_shortcut_trigger)
        val etExpansion = findViewById<EditText>(R.id.et_shortcut_expansion)
        val btnAdd = findViewById<Button>(R.id.btn_add_shortcut)

        btnAdd.setOnClickListener {
            val trigger = etTrigger.text.toString().trim()
            val expansion = etExpansion.text.toString().trim()

            if (trigger.isEmpty() || expansion.isEmpty()) {
                Toast.makeText(this, "Please enter both trigger and expanded text", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val validTrigger = if (trigger.startsWith("/")) trigger else "/$trigger"
            prefs.saveShortcut(validTrigger, expansion)
            etTrigger.setText("")
            etExpansion.setText("")
            renderShortcutsList()
            Toast.makeText(this, "Shortcut saved: $validTrigger", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderShortcutsList() {
        shortcutsContainer.removeAllViews()
        val allShortcuts = prefs.getAllShortcuts()

        for ((trigger, expansion) in allShortcuts) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, 8)
                }
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_toolbar_pill)
                setPadding(16, 10, 16, 10)
            }

            val textTrigger = TextView(this).apply {
                text = trigger
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.kb_accent))
                textSize = 14f
                paint.isFakeBoldText = true
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f)
            }

            val textExpansion = TextView(this).apply {
                text = expansion
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.kb_text_primary))
                textSize = 13f
                isSingleLine = true
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2.5f)
            }

            val btnDelete = TextView(this).apply {
                text = "🗑️"
                textSize = 15f
                setPadding(12, 0, 4, 0)
                setOnClickListener {
                    prefs.deleteShortcut(trigger)
                    renderShortcutsList()
                }
            }

            row.addView(textTrigger)
            row.addView(textExpansion)
            row.addView(btnDelete)

            shortcutsContainer.addView(row)
        }
    }
}
