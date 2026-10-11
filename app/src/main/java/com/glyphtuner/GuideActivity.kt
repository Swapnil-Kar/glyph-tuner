package com.glyphtuner

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/**
 * "How it works": five short cards, each with a little film of the back of the phone.
 * Opened from the ? button, and offered at the end of the first-run tour.
 * Back / Next buttons, the dots, a swipe, or the system Back gesture move between cards.
 */
class GuideActivity : Activity() {

    private class Card(val title: String, val body: String, val tip: Pair<String, String>? = null)

    private lateinit var prefs: TunerPrefs
    private lateinit var tuner: Tuner
    private lateinit var cards: List<Card>
    private var page = 0
    private var demo: GuideDemo? = null
    private var pageStartedAt = 0.0

    private lateinit var matrix: MatrixView
    private lateinit var counter: TextView
    private lateinit var kicker: TextView
    private lateinit var titleView: DotTextView
    private lateinit var bodyText: TextView
    private lateinit var tipRow: LinearLayout
    private lateinit var tipKey: TextView
    private lateinit var tipText: TextView
    private lateinit var content: View
    private lateinit var dotsRow: LinearLayout
    private lateinit var backButton: TextView
    private lateinit var nextButton: TextView

    private val main = Handler(Looper.getMainLooper())
    private val frameLoop = object : Runnable {
        override fun run() {
            tick()
            main.postDelayed(this, 33)
        }
    }

    private lateinit var swipes: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = TunerPrefs(this)
        tuner = prefs.tuner()
        cards = makeCards()
        swipes = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val start = e1 ?: return false
                val dx = e2.x - start.x
                val dy = e2.y - start.y
                if (abs(dx) < dp(70) || abs(dx) < abs(dy) * 1.5f) return false
                if (dx < 0) go(page + 1) else go(page - 1)
                return true
            }
        })
        setContentView(build())
        show((savedInstanceState?.getInt(KEY_PAGE) ?: 0).coerceIn(0, cards.size - 1), fade = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_PAGE, page)
    }

    override fun onResume() {
        super.onResume()
        main.post(frameLoop)
    }

    override fun onPause() {
        main.removeCallbacks(frameLoop)
        super.onPause()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        swipes.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    @Deprecated("Back goes to the previous card")
    override fun onBackPressed() {
        if (page > 0) go(page - 1) else finish()
    }

    // ---------- Cards ----------

    private fun makeCards(): List<Card> {
        val strings = tuner.strings
        val letters = strings.joinToString(" ") { it.display() }
        val order = if (strings.size == 6) "thickest to thinnest" else "in order"
        return listOf(
            Card(
                "FLIP AND\nPLUCK",
                "Start the tuner, put the phone face down and pluck a string. The big letter on the back is the string you plucked: $letters, $order.",
            ),
            Card(
                "▲ TIGHTEN\n▼ LOOSEN",
                "The arrow tells you which way to turn the peg. Up means the string is too low: tighten it. Down means it's too high: loosen it.",
            ),
            Card(
                "THE RING SAYS\nHOW FAR",
                "The ring spins fast when you're far off and slows down as you get close. Turn slowly. When it stops and glows, you're in tune.",
            ),
            Card(
                "CLICK AND\nA DOT",
                "In tune, the phone clicks once and that string's dot lights up along the bottom. Solid dots are done. Then pluck the next string.",
            ),
            Card(
                "GO PLAY",
                "Tune every string and you get a little celebration. Then the tuner switches itself off.",
                "NEXT TIME" to "Skip the app: swipe down and tap the Tuner tile, or tap the widget.",
            ),
        )
    }

    private fun go(n: Int) {
        if (n < 0 || n >= cards.size || n == page) return
        show(n)
    }

    private fun show(n: Int, fade: Boolean = true) {
        page = n
        val card = cards[n]
        demo = GuideDemo(n, tuner)
        pageStartedAt = TunerEngine.now()
        matrix.show(demo?.frame(0.0))

        counter.text = "${n + 1} / ${cards.size}"
        kicker.text = "STEP ${n + 1}"
        titleView.text = card.title
        bodyText.text = card.body
        val tip = card.tip
        tipRow.visibility = if (tip == null) View.GONE else View.VISIBLE
        if (tip != null) {
            tipKey.text = tip.first
            tipText.text = tip.second
        }

        for (i in 0 until dotsRow.childCount) {
            val bar = (dotsRow.getChildAt(i) as FrameLayout).getChildAt(0)
            bar.background = Ui.shape(if (i == n) Ui.WHITE else Ui.DIM_LINE, radiusPx = dpF(3f))
            bar.layoutParams = FrameLayout.LayoutParams(dp(if (i == n) 18 else 6), dp(6), Gravity.CENTER)
        }
        backButton.setTextColor(if (n == 0) 0xFF444444.toInt() else Ui.WHITE)
        backButton.isEnabled = n > 0
        nextButton.text = if (n == cards.size - 1) "GOT IT" else "NEXT"

        if (fade) {
            content.alpha = 0f
            content.animate().alpha(1f).setDuration(200).start()
        }
    }

    private fun tick() {
        val d = demo ?: return
        matrix.show(d.frame(TunerEngine.now() - pageStartedAt))
        // Like the real thing: a click when a string locks, a longer buzz for the celebration.
        if (!prefs.vibrate) return
        when {
            d.justCelebrated -> Haptics.celebrate(this)
            d.justLocked -> Haptics.lock(this)
        }
    }

    // ---------- Layout ----------

    private fun build(): View {
        val root = linear(this, vertical = true).apply { setBackgroundColor(Ui.BLACK) }
        root.padForSystemBars(dp(24), dp(8), dp(24), dp(24))

        val top = linear(this, vertical = false, gravity = Gravity.CENTER_VERTICAL)
        top.addView(iconButton(R.drawable.ic_close, "Close the guide", Ui.WHITE) { finish() })
        counter = label("", 12f, Ui.GREY, 0.15f).apply { gravity = Gravity.CENTER }
        top.addView(counter, lp(0, WRAP, 1f))
        top.addView(View(this), lp(dp(44), dp(44)))
        root.addView(top, lp(MATCH, dp(48)))

        val column = linear(this, vertical = true)
        val m = MatrixView(this).apply { maxSizePx = dp(280) }
        matrix = m
        column.addView(FrameLayout(this).apply {
            addView(m, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
        }, lp(MATCH, dp(260), top = dp(8)))

        kicker = label("", 11f, Ui.GREY)
        column.addView(kicker, lp(MATCH, WRAP, top = dp(24)))
        titleView = DotTextView(this).apply {
            maxPitchPx = dpF(6f)
            centered = false
        }
        column.addView(titleView, lp(MATCH, WRAP, top = dp(10)))
        bodyText = body("", 16f, Ui.LIGHT)
        column.addView(bodyText, lp(MATCH, WRAP, top = dp(14)))

        tipRow = linear(this, vertical = false)
        tipKey = label("", 12f, Ui.WHITE, 0.12f)
        tipRow.addView(tipKey, lp(dp(84), WRAP))
        tipText = body("", 14f, Ui.SOFT)
        tipRow.addView(tipText, lp(0, WRAP, 1f))
        column.addView(tipRow, lp(MATCH, WRAP, top = dp(16)))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(column)
        }
        content = column
        root.addView(scroll, lp(MATCH, 0, 1f))

        dotsRow = linear(this, vertical = false, gravity = Gravity.CENTER)
        for (i in cards.indices) {
            dotsRow.addView(FrameLayout(this).apply {
                contentDescription = "Card ${i + 1}"
                isClickable = true
                isFocusable = true
                setOnClickListener { go(i) }
                addView(View(context), FrameLayout.LayoutParams(dp(6), dp(6), Gravity.CENTER))
            }, lp(dp(26), dp(32)))
        }
        root.addView(dotsRow, lp(MATCH, dp(32), top = dp(8)))

        val buttons = linear(this, vertical = false)
        backButton = bigButton("BACK", filled = false) { go(page - 1) }
        nextButton = bigButton("NEXT") {
            if (page == cards.size - 1) finish() else go(page + 1)
        }
        buttons.addView(backButton, lp(0, dp(64), 1f))
        buttons.addView(nextButton, lp(0, dp(64), 2f, left = dp(10)))
        root.addView(buttons, lp(MATCH, dp(64), top = dp(8)))
        return root
    }

    private companion object {
        const val KEY_PAGE = "page"
    }
}
