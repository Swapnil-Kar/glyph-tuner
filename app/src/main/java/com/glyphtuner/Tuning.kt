package com.glyphtuner

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/** One string to tune. */
data class GuitarString(
    /** "6th" is the thickest guitar string; a ukulele's are "4th" to "1st". */
    val name: String,
    /** Scientific pitch name, e.g. "E2" or "Eb2". */
    val note: String,
    /** What the Glyph Matrix shows. */
    val letter: Char,
    /** Show a small flat sign next to the letter. */
    val flat: Boolean,
    val frequency: Double,
)

/** A named set of strings, thickest/lowest-positioned first (left to right on the matrix). */
class TuningPreset(val id: String, val label: String, private val notes: List<String>) {
    /** The strings at the given reference pitch (A4 = [referenceHz]). */
    fun strings(referenceHz: Double = 440.0): List<GuitarString> {
        val parsed = notes.map { Notes.parse(it) }
        val lowest = parsed.minByOrNull { it.midi }!!
        val highest = parsed.maxByOrNull { it.midi }!!
        // Guitarists write the highest string in lowercase when it shares the lowest one's name (E ... e).
        val lowercaseHighest = lowest.letter == highest.letter && lowest.flat == highest.flat && lowest.midi != highest.midi
        return parsed.mapIndexed { i, n ->
            val number = parsed.size - i
            val name = when (number) { 1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; else -> "${number}th" }
            val letter = if (lowercaseHighest && n === highest) n.letter.lowercaseChar() else n.letter
            GuitarString(name, notes[i], letter, n.flat, Notes.frequency(n.midi, referenceHz))
        }
    }
}

object Tunings {
    val STANDARD = TuningPreset("standard", "Standard (E A D G B E)", listOf("E2", "A2", "D3", "G3", "B3", "E4"))
    val DROP_D = TuningPreset("drop_d", "Drop D (D A D G B E)", listOf("D2", "A2", "D3", "G3", "B3", "E4"))
    val HALF_STEP_DOWN = TuningPreset("half_down", "Half-step down (Eb Ab Db Gb Bb Eb)", listOf("Eb2", "Ab2", "Db3", "Gb3", "Bb3", "Eb4"))
    val OPEN_G = TuningPreset("open_g", "Open G (D G D G B D)", listOf("D2", "G2", "D3", "G3", "B3", "D4"))
    val DADGAD = TuningPreset("dadgad", "DADGAD (D A D G A D)", listOf("D2", "A2", "D3", "G3", "A3", "D4"))
    val UKULELE = TuningPreset("ukulele", "Ukulele (G C E A)", listOf("G4", "C4", "E4", "A4"))

    val ALL = listOf(STANDARD, DROP_D, HALF_STEP_DOWN, OPEN_G, DADGAD, UKULELE)

    fun byId(id: String?): TuningPreset = ALL.firstOrNull { it.id == id } ?: STANDARD
}

object Notes {
    data class Parsed(val letter: Char, val flat: Boolean, val midi: Int)

    private val SEMITONE = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)

    /** "E2", "Eb2", "G4" → letter, flat, MIDI number. */
    fun parse(note: String): Parsed {
        val letter = note[0].uppercaseChar()
        val flat = note.length > 2 && note[1] == 'b'
        val octave = note.substring(if (flat) 2 else 1).toInt()
        val midi = 12 * (octave + 1) + SEMITONE.getValue(letter) - (if (flat) 1 else 0)
        return Parsed(letter, flat, midi)
    }

    fun frequency(midi: Int, referenceHz: Double = 440.0) = referenceHz * 2.0.pow((midi - 69) / 12.0)
}

/** Matches heard pitches to the strings of one tuning. */
class Tuner(val preset: TuningPreset = Tunings.STANDARD, val referenceHz: Double = 440.0) {
    val strings: List<GuitarString> = preset.strings(referenceHz)

    data class Match(val index: Int, val cents: Double)

    /** The string whose target is closest to [freq]. */
    fun nearest(freq: Double): Match {
        var best = 0
        var bestCents = cents(freq, strings[0].frequency)
        for (i in 1 until strings.size) {
            val c = cents(freq, strings[i].frequency)
            if (abs(c) < abs(bestCents)) {
                best = i
                bestCents = c
            }
        }
        return Match(best, bestCents)
    }

    /**
     * Like [nearest], but fixes the classic phone-mic mistake: phone microphones barely pick up
     * a low string's fundamental, so a note can be heard one octave too high. If halving the
     * frequency lands much closer to a real string, that's what was played.
     */
    fun resolve(freq: Double): Match {
        val full = nearest(freq)
        val half = freq / 2.0
        if (half >= MIN_HZ) {
            val h = nearest(half)
            if (abs(h.cents) + 60.0 < abs(full.cents)) return h
        }
        return full
    }

    /**
     * Measures [freq] against one chosen string only (when you've locked a string).
     * Whole octaves are ignored, so a strong overtone still reads right.
     */
    fun against(index: Int, freq: Double): Match {
        var c = cents(freq, strings[index].frequency)
        while (c > 600.0) c -= 1200.0
        while (c < -600.0) c += 1200.0
        return Match(index, c)
    }

    companion object {
        /** The detector's range: low enough for Drop D, high enough for a ukulele's A. */
        const val MIN_HZ = 60.0
        const val MAX_HZ = 500.0

        /** How far [freq] is from [target], in cents (100 cents = one semitone). */
        fun cents(freq: Double, target: Double): Double = 1200.0 * ln(freq / target) / ln(2.0)
    }
}
