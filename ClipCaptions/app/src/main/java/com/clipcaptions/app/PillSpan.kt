package com.clipcaptions.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.style.ReplacementSpan
import kotlin.math.roundToInt

/** Draws its text as a small rounded badge, such as "Recommended" on an option. */
class PillSpan(private val background: Int, private val foreground: Int) : ReplacementSpan() {

    private fun badgePaint(paint: Paint) = Paint(paint).apply {
        textSize = paint.textSize * 0.72f
        isFakeBoldText = true
    }

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val p = badgePaint(paint)
        return (p.measureText(text, start, end) + p.textSize * 0.6f).roundToInt()
    }

    override fun draw(
        canvas: Canvas, text: CharSequence, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint,
    ) {
        val p = badgePaint(paint)
        val width = p.measureText(text, start, end) + p.textSize * 0.6f
        val metrics = p.fontMetrics
        val textHeight = metrics.descent - metrics.ascent
        // Centred on the line's text.
        val centre = y + (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
        val rect = RectF(x, centre - textHeight * 0.62f, x + width, centre + textHeight * 0.62f)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background }
        canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, bg)
        p.color = foreground
        canvas.drawText(text, start, end, x + p.textSize * 0.3f, centre - (metrics.ascent + metrics.descent) / 2f, p)
    }
}
