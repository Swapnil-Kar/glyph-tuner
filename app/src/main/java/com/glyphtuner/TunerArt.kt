package com.glyphtuner

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws the Strobe Ring tuner on the Phone (3) Glyph Matrix: 25 x 25 brightness values
 * (0-255), row by row. No Android code, so it can be tested anywhere.
 */
object TunerArt {
    const val SIZE = 25
    private const val C = 12
    private const val TAU = 2 * PI

    private const val RING_RADIUS = 8.6
    private const val RING_HALF_WIDTH = 1.1
    private const val SEGMENTS = 12
    private const val MARQUEE_DOTS_PER_SECOND = 20.0

    /** How long the "I'm on" sweep takes. */
    const val SWEEP_SECONDS = 0.9
    private const val SWEEP_TAIL = 1.3
    /** How long the "switched off" animation takes. */
    const val GOODBYE_SECONDS = 1.4
    /** How long the old letter takes to turn into the new one. */
    private const val MORPH_SECONDS = 0.35
    /** The arrow's little shake when it flips. */
    private const val SHAKE_SECONDS = 0.35
    /** The ripple when a note comes into tune. */
    private const val RIPPLE_SECONDS = 0.9
    /** A finished string's dot dropping into place. */
    private const val BOUNCE_SECONDS = 0.7

    /** How to draw: which strings, which way up, how bright. */
    class Look(
        val strings: List<GuitarString>,
        val rotate180: Boolean = false,
        /** 0..1 multiplier, e.g. lower at night. */
        val brightness: Double = 1.0,
    ) {
        /** One rim dot per string along the bottom arc, thickest string on the left. */
        val rim: Array<IntArray> = rimPositions(strings.size)
    }

    // ---------- Shapes ----------

    private val FONT: Map<Char, Array<String>> = mapOf(
        'A' to arrayOf("01110", "10001", "10001", "11111", "10001", "10001", "10001"),
        'B' to arrayOf("11110", "10001", "10001", "11110", "10001", "10001", "11110"),
        'C' to arrayOf("01110", "10001", "10000", "10000", "10000", "10001", "01110"),
        'D' to arrayOf("11110", "10001", "10001", "10001", "10001", "10001", "11110"),
        'E' to arrayOf("11111", "10000", "10000", "11110", "10000", "10000", "11111"),
        'F' to arrayOf("11111", "10000", "10000", "11110", "10000", "10000", "10000"),
        'G' to arrayOf("01110", "10001", "10000", "10111", "10001", "10001", "01111"),
        'H' to arrayOf("10001", "10001", "10001", "11111", "10001", "10001", "10001"),
        'I' to arrayOf("01110", "00100", "00100", "00100", "00100", "00100", "01110"),
        'J' to arrayOf("00111", "00010", "00010", "00010", "00010", "10010", "01100"),
        'K' to arrayOf("10001", "10010", "10100", "11000", "10100", "10010", "10001"),
        'L' to arrayOf("10000", "10000", "10000", "10000", "10000", "10000", "11111"),
        'M' to arrayOf("10001", "11011", "10101", "10101", "10001", "10001", "10001"),
        'N' to arrayOf("10001", "11001", "10101", "10011", "10001", "10001", "10001"),
        'O' to arrayOf("01110", "10001", "10001", "10001", "10001", "10001", "01110"),
        'P' to arrayOf("11110", "10001", "10001", "11110", "10000", "10000", "10000"),
        'R' to arrayOf("11110", "10001", "10001", "11110", "10100", "10010", "10001"),
        'S' to arrayOf("01111", "10000", "10000", "01110", "00001", "00001", "11110"),
        'T' to arrayOf("11111", "00100", "00100", "00100", "00100", "00100", "00100"),
        'U' to arrayOf("10001", "10001", "10001", "10001", "10001", "10001", "01110"),
        'V' to arrayOf("10001", "10001", "10001", "10001", "10001", "01010", "00100"),
        'W' to arrayOf("10001", "10001", "10001", "10101", "10101", "11011", "10001"),
        'X' to arrayOf("10001", "10001", "01010", "00100", "01010", "10001", "10001"),
        'Y' to arrayOf("10001", "10001", "01010", "00100", "00100", "00100", "00100"),
        'Z' to arrayOf("11111", "00001", "00010", "00100", "01000", "10000", "11111"),
        'a' to arrayOf("00000", "00000", "01110", "00001", "01111", "10001", "01111"),
        'b' to arrayOf("10000", "10000", "11110", "10001", "10001", "10001", "11110"),
        'c' to arrayOf("00000", "00000", "01110", "10000", "10000", "10001", "01110"),
        'd' to arrayOf("00001", "00001", "01111", "10001", "10001", "10001", "01111"),
        'e' to arrayOf("00000", "00000", "01110", "10001", "11111", "10000", "01110"),
        'f' to arrayOf("00110", "01001", "01000", "11100", "01000", "01000", "01000"),
        'g' to arrayOf("00000", "01111", "10001", "10001", "01111", "00001", "01110"),
        '0' to arrayOf("01110", "10001", "10011", "10101", "11001", "10001", "01110"),
        '1' to arrayOf("00100", "01100", "00100", "00100", "00100", "00100", "01110"),
        '2' to arrayOf("01110", "10001", "00001", "00010", "00100", "01000", "11111"),
        '3' to arrayOf("11110", "00001", "00001", "01110", "00001", "00001", "11110"),
        '4' to arrayOf("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
        '5' to arrayOf("11111", "10000", "11110", "00001", "00001", "10001", "01110"),
        '6' to arrayOf("00110", "01000", "10000", "11110", "10001", "10001", "01110"),
        '7' to arrayOf("11111", "00001", "00010", "00100", "01000", "01000", "01000"),
        '8' to arrayOf("01110", "10001", "10001", "01110", "10001", "10001", "01110"),
        '9' to arrayOf("01110", "10001", "10001", "01111", "00001", "00010", "01100"),
        '#' to arrayOf("01010", "01010", "11111", "01010", "11111", "01010", "01010"),
        '-' to arrayOf("00000", "00000", "00000", "11111", "00000", "00000", "00000"),
        '!' to arrayOf("00100", "00100", "00100", "00100", "00100", "00000", "00100"),
        '\'' to arrayOf("00100", "00100", "01000", "00000", "00000", "00000", "00000"),
        ',' to arrayOf("00000", "00000", "00000", "00000", "00110", "00100", "01000"),
        ' ' to arrayOf("00000", "00000", "00000", "00000", "00000", "00000", "00000"),
        'Q' to arrayOf("01110", "10001", "10001", "10001", "10101", "10010", "01101"),
        '?' to arrayOf("01110", "10001", "00001", "00010", "00100", "00000", "00100"),
        '.' to arrayOf("000", "000", "000", "000", "000", "000", "010"),
        ':' to arrayOf("000", "010", "000", "000", "000", "010", "000"),
        '▲' to arrayOf("00000", "00100", "01110", "11111", "11111", "00000", "00000"),
        '▼' to arrayOf("00000", "11111", "11111", "01110", "00100", "00000", "00000"),
    )

    /** The 7-row dot shape for [ch] (also '♭'), or null if the font can't draw it. Used by the app screen too. */
    fun glyph(ch: Char): Array<String>? = when (ch) {
        '♭' -> arrayOf("000") + FLAT
        else -> FONT[ch] ?: FONT[ch.uppercaseChar()]
    }

    private val FLAT = arrayOf("100", "100", "110", "101", "101", "110")
    private val ARROW_UP = arrayOf("00100", "01110", "11111")
    private val ARROW_DOWN = arrayOf("11111", "01110", "00100")
    private val PLAY = arrayOf("1000", "1100", "1110", "1111", "1110", "1100", "1000")
    private val HEART = arrayOf(
        "011000110", "111101111", "111111111", "111111111",
        "011111110", "001111100", "000111000", "000010000",
    )

    /** Which LEDs physically exist on the Phone (3): a 25 x 25 grid with the corners cut off. */
    private val ROW_WIDTHS = intArrayOf(7, 11, 15, 17, 19, 21, 21, 23, 23, 25, 25, 25, 25, 25, 25, 25, 23, 23, 21, 21, 19, 17, 15, 11, 7)

    fun exists(row: Int, col: Int): Boolean {
        if (row !in 0 until SIZE || col !in 0 until SIZE) return false
        val start = (SIZE - ROW_WIDTHS[row]) / 2
        return col >= start && col < start + ROW_WIDTHS[row]
    }

    /**
     * Rim dots spread along the bottom arc, left to right, each as
     * [outerRow, outerCol, innerRow, innerCol]. Angles are measured from "right", clockwise
     * on screen (rows grow downwards), so 90° is straight down.
     */
    fun rimPositions(n: Int): Array<IntArray> {
        return Array(n) { k ->
            val a = rimAngle(n, k)
            intArrayOf(
                (C + 11 * sin(a)).roundToInt(), (C + 11 * cos(a)).roundToInt(),
                (C + 10 * sin(a)).roundToInt(), (C + 10 * cos(a)).roundToInt(),
            )
        }
    }

    /** Where string [k] of [n] sits on the bottom arc, in radians (clockwise from "right"). */
    private fun rimAngle(n: Int, k: Int): Double {
        val spread = if (n <= 4) 105.0 else 130.0
        val deg = 90 + spread / 2 - (if (n > 1) spread * k / (n - 1) else spread / 2)
        return deg * PI / 180
    }

    private class Canvas {
        val px = FloatArray(SIZE * SIZE)
        fun put(row: Int, col: Int, v: Double) {
            if (!exists(row, col)) return
            val i = row * SIZE + col
            if (v > px[i]) px[i] = v.toFloat()
        }
        fun eachLed(block: (row: Int, col: Int, d: Double, a: Double) -> Unit) {
            for (r in 0 until SIZE) for (c in 0 until SIZE) {
                if (exists(r, c)) block(r, c, hypot((r - C).toDouble(), (c - C).toDouble()), atan2((r - C).toDouble(), (c - C).toDouble()))
            }
        }
        fun shape(rows: Array<String>, top: Int, left: Int, v: Double) {
            for (r in rows.indices) for (c in rows[r].indices) if (rows[r][c] == '1') put(top + r, left + c, v)
        }
        fun ring(v: Double) = eachLed { r, c, d, _ -> if (abs(d - RING_RADIUS) < RING_HALF_WIDTH) put(r, c, v) }
    }

    /** Applies brightness and rotation, then converts to the SDK's whole numbers. */
    private fun finish(cv: Canvas, look: Look): IntArray {
        val out = IntArray(SIZE * SIZE)
        for (i in out.indices) {
            val v = cv.px[i]
            if (v <= 0f) continue
            // Dimmed LEDs keep a minimum level so faint parts don't vanish.
            val dimmed = max(v * look.brightness, if (look.brightness < 1.0) 6.0 else 0.0)
            out[if (look.rotate180) SIZE * SIZE - 1 - i else i] = dimmed.roundToInt().coerceIn(0, 255)
        }
        return out
    }

    private fun letter(cv: Canvas, s: GuitarString, v: Double) {
        if (s.flat) {
            FONT[s.letter]?.let { cv.shape(it, 9, 9, v) }
            cv.shape(FLAT, 10, 15, v)
        } else {
            FONT[s.letter]?.let { cv.shape(it, 9, 10, v) }
        }
    }

    /** The dots of a string's letter, sorted by angle around the middle so they travel to nearby spots. */
    private fun letterDots(s: GuitarString): List<IntArray> {
        val out = ArrayList<IntArray>()
        fun add(rows: Array<String>?, top: Int, left: Int) {
            rows?.forEachIndexed { r, row -> row.forEachIndexed { c, ch -> if (ch == '1') out += intArrayOf(top + r, left + c) } }
        }
        if (s.flat) {
            add(FONT[s.letter], 9, 9)
            add(FLAT, 10, 15)
        } else {
            add(FONT[s.letter], 9, 10)
        }
        out.sortBy { atan2(it[0] - C.toDouble(), it[1] - C.toDouble()) }
        return out
    }

    /** The current letter, or (just after a string change) the old letter's dots flying into the new one. */
    private fun currentLetter(cv: Canvas, s: TunerState.Snapshot, str: GuitarString, look: Look, v: Double) {
        val prev = s.previousIndex
        if (s.sinceSelect >= MORPH_SECONDS || prev !in look.strings.indices || prev == s.stringIndex) {
            letter(cv, str, v)
            return
        }
        val from = letterDots(look.strings[prev])
        val to = letterDots(str)
        if (from.isEmpty() || to.isEmpty()) {
            letter(cv, str, v)
            return
        }
        val k = (s.sinceSelect / MORPH_SECONDS).coerceIn(0.0, 1.0)
        val e = if (k < 0.5) 2 * k * k else 1 - (-2 * k + 2).pow(2) / 2
        for (i in 0 until max(from.size, to.size)) {
            val p = from[i % from.size]
            val q = to[i % to.size]
            cv.put((p[0] + (q[0] - p[0]) * e).roundToInt(), (p[1] + (q[1] - p[1]) * e).roundToInt(), v)
        }
    }

    /** One ring of light going out from the middle, [t] seconds after a note came into tune. */
    private fun ripple(cv: Canvas, t: Double) {
        if (t < 0 || t > RIPPLE_SECONDS) return
        val radius = t * 14
        val fade = 1 - t / RIPPLE_SECONDS
        cv.eachLed { r, c, d, _ -> if (abs(d - radius) < 0.8) cv.put(r, c, 255 * fade) }
    }

    private fun easeOut(x: Double) = 1 - (1 - x).pow(3)

    private fun bounceOut(x0: Double): Double {
        val n = 7.5625
        val d = 2.75
        var x = x0
        return when {
            x < 1 / d -> n * x * x
            x < 2 / d -> { x -= 1.5 / d; n * x * x + 0.75 }
            x < 2.5 / d -> { x -= 2.25 / d; n * x * x + 0.9375 }
            else -> { x -= 2.625 / d; n * x * x + 0.984375 }
        }
    }

    // ---------- The tuner ----------

    /**
     * One frame of the tuner. [sinceStart] is how long ago listening started (seconds),
     * for the sweep that says "I'm on".
     */
    fun render(s: TunerState.Snapshot, now: Double, look: Look, sinceStart: Double = 1e9): IntArray {
        if (s.paused) return paused(look)
        if (s.blocked) return cross(look)
        s.celebration?.let { return celebration(now - it.startedAt, it.text, look, it.style) }

        val cv = Canvas()
        val str = if (s.stringIndex in look.strings.indices) look.strings[s.stringIndex] else null

        when {
            // Just switched on: one bright dot sweeps around the ring.
            (s.idle || str == null) && sinceStart in 0.0..SWEEP_SECONDS -> sweep(cv, sinceStart)
            // Waiting for a note: a slowly breathing ring that shimmers with any sound it hears.
            s.idle || str == null -> listening(cv, now, s.level)
            // In tune, or a string was just marked done: the ring stops and glows.
            s.inTune || s.showDone -> {
                cv.ring(255 * (0.78 + 0.22 * sin(now * 7)))
                ripple(cv, s.sinceInTune)
                currentLetter(cv, s, str, look, 255.0)
            }
            // Note sounding, out of tune: spinning ring, letter, and an arrow saying which way.
            // Closer to the target, the gaps between the strobe segments fill in.
            s.noteOn -> {
                val closeness = 1 - min(abs(s.cents), 25.0) / 25.0
                strobe(cv, s.phase, 255.0, 24 + closeness * closeness * 150)
                currentLetter(cv, s, str, look, 190.0)
                val up = s.arrowUp
                val arrow = if (abs(s.cents) > 10) 255.0 else 120 + 135 * (0.5 + 0.5 * sin(now * 12))
                // Went past the target: the arrow flips with a tiny shake.
                val shake = if (s.sinceArrowFlip < SHAKE_SECONDS) (1.3 * sin(s.sinceArrowFlip * 55)).roundToInt() else 0
                cv.shape(if (up) ARROW_UP else ARROW_DOWN, if (up) 5 else 17, 10 + shake, arrow)
            }
            // The note just faded: hold the last string, ring still and dim.
            else -> {
                strobe(cv, s.phase, 90.0, 12.0)
                currentLetter(cv, s, str, look, 110.0)
            }
        }
        rim(cv, s, now, look)
        return finish(cv, look)
    }

    private fun strobe(cv: Canvas, phase: Double, on: Double, off: Double) {
        val seg = TAU / SEGMENTS
        cv.eachLed { r, c, d, a ->
            if (abs(d - RING_RADIUS) < RING_HALF_WIDTH) {
                val k = floor((((a - phase) % TAU) + TAU) % TAU / seg).toInt()
                cv.put(r, c, if (k % 2 == 0) on else off)
            }
        }
    }

    /** The idle ring: breathes slowly, and its dots light up with the loudness ([level], 0..1). */
    private fun listening(cv: Canvas, now: Double, level: Double) {
        val base = 20 + 35 * (0.5 + 0.5 * sin(now * PI))
        val lift = 90 * level.coerceIn(0.0, 1.0)
        cv.eachLed { r, c, d, _ ->
            if (abs(d - RING_RADIUS) < RING_HALF_WIDTH) {
                cv.put(r, c, base + lift * (0.5 + 0.5 * sin(now * 11 + r * 1.9 + c * 2.7)))
            }
        }
    }

    /** A bright dot with a fading tail goes once around the ring, starting at the top. */
    private fun sweep(cv: Canvas, t: Double) {
        val head = t / SWEEP_SECONDS * TAU
        cv.eachLed { r, c, d, a ->
            if (abs(d - RING_RADIUS) < RING_HALF_WIDTH) {
                val fromTop = ((a + PI / 2) % TAU + TAU) % TAU
                val behind = head - fromTop
                cv.put(r, c, if (behind >= 0 && behind < SWEEP_TAIL) 30 + 225 * (1 - behind / SWEEP_TAIL) else 30.0)
            }
        }
    }

    private fun rim(cv: Canvas, s: TunerState.Snapshot, now: Double, look: Look) {
        for (k in look.rim.indices) {
            val done = k < s.tuned.size && s.tuned[k]
            // A string just marked done: its dot drops into place with a bounce.
            if (done && k == s.lastDone && s.sinceDone < BOUNCE_SECONDS) {
                val off = 4 * (1 - bounceOut(s.sinceDone / BOUNCE_SECONDS))
                val a = rimAngle(look.rim.size, k)
                cv.put((C + (11 - off) * sin(a)).roundToInt(), (C + (11 - off) * cos(a)).roundToInt(), 255.0)
                cv.put((C + (10 - off) * sin(a)).roundToInt(), (C + (10 - off) * cos(a)).roundToInt(), 217.0)
                continue
            }
            val v = when {
                done -> 255.0
                k == s.stringIndex && !s.idle -> 110 + 145 * (0.5 + 0.5 * sin(now * 8))
                else -> 46.0
            }
            cv.put(look.rim[k][0], look.rim[k][1], v)
            cv.put(look.rim[k][2], look.rim[k][3], v * 0.85)
        }
    }

    // ---------- Other screens ----------

    /** Shown by the Glyph Toy when the tuner isn't listening: long-press to try starting it. */
    fun startHint(look: Look): IntArray {
        val cv = Canvas()
        cv.ring(60.0)
        cv.shape(PLAY, 9, 11, 255.0)
        return finish(cv, look)
    }

    /** Another app has the microphone for now: a pause sign inside a dim ring. */
    fun paused(look: Look): IntArray {
        val cv = Canvas()
        cv.ring(40.0)
        for (r in 8..16) for (c in 0..1) {
            cv.put(r, 9 + c, 220.0)
            cv.put(r, 14 + c, 220.0)
        }
        return finish(cv, look)
    }

    /** The welcome picture: the ring draws itself dot by dot, the string dots pop in, then it breathes. Loops every 6 s. */
    fun hello(t: Double, look: Look): IntArray {
        val cv = Canvas()
        val cyc = ((t % 6.0) + 6.0) % 6.0
        val q = (cyc / 1.8).coerceAtMost(1.0)
        cv.eachLed { r, c, d, a ->
            if (abs(d - RING_RADIUS) < RING_HALF_WIDTH) {
                val fromTop = ((a + PI / 2) % TAU + TAU) % TAU
                when {
                    q < 1.0 && fromTop <= q * TAU -> cv.put(r, c, if (q * TAU - fromTop < 0.5) 255.0 else 150.0)
                    q >= 1.0 -> cv.put(r, c, 120 + 100 * (0.5 + 0.5 * sin((cyc - 1.8) * 3)))
                }
            }
        }
        val shown = floor((cyc - 1.9) / 0.12).toInt().coerceIn(0, look.rim.size)
        for (k in 0 until shown) {
            cv.put(look.rim[k][0], look.rim[k][1], 200.0)
            cv.put(look.rim[k][2], look.rim[k][3], 170.0)
        }
        return finish(cv, look)
    }

    /** The app screen's mirror while the tuner is off: a dim ring breathing slowly. */
    fun resting(now: Double, look: Look): IntArray {
        val cv = Canvas()
        cv.ring(30 + 25 * (0.5 + 0.5 * sin(now * PI * 0.7)))
        return finish(cv, look)
    }

    /** Listening stopped: the ring shrinks to one dot in the middle, which fades out. */
    fun goodbye(t: Double, look: Look): IntArray {
        val cv = Canvas()
        when {
            t < 0.3 -> cv.ring(200.0)
            t < 0.9 -> {
                val radius = RING_RADIUS * (1 - (t - 0.3) / 0.6)
                cv.eachLed { r, c, d, _ -> if (abs(d - radius) < 1.0) cv.put(r, c, 220.0) }
                if (radius < 1.5) cv.put(C, C, 255.0)
            }
            t < GOODBYE_SECONDS -> cv.put(C, C, 255 * (1 - (t - 0.9) / (GOODBYE_SECONDS - 0.9)))
        }
        return finish(cv, look)
    }

    /** Android is blocking the microphone. */
    fun cross(look: Look): IntArray {
        val cv = Canvas()
        cv.eachLed { r, c, d, _ ->
            val x = (c - C) / 12.5
            val y = (r - C) / 12.5
            if ((abs(x - y) < 0.13 || abs(x + y) < 0.13) && d / 12.5 < 0.75) cv.put(r, c, 255.0)
        }
        return finish(cv, look)
    }

    // ---------- All strings tuned ----------

    private const val GATHER = 0.7
    private const val BURST = 1.2
    private const val HEART_SECONDS = 1.3

    private fun textWidth(text: String) = text.length * 6 - 1
    private fun scrollSeconds(text: String) = (SIZE + textWidth(text)) / MARQUEE_DOTS_PER_SECOND

    /** How long the whole celebration lasts for [text]. */
    fun celebrationSeconds(text: String) = GATHER + BURST + scrollSeconds(text) + HEART_SECONDS

    /** How long [message] takes to scroll past. */
    fun messageSeconds(text: String) = scrollSeconds(text)

    /** A short hint scrolling across a dim ring, e.g. "OPEN APP". [t] seconds since it started. */
    fun message(t: Double, text: String, look: Look): IntArray {
        val cv = Canvas()
        cv.ring(40.0)
        var x = SIZE - floor(t * MARQUEE_DOTS_PER_SECOND).toInt()
        for (ch in text) {
            FONT[ch]?.let { cv.shape(it, 9, x, 255.0) }
            x += 6
        }
        return finish(cv, look)
    }

    /**
     * All strings tuned. An opening animation ([style] 0: the rim dots rush to the centre and burst;
     * 1: fireworks; 2: six strings ring and settle; 3: equalizer bars), then the message scrolls past
     * and a heart beats twice.
     */
    fun celebration(t: Double, text: String, look: Look, style: Int = 0): IntArray {
        val cv = Canvas()
        if (style != 0 && t < GATHER + BURST) {
            when (style) {
                1 -> fireworks(cv, t)
                2 -> strings(cv, t)
                else -> equalizer(cv, t)
            }
            return finish(cv, look)
        }
        if (t < GATHER) {
            val q = t / GATHER
            for (p in look.rim) {
                val a = atan2((p[0] - C).toDouble(), (p[1] - C).toDouble())
                val rr = 10.5 * (1 - q * q)
                cv.put((C + rr * sin(a)).roundToInt(), (C + rr * cos(a)).roundToInt(), 255.0)
                cv.put((C + (rr + 1.3) * sin(a)).roundToInt(), (C + (rr + 1.3) * cos(a)).roundToInt(), 90.0)
            }
            return finish(cv, look)
        }
        val b = t - GATHER
        if (b < BURST) {
            if (b < 0.15) cv.eachLed { r, c, d, _ -> if (d < 2.2) cv.put(r, c, 255.0) }
            for (off in doubleArrayOf(0.0, 0.3)) {
                val radius = (b - off) * 13
                if (radius > 0) {
                    val fade = max(0.0, 1 - (b - off) / 1.0)
                    cv.eachLed { r, c, d, _ -> if (abs(d - radius) < 0.9) cv.put(r, c, 255 * fade) }
                }
            }
            return finish(cv, look)
        }
        val m = b - BURST
        if (m < scrollSeconds(text)) {
            var x = SIZE - floor(m * MARQUEE_DOTS_PER_SECOND).toInt()
            for (ch in text) {
                FONT[ch]?.let { cv.shape(it, 9, x, 255.0) }
                x += 6
            }
            return finish(cv, look)
        }
        val h = m - scrollSeconds(text)
        val beat = 0.55 + 0.45 * abs(sin(h * PI * 1.6))
        cv.shape(HEART, 8, 8, 255 * beat)
        return finish(cv, look)
    }

    /** Three rockets rise from the bottom and pop. Lasts about 1.9 s. */
    private fun fireworks(cv: Canvas, t: Double) {
        val rockets = arrayOf(doubleArrayOf(7.0, 0.0, 7.0), doubleArrayOf(17.0, 0.35, 5.0), doubleArrayOf(12.0, 0.7, 9.0))
        for (rk in rockets) {
            val col = rk[0].toInt()
            val s = t - rk[1]
            val apex = rk[2]
            if (s < 0) continue
            if (s < 0.5) {
                val row = (22 - (22 - apex) * easeOut(s / 0.5)).roundToInt()
                cv.put(row, col, 255.0)
                cv.put(row + 1, col, 110.0)
                cv.put(row + 2, col, 40.0)
            } else if (s < 1.2) {
                val q = (s - 0.5) / 0.7
                val radius = 6 * easeOut(q)
                val fall = q * q * 2.5
                for (i in 0 until 12) {
                    val a = i * TAU / 12
                    cv.put((apex + radius * sin(a) + fall).roundToInt(), (col + radius * cos(a)).roundToInt(), 255 * (1 - q))
                }
                for (i in 0 until 6) {
                    val a = i * TAU / 6 + 0.5
                    cv.put((apex + radius * 0.5 * sin(a) + fall).roundToInt(), (col + radius * 0.5 * cos(a)).roundToInt(), 200 * (1 - q))
                }
                if (q < 0.25) cv.put(apex.toInt(), col, 255.0)
            }
        }
    }

    /** Six lines, plucked one after another, ring like strings and come to rest. */
    private fun strings(cv: Canvas, t: Double) {
        intArrayOf(7, 9, 11, 13, 15, 17).forEachIndexed { j, row ->
            val start = (SIZE - ROW_WIDTHS[row]) / 2 + 1
            val len = ROW_WIDTHS[row] - 2
            val s = t - j * 0.12
            val amp = if (s > 0) exp(-s * 2.2) else 0.0
            for (i in 0 until len) {
                val wave = amp * 1.3 * sin(PI * (i + 0.5) / len) * sin(i * 0.9 + s * 32)
                cv.put((row + wave).roundToInt(), start + i, if (s > 0) 150 + 105 * min(1.0, amp * 2) else 90.0)
            }
        }
    }

    /** Equalizer bars bouncing. */
    private fun equalizer(cv: Canvas, t: Double) {
        for (j in 0 until 9) {
            val col = 4 + j * 2
            val h = (3 + 8 * (0.5 + 0.5 * sin(t * (3 + j * 0.7) + j * 1.3))).roundToInt()
            for (r in 0 until h) cv.put(19 - r, col, 255.0 - r * 12)
        }
    }

    /** Characters the font can draw. */
    fun canDraw(ch: Char) = FONT.containsKey(ch)
}
