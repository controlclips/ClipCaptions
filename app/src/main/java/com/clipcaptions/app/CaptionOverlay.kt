package com.clipcaptions.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The floating caption box drawn over other apps. Drag it up or down; tap ✕ to stop.
 * Each line shows its translation when there is one, "…" while a translation is on its way,
 * or the words as heard. All methods must be called on the main thread.
 *
 * [onScreenTouched] is called when you touch the screen anywhere outside the box (Android only
 * says that a touch happened, not where), which helps spot swipes to the next video.
 */
class CaptionOverlay(
    private val context: Context,
    initialLabel: String,
    private val onClose: () -> Unit,
    private val onScreenTouched: () -> Unit,
) {

    private class Line(val id: Int, val original: String, val awaiting: Boolean, var translated: String?)

    private val windowManager: WindowManager = context.getSystemService(WindowManager::class.java)
    private val lines = ArrayList<Line>()
    private var nextId = 1
    private var label = initialLabel
    private var liveOriginal = ""
    private var liveAwaiting = false
    private var liveTranslated: String? = null
    private var status: String? = null
    private var hintToken = 0

    // After a swipe to another video the box shrinks to its header until new words arrive.
    private var cleared = false

    private lateinit var root: FrameLayout
    private lateinit var labelView: TextView
    private lateinit var mainText: TextView
    private lateinit var originalText: TextView
    private lateinit var hintText: TextView
    private lateinit var params: WindowManager.LayoutParams
    private var shown = false

    private val white = Color.WHITE
    private val olderWhite = Color.argb(190, 255, 255, 255)
    private val dimWhite = Color.argb(150, 255, 255, 255)

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    fun show() {
        if (shown) return

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(6f), dp(10f), dp(14f))
            background = GradientDrawable().apply {
                cornerRadius = dp(18f).toFloat()
                setColor(Color.argb(220, 14, 17, 26))
            }
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        labelView = TextView(context).apply {
            text = label
            setTextColor(dimWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
        }
        val closeView = TextView(context).apply {
            text = "✕"
            setTextColor(white)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(12f), dp(8f), dp(6f), dp(8f))
            contentDescription = "Stop captions"
            isClickable = true
            setOnClickListener { onClose() }
        }
        header.addView(labelView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(
            closeView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )

        mainText = TextView(context).apply {
            setTextColor(white)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setLineSpacing(0f, 1.12f)
            setPadding(0, 0, dp(6f), 0)
        }
        originalText = TextView(context).apply {
            setTextColor(dimWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(6f), dp(6f), 0)
            visibility = View.GONE
        }
        hintText = TextView(context).apply {
            setTextColor(Color.rgb(255, 200, 87))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(8f), dp(6f), 0)
            visibility = View.GONE
        }

        val fullWidth = LinearLayout.LayoutParams.MATCH_PARENT
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        box.addView(header, LinearLayout.LayoutParams(fullWidth, wrap))
        box.addView(mainText, LinearLayout.LayoutParams(fullWidth, wrap))
        box.addView(originalText, LinearLayout.LayoutParams(fullWidth, wrap))
        box.addView(hintText, LinearLayout.LayoutParams(fullWidth, wrap))

        root = FrameLayout(context).apply {
            setPadding(dp(12f), 0, dp(12f), 0)
            addView(box, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            y = (context.resources.displayMetrics.heightPixels * 0.16f).toInt()
        }

        // Drag the box up and down. Touches outside it go straight to the app underneath.
        var downRawY = 0f
        var downY = 0
        root.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_OUTSIDE -> onScreenTouched()
                MotionEvent.ACTION_DOWN -> {
                    downRawY = event.rawY
                    downY = params.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val maxY = (context.resources.displayMetrics.heightPixels - root.height).coerceAtLeast(0)
                    params.y = (downY + (event.rawY - downRawY)).toInt().coerceIn(0, maxY)
                    windowManager.updateViewLayout(root, params)
                }
            }
            true
        }

        windowManager.addView(root, params)
        shown = true
        render()
    }

    fun remove() {
        if (!shown) return
        try {
            windowManager.removeView(root)
        } catch (ignored: Exception) {
        }
        shown = false
    }

    /** The small heading, such as "SPANISH → ENGLISH". */
    fun setLabel(text: String) {
        label = text
        if (shown) labelView.text = text
    }

    /** Removes all captions, for when you move on to another video. */
    fun clearCaptions() {
        lines.clear()
        liveOriginal = ""
        liveAwaiting = false
        liveTranslated = null
        status = null
        cleared = true
        render()
    }

    /** Placeholder shown before any words arrive, such as "Loading the speech model…". */
    fun setStatus(text: String?) {
        status = text
        if (text != null) cleared = false
        render()
    }

    /** A warning under the captions that stays until changed. Pass null to clear it. */
    fun setHint(text: String?) {
        hintToken++
        showHint(text)
    }

    /** A note under the captions that disappears after a few seconds. */
    fun flashHint(text: String) {
        if (!shown) return
        val token = ++hintToken
        showHint(text)
        root.postDelayed({ if (token == hintToken) showHint(null) }, 6000L)
    }

    private fun showHint(text: String?) {
        if (!shown) return
        hintText.text = text ?: ""
        hintText.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    /**
     * The sentence still being spoken. Pass "" to clear it. While new words arrive, the previous
     * translation stays on screen until the updated one is ready, so the text doesn't flicker.
     */
    fun setLive(original: String, awaitingTranslation: Boolean) {
        if (original.isEmpty()) {
            liveOriginal = ""
            liveAwaiting = false
            liveTranslated = null
        } else {
            liveOriginal = original
            liveAwaiting = awaitingTranslation
            cleared = false
        }
        render()
    }

    fun setLiveTranslation(translated: String) {
        if (liveOriginal.isEmpty()) return
        liveTranslated = translated
        render()
    }

    /** Adds a finished sentence and returns its id, for attaching the translation later. */
    fun addLine(original: String, awaitingTranslation: Boolean): Int {
        val line = Line(nextId++, original, awaitingTranslation, null)
        lines.add(line)
        while (lines.size > 2) lines.removeAt(0)
        liveOriginal = ""
        liveAwaiting = false
        liveTranslated = null
        status = null
        cleared = false
        render()
        return line.id
    }

    fun setTranslation(id: Int, translated: String) {
        val line = lines.firstOrNull { it.id == id } ?: return
        line.translated = translated
        render()
    }

    private fun render() {
        if (!shown) return
        val text = SpannableStringBuilder()

        lines.forEachIndexed { index, line ->
            val shownText = line.translated ?: if (line.awaiting) "…" else line.original
            if (text.isNotEmpty()) text.append('\n')
            val isNewest = index == lines.lastIndex && liveOriginal.isEmpty()
            text.appendColored(shownText, if (isNewest) white else olderWhite)
        }

        if (liveOriginal.isNotEmpty()) {
            val liveText = liveTranslated ?: if (liveAwaiting) "…" else liveOriginal
            if (text.isNotEmpty()) text.append('\n')
            text.appendColored(liveText, dimWhite)
        }

        if (text.isEmpty() && !cleared) text.appendColored(status ?: "Listening…", dimWhite)
        mainText.text = text
        mainText.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE

        // Under a translation, show the words as heard in small print.
        val source: String
        val isTranslated: Boolean
        if (liveOriginal.isNotEmpty()) {
            source = liveOriginal
            isTranslated = liveAwaiting || liveTranslated != null
        } else {
            val last = lines.lastOrNull()
            source = last?.original ?: ""
            isTranslated = last != null && (last.awaiting || last.translated != null)
        }
        if (isTranslated && source.isNotEmpty()) {
            originalText.text = source
            originalText.visibility = View.VISIBLE
        } else {
            originalText.visibility = View.GONE
        }
    }

    private fun SpannableStringBuilder.appendColored(value: String, color: Int) {
        val start = length
        append(value)
        setSpan(ForegroundColorSpan(color), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
