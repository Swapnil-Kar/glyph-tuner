package com.glyphtuner

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** The app's look: black, white, greys, one red. Shared by every screen. */
object Ui {
    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    /** Secondary text. About 6:1 on black. */
    const val GREY = 0xFF8C8C8C.toInt()
    /** Body text. */
    const val SOFT = 0xFFA6A6A6.toInt()
    const val LIGHT = 0xFFBDBDBD.toInt()
    /** Borders of things you can tap. */
    const val LINE = 0xFF2A2A2A.toInt()
    const val DIM_LINE = 0xFF333333.toInt()
    /** Thin dividers between rows. */
    const val FAINT = 0xFF1C1C1C.toInt()
    const val SURFACE = 0xFF141414.toInt()
    /** The "listening" dot. The only red in the app. */
    const val RED = 0xFFD71921.toInt()

    val MONO: Typeface = Typeface.MONOSPACE
    val SANS: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val SANS_MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val SANS_BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

    /** A rounded or oval shape with an optional border. */
    fun shape(fill: Int, stroke: Int = fill, strokePx: Int = 0, radiusPx: Float = 0f, oval: Boolean = false) =
        GradientDrawable().apply {
            shape = if (oval) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
            if (!oval) cornerRadius = radiusPx
            setColor(fill)
            if (strokePx > 0) setStroke(strokePx, stroke)
        }
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
fun Context.dpF(value: Float): Float = value * resources.displayMetrics.density

/** Small uppercase monospaced label, e.g. "TUNING". */
fun Context.label(text: String, sizeSp: Float = 11f, color: Int = Ui.GREY, spacing: Float = 0.18f) = TextView(this).apply {
    this.text = text
    typeface = Ui.MONO
    letterSpacing = spacing
    setTextColor(color)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
}

/** Ordinary readable text. */
fun Context.body(text: String, sizeSp: Float = 16f, color: Int = Ui.SOFT, face: Typeface = Ui.SANS) = TextView(this).apply {
    this.text = text
    typeface = face
    setTextColor(color)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setLineSpacing(0f, 1.35f)
}

/** A big pill-shaped button (64 dp tall). [filled] = white with black text, otherwise outlined. */
fun Context.bigButton(text: String, filled: Boolean = true, onClick: () -> Unit) = TextView(this).apply {
    this.text = text
    gravity = Gravity.CENTER
    typeface = Ui.SANS_BOLD
    letterSpacing = 0.12f
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
    setTextColor(if (filled) Ui.BLACK else Ui.WHITE)
    background = if (filled) Ui.shape(Ui.WHITE, radiusPx = dpF(32f)) else Ui.shape(Ui.BLACK, Ui.LINE, dp(1), dpF(32f))
    isClickable = true
    isFocusable = true
    setOnClickListener {
        it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        onClick()
    }
    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64))
}

/** A plain text button with a 44 dp touch area, e.g. "SKIP" or "NOT NOW". */
fun Context.textButton(text: String, color: Int = Ui.GREY, onClick: () -> Unit) = label(text, 12f, color).apply {
    gravity = Gravity.CENTER
    minHeight = dp(44)
    minWidth = dp(44)
    setPadding(dp(8), 0, dp(8), 0)
    isClickable = true
    isFocusable = true
    setOnClickListener { onClick() }
}

/** A 44 dp square icon button. */
fun Context.iconButton(icon: Int, description: String, tint: Int = Ui.GREY, onClick: () -> Unit) = ImageView(this).apply {
    setImageResource(icon)
    setColorFilter(tint)
    contentDescription = description
    scaleType = ImageView.ScaleType.CENTER
    isClickable = true
    isFocusable = true
    setOnClickListener { onClick() }
    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
}

/** Pads [view] so its content stays clear of the status bar, navigation bar and camera cutout. */
fun View.padForSystemBars(left: Int, top: Int, right: Int, bottom: Int) {
    setOnApplyWindowInsetsListener { v, insets ->
        val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        v.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
        insets
    }
}

fun linear(context: Context, vertical: Boolean, gravity: Int = Gravity.NO_GRAVITY) = LinearLayout(context).apply {
    orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
    this.gravity = gravity
}

fun lp(width: Int, height: Int, weight: Float = 0f, top: Int = 0, left: Int = 0, right: Int = 0, bottom: Int = 0) =
    LinearLayout.LayoutParams(width, height, weight).apply {
        topMargin = top
        leftMargin = left
        rightMargin = right
        bottomMargin = bottom
    }

const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

/** Sets the text only when it changed, so a 30-frames-a-second screen doesn't re-layout for nothing. */
fun TextView.setIfChanged(value: CharSequence) {
    if (text?.toString() != value.toString()) text = value
}

/** "Eb2" → "E♭2". */
fun prettyNote(note: String): String =
    if (note.length > 2 && note[1] == 'b') note.substring(0, 1) + "♭" + note.substring(2) else note

/** The letter as shown on screen, with a flat sign when needed: "E♭". */
fun GuitarString.display(): String = letter.toString() + (if (flat) "♭" else "")
