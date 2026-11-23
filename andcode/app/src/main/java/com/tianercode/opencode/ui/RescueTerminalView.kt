package com.tianercode.opencode.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.SystemClock
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.tianercode.opencode.runtime.local.LocalRuntimeCommandResult








private val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
private val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT












fun interface RescueCommandExecutor {
    fun execute(command: String, onResult: (LocalRuntimeCommandResult) -> Unit)
}















class RescueTerminalView(
    context: Context,
    private val executor: RescueCommandExecutor,
    private val onReadEngineLog: () -> Unit,
    private val onRestartEngine: () -> Unit,
    private val onClose: () -> Unit,
) : LinearLayout(context) {
    
    private data class TranscriptLine(val text: String, val color: Int)

    
    
    
    
    
    
    
    
    
    private val outputText = OpenCodeTheme.v2TextBase
    private val commandText = OpenCodeTheme.v2TextCodeAccent
    private val okText = OpenCodeTheme.v2StateFgSuccess
    private val errorText = OpenCodeTheme.v2StateFgDanger
    private val mutedText = OpenCodeTheme.v2TextMuted
    private val hostText = OpenCodeTheme.v2TextFaint

    
    private val transcript = ArrayDeque<TranscriptLine>()
    private val output = TextView(context)
    private val outputScroll = ScrollView(context)
    private val input = EditText(context)
    private val runButton = Button(context)
    private val statusLine = TextView(context)

    
    private val composerCard = LinearLayout(context)

    
    private val touchBar = LinearLayout(context)

    private val pasteButton = Button(context)
    private val copyButton = Button(context)
    private val cutButton = Button(context)

    
    private val history = ArrayList<String>()

    
    private var historyCursor = 0

    private val historyUp = Button(context)
    private val historyDown = Button(context)

    
    private var running = false

    
    private var basePaddingLeft = 0
    private var basePaddingTop = 0
    private var basePaddingRight = 0
    private var basePaddingBottom = 0
    private var imePadding = 0
    private val imeWatcher = ViewTreeObserver.OnGlobalLayoutListener { refreshImePadding() }
    private var imePoll: Runnable? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(BACKGROUND_COLOR)
        basePaddingLeft = dp(12)
        basePaddingTop = dp(10)
        basePaddingRight = dp(12)
        basePaddingBottom = dp(8)
        setPadding(basePaddingLeft, basePaddingTop, basePaddingRight, basePaddingBottom)
        
        isClickable = true

        configureOutput()
        configureInput()
        configureRunButton()
        statusLine.apply {
            textSize = 13f
            typeface = HostStyle.sans(context, 440)
            setTextColor(mutedText)
            includeFontPadding = false
            setPadding(0, dp(4), 0, 0)
        }

        addView(buildHeader(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(
            outputScroll,
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) },
        )
        addView(
            buildComposerCard(),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) },
        )
        addView(
            buildTouchBar(),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) },
        )
        addView(statusLine, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        
        input.setOnFocusChangeListener { _, hasFocus ->
            watchImeFor(if (hasFocus) 2_000L else 1_000L)
            refreshImePadding()
        }

        
        
        
        
        
        
        
        updateRunState()
    }

    

    






    fun appendHostOutput(title: String, content: String) {
        appendLine("── $title ──", hostText)
        appendRawOutput(content)
        appendLine("", mutedText)
    }

    

    private fun buildHeader(): View {
        val title =
            TextView(context).apply {
                text = "救援终端"
                
                textSize = 15f
                setTextColor(OpenCodeTheme.v2TextBase)
                typeface = HostStyle.sans(context, 530)
                includeFontPadding = false
                setPadding(0, dp(4), 0, dp(4))
            }
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            
            
            addView(
                headerAction("引擎日志") { onReadEngineLog() },
                LinearLayout.LayoutParams(WRAP_CONTENT, dp(44)),
            )
            addView(
                headerAction("重启引擎") { onRestartEngine() },
                LinearLayout.LayoutParams(WRAP_CONTENT, dp(44)),
            )
            addView(
                headerAction("关闭") { onClose() },
                LinearLayout.LayoutParams(WRAP_CONTENT, dp(44)),
            )
        }
    }

    
    private fun headerAction(
        label: String,
        onClick: () -> Unit,
    ): Button =
        Button(context).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            includeFontPadding = false
            minimumWidth = 0
            minWidth = 0
            minimumHeight = 0
            minHeight = 0
            setPadding(dp(12), 0, dp(12), 0)
            setTextColor(OpenCodeTheme.v2TextMuted)
            typeface = HostStyle.sans(context, 440)
            stateListAnimator = null
            background =
                RippleDrawable(
                    ColorStateList.valueOf(OpenCodeTheme.v2OverlayPressed),
                    GradientDrawable().apply {
                        setColor(android.graphics.Color.TRANSPARENT)
                        cornerRadius = dp(OpenCodeTheme.RADIUS_MD).toFloat()
                    },
                    null,
                )
            setOnClickListener { onClick() }
        }

    private fun configureOutput() {
        output.apply {
            typeface = HostStyle.mono(context)
            
            textSize = 13f
            letterSpacing = -0.04f / 13f
            setTextColor(outputText)
            setLineSpacing(dp(7).toFloat(), 1f)
            
            
            
            
            
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        outputScroll.apply {
            
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            
            
            
            defaultFocusHighlightEnabled = false
            isFocusable = false
            isFocusableInTouchMode = false
            addView(
                output,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
    }

    private fun configureInput() {
        input.apply {
            typeface = HostStyle.mono(context)
            
            
            textSize = 13f
            letterSpacing = -0.04f / 13f
            setTextColor(OpenCodeTheme.v2TextBase)
            setHintTextColor(OpenCodeTheme.v2TextFaint)
            hint = "输入命令，例如 apk info"
            
            isSingleLine = true
            
            
            
            
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            
            background = null
            minimumHeight = dp(44)
            minHeight = dp(44)
            setPadding(0, dp(8), 0, dp(8))
            includeFontPadding = false
            setOnEditorActionListener { _, actionId, event ->
                val send =
                    actionId == EditorInfo.IME_ACTION_SEND ||
                        actionId == EditorInfo.IME_ACTION_DONE ||
                        
                        (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                if (send) {
                    submitInput()
                    true
                } else {
                    false
                }
            }
            
            
            setOnFocusChangeListener { _, hasFocus -> watchImeFor(if (hasFocus) 2_000L else 1_000L) }
        }
    }

    private fun configureRunButton() {
        runButton.apply {
            
            text = "执行 ↑"
            touchBarSubmitStyle()
            setOnClickListener { submitInput() }
        }
    }

    






    private fun buildComposerCard(): View =
        composerCard.apply {
            orientation = VERTICAL
            
            setPadding(0, dp(2), 0, dp(2))
            background =
                GradientDrawable().apply {
                    
                    setColor(OpenCodeTheme.v2BackgroundBase)
                    cornerRadius = dp(OpenCodeTheme.RADIUS_XL).toFloat()
                }
            elevation = HostStyle.dpFloat(context, 2)

            addView(
                LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), 0, dp(16), 0)
                    
                    addView(
                        TextView(context).apply {
                            text = "\$"
                            typeface = HostStyle.mono(context)
                            textSize = 13f
                            setTextColor(OpenCodeTheme.v2TextMuted)
                            setPadding(0, 0, dp(8), 0)
                            includeFontPadding = false
                        },
                        LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT),
                    )
                    addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
            )

            
            
            addView(
                LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), 0, dp(8), 0)
                    pasteButton.apply {
                        text = "粘贴"
                        ghostMutedStyle()
                        setOnClickListener { pasteFromClipboard() }
                    }
                    copyButton.apply {
                        text = "复制"
                        ghostMutedStyle()
                        setOnClickListener { copyOutput() }
                    }
                    cutButton.apply {
                        text = "剪切"
                        ghostMutedStyle()
                        setOnClickListener { cutInput() }
                    }
                    addView(pasteButton, LinearLayout.LayoutParams(WRAP_CONTENT, dp(28)))
                    addView(copyButton, LinearLayout.LayoutParams(WRAP_CONTENT, dp(28)).apply { leftMargin = dp(2) })
                    addView(cutButton, LinearLayout.LayoutParams(WRAP_CONTENT, dp(28)).apply { leftMargin = dp(2) })
                },
                LinearLayout.LayoutParams(MATCH_PARENT, dp(44)),
            )
        }

    
    private fun Button.ghostMutedStyle() {
        textSize = 13f
        isAllCaps = false
        includeFontPadding = false
        minimumWidth = 0
        minWidth = 0
        minimumHeight = 0
        minHeight = 0
        setPadding(dp(11), 0, dp(11), 0)
        setTextColor(OpenCodeTheme.v2TextMuted)
        typeface = HostStyle.sans(context, 440)
        stateListAnimator = null
        background =
            RippleDrawable(
                ColorStateList.valueOf(OpenCodeTheme.v2OverlayPressed),
                GradientDrawable().apply {
                    setColor(android.graphics.Color.TRANSPARENT)
                    cornerRadius = dp(OpenCodeTheme.RADIUS_MD).toFloat()
                },
                null,
            )
    }

    





    private fun recallHistory(delta: Int) {
        if (history.isEmpty()) return
        val next = (historyCursor + delta).coerceIn(0, history.size)
        if (next == historyCursor) return
        historyCursor = next
        input.setText(if (next == history.size) "" else history[next])
        input.setSelection(input.text?.length ?: 0)
        updateHistoryButtons()
    }

    




    private fun updateHistoryButtons() {
        historyUp.isEnabled = historyCursor < history.size
        historyDown.isEnabled = historyCursor > 0
        historyUp.alpha = if (historyUp.isEnabled) 1f else 0.35f
        historyDown.alpha = if (historyDown.isEnabled) 1f else 0.35f
    }

    











    private fun buildTouchBar(): View =
        touchBar.apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background =
                GradientDrawable().apply {
                    setColor(OpenCodeTheme.v2BackgroundBase)
                    cornerRadius = dp(OpenCodeTheme.RADIUS_XL).toFloat()
                    setStroke(dp(1), OpenCodeTheme.v2BorderBase)
                }

            historyUp.apply {
                text = "↑"
                touchBarKeyStyle()
                setOnClickListener { recallHistory(+1) }
            }
            historyDown.apply {
                text = "↓"
                touchBarKeyStyle()
                setOnClickListener { recallHistory(-1) }
            }
            listOf(
                historyUp,
                historyDown,
                touchBarKey("←") { moveCursor(-1) },
                touchBarKey("→") { moveCursor(+1) },
                touchBarKey("/") { insertAtCursor("/") },
            ).forEachIndexed { index, key ->
                addView(
                    key,
                    LinearLayout.LayoutParams(dp(44), dp(44)).apply { if (index > 0) leftMargin = dp(4) },
                )
            }
            updateHistoryButtons()

            addView(View(context), LinearLayout.LayoutParams(0, dp(1), 1f))
            addView(runButton, LinearLayout.LayoutParams(WRAP_CONTENT, dp(44)).apply { leftMargin = dp(4) })
        }

    
    private fun Button.touchBarKeyStyle() {
        textSize = 17f
        isAllCaps = false
        includeFontPadding = false
        minimumWidth = 0
        minWidth = 0
        minimumHeight = 0
        minHeight = 0
        setPadding(0, 0, 0, 0)
        setTextColor(OpenCodeTheme.v2IconMuted)
        typeface = HostStyle.mono(context)
        gravity = Gravity.CENTER
        stateListAnimator = null
        background =
            RippleDrawable(
                ColorStateList.valueOf(OpenCodeTheme.v2OverlayPressed),
                GradientDrawable().apply {
                    setColor(android.graphics.Color.TRANSPARENT)
                    cornerRadius = dp(OpenCodeTheme.RADIUS_LG).toFloat()
                },
                null,
            )
    }

    private fun touchBarKey(
        label: String,
        onClick: () -> Unit,
    ): Button =
        Button(context).apply {
            text = label
            touchBarKeyStyle()
            setOnClickListener { onClick() }
        }

    




    private fun Button.touchBarSubmitStyle() {
        textSize = 15f
        isAllCaps = false
        includeFontPadding = false
        minimumWidth = 0
        minWidth = 0
        minimumHeight = 0
        minHeight = 0
        setPadding(dp(16), 0, dp(16), 0)
        setTextColor(OpenCodeTheme.v2TextContrast)
        typeface = HostStyle.sans(context, 530)
        gravity = Gravity.CENTER
        stateListAnimator = null
        elevation = HostStyle.dpFloat(context, 1)
        val radius = dp(OpenCodeTheme.RADIUS_XL).toFloat()
        val base =
            GradientDrawable().apply {
                setColor(OpenCodeTheme.v2BackgroundContrast)
                cornerRadius = radius
            }
        
        val highlight =
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x33ffffff, 0x00ffffff)).apply {
                cornerRadius = radius
            }
        background = android.graphics.drawable.LayerDrawable(arrayOf(base, highlight))
        foreground =
            RippleDrawable(
                ColorStateList.valueOf(OpenCodeTheme.v2OverlayHover),
                null,
                null,
            )
    }

    

    private val clipboard: ClipboardManager?
        get() = context.getSystemService(ClipboardManager::class.java)

    




    private fun pasteFromClipboard() {
        val clip = clipboard?.primaryClip
        val text =
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
            } else {
                ""
            }
        if (text.isEmpty()) {
            toast("剪贴板里没有可粘贴的文本")
            return
        }
        val current = input.text?.toString().orEmpty()
        val start = input.selectionStart.coerceAtLeast(0)
        val end = input.selectionEnd.coerceAtLeast(0)
        val low = minOf(start, end).coerceIn(0, current.length)
        val high = maxOf(start, end).coerceIn(0, current.length)
        input.setText(current.substring(0, low) + text + current.substring(high))
        input.setSelection((low + text.length).coerceIn(0, input.text?.length ?: 0))
        input.requestFocus()
    }

    





    private fun copyOutput() {
        val text = transcript.joinToString("\n") { it.text }
        if (text.isBlank()) {
            toast("还没有可复制的输出")
            return
        }
        clipboard?.setPrimaryClip(ClipData.newPlainText("救援终端输出", text))
        toast("已复制全部输出")
    }

    




    private fun cutInput() {
        val current = input.text?.toString().orEmpty()
        if (current.isEmpty()) {
            toast("输入框里没有可剪切的内容")
            return
        }
        val start = input.selectionStart.coerceAtLeast(0)
        val end = input.selectionEnd.coerceAtLeast(0)
        val low = minOf(start, end).coerceIn(0, current.length)
        val high = maxOf(start, end).coerceIn(0, current.length)
        val hasSelection = high > low
        val removed = if (hasSelection) current.substring(low, high) else current
        val kept = if (hasSelection) current.removeRange(low, high) else ""
        clipboard?.setPrimaryClip(ClipData.newPlainText("救援终端命令", removed))
        input.setText(kept)
        input.setSelection(low.coerceIn(0, kept.length))
        input.requestFocus()
        toast("已剪切")
    }

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    
    private fun insertAtCursor(text: String) {
        val current = input.text?.toString().orEmpty()
        val start = input.selectionStart.coerceAtLeast(0)
        val end = input.selectionEnd.coerceAtLeast(0)
        val low = minOf(start, end).coerceIn(0, current.length)
        val high = maxOf(start, end).coerceIn(0, current.length)
        input.setText(current.substring(0, low) + text + current.substring(high))
        input.setSelection((low + text.length).coerceIn(0, (input.text?.length ?: 0)))
        input.requestFocus()
    }

    





    private fun moveCursor(delta: Int) {
        val length = input.text?.length ?: 0
        val from = input.selectionStart.coerceAtLeast(0)
        val next = (from + delta).coerceIn(0, length)
        input.setSelection(next)
        input.requestFocus()
    }

    

    private fun submitInput() {
        
        
        if (running) return
        val command = input.text?.toString().orEmpty()
        if (command.isNotBlank()) {
            
            if (history.lastOrNull() != command) history.add(command)
            historyCursor = history.size
            updateHistoryButtons()
            
            
            input.setText("")
        }
        runCommand(command)
    }

    





    private fun runCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty() || running) return
        running = true
        input.setText("")
        updateRunState()
        appendLine("$ $trimmed", commandText)
        executor.execute(trimmed) { result -> onCommandFinished(result) }
    }

    private fun onCommandFinished(result: LocalRuntimeCommandResult) {
        running = false
        updateRunState()
        appendRawOutput(result.output)
        appendLine(
            "[exit ${result.exitCode}]",
            if (result.exitCode == 0) okText else errorText,
        )
        appendLine("", mutedText)
    }

    private fun updateRunState() {
        runButton.isEnabled = !running
        
        runButton.alpha = if (running) 0.5f else 1f
        
        
        
        
        if (running) {
            statusLine.text = "执行中…"
            statusLine.visibility = View.VISIBLE
        } else {
            statusLine.text = ""
            statusLine.visibility = View.GONE
        }
    }

    

    





    private fun appendRawOutput(text: String) {
        if (text.isEmpty()) {
            appendLine("（无输出）", mutedText)
            return
        }
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.trimEnd('\n').split('\n')
        appendLines(lines, outputText)
    }

    private fun appendLine(
        text: String,
        color: Int,
    ) {
        appendLines(listOf(text), color)
    }

    private fun appendLines(
        lines: List<String>,
        color: Int,
    ) {
        if (lines.isEmpty()) return
        lines.forEach { transcript.addLast(TranscriptLine(it, color)) }
        
        while (transcript.size > MAX_TRANSCRIPT_LINES) transcript.removeFirst()
        rerenderTranscript()
    }

    





    private fun rerenderTranscript() {
        
        val stickToBottom = !outputScroll.canScrollVertically(1)
        val builder = SpannableStringBuilder()
        transcript.forEachIndexed { index, line ->
            if (index > 0) builder.append('\n')
            val start = builder.length
            builder.append(line.text)
            if (line.text.isNotEmpty()) {
                builder.setSpan(
                    ForegroundColorSpan(line.color),
                    start,
                    builder.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        output.text = builder
        if (stickToBottom) outputScroll.post { outputScroll.fullScroll(View.FOCUS_DOWN) }
    }

    

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalLayoutListener(imeWatcher)
        refreshImePadding()
    }

    override fun onDetachedFromWindow() {
        runCatching { viewTreeObserver.removeOnGlobalLayoutListener(imeWatcher) }
        imePoll?.let(::removeCallbacks)
        imePoll = null
        super.onDetachedFromWindow()
    }

    













    private fun refreshImePadding() {
        if (!isAttachedToWindow) return
        val keyboard = measureKeyboardOverlap()
        if (keyboard != imePadding) {
            imePadding = keyboard
            setPadding(basePaddingLeft, basePaddingTop, basePaddingRight, basePaddingBottom + keyboard)
        }
    }

    




    private fun measureKeyboardOverlap(): Int {
        val frame = Rect()
        getWindowVisibleDisplayFrame(frame)
        if (frame.isEmpty || height <= 0) {
            val rootInsets = runCatching { ViewCompat.getRootWindowInsets(this) }.getOrNull() ?: return 0
            val barsBottom = rootInsets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            return (rootInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom - barsBottom).coerceAtLeast(0)
        }
        val location = IntArray(2)
        getLocationOnScreen(location)
        val overlap = location[1] + height - frame.bottom
        return if (overlap < dp(IME_NOISE_DP)) 0 else overlap
    }

    





    private fun watchImeFor(millis: Long) {
        imePoll?.let(::removeCallbacks)
        val deadline = SystemClock.uptimeMillis() + millis
        val poll =
            object : Runnable {
                override fun run() {
                    refreshImePadding()
                    if (SystemClock.uptimeMillis() < deadline) {
                        imePoll = this
                        postDelayed(this, 100L)
                    } else {
                        imePoll = null
                    }
                }
            }
        imePoll = poll
        post(poll)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        








        val BACKGROUND_COLOR: Int = OpenCodeTheme.v2BackgroundDeep

        
        private const val MAX_TRANSCRIPT_LINES = 500

        
        private const val IME_NOISE_DP = 40
    }
}
