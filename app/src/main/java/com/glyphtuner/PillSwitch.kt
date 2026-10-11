package com.glyphtuner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Switch

/** An on/off switch in the app's style: white pill with a black knob when on, dark grey when off. */
class PillSwitch(context: Context) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    var isChecked = false
        set(value) {
            field = value
            invalidate()
        }

    /** Called after the user flips it. */
    var onChange: ((Boolean) -> Unit)? = null

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener {
            isChecked = !isChecked
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onChange?.invoke(isChecked)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(context.dp(52), context.dp(44))
    }

    override fun onDraw(canvas: Canvas) {
        val h = context.dpF(30f)
        val inset = context.dpF(3f)
        val top = (height - h) / 2f
        rect.set(0f, top, width.toFloat(), top + h)
        track.color = if (isChecked) Ui.WHITE else Ui.LINE
        canvas.drawRoundRect(rect, h / 2, h / 2, track)
        val r = h / 2 - inset
        val cx = if (isChecked) width - inset - r else inset + r
        knob.color = if (isChecked) Ui.BLACK else Ui.GREY
        canvas.drawCircle(cx, top + h / 2, r, knob)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = Switch::class.java.name
        info.isCheckable = true
        info.isChecked = isChecked
    }
}
