package com.glyphtuner

import kotlin.math.abs
import kotlin.random.Random

/**
 * Everything the tuner knows: which string you're playing, how far off it is, which strings
 * are done, and whether to celebrate. Readings arrive from the microphone thread through
 * [onAudio]; the drawing loop reads it through [snapshot]. Times are in seconds.
 * No Android code, so it can be tested anywhere.
 *
 * [celebrationMessage] returns the line to scroll when all strings are tuned (null skips it).
 * There's at most one celebration per listening session: a string that slips and gets re-tuned
 * afterwards doesn't set it off again. Each new session (start, tune, it switches itself off)
 * gets its own.
 */
class TunerState(
    val tuner: Tuner = Tuner(),
    private val celebrationMessage: () -> String? = { Messages.pick() },
) {

    /** [style]: which opening animation plays (0 burst, 1 fireworks, 2 six strings, 3 equalizer). */
    data class Celebration(val startedAt: Double, val text: String, val style: Int = 0)

    class Snapshot(
        /** Index into [Tuner.strings], or -1 before the first note. */
        val stringIndex: Int,
        /** How far off the current string is, in cents (negative = too low). */
        val cents: Double,
        /** A note is sounding right now. */
        val noteOn: Boolean,
        /** Sounding and in the in-tune zone. */
        val inTune: Boolean,
        /** Which way the arrow points: true = ▲ (too low, tighten). Steadied so it doesn't flicker. */
        val arrowUp: Boolean,
        /** A string was just marked done: hold the glowing ring for a moment. */
        val showDone: Boolean,
        /** Seconds since the last clear note (very large if none yet). */
        val sinceNote: Double,
        /** Seconds since the string changed. */
        val sinceSelect: Double,
        val tuned: BooleanArray,
        /** Rotation of the strobe ring, in radians. */
        val phase: Double,
        /** Android is feeding the app pure silence for no known reason. */
        val blocked: Boolean,
        /** Another app (a call, a voice note) has the microphone for now. */
        val paused: Boolean,
        val celebration: Celebration?,
        /** How loud the microphone is right now, 0 (quiet room) to 1 (a string ringing close by). */
        val level: Double = 0.0,
        /** The string shown before the current one (for the letter changing shape), or -1. */
        val previousIndex: Int = -1,
        /** Seconds since the note came into tune (very large if it isn't). */
        val sinceInTune: Double = 1e9,
        /** Seconds since the arrow last changed direction mid-note. */
        val sinceArrowFlip: Double = 1e9,
        /** The string most recently marked done, and how long ago. */
        val lastDone: Int = -1,
        val sinceDone: Double = 1e9,
    ) {
        val idle: Boolean get() = sinceNote > IDLE_SECONDS
        val tunedCount: Int get() = tuned.count { it }
    }

    private val count = tuner.strings.size
    private val tuned = BooleanArray(count)
    private var current = -1
    private var previous = -1
    private var inTuneAt = Double.NaN
    private var arrowFlippedAt = Double.NaN
    private var lastDone = -1
    private var doneAt = Double.NaN
    private var lastStyle = -1
    private var cents = 0.0
    private var noteOn = false
    private var noteStartedAt = Double.NaN
    private var lastPitchAt = Double.NaN
    private var selectedAt = Double.NaN
    private var inTune = false
    private var inTuneSince = Double.NaN
    private var outOfTuneSince = Double.NaN
    private var arrowUp = true
    private var arrowFlipSince = Double.NaN
    private var doneUntil = Double.NaN
    private var candidate = -1
    private var candidateHits = 0
    private val recent = DoubleArray(7)
    private var recentCount = 0
    private var recentPos = 0
    private val onset = ArrayList<Tuner.Match>(ONSET_READINGS)
    private var phase = 0.0
    private var lastSnapshotAt = Double.NaN
    private var celebration: Celebration? = null
    private var celebrated = false
    private var silentSince = Double.NaN
    private var blocked = false
    private var paused = false
    private var level = 0.0
    private var locked = -1

    /** Which string is locked (only that one is listened for), or -1 for automatic. */
    val lockedString: Int @Synchronized get() = locked

    /**
     * Listen for one string only ([index]), or for any string (-1). Handy in a noisy room,
     * or when a string is so far off that it sounds like its neighbour.
     */
    @Synchronized
    fun lockString(index: Int) {
        val i = if (index in 0 until count) index else -1
        if (i == locked) return
        locked = i
        onset.clear()
        candidate = -1
        candidateHits = 0
        if (i >= 0 && i != current) {
            previous = current
            current = i
            selectedAt = lastSnapshotAt
            noteOn = false
            inTune = false
            inTuneSince = Double.NaN
            outOfTuneSince = Double.NaN
            doneUntil = Double.NaN
            resetRecent()
        }
    }

    /** Another app has taken the microphone (true) or given it back (false). */
    @Synchronized
    fun setPaused(value: Boolean) {
        paused = value
        if (value) {
            level = 0.0
            blocked = false
            silentSince = Double.NaN
        }
    }

    /**
     * One reading from the microphone. [freq] is the detected pitch in Hz (null if none),
     * [clarity] how clean it is (0..1), [db] the loudness, [allZero] true for pure digital silence.
     * A new note needs a few clean, loud, steady readings in a row (so talking doesn't flash
     * letters); a note that's already ringing is followed further into its fade.
     */
    @Synchronized
    fun onAudio(now: Double, freq: Double?, clarity: Double, db: Double, allZero: Boolean) {
        // Loudness for the "it hears you" shimmer: rises fast, falls slowly.
        val target = if (paused || allZero) 0.0 else ((db - LEVEL_FLOOR_DB) / (LEVEL_CEIL_DB - LEVEL_FLOOR_DB)).coerceIn(0.0, 1.0)
        level += (if (target > level) LEVEL_RISE else LEVEL_FALL) * (target - level)

        if (paused) {
            onset.clear()
            releaseIfQuiet(now)
            return
        }
        if (allZero) {
            if (silentSince.isNaN()) silentSince = now
            if (now - silentSince > BLOCKED_AFTER_SECONDS) blocked = true
        } else {
            silentSince = Double.NaN
            blocked = false
        }

        val usable = freq != null && if (noteOn) {
            clarity >= SUSTAIN_CLARITY && db > SUSTAIN_GATE_DB
        } else {
            clarity >= ONSET_CLARITY && db > ONSET_GATE_DB
        }
        if (!usable) {
            onset.clear()
            releaseIfQuiet(now)
            return
        }

        val match = if (locked >= 0) tuner.against(locked, freq!!) else tuner.resolve(freq!!)

        // A new note: wait for a few readings that agree, so speech and noise don't count.
        if (!noteOn || current < 0) {
            onset += match
            if (onset.size > ONSET_READINGS) onset.removeAt(0)
            if (onset.size < ONSET_READINGS) return
            val sameString = onset.all { it.index == match.index }
            val spread = onset.maxOf { it.cents } - onset.minOf { it.cents }
            if (!sameString || spread > ONSET_SPREAD_CENTS) return
            onset.clear()
            lastPitchAt = now
            startNote(match, now)
            return
        }

        lastPitchAt = now

        // While a note rings, only switch strings after several readings agree,
        // so one odd reading doesn't make the letter flicker.
        if (match.index != current) {
            if (match.index == candidate) candidateHits++ else {
                candidate = match.index
                candidateHits = 1
            }
            if (candidateHits >= SWITCH_READINGS) startNote(match, now)
            return
        }

        candidate = -1
        candidateHits = 0
        push(match.cents)
        cents += SMOOTHING * (median() - cents)
        update(now)
    }

    /** The current state for drawing. Also advances the strobe ring's rotation. */
    @Synchronized
    fun snapshot(now: Double): Snapshot {
        releaseIfQuiet(now)
        val dt = if (lastSnapshotAt.isNaN()) 0.0 else (now - lastSnapshotAt).coerceIn(0.0, 0.1)
        lastSnapshotAt = now

        val showInTune = noteOn && inTune
        if (noteOn && !showInTune) phase += cents.coerceIn(-50.0, 50.0) * STROBE_SPEED * dt

        celebration?.let {
            if (now - it.startedAt > TunerArt.celebrationSeconds(it.text)) celebration = null
        }

        return Snapshot(
            stringIndex = current,
            cents = cents,
            noteOn = noteOn,
            inTune = showInTune,
            arrowUp = arrowUp,
            showDone = !doneUntil.isNaN() && now < doneUntil,
            sinceNote = if (lastPitchAt.isNaN()) 1e9 else now - lastPitchAt,
            sinceSelect = if (selectedAt.isNaN()) 1e9 else now - selectedAt,
            tuned = tuned.copyOf(),
            phase = phase,
            blocked = blocked,
            paused = paused,
            celebration = celebration,
            level = level,
            previousIndex = previous,
            sinceInTune = if (showInTune && !inTuneAt.isNaN()) now - inTuneAt else 1e9,
            sinceArrowFlip = if (arrowFlippedAt.isNaN()) 1e9 else now - arrowFlippedAt,
            lastDone = lastDone,
            sinceDone = if (doneAt.isNaN()) 1e9 else now - doneAt,
        )
    }

    private fun startNote(match: Tuner.Match, now: Double) {
        if (match.index != current) {
            previous = current
            current = match.index
            selectedAt = now
            doneUntil = Double.NaN
        }
        noteOn = true
        noteStartedAt = now
        candidate = -1
        candidateHits = 0
        inTune = false
        inTuneSince = Double.NaN
        outOfTuneSince = Double.NaN
        arrowFlipSince = Double.NaN
        resetRecent()
        push(match.cents)
        cents = match.cents
        arrowUp = cents < 0
        arrowFlippedAt = Double.NaN
        inTuneAt = Double.NaN
    }

    private fun releaseIfQuiet(now: Double) {
        if (noteOn && now - lastPitchAt > NOTE_RELEASE_SECONDS) {
            noteOn = false
            inTune = false
            inTuneSince = Double.NaN
            outOfTuneSince = Double.NaN
            candidate = -1
            candidateHits = 0
            resetRecent()
        }
    }

    private fun update(now: Double) {
        // The first moment of a pluck rings sharp, so it doesn't count yet.
        val settled = now - noteStartedAt >= ATTACK_SECONDS
        val off = abs(cents)

        // In-tune zone with hysteresis: easy to enter, only left when clearly off.
        if (!inTune && off <= IN_TUNE_CENTS) {
            inTune = true
            inTuneAt = now
        }
        else if (inTune && off > OUT_OF_TUNE_CENTS) inTune = false

        // Arrow direction: far off it follows at once; near the target the other
        // direction has to hold for a moment, so it doesn't flicker ▲▼▲.
        val wantUp = cents < 0
        if (wantUp == arrowUp) {
            arrowFlipSince = Double.NaN
        } else if (off > 10.0) {
            arrowUp = wantUp
            arrowFlippedAt = now
            arrowFlipSince = Double.NaN
        } else {
            if (arrowFlipSince.isNaN()) arrowFlipSince = now
            if (now - arrowFlipSince >= ARROW_FLIP_SECONDS) {
                arrowUp = wantUp
                arrowFlippedAt = now
                arrowFlipSince = Double.NaN
            }
        }

        if (!settled) return

        if (inTune) {
            outOfTuneSince = Double.NaN
            if (inTuneSince.isNaN()) inTuneSince = now
            if (!tuned[current] && now - inTuneSince >= LOCK_SECONDS) {
                tuned[current] = true
                doneUntil = now + DONE_HOLD_SECONDS
                lastDone = current
                doneAt = now
                if (tuned.all { it } && !celebrated) {
                    celebrated = true
                    celebrationMessage()?.let {
                        // A different opening animation from last time.
                        var style = Random.nextInt(CELEBRATION_STYLES)
                        if (style == lastStyle) style = (style + 1) % CELEBRATION_STYLES
                        lastStyle = style
                        celebration = Celebration(now, it, style)
                    }
                }
            }
        } else {
            inTuneSince = Double.NaN
            // A string marked done that has clearly drifted gets unmarked, so the rim dots stay honest.
            if (off > UNTUNE_CENTS) {
                if (outOfTuneSince.isNaN()) outOfTuneSince = now
                if (tuned[current] && now - outOfTuneSince >= UNTUNE_SECONDS) {
                    tuned[current] = false
                }
            } else {
                outOfTuneSince = Double.NaN
            }
        }
    }

    private fun resetRecent() {
        recentCount = 0
        recentPos = 0
    }

    private fun push(value: Double) {
        recent[recentPos] = value
        recentPos = (recentPos + 1) % recent.size
        if (recentCount < recent.size) recentCount++
    }

    private fun median(): Double {
        val sorted = recent.copyOf(recentCount)
        sorted.sort()
        return sorted[recentCount / 2]
    }

    companion object {
        /** Within this many cents counts as in tune (most people can't hear under ~5). */
        const val IN_TUNE_CENTS = 4.0
        /** Once in tune, only further off than this breaks it. */
        const val OUT_OF_TUNE_CENTS = 8.0
        /** Hold in tune this long and the string is marked done. */
        const val LOCK_SECONDS = 0.3
        /** How long the glowing "done" ring stays after a string is marked. */
        const val DONE_HOLD_SECONDS = 1.5
        /** A done string drifting further than this... */
        const val UNTUNE_CENTS = 12.0
        /** ...for this long gets unmarked. */
        const val UNTUNE_SECONDS = 0.8
        /** Ignore the sharp start of each pluck for this long. */
        const val ATTACK_SECONDS = 0.12
        /** Near the target, the arrow needs this long pointing the other way to flip. */
        const val ARROW_FLIP_SECONDS = 0.35
        /** No usable reading for this long = the note has stopped. */
        const val NOTE_RELEASE_SECONDS = 0.5
        /** No note for this long = back to the waiting screen. */
        const val IDLE_SECONDS = 3.0
        /** Readings needed before switching strings mid-note. */
        const val SWITCH_READINGS = 4
        /** Steady readings needed before a new note shows (about 70 ms). */
        const val ONSET_READINGS = 3
        /** Those readings must agree within this many cents (a voice slides far more). */
        const val ONSET_SPREAD_CENTS = 30.0
        /** How quickly the reading follows new values (0..1). */
        const val SMOOTHING = 0.25
        /** Strobe speed: radians per second for each cent off. */
        const val STROBE_SPEED = 0.075
        /** Pure silence for this long means Android is blocking the mic. */
        const val BLOCKED_AFTER_SECONDS = 1.0

        /** A new note must be this clean (0..1)... */
        const val ONSET_CLARITY = 0.85
        /** ...and this loud (dB below full scale). */
        const val ONSET_GATE_DB = -60.0
        /** A ringing note is followed down to this clarity... */
        const val SUSTAIN_CLARITY = 0.70
        /** ...and this loudness. */
        const val SUSTAIN_GATE_DB = -70.0

        /** Loudness shown as 0 (a quiet room)... */
        const val LEVEL_FLOOR_DB = -58.0
        /** ...up to 1 (a string ringing close by). */
        const val LEVEL_CEIL_DB = -28.0
        /** How fast the level follows a louder / quieter reading (per reading, about 23 ms). */
        const val LEVEL_RISE = 0.5
        const val LEVEL_FALL = 0.08

        /** How many different celebration openings there are. */
        const val CELEBRATION_STYLES = 4
    }
}
