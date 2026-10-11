package com.glyphtuner

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow

/**
 * The little films in the "How it works" guide. Each one runs a real [TunerState] fed with
 * made-up plucks, so the picture is exactly what the back of the phone would show. Each step
 * loops forever. No Android code, so it can be tested anywhere.
 *
 * Steps: 0 flip and pluck (the letter changes), 1 tighten / loosen (the arrow), 2 the ring says
 * how far (it slows down and glows), 3 click and a dot (a string gets marked done),
 * 4 go play (the last string, then the celebration).
 */
class GuideDemo(val step: Int, private val tuner: Tuner = Tuner()) {

    /** What sounds at one moment of a film: string [index] played [cents] off. */
    private class Note(val index: Int, val cents: Double)

    private val look = TunerArt.Look(tuner.strings)
    private val count = tuner.strings.size

    /** Strings already in tune before the film starts (for steps 3 and 4). */
    private val alreadyDone: Int = when (step) {
        3 -> min(3, count - 2).coerceAtLeast(0)
        4 -> count - 1
        else -> 0
    }

    /** Seconds per loop. */
    val period: Double = when (step) {
        0 -> 7.6
        1 -> 6.4
        2 -> 7.2
        3 -> 5.0
        else -> FINALE_START + 1.4 + TunerArt.celebrationSeconds(CELEBRATION_TEXT) + 1.2
    }

    private var state = TunerState(tuner) { CELEBRATION_TEXT }
    private var loopStartedAt = 0.0
    /** How far into the current loop the made-up microphone has got. */
    private var fedTo = 0.0
    private var lastTunedCount = 0
    private var lastCelebration: TunerState.Celebration? = null

    /** A string was marked done since the previous frame: the guide clicks the phone, like the real thing. */
    var justLocked = false
        private set

    /** The celebration started since the previous frame. */
    var justCelebrated = false
        private set

    init {
        restart(0.0)
    }

    /** The picture [t] seconds after the film first started. Call it with times that only go forward. */
    fun frame(t: Double): IntArray {
        if (t - loopStartedAt >= period) restart(loopStartedAt + period * floor((t - loopStartedAt) / period))
        val u = t - loopStartedAt
        while (fedTo + HOP <= u) {
            fedTo += HOP
            feed(fedTo, note(fedTo))
        }
        val snap = state.snapshot(SCRIPT_START + u)
        justLocked = snap.tunedCount > lastTunedCount
        justCelebrated = snap.celebration != null && snap.celebration != lastCelebration
        lastTunedCount = snap.tunedCount
        lastCelebration = snap.celebration
        return TunerArt.render(snap, SCRIPT_START + u, look)
    }

    /** A fresh tuner for the next loop, with the earlier strings already tuned where the step needs it. */
    private fun restart(at: Double) {
        state = TunerState(tuner) { CELEBRATION_TEXT }
        loopStartedAt = at
        fedTo = 0.0
        // Tune the earlier strings out of sight, then leave a quiet gap so the film starts calm.
        var s = 0.0
        for (i in 0 until alreadyDone) {
            val end = s + 0.7
            while (s < end) { hear(s, i, 0.0); s += HOP }
            val gapEnd = s + 0.7
            while (s < gapEnd) { quiet(s); s += HOP }
        }
        while (s < SCRIPT_START - HOP) { quiet(s); s += HOP }
        val snap = state.snapshot(SCRIPT_START)
        lastTunedCount = snap.tunedCount
        lastCelebration = snap.celebration
        justLocked = false
        justCelebrated = false
    }

    private fun feed(u: Double, n: Note?) {
        val t = SCRIPT_START + u
        if (n == null) quiet(t) else hear(t, n.index, n.cents)
    }

    private fun hear(t: Double, index: Int, cents: Double) {
        val freq = tuner.strings[index.coerceIn(0, count - 1)].frequency * 2.0.pow(cents / 1200.0)
        state.onAudio(t, freq, 0.95, -32.0, false)
    }

    private fun quiet(t: Double) = state.onAudio(t, null, 0.0, -75.0, false)

    /** The script: what sounds [u] seconds into the loop, or null for silence. */
    private fun note(u: Double): Note? = when (step) {
        // A few different strings, none in tune: the letter changes each time.
        0 -> when {
            u < 0.6 -> null
            u < 2.0 -> Note(0, -16.0)
            u < 2.3 -> null
            u < 3.7 -> Note(1, 12.0)
            u < 4.0 -> null
            u < 5.4 -> Note(2 % count, -9.0)
            u < 5.7 -> null
            u < 6.9 -> Note(3 % count, 18.0)
            else -> null
        }
        // Too low (arrow up), then turned too far (arrow down).
        1 -> {
            val i = min(3, count - 1)
            when {
                u < 0.4 -> null
                u < 2.6 -> Note(i, lerp(-30.0, -14.0, (u - 0.4) / 2.2))
                u < 3.2 -> null
                u < 5.4 -> Note(i, lerp(22.0, 12.0, (u - 3.2) / 2.2))
                else -> null
            }
        }
        // Far off, then slowly closer: the ring spins fast, slows down, stops and glows.
        2 -> {
            val i = min(2, count - 1)
            when {
                u < 0.4 -> null
                u < 4.4 -> Note(i, -45.0 * (1 - (u - 0.4) / 4.0).pow(2))
                u < 6.2 -> Note(i, 0.0)
                else -> null
            }
        }
        // The next string comes into tune: click, and its dot pops in.
        3 -> when {
            u < 0.6 -> null
            u < 2.2 -> Note(alreadyDone, -14.0 * (1 - (u - 0.6) / 1.6).pow(2))
            u < 3.8 -> Note(alreadyDone, 0.0)
            else -> null
        }
        // The last string: in tune, then the celebration.
        else -> when {
            u < 0.5 -> null
            u < FINALE_START -> Note(count - 1, -9.0 * (1 - (u - 0.5) / (FINALE_START - 0.5)).pow(2))
            u < FINALE_START + 1.0 -> Note(count - 1, 0.0)
            else -> null
        }
    }

    private fun lerp(a: Double, b: Double, f: Double) = a + (b - a) * f.coerceIn(0.0, 1.0)

    companion object {
        const val STEPS = 5
        /** The line the guide's celebration scrolls. */
        const val CELEBRATION_TEXT = "GO PLAY"
        /** One made-up microphone reading, about as often as the real one. */
        private const val HOP = 1024.0 / 44100.0
        /** Script time 0, after room for the strings tuned out of sight. */
        private const val SCRIPT_START = 20.0
        /** In the last step, when the final string reaches the target. */
        private const val FINALE_START = 1.6
    }
}
