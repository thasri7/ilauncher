package com.custom.keyboard

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognizerIntent
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.SuperscriptSpan
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class KeyboardIME : InputMethodService() {

    private enum class KeyboardMode {
        LETTERS,
        SYMBOLS,
        NUMPAD,
        EMOJI,
        CURSOR_DPAD
    }

    private var currentMode = KeyboardMode.LETTERS
    private var isShifted = false
    private var isCapsLock = false
    private var lastSpaceTapTime = 0L
    private var isPrimaryLang = true
    private var isStealthMode = false
    private var isFloatingMode = false
    private var lastDeletedText = ""

    private lateinit var prefs: KeyboardPreferences
    private lateinit var dictionary: DictionaryEngine
    private lateinit var appLauncher: AppLauncherHelper
    private val langManager = LanguageManager()
    private val translator = TranslatorEngine()
    private val mathCalc = MathCalculator()
    private val unitConverter = UnitConverter()
    private val quickReplies = QuickReplyTemplates()
    private val fancyConverter = FancyFontConverter()
    private var activeFancyStyle: FancyFontConverter.Style? = null

    // Hardware Haptics & Audio
    private var vibrator: Vibrator? = null
    private var soundPool: SoundPool? = null
    private var clickSoundId: Int = 0

    private lateinit var rootView: View
    private lateinit var keyboardRoot: LinearLayout
    private lateinit var keysWrapper: LinearLayout
    private lateinit var rowNumbers: LinearLayout
    private lateinit var row1: LinearLayout
    private lateinit var row2: LinearLayout
    private lateinit var row3: LinearLayout
    private lateinit var row4: LinearLayout

    // Floating overlay bubble
    private lateinit var keyPopupPreview: TextView

    // Persistent button references for ZERO-flicker case switching
    private val letterButtons = mutableListOf<Pair<Button, String>>()
    private var shiftButton: Button? = null

    // Fold & Toolbar
    private lateinit var foldBar: LinearLayout
    private lateinit var btnUnfold: TextView
    private lateinit var topToolbarScroll: HorizontalScrollView

    // Unified Smart Suggestion & Action Strip
    private lateinit var smartStripFrame: FrameLayout
    private lateinit var suggestionTypingBar: LinearLayout
    private lateinit var smartIdleScroll: HorizontalScrollView
    private lateinit var smartIdleContainer: LinearLayout
    private lateinit var featureDrawerScroll: HorizontalScrollView
    private lateinit var featureDrawerContainer: LinearLayout
    private val recentClips = mutableListOf<String>()
    private var activeDrawerType: String? = null
    private var floatMode = 0 // 0 = Full, 1 = Dock Right, 2 = Dock Left

    // Suggestions
    private lateinit var suggestionLeft: TextView
    private lateinit var suggestionCenter: TextView
    private lateinit var suggestionRight: TextView

    private val composingWord = java.lang.StringBuilder()
    private var currentCenterSuggestion = ""
    private var pendingLaunchPackage: String? = null

    // Glide Typing state
    private val glideChars = mutableListOf<Char>()
    private var isGliding = false

    private val handler = Handler(Looper.getMainLooper())
    private var backspaceRunnable: Runnable? = null

    // Layout definitions
    private val numberKeys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
    private val qwertyRow1 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
    private val qwertyRow2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
    private val qwertyRow3 = listOf("z", "x", "c", "v", "b", "n", "m")

    private val secondaryMap = mapOf(
        "q" to "1", "w" to "2", "e" to "3", "r" to "4", "t" to "5",
        "y" to "6", "u" to "7", "i" to "8", "o" to "9", "p" to "0",
        "a" to "@", "s" to "#", "d" to "$", "f" to "%", "g" to "&",
        "h" to "*", "j" to "-", "k" to "+", "l" to "=",
        "z" to "(", "x" to ")", "c" to "\"", "v" to "'", "b" to ":",
        "n" to ";", "m" to "/"
    )

    private val symbolsRow1 = listOf("+", "×", "÷", "=", "/", "_", "<", ">", "[", "]")
    private val symbolsRow2 = listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")")
    private val symbolsRow3 = listOf("-", "'", "\"", ":", ";", "?", "`", "~")

    private val emojiRow1 = listOf("😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇")
    private val emojiRow2 = listOf("😍", "🥰", "😘", "😋", "😜", "🤔", "🤫", "😎", "🥳", "🤩")
    private val emojiRow3 = listOf("👍", "👎", "👏", "🙌", "🤝", "🔥", "✨", "💯", "❤️", "🚀")

    override fun onCreate() {
        super.onCreate()
        prefs = KeyboardPreferences(this)
        dictionary = DictionaryEngine(this)
        appLauncher = AppLauncherHelper(this)
        captureInitialClipboard()

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

        try {
            soundPool = SoundPool.Builder()
                .setMaxStreams(6)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .build()
            clickSoundId = soundPool?.load(this, R.raw.key_click, 1) ?: 0
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            soundPool?.release()
            soundPool = null
        } catch (_: Exception) {}
    }

    override fun onCreateInputView(): View {
        rootView = layoutInflater.inflate(R.layout.keyboard_view, null)

        keyboardRoot = rootView.findViewById(R.id.keyboard_root)
        foldBar = rootView.findViewById(R.id.fold_bar)
        btnUnfold = rootView.findViewById(R.id.btn_unfold)
        topToolbarScroll = rootView.findViewById(R.id.top_toolbar_scroll)

        smartStripFrame = rootView.findViewById(R.id.smart_strip_frame)
        suggestionTypingBar = rootView.findViewById(R.id.suggestion_typing_bar)
        smartIdleScroll = rootView.findViewById(R.id.smart_idle_scroll)
        smartIdleContainer = rootView.findViewById(R.id.smart_idle_container)
        featureDrawerScroll = rootView.findViewById(R.id.feature_drawer_scroll)
        featureDrawerContainer = rootView.findViewById(R.id.feature_drawer_container)

        suggestionLeft = rootView.findViewById(R.id.suggestion_left)
        suggestionCenter = rootView.findViewById(R.id.suggestion_center)
        suggestionRight = rootView.findViewById(R.id.suggestion_right)

        keysWrapper = rootView.findViewById(R.id.keyboard_keys_wrapper)
        rowNumbers = rootView.findViewById(R.id.row_numbers)
        row1 = rootView.findViewById(R.id.row_1)
        row2 = rootView.findViewById(R.id.row_2)
        row3 = rootView.findViewById(R.id.row_3)
        row4 = rootView.findViewById(R.id.row_4)

        keyPopupPreview = rootView.findViewById(R.id.key_popup_preview)

        setupToolbar()
        setupSuggestions()
        applyColorTheme()
        renderKeyboard()
        updateSmartIdleBar()

        return rootView
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        captureInitialClipboard()
        composingWord.setLength(0)
        currentCenterSuggestion = ""
        pendingLaunchPackage = null
        glideChars.clear()
        isGliding = false
        lastSpaceTapTime = 0L

        checkAutoCapitalization()

        if (::rootView.isInitialized) {
            clearSuggestions()
            closeFeatureDrawer()
            keyPopupPreview.visibility = View.GONE
            applyColorTheme()
            updateKeyCase()
            updateSmartIdleBar()
        }
    }

    private fun checkAutoCapitalization() {
        if (!prefs.autoCapitalize || isCapsLock) return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(3, 0)?.toString() ?: ""
        val shouldShift = before.isEmpty() || before.endsWith(". ") || before.endsWith("? ") || before.endsWith("! ") || before.endsWith("\n")
        if (shouldShift != isShifted) {
            isShifted = shouldShift
            updateKeyCase()
        }
    }

    private fun buildKeyLabel(displayChar: String, secondary: String?): CharSequence {
        if (secondary == null) return displayChar
        val ssb = SpannableStringBuilder()
        ssb.append(displayChar)
        val start = ssb.length
        ssb.append(secondary)
        val end = ssb.length
        val hintColor = ContextCompat.getColor(this, R.color.kb_hint_color)
        ssb.setSpan(RelativeSizeSpan(0.55f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        ssb.setSpan(SuperscriptSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        ssb.setSpan(ForegroundColorSpan(hintColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return ssb
    }

    // In-place case update without recreating any Views
    private fun updateKeyCase() {
        if (currentMode != KeyboardMode.LETTERS) return
        val isUpper = isShifted || isCapsLock
        shiftButton?.text = if (isCapsLock) "⇪" else if (isShifted) "⬆" else "⇧"
        for ((btn, baseChar) in letterButtons) {
            val displayChar = if (isUpper) baseChar.uppercase() else baseChar.lowercase()
            val secondary = secondaryMap[baseChar]
            btn.text = buildKeyLabel(displayChar, secondary)
        }
    }

    private fun captureInitialClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()
        if (!clip.isNullOrEmpty() && !recentClips.contains(clip)) {
            recentClips.add(0, clip)
            if (recentClips.size > 8) {
                recentClips.removeAt(recentClips.lastIndex)
            }
        }
    }

    private fun setupToolbar() {
        val btnFold = rootView.findViewById<TextView>(R.id.action_fold)
        val btnVoice = rootView.findViewById<TextView>(R.id.action_voice)
        val btnClips = rootView.findViewById<TextView>(R.id.action_clips)
        val btnQuick = rootView.findViewById<TextView>(R.id.action_quick)
        val btnTranslate = rootView.findViewById<TextView>(R.id.action_translate)
        val btnApps = rootView.findViewById<TextView>(R.id.action_apps)
        val btnFancy = rootView.findViewById<TextView>(R.id.action_fancy)
        val btnFocus = rootView.findViewById<TextView>(R.id.action_focus)
        val btnOneHand = rootView.findViewById<TextView>(R.id.action_one_hand)
        val btnSettings = rootView.findViewById<TextView>(R.id.action_settings)
        val btnUndo = rootView.findViewById<TextView>(R.id.action_undo)
        val btnSwitch = rootView.findViewById<TextView>(R.id.action_switch_ime)

        btnFold?.setOnClickListener {
            feedback(it)
            toggleFold(true)
        }

        btnUnfold?.setOnClickListener {
            feedback(it)
            toggleFold(false)
        }

        btnVoice?.setOnClickListener {
            feedback(it)
            try {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to type with iKeyboard...")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (_: Exception) {
                currentInputConnection?.commitText("🎙️ ", 1)
            }
        }

        btnClips?.setOnClickListener {
            feedback(it)
            showFeatureDrawer("clips")
        }

        btnQuick?.setOnClickListener {
            feedback(it)
            showFeatureDrawer("quick")
        }

        btnTranslate?.setOnClickListener {
            feedback(it)
            showFeatureDrawer("translate")
        }

        btnApps?.setOnClickListener {
            feedback(it)
            showFeatureDrawer("apps")
        }

        btnFancy?.setOnClickListener {
            feedback(it)
            showFeatureDrawer("font")
        }

        btnFocus?.setOnClickListener {
            feedback(it)
            currentMode = if (currentMode == KeyboardMode.CURSOR_DPAD) KeyboardMode.LETTERS else KeyboardMode.CURSOR_DPAD
            btnFocus.text = if (currentMode == KeyboardMode.CURSOR_DPAD) "🔤" else "🎯"
            renderKeyboard()
        }

        btnOneHand?.setOnClickListener {
            feedback(it)
            toggleFloatMode(it as TextView)
        }

        btnSettings?.setOnClickListener {
            feedback(it)
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }

        btnUndo?.setOnClickListener {
            feedback(it)
            if (lastDeletedText.isNotEmpty()) {
                currentInputConnection?.commitText(lastDeletedText, 1)
                lastDeletedText = ""
            }
        }

        btnSwitch?.setOnClickListener {
            feedback(it)
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showInputMethodPicker()
        }
    }

    private fun toggleFold(folded: Boolean) {
        if (folded) {
            topToolbarScroll.visibility = View.GONE
            smartStripFrame.visibility = View.GONE
            foldBar.visibility = View.VISIBLE
        } else {
            topToolbarScroll.visibility = View.VISIBLE
            smartStripFrame.visibility = View.VISIBLE
            foldBar.visibility = View.GONE
        }
    }

    private fun toggleFloatMode(btn: TextView) {
        floatMode = (floatMode + 1) % 3
        btn.text = "🪟"
        val density = resources.displayMetrics.density
        val dockMargin = (68 * density).toInt()
        val params = keyboardRoot.layoutParams as FrameLayout.LayoutParams
        when (floatMode) {
            0 -> {
                params.marginStart = 0
                params.marginEnd = 0
            }
            1 -> {
                params.marginStart = dockMargin
                params.marginEnd = 0
            }
            2 -> {
                params.marginStart = 0
                params.marginEnd = dockMargin
            }
        }
        keyboardRoot.layoutParams = params
    }

    private fun applyColorTheme() {
        val rootBg = when (prefs.theme) {
            "amoled" -> Color.BLACK
            "neon" -> Color.parseColor("#0A0E17")
            else -> ContextCompat.getColor(this, R.color.kb_bg)
        }
        rootView.setBackgroundColor(rootBg)
    }

    private fun showFeatureDrawer(type: String) {
        if (activeDrawerType == type && featureDrawerScroll.visibility == View.VISIBLE) {
            closeFeatureDrawer()
            return
        }
        activeDrawerType = type
        featureDrawerScroll.visibility = View.VISIBLE
        smartIdleScroll.visibility = View.GONE
        suggestionTypingBar.visibility = View.GONE
        featureDrawerContainer.removeAllViews()

        // ✕ Close chip
        val closeBtn = TextView(this).apply {
            text = "✕ Close"
            setTextColor(ContextCompat.getColor(this@KeyboardIME, R.color.kb_accent))
            background = ContextCompat.getDrawable(this@KeyboardIME, R.drawable.bg_toolbar_pill)
            textSize = 12f
            setPadding(14, 6, 14, 6)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 8, 0) }
            setOnClickListener {
                feedback(it)
                closeFeatureDrawer()
            }
        }
        featureDrawerContainer.addView(closeBtn)

        when (type) {
            "quick" -> {
                for (template in quickReplies.templates) {
                    val chip = TextView(this).apply {
                        text = "💬 $template"
                        setTextColor(ContextCompat.getColor(this@KeyboardIME, R.color.kb_text_primary))
                        background = ContextCompat.getDrawable(this@KeyboardIME, R.drawable.bg_toolbar_pill)
                        textSize = 12f
                        setPadding(14, 6, 14, 6)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, 0, 8, 0) }
                        setOnClickListener {
                            feedback(it)
                            currentInputConnection?.commitText("$template ", 1)
                            closeFeatureDrawer()
                        }
                    }
                    featureDrawerContainer.addView(chip)
                }
            }
            "clips" -> {
                captureInitialClipboard()
                if (recentClips.isNotEmpty()) {
                    val clearBtn = TextView(this).apply {
                        text = "🗑 Clear"
                        setTextColor(Color.parseColor("#EF4444"))
                        background = ContextCompat.getDrawable(this@KeyboardIME, R.drawable.bg_toolbar_pill)
                        textSize = 12f
                        setPadding(12, 6, 12, 6)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, 0, 8, 0) }
                        setOnClickListener {
                            feedback(it)
                            recentClips.clear()
                            showFeatureDrawer("clips")
                        }
                    }
                    featureDrawerContainer.addView(clearBtn)

                    for (clip in recentClips) {
                        val chip = TextView(this).apply {
                            text = if (clip.length > 25) clip.take(22) + "..." else clip
                            setTextColor(ContextCompat.getColor(this@KeyboardIME, R.color.kb_text_primary))
                            background = ContextCompat.getDrawable(this@KeyboardIME, R.drawable.bg_toolbar_pill)
                            textSize = 12f
                            setPadding(14, 6, 14, 6)
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { setMargins(0, 0, 8, 0) }
                            setOnClickListener {
                                feedback(it)
                                currentInputConnection?.commitText(clip, 1)
                                closeFeatureDrawer()
                            }
                        }
                        featureDrawerContainer.addView(chip)
                    }
                } else {
                    val emptyTv = TextView(this).apply {
                        text = "No clipboard history"
                        setTextColor(ContextCompat.getColor(this@KeyboardIME, R.color.kb_text_secondary))
                        textSize = 12f
                        setPadding(12, 6, 12, 6)
                    }
                    featureDrawerContainer.addView(emptyTv)
                }
            }
            "translate" -> {
                val phrases = listOf(
                    "Salam (Peace)" to "Salam",
                    "Marhaban (Hello)" to "Marhaban",
                    "Shukran (Thanks)" to "Shukran",
                    "Afwan (Welcome)" to "Afwan",
                    "Bonjour (Hello)" to "Bonjour",
                    "Merci (Thanks)" to "Merci",
                    "De rien (Welcome)" to "De rien",
                    "Hola (Hello)" to "Hola",
                    "Gracias (Thanks)" to "Gracias",
                    "De nada (Welcome)" to "De nada"
                )
                for ((label, phrase) in phrases) {
                    val chip = TextView(this).apply {
                        text = "🌐 $label"
                        setTextColor(ContextCompat.getColor(this@KeyboardIME, R.color.kb_text_primary))
                        background = ContextCompat.getDrawable(this@KeyboardIME, R.drawable.bg_toolbar_pill)
                        textSize = 12f
                        setPadding(14, 6, 14, 6)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, 0, 8, 0) }
                        setOnClickListener {
                            feedback(it)
                            currentInputConnection?.commitText("$phrase ", 1)
                            closeFeatureDrawer()
                        }
                    }
                    featureDrawerContainer.addView(chip)
                }
            }
            "apps" -> {
                val allApps = appLauncher.getAllApps()
                if (allApps.isNotEmpty()) {
                    for (app in allApps) {
                        val chip = TextView(this).apply {
                            text = "🚀 ${app.name}"
                            setTextColor(ContextCompat.getColor(this@KeyboardIME, R.color.kb_text_primary))
                            background = ContextCompat.getDrawable(this@KeyboardIME, R.drawable.bg_toolbar_pill)
                            textSize = 12f
                            setPadding(14, 6, 14, 6)
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { setMargins(0, 0, 8, 0) }
                            setOnClickListener {
                                feedback(it)
                                appLauncher.launchApp(app.packageName)
                                closeFeatureDrawer()
                            }
                        }
                        featureDrawerContainer.addView(chip)
                    }
                } else {
                    val emptyTv = TextView(this).apply {
                        text = "No launchable apps found"
                        setTextColor(ContextCompat.getColor(this@KeyboardIME, R.color.kb_text_secondary))
                        textSize = 12f
                        setPadding(12, 6, 12, 6)
                    }
                    featureDrawerContainer.addView(emptyTv)
                }
            }
            "font" -> {
                val styles = listOf(
                    "Normal" to null,
                    "𝗕𝗼𝗹𝗱" to FancyFontConverter.Style.BOLD_SANS,
                    "𝘐𝘵𝘢𝘭𝘪𝘤" to FancyFontConverter.Style.ITALIC_SANS,
                    "𝙼𝚘𝚗𝚘" to FancyFontConverter.Style.MONOSPACE,
                    "𝔻𝕠𝕦𝕓𝕝𝕖" to FancyFontConverter.Style.DOUBLE_STRUCK
                )
                for ((label, style) in styles) {
                    val chip = TextView(this).apply {
                        text = if (activeFancyStyle == style) "✓ $label" else label
                        setTextColor(if (activeFancyStyle == style) ContextCompat.getColor(this@KeyboardIME, R.color.kb_accent) else ContextCompat.getColor(this@KeyboardIME, R.color.kb_text_primary))
                        background = ContextCompat.getDrawable(this@KeyboardIME, R.drawable.bg_toolbar_pill)
                        textSize = 12f
                        setPadding(14, 6, 14, 6)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, 0, 8, 0) }
                        setOnClickListener {
                            feedback(it)
                            activeFancyStyle = style
                            closeFeatureDrawer()
                        }
                    }
                    featureDrawerContainer.addView(chip)
                }
            }
        }
    }

    private fun closeFeatureDrawer() {
        activeDrawerType = null
        featureDrawerScroll.visibility = View.GONE
        if (composingWord.isNotEmpty()) {
            suggestionTypingBar.visibility = View.VISIBLE
            smartIdleScroll.visibility = View.GONE
        } else {
            suggestionTypingBar.visibility = View.GONE
            smartIdleScroll.visibility = View.VISIBLE
            updateSmartIdleBar()
        }
    }

    private fun updateSmartIdleBar() {
        if (activeDrawerType != null) return
        featureDrawerScroll.visibility = View.GONE
        suggestionTypingBar.visibility = View.GONE
        smartIdleScroll.visibility = View.VISIBLE
        smartIdleContainer.removeAllViews()

        // 1. Instant Paste chip if clipboard has content
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()
        if (!clip.isNullOrEmpty()) {
            val preview = if (clip.length > 15) clip.take(13) + "..." else clip
            val pasteChip = TextView(this).apply {
                text = "📋 Paste: \"$preview\""
                setTextColor(ContextCompat.getColor(this@KeyboardIME, R.color.kb_accent))
                background = ContextCompat.getDrawable(this@KeyboardIME, R.drawable.bg_toolbar_pill)
                textSize = 12f
                setPadding(12, 6, 12, 6)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 6, 0) }
                setOnClickListener {
                    feedback(it)
                    currentInputConnection?.commitText(clip, 1)
                }
            }
            smartIdleContainer.addView(pasteChip)
        }

        // 2. Quick phrase shortcuts
        val quickPhrases = listOf("Hi! 👋", "Thanks! 🙏", "On my way 🚗", "Sounds good 👍", "OK 👌")
        for (qp in quickPhrases) {
            val qpChip = TextView(this).apply {
                text = qp
                setTextColor(ContextCompat.getColor(this@KeyboardIME, R.color.kb_text_primary))
                background = ContextCompat.getDrawable(this@KeyboardIME, R.drawable.bg_toolbar_pill)
                textSize = 12f
                setPadding(12, 6, 12, 6)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 6, 0) }
                setOnClickListener {
                    feedback(it)
                    currentInputConnection?.commitText("$qp ", 1)
                }
            }
            smartIdleContainer.addView(qpChip)
        }

        // 3. Top frequent emojis
        val topEmojis = listOf("😊", "😂", "👍", "❤️", "🔥", "🎉", "✨")
        for (emoji in topEmojis) {
            val emojiChip = TextView(this).apply {
                text = emoji
                setTextColor(ContextCompat.getColor(this@KeyboardIME, R.color.kb_text_primary))
                background = ContextCompat.getDrawable(this@KeyboardIME, R.drawable.bg_toolbar_pill)
                textSize = 13f
                setPadding(10, 5, 10, 5)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 6, 0) }
                setOnClickListener {
                    feedback(it)
                    currentInputConnection?.commitText(emoji, 1)
                }
            }
            smartIdleContainer.addView(emojiChip)
        }
    }

    private fun setupSuggestions() {
        suggestionLeft.setOnClickListener {
            applySuggestion(suggestionLeft.text.toString())
        }
        suggestionCenter.setOnClickListener {
            val pkg = pendingLaunchPackage
            if (pkg != null) {
                appLauncher.launchApp(pkg)
                clearSuggestions()
                pendingLaunchPackage = null
                return@setOnClickListener
            }
            applySuggestion(suggestionCenter.text.toString())
        }
        suggestionRight.setOnClickListener {
            applySuggestion(suggestionRight.text.toString())
        }
    }

    private fun updateSuggestions() {
        if (!prefs.autoCorrect || currentMode == KeyboardMode.NUMPAD) {
            clearSuggestions()
            return
        }

        val query = composingWord.toString()
        if (query.isEmpty()) {
            clearSuggestions()
            return
        }

        // Active text being typed: switch to typing suggestion bar
        featureDrawerScroll.visibility = View.GONE
        smartIdleScroll.visibility = View.GONE
        suggestionTypingBar.visibility = View.VISIBLE

        val matchedApp = appLauncher.findMatchingApp(query)
        if (matchedApp != null) {
            suggestionLeft.text = query
            suggestionCenter.text = "🚀 Open ${matchedApp.name}"
            suggestionCenter.setTextColor(Color.parseColor("#38BDF8"))
            suggestionRight.text = ""
            pendingLaunchPackage = matchedApp.packageName
            currentCenterSuggestion = ""
            return
        }
        pendingLaunchPackage = null
        suggestionCenter.setTextColor(ContextCompat.getColor(this, R.color.accent))

        val convertedUnit = unitConverter.convert(query)
        if (convertedUnit != null) {
            suggestionLeft.text = query
            suggestionCenter.text = "💱 $convertedUnit"
            suggestionRight.text = ""
            currentCenterSuggestion = convertedUnit
            return
        }

        val mathResult = mathCalc.evaluate(query)
        if (mathResult != null) {
            suggestionLeft.text = query
            suggestionCenter.text = "= $mathResult"
            suggestionRight.text = ""
            currentCenterSuggestion = mathResult
            return
        }

        val translation = translator.translate(query, prefs.secondaryLanguage)
        if (translation != null) {
            suggestionLeft.text = query
            suggestionCenter.text = "🌐 $translation"
            suggestionRight.text = ""
            currentCenterSuggestion = translation
            return
        }

        val result = dictionary.getSuggestions(query)
        suggestionLeft.text = result.exact
        suggestionCenter.text = result.topMatch
        suggestionRight.text = result.alternative
        currentCenterSuggestion = result.topMatch
    }

    private fun clearSuggestions() {
        suggestionLeft.text = ""
        suggestionCenter.text = ""
        suggestionRight.text = ""
        currentCenterSuggestion = ""
        pendingLaunchPackage = null
        if (activeDrawerType == null) {
            updateSmartIdleBar()
        }
    }

    private fun applySuggestion(word: String) {
        if (word.isEmpty()) return
        val ic = currentInputConnection ?: return

        val len = composingWord.length
        if (len > 0) {
            ic.deleteSurroundingText(len, 0)
        }
        val cleanWord = when {
            word.startsWith("💱") -> word.substring(2).trim()
            word.startsWith("🌐") -> word.substring(2).trim()
            word.startsWith("=") -> word.substring(1).trim()
            else -> word
        }

        ic.commitText("$cleanWord ", 1)
        dictionary.learnWord(cleanWord)
        composingWord.setLength(0)
        clearSuggestions()
        checkAutoCapitalization()
    }

    private fun finishWordCommit() {
        if (composingWord.isNotEmpty()) {
            dictionary.learnWord(composingWord.toString())
            composingWord.setLength(0)
        }
        clearSuggestions()
        checkAutoCapitalization()
    }

    private fun getSelectedTypeface(): Typeface {
        val base = when (prefs.typefaceStyle) {
            "mono" -> Typeface.MONOSPACE
            "serif" -> Typeface.SERIF
            else -> Typeface.SANS_SERIF
        }
        return Typeface.create(base, Typeface.BOLD)
    }

    private fun showKeyPopup(anchorView: View, charText: String) {
        if (!prefs.keyPopupsEnabled || isStealthMode) return
        keyPopupPreview.text = charText

        // Compute anchor view position relative to rootView (keyboard_outer_frame)
        val anchorLoc = IntArray(2)
        val rootLoc = IntArray(2)
        anchorView.getLocationOnScreen(anchorLoc)
        rootView.getLocationOnScreen(rootLoc)

        val relativeLeft = anchorLoc[0] - rootLoc[0]
        val relativeTop = anchorLoc[1] - rootLoc[1]

        val popupWidth = keyPopupPreview.layoutParams?.width ?: (56 * resources.displayMetrics.density).toInt()
        val popupHeight = keyPopupPreview.layoutParams?.height ?: (62 * resources.displayMetrics.density).toInt()

        // Center horizontally above the keycap
        val centerX = relativeLeft + (anchorView.width - popupWidth) / 2
        // Float immediately above the keycap (with small overlap for natural finger sightline)
        val topY = (relativeTop - popupHeight + (10 * resources.displayMetrics.density).toInt()).coerceAtLeast(0)

        val params = FrameLayout.LayoutParams(popupWidth, popupHeight).apply {
            leftMargin = centerX.coerceAtLeast(0)
            topMargin = topY
        }
        keyPopupPreview.layoutParams = params
        keyPopupPreview.visibility = View.VISIBLE
    }

    private fun hideKeyPopup() {
        keyPopupPreview.visibility = View.GONE
    }

    private fun renderKeyboard() {
        rowNumbers.removeAllViews()
        row1.removeAllViews()
        row2.removeAllViews()
        row3.removeAllViews()
        row4.removeAllViews()
        letterButtons.clear()
        shiftButton = null

        val density = resources.displayMetrics.density
        val rowHeightPx = (prefs.rowHeightDp * density).toInt()

        row1.layoutParams.height = rowHeightPx
        row2.layoutParams.height = rowHeightPx
        row3.layoutParams.height = rowHeightPx
        row4.layoutParams.height = rowHeightPx

        when (currentMode) {
            KeyboardMode.LETTERS -> {
                renderNumberRow(density)
                renderLettersMode()
                renderBottomRow()
            }
            KeyboardMode.SYMBOLS -> {
                renderNumberRow(density)
                renderSymbolsMode()
                renderBottomRow()
            }
            KeyboardMode.NUMPAD -> {
                rowNumbers.visibility = View.GONE
                renderNumpadMode()
            }
            KeyboardMode.EMOJI -> {
                rowNumbers.visibility = View.GONE
                renderEmojiMode()
            }
            KeyboardMode.CURSOR_DPAD -> {
                rowNumbers.visibility = View.GONE
                renderCursorDpadMode()
            }
        }
    }

    private fun renderNumberRow(density: Float) {
        if (prefs.showNumberRow) {
            rowNumbers.visibility = View.VISIBLE
            rowNumbers.layoutParams.height = ((prefs.rowHeightDp - 6) * density).toInt()
            for (num in numberKeys) {
                val key = createKey(num, weight = 1f) {
                    commitCharacter(num)
                    composingWord.append(num)
                    updateSuggestions()
                }
                rowNumbers.addView(key)
            }
        } else {
            rowNumbers.visibility = View.GONE
        }
    }

    private fun renderLettersMode() {
        if (!isPrimaryLang && prefs.secondaryLanguage == "ar") {
            renderArabicLayout()
            return
        }
        if (!isPrimaryLang && prefs.secondaryLanguage == "fr") {
            renderFrenchLayout()
            return
        }

        val isUpper = isShifted || isCapsLock

        for (char in qwertyRow1) {
            val displayChar = if (isUpper) char.uppercase() else char
            val secondary = secondaryMap[char]
            val key = createGlideKey(char, displayChar, secondary, weight = 1f)
            letterButtons.add(Pair(key, char))
            row1.addView(key)
        }

        val padLeft = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.5f)
        }
        row2.addView(padLeft)
        for (char in qwertyRow2) {
            val displayChar = if (isUpper) char.uppercase() else char
            val secondary = secondaryMap[char]
            val key = createGlideKey(char, displayChar, secondary, weight = 1f)
            letterButtons.add(Pair(key, char))
            row2.addView(key)
        }
        val padRight = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.5f)
        }
        row2.addView(padRight)

        val shiftLabel = if (isCapsLock) "⇪" else if (isShifted) "⬆" else "⇧"
        shiftButton = createActionKey(shiftLabel, weight = 1.5f) {
            if (isShifted && !isCapsLock) {
                isCapsLock = true
            } else if (isCapsLock) {
                isCapsLock = false
                isShifted = false
            } else {
                isShifted = true
            }
            updateKeyCase() // Smooth instant in-place text update without recreate!
        }
        row3.addView(shiftButton)

        for (char in qwertyRow3) {
            val displayChar = if (isUpper) char.uppercase() else char
            val secondary = secondaryMap[char]
            val key = createGlideKey(char, displayChar, secondary, weight = 1f)
            letterButtons.add(Pair(key, char))
            row3.addView(key)
        }

        val backspaceKey = createBackspaceKey(weight = 1.5f)
        row3.addView(backspaceKey)
    }

    private fun renderArabicLayout() {
        for (ch in langManager.arabicRow1) {
            row1.addView(createKey(ch, weight = 1f) {
                commitCharacter(ch)
                composingWord.append(ch)
                updateSuggestions()
            })
        }
        for (ch in langManager.arabicRow2) {
            row2.addView(createKey(ch, weight = 1f) {
                commitCharacter(ch)
                composingWord.append(ch)
                updateSuggestions()
            })
        }
        for (ch in langManager.arabicRow3) {
            row3.addView(createKey(ch, weight = 1f) {
                commitCharacter(ch)
                composingWord.append(ch)
                updateSuggestions()
            })
        }
        row3.addView(createBackspaceKey(weight = 1.5f))
    }

    private fun renderFrenchLayout() {
        val isUpper = isShifted || isCapsLock
        for (ch in langManager.frenchRow1) {
            val d = if (isUpper) ch.uppercase() else ch
            val key = createKey(d, weight = 1f) {
                commitCharacter(d)
                composingWord.append(d)
                updateSuggestions()
                if (isShifted && !isCapsLock) {
                    isShifted = false
                    updateKeyCase()
                }
            }
            letterButtons.add(Pair(key, ch))
            row1.addView(key)
        }
        for (ch in langManager.frenchRow2) {
            val d = if (isUpper) ch.uppercase() else ch
            val key = createKey(d, weight = 1f) {
                commitCharacter(d)
                composingWord.append(d)
                updateSuggestions()
                if (isShifted && !isCapsLock) {
                    isShifted = false
                    updateKeyCase()
                }
            }
            letterButtons.add(Pair(key, ch))
            row2.addView(key)
        }
        for (ch in langManager.frenchRow3) {
            val d = if (isUpper) ch.uppercase() else ch
            val key = createKey(d, weight = 1f) {
                commitCharacter(d)
                composingWord.append(d)
                updateSuggestions()
                if (isShifted && !isCapsLock) {
                    isShifted = false
                    updateKeyCase()
                }
            }
            letterButtons.add(Pair(key, ch))
            row3.addView(key)
        }
        row3.addView(createBackspaceKey(weight = 1.5f))
    }

    private fun renderSymbolsMode() {
        for (sym in symbolsRow1) {
            val key = createKey(sym, weight = 1f) {
                commitCharacter(sym)
                finishWordCommit()
            }
            row1.addView(key)
        }

        for (sym in symbolsRow2) {
            val key = createKey(sym, weight = 1f) {
                commitCharacter(sym)
                finishWordCommit()
            }
            row2.addView(key)
        }

        val padLeft = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.5f)
        }
        row3.addView(padLeft)
        for (sym in symbolsRow3) {
            val key = createKey(sym, weight = 1f) {
                commitCharacter(sym)
                finishWordCommit()
            }
            row3.addView(key)
        }
        val backspaceKey = createBackspaceKey(weight = 1.5f)
        row3.addView(backspaceKey)
    }

    private fun renderNumpadMode() {
        val numpadRows = listOf(
            listOf("C", "(", ")", "/"),
            listOf("7", "8", "9", "*"),
            listOf("4", "5", "6", "-"),
            listOf("1", "2", "3", "+")
        )

        for (i in 0..2) {
            val rList = numpadRows[i]
            val target = listOf(row1, row2, row3)[i]
            for (item in rList) {
                val key = createKey(item, weight = 1f) {
                    if (item == "C") {
                        deleteChar()
                    } else {
                        commitCharacter(item)
                    }
                }
                target.addView(key)
            }
        }

        row4.addView(createActionKey("ABC", weight = 1f) {
            currentMode = KeyboardMode.LETTERS
            renderKeyboard()
        })
        row4.addView(createKey("0", weight = 1f) { commitCharacter("0") })
        row4.addView(createKey(".", weight = 1f) { commitCharacter(".") })
        row4.addView(createKey("+", weight = 1f) { commitCharacter("+") })
        row4.addView(createEnterKey(weight = 1.5f))
    }

    private fun renderEmojiMode() {
        for (emoji in emojiRow1) {
            row1.addView(createKey(emoji, weight = 1f) { commitCharacter(emoji) })
        }
        for (emoji in emojiRow2) {
            row2.addView(createKey(emoji, weight = 1f) { commitCharacter(emoji) })
        }
        for (emoji in emojiRow3) {
            row3.addView(createKey(emoji, weight = 1f) { commitCharacter(emoji) })
        }

        row4.addView(createActionKey("ABC", weight = 1.5f) {
            currentMode = KeyboardMode.LETTERS
            renderKeyboard()
        })
        row4.addView(createKey("❤️", weight = 1f) { commitCharacter("❤️") })
        row4.addView(createSpacebarKey(weight = 3.5f))
        row4.addView(createBackspaceKey(weight = 1.5f))
        row4.addView(createEnterKey(weight = 1.5f))
    }

    private fun sendDpad(keyCode: Int) {
        val ic = currentInputConnection ?: return
        val eventDown = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val eventUp = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        ic.sendKeyEvent(eventDown)
        ic.sendKeyEvent(eventUp)
        sendDownUpKeyEvents(keyCode)
    }

    private fun renderCursorDpadMode() {
        // Row 1: Select All | ▲ Up | Copy | Cut
        row1.addView(createActionKey("Select All", weight = 1.3f) {
            currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
        })
        row1.addView(createActionKey("▲ Up", weight = 1.4f) {
            sendDpad(KeyEvent.KEYCODE_DPAD_UP)
        })
        row1.addView(createActionKey("Copy", weight = 1.2f) {
            currentInputConnection?.performContextMenuAction(android.R.id.copy)
        })
        row1.addView(createActionKey("Cut", weight = 1.1f) {
            currentInputConnection?.performContextMenuAction(android.R.id.cut)
        })

        // Row 2: ◀ Left | ▼ Down | Right ▶ | Paste
        row2.addView(createActionKey("◀ Left", weight = 1.3f) {
            sendDpad(KeyEvent.KEYCODE_DPAD_LEFT)
        })
        row2.addView(createActionKey("▼ Down", weight = 1.4f) {
            sendDpad(KeyEvent.KEYCODE_DPAD_DOWN)
        })
        row2.addView(createActionKey("Right ▶", weight = 1.3f) {
            sendDpad(KeyEvent.KEYCODE_DPAD_RIGHT)
        })
        row2.addView(createActionKey("Paste", weight = 1.1f) {
            currentInputConnection?.performContextMenuAction(android.R.id.paste)
        })

        // Row 3: ◀◀ Word | Clear | Word ▶▶ | Backspace
        row3.addView(createActionKey("◀◀ Word", weight = 1.2f) {
            val ic = currentInputConnection ?: return@createActionKey
            ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, 0, KeyEvent.META_CTRL_ON))
            ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT, 0, KeyEvent.META_CTRL_ON))
            sendDpad(KeyEvent.KEYCODE_DPAD_LEFT)
        })
        row3.addView(createActionKey("Clear", weight = 1.2f) {
            val ic = currentInputConnection ?: return@createActionKey
            ic.deleteSurroundingText(100, 100)
        })
        row3.addView(createActionKey("Word ▶▶", weight = 1.2f) {
            val ic = currentInputConnection ?: return@createActionKey
            ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 0, KeyEvent.META_CTRL_ON))
            ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, 0, KeyEvent.META_CTRL_ON))
            sendDpad(KeyEvent.KEYCODE_DPAD_RIGHT)
        })
        row3.addView(createBackspaceKey(weight = 1.4f))

        // Row 4: ABC | Space | Enter
        row4.addView(createActionKey("ABC", weight = 1.5f) {
            currentMode = KeyboardMode.LETTERS
            renderKeyboard()
        })
        row4.addView(createSpacebarKey(weight = 3.5f))
        row4.addView(createEnterKey(weight = 1.5f))
    }

    private fun renderBottomRow() {
        val modeLabel = if (currentMode == KeyboardMode.LETTERS) "?123" else "ABC"
        val modeKey = createActionKey(modeLabel, weight = 1.2f) {
            currentMode = if (currentMode == KeyboardMode.LETTERS) KeyboardMode.SYMBOLS else KeyboardMode.LETTERS
            renderKeyboard()
        }
        row4.addView(modeKey)

        val langLabel = if (isPrimaryLang) "🌐 ${prefs.secondaryLanguage.uppercase()}" else "🌐 EN"
        val langToggleKey = createActionKey(langLabel, weight = 1.3f) {
            isPrimaryLang = !isPrimaryLang
            renderKeyboard()
        }
        row4.addView(langToggleKey)

        val emojiKey = createActionKey("😀", weight = 0.9f) {
            currentMode = KeyboardMode.EMOJI
            renderKeyboard()
        }
        row4.addView(emojiKey)

        val commaKey = createKey(",", weight = 0.8f) {
            commitCharacter(",")
            finishWordCommit()
        }
        row4.addView(commaKey)

        val spaceKey = createSpacebarKey(weight = 3.5f)
        row4.addView(spaceKey)

        val periodKey = createKey(".", weight = 0.8f) {
            commitCharacter(".")
            finishWordCommit()
        }
        row4.addView(periodKey)

        val enterKey = createEnterKey(weight = 1.6f)
        row4.addView(enterKey)
    }

    private fun createKey(text: String, weight: Float, onClick: () -> Unit): Button {
        return Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                setMargins(2, 2, 2, 2)
            }
            this.text = text
            val textColor = if (prefs.theme == "neon") Color.parseColor("#00F2FE") else ContextCompat.getColor(this@KeyboardIME, R.color.kb_text_primary)
            setTextColor(textColor)
            textSize = prefs.fontSizeSp
            typeface = getSelectedTypeface()
            background = ContextCompat.getDrawable(this@KeyboardIME, R.drawable.bg_key)
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    feedback(v)
                }
                false
            }
            setOnClickListener {
                onClick()
            }
        }
    }

    private fun createGlideKey(baseChar: String, displayChar: String, secondary: String?, weight: Float): Button {
        val btn = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                setMargins(2, 2, 2, 2)
            }
            text = buildKeyLabel(displayChar, secondary)
            val textColor = if (prefs.theme == "neon") Color.parseColor("#00F2FE") else ContextCompat.getColor(this@KeyboardIME, R.color.kb_text_primary)
            setTextColor(textColor)
            textSize = prefs.fontSizeSp
            typeface = getSelectedTypeface()
            background = ContextCompat.getDrawable(this@KeyboardIME, R.drawable.bg_key)
            isAllCaps = false
            setPadding(0, 0, 0, 0)
        }

        var startX = 0f
        var startY = 0f
        var isLongPressed = false
        val longPressRunnable = Runnable {
            if (!secondary.isNullOrEmpty() && !isGliding) {
                isLongPressed = true
                feedback(btn)
                commitCharacter(secondary)
                finishWordCommit()
            }
        }

        btn.setOnTouchListener { v, event ->
            val isUpper = isShifted || isCapsLock
            val charLetter = if (isUpper) baseChar.uppercase() else baseChar.lowercase()
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    isLongPressed = false
                    isGliding = false
                    glideChars.clear()
                    baseChar.firstOrNull()?.let { glideChars.add(it) }
                    showKeyPopup(btn, charLetter)
                    feedback(v)
                    handler.postDelayed(longPressRunnable, prefs.longPressDelayMs)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = abs(event.x - startX)
                    val dy = abs(event.y - startY)
                    if (dx > 30f || dy > 30f) {
                        handler.removeCallbacks(longPressRunnable)
                        hideKeyPopup()
                        isGliding = true
                        val touchedChar = findKeyCharUnderTouch(event.rawX, event.rawY)
                        if (touchedChar != null && (glideChars.isEmpty() || glideChars.last() != touchedChar)) {
                            glideChars.add(touchedChar)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    hideKeyPopup()
                    if (isLongPressed) {
                        return@setOnTouchListener true
                    }
                    if (isGliding && glideChars.size >= 3) {
                        val matchedWord = dictionary.matchGlidePath(glideChars)
                        if (!matchedWord.isNullOrEmpty()) {
                            feedback(v)
                            val formattedWord = formatWithFancyStyle(matchedWord)
                            currentInputConnection?.commitText("$formattedWord ", 1)
                            dictionary.learnWord(matchedWord)
                            finishWordCommit()
                            glideChars.clear()
                            isGliding = false
                            return@setOnTouchListener true
                        }
                    }

                    val formatted = formatWithFancyStyle(charLetter)
                    commitCharacter(formatted)
                    composingWord.append(charLetter)
                    updateSuggestions()
                    if (isShifted && !isCapsLock) {
                        isShifted = false
                        updateKeyCase() // In-place smooth update without view destruction!
                    }
                    glideChars.clear()
                    isGliding = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    hideKeyPopup()
                    glideChars.clear()
                    isGliding = false
                    true
                }
                else -> false
            }
        }
        return btn
    }

    private fun formatWithFancyStyle(str: String): String {
        val style = activeFancyStyle ?: return str
        return fancyConverter.convert(str, style)
    }

    private fun findKeyCharUnderTouch(rawX: Float, rawY: Float): Char? {
        val rows = listOf(row1, row2, row3)
        for (row in rows) {
            val location = IntArray(2)
            row.getLocationOnScreen(location)
            val rowY = location[1]
            if (rawY >= rowY && rawY <= rowY + row.height) {
                for (i in 0 until row.childCount) {
                    val child = row.getChildAt(i)
                    if (child is Button) {
                        val childLoc = IntArray(2)
                        child.getLocationOnScreen(childLoc)
                        if (rawX >= childLoc[0] && rawX <= childLoc[0] + child.width) {
                            return child.text.firstOrNull()
                        }
                    }
                }
            }
        }
        return null
    }

    private fun createSpacebarKey(weight: Float): Button {
        val langLabel = if (isPrimaryLang) "EN" else prefs.secondaryLanguage.uppercase()
        val btn = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                setMargins(2, 2, 2, 2)
            }
            text = "Space  •  $langLabel"
            setTextColor(ContextCompat.getColor(this@KeyboardIME, R.color.kb_text_secondary))
            textSize = 13f
            background = ContextCompat.getDrawable(this@KeyboardIME, R.drawable.bg_key)
            isAllCaps = false
            setPadding(0, 0, 0, 0)
        }

        var startX = 0f
        var totalMovedX = 0f
        val swipeThreshold = 35f

        btn.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    totalMovedX = 0f
                    feedback(v)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.x - startX
                    if (abs(deltaX) >= swipeThreshold) {
                        if (deltaX > 0) {
                            sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
                        } else {
                            sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT)
                        }
                        feedback(v)
                        totalMovedX += deltaX
                        startX = event.x
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(totalMovedX) < swipeThreshold) {
                        handleSpacebarCommit()
                    }
                    true
                }
                else -> false
            }
        }
        return btn
    }

    private fun handleSpacebarCommit() {
        val now = SystemClock.uptimeMillis()
        val query = composingWord.toString()

        if (prefs.doubleTapPeriod && (now - lastSpaceTapTime < 400L) && composingWord.isEmpty()) {
            val ic = currentInputConnection
            if (ic != null) {
                val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
                if (before.endsWith(" ")) {
                    ic.deleteSurroundingText(1, 0)
                    ic.commitText(". ", 1)
                    lastSpaceTapTime = 0L
                    checkAutoCapitalization()
                    return
                }
            }
        }
        lastSpaceTapTime = now

        val shortcut = dictionary.checkShortcut(query)
        if (shortcut != null) {
            val len = composingWord.length
            if (len > 0) {
                currentInputConnection?.deleteSurroundingText(len, 0)
            }
            currentInputConnection?.commitText("$shortcut ", 1)
            composingWord.setLength(0)
            clearSuggestions()
            checkAutoCapitalization()
            return
        }

        // Keep what the user typed! Only suggest in strip for user to tap if wanted.
        commitCharacter(" ")
        finishWordCommit()
    }

    private fun createActionKey(text: String, weight: Float, onClick: () -> Unit): Button {
        return Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                setMargins(2, 2, 2, 2)
            }
            this.text = text
            val textColor = if (prefs.theme == "neon") Color.parseColor("#38BDF8") else ContextCompat.getColor(this@KeyboardIME, R.color.kb_text_primary)
            setTextColor(textColor)
            textSize = 17f
            typeface = getSelectedTypeface()
            background = ContextCompat.getDrawable(this@KeyboardIME, R.drawable.bg_key_action)
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    feedback(v)
                }
                false
            }
            setOnClickListener {
                onClick()
            }
        }
    }

    private fun createBackspaceKey(weight: Float): Button {
        val btn = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                setMargins(2, 2, 2, 2)
            }
            text = "⌫"
            setTextColor(ContextCompat.getColor(this@KeyboardIME, R.color.kb_text_primary))
            textSize = 20f
            typeface = getSelectedTypeface()
            background = ContextCompat.getDrawable(this@KeyboardIME, R.drawable.bg_key_action)
            setPadding(0, 0, 0, 0)
        }

        var startX = 0f
        btn.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    feedback(v)
                    deleteChar()
                    backspaceRunnable = object : Runnable {
                        override fun run() {
                            deleteChar()
                            handler.postDelayed(this, 50)
                        }
                    }
                    handler.postDelayed(backspaceRunnable!!, 350)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    backspaceRunnable?.let { handler.removeCallbacks(it) }
                    backspaceRunnable = null
                    val deltaX = startX - event.x
                    if (deltaX > 70f) {
                        deletePreviousWord()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    backspaceRunnable?.let { handler.removeCallbacks(it) }
                    backspaceRunnable = null
                    true
                }
                else -> false
            }
        }
        return btn
    }

    private fun deletePreviousWord() {
        val ic = currentInputConnection ?: return
        val textBefore = ic.getTextBeforeCursor(30, 0)?.toString() ?: ""
        if (textBefore.isNotEmpty()) {
            val trimmed = textBefore.trimEnd()
            val lastSpaceIndex = trimmed.lastIndexOfAny(charArrayOf(' ', '\n', '\t', '.', ',', '!', '?'))
            val deleteCount = if (lastSpaceIndex == -1) {
                textBefore.length
            } else {
                textBefore.length - lastSpaceIndex - 1
            }
            if (deleteCount > 0) {
                lastDeletedText = textBefore.takeLast(deleteCount)
                ic.deleteSurroundingText(deleteCount, 0)
            }
        }
        composingWord.setLength(0)
        clearSuggestions()
    }

    private fun createEnterKey(weight: Float): Button {
        return Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                setMargins(2, 2, 2, 2)
            }
            text = "↵"
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = getSelectedTypeface()
            background = ContextCompat.getDrawable(this@KeyboardIME, R.drawable.bg_key_enter)
            setPadding(0, 0, 0, 0)
            setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    feedback(v)
                }
                false
            }
            setOnClickListener {
                finishWordCommit()
                handleEnter()
            }
        }
    }

    private fun commitCharacter(char: String) {
        currentInputConnection?.commitText(char, 1)
    }

    private fun deleteChar() {
        val ic = currentInputConnection ?: return
        val selectedText = ic.getSelectedText(0)
        if (!selectedText.isNullOrEmpty()) {
            lastDeletedText = selectedText.toString()
            ic.commitText("", 1)
        } else {
            val charBefore = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
            if (charBefore.isNotEmpty()) {
                lastDeletedText = charBefore
            }
            ic.deleteSurroundingText(1, 0)
        }
        if (composingWord.isNotEmpty()) {
            composingWord.deleteCharAt(composingWord.length - 1)
            updateSuggestions()
        } else {
            clearSuggestions()
        }
    }

    private fun handleEnter() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo ?: run {
            ic.commitText("\n", 1)
            return
        }

        val action = info.imeOptions and (EditorInfo.IME_MASK_ACTION)
        when (action) {
            EditorInfo.IME_ACTION_DONE -> ic.performEditorAction(EditorInfo.IME_ACTION_DONE)
            EditorInfo.IME_ACTION_GO -> ic.performEditorAction(EditorInfo.IME_ACTION_GO)
            EditorInfo.IME_ACTION_SEARCH -> ic.performEditorAction(EditorInfo.IME_ACTION_SEARCH)
            EditorInfo.IME_ACTION_SEND -> ic.performEditorAction(EditorInfo.IME_ACTION_SEND)
            EditorInfo.IME_ACTION_NEXT -> ic.performEditorAction(EditorInfo.IME_ACTION_NEXT)
            else -> ic.commitText("\n", 1)
        }
    }

    private fun feedback(view: View) {
        if (isStealthMode) return

        // 1. Hardware Linear Motor Vibration (Huawei EMUI / HarmonyOS & Android)
        if (prefs.hapticFeedback) {
            try {
                // View haptic with flag override
                view.isHapticFeedbackEnabled = true
                view.performHapticFeedback(
                    HapticFeedbackConstants.KEYBOARD_TAP,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING or HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                )

                // Direct actuator pulse (crisp 18ms tap for Huawei P70 Ultra)
                val vib = vibrator
                if (vib != null && vib.hasVibrator()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vib.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vib.vibrate(18)
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Hardware Click Sound (SoundPool with instant 0ms latency + AudioManager)
        if (prefs.soundEnabled) {
            try {
                if (clickSoundId != 0) {
                    soundPool?.play(clickSoundId, 0.9f, 0.9f, 1, 0, 1.0f)
                }
                val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                am?.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, 1.0f)
            } catch (_: Exception) {}
        }
    }
}
