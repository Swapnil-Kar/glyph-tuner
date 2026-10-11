package com.glyphtuner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The Glyph Matrix drawn on screen: the same 25 x 25 round grid of dots as the back of the
 * phone, showing the same picture. Feed it frames with [show].
 */
class MatrixView(context: Context) : View(context) {
    private var frame: IntArray? = null
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Largest size it grows to. */
    var maxSizePx = context.dp(320)

    init {
        contentDescription = "The back of your phone"
    }

    /** Shows a frame (25 x 25 brightness values, 0..255). Null = all lights off. */
    fun show(newFrame: IntArray?) {
        frame = newFrame
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val size = when {
            MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED -> w
            MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED -> h
            else -> min(w, h)
        }.coerceAtMost(maxSizePx)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        val pitch = size / TunerArt.SIZE
        val radius = pitch * 0.36f
        val left = (width - size) / 2f
        val top = (height - size) / 2f
        val f = frame
        for (row in 0 until TunerArt.SIZE) {
            for (col in 0 until TunerArt.SIZE) {
                if (!TunerArt.exists(row, col)) continue
                val value = f?.get(row * TunerArt.SIZE + col) ?: 0
                val b = (value / 255f).coerceIn(0f, 1f)
                val cx = left + (col + 0.5f) * pitch
                val cy = top + (row + 0.5f) * pitch
                if (b > 0.72f) {
                    glow.color = Color.argb(((b - 0.72f) / 0.28f * 60).roundToInt(), 255, 255, 255)
                    canvas.drawCircle(cx, cy, radius * 1.9f, glow)
                }
                // Unlit dots stay faintly visible, like the real matrix behind the glass.
                val level = (22 + 233 * b.pow(0.85f)).roundToInt()
                dot.color = Color.rgb(level, level, level)
                canvas.drawCircle(cx, cy, radius, dot)
            }
        }
    }
}
