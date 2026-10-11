package com.glyphtuner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Big text drawn with dots, in the same 5 x 7 dot font as the Glyph Matrix, so the screen and
 * the back of the phone speak the same language. Lines are split on '\n'.
 */
class DotTextView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.WHITE }

    var text: String = ""
        set(value) {
            if (field == value) return
            field = value
            contentDescription = value.replace('\n', ' ')
            requestLayout()
            invalidate()
        }

    /** Dot spacing it never grows past. It shrinks to fit the width. */
    var maxPitchPx = context.dpF(9f)
        set(value) { field = value; requestLayout(); invalidate() }

    /** Centre each line (true) or start at the left (false). */
    var centered = true

    var color: Int
        get() = paint.color
        set(value) { paint.color = value; invalidate() }

    private fun lines() = text.split('\n')

    /** Width of one line, in dot columns (one blank column between letters). */
    private fun columns(line: String): Int {
        var cols = 0
        line.forEachIndexed { i, ch ->
            cols += TunerArt.glyph(ch)?.get(0)?.length ?: 5
            if (i < line.length - 1) cols += 1
        }
        return cols
    }

    private fun pitchFor(width: Int): Float {
        val widest = max(1, lines().maxOf { columns(it) })
        return min(maxPitchPx, width.toFloat() / widest)
    }

    private fun heightFor(pitch: Float): Int {
        val n = lines().size
        return ((n * 7 + (n - 1) * 3) * pitch).toInt() + 1
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val available = MeasureSpec.getSize(widthMeasureSpec)
        val widest = max(1, lines().maxOf { columns(it) })
        val natural = (widest * maxPitchPx).toInt() + 1
        val w = when (mode) {
            MeasureSpec.EXACTLY -> available
            MeasureSpec.AT_MOST -> min(available, natural)
            else -> natural
        }
        setMeasuredDimension(w, heightFor(pitchFor(w)))
    }

    override fun onDraw(canvas: Canvas) {
        val pitch = pitchFor(width)
        val radius = pitch * 0.38f
        lines().forEachIndexed { li, line ->
            var x = if (centered) (width - columns(line) * pitch) / 2f else 0f
            val top = li * 10 * pitch
            line.forEach { ch ->
                val g = TunerArt.glyph(ch)
                val w = g?.get(0)?.length ?: 5
                g?.forEachIndexed { r, rowBits ->
                    rowBits.forEachIndexed { c, bit ->
                        if (bit == '1') canvas.drawCircle(x + (c + 0.5f) * pitch, top + (r + 0.5f) * pitch, radius, paint)
                    }
                }
                x += (w + 1) * pitch
            }
        }
    }
}
