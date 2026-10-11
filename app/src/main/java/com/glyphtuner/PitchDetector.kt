package com.glyphtuner

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Finds the pitch of a single plucked string using the YIN method
 * (de Cheveigné & Kawahara, 2002), limited to the instrument range.
 *
 * Two passes: a rough pass on the raw sound, then a precise pass after filtering out the
 * high overtones. Real steel strings are slightly stiff, so their high overtones run sharp;
 * measuring close to the fundamental keeps the reading honest, like a clip-on tuner.
 * No Android code, so it can be tested anywhere.
 */
class PitchDetector(
    private val sampleRate: Int,
    private val windowSize: Int = 2048,
    minFrequency: Double = Tuner.MIN_HZ,
    maxFrequency: Double = Tuner.MAX_HZ,
    private val threshold: Float = 0.15f,
) {
    private val minLag = (sampleRate / maxFrequency).toInt()
    private val maxLag = (sampleRate / minFrequency).toInt()
    private val diff = FloatArray(maxLag + 2)

    /** Samples analysed (the most recent ones at the end of the buffer). */
    private val analysed = windowSize + maxLag + 2

    /** Extra samples in front so the filter has settled before the analysed part. */
    private val warmUp = 512

    /** How many samples [detect] reads from the end of the buffer you pass in. */
    val requiredSamples = analysed + warmUp

    private val filtered = FloatArray(requiredSamples)

    data class Result(
        val frequency: Double,
        /** 0..1. Close to 1 = a clean, steady note. Noise scores low. */
        val clarity: Double,
    )

    /** The pitch of the last [requiredSamples] samples of [samples], or null if no clear note. */
    fun detect(samples: FloatArray): Result? {
        val start = samples.size - requiredSamples
        require(start >= 0) { "Need at least $requiredSamples samples" }

        // Pass 1: rough pitch and clarity from the raw sound.
        val rough = yin(samples, samples.size - analysed, maxLag) ?: return null

        // Pass 2: keep only the lowest few overtones, then measure precisely.
        val cutoff = (rough.frequency * 3.5).coerceIn(250.0, 2000.0)
        lowPass(samples, start, cutoff)
        val searchTo = minOf(maxLag, (sampleRate / rough.frequency * 2.2).toInt() + 2)
        val precise = yin(filtered, warmUp, searchTo) ?: return rough

        // Trust the precise pass only when it agrees with the rough one (same note or an octave).
        val ratio = precise.frequency / rough.frequency
        val agrees = ratio in 0.97..1.03 || ratio in 0.485..0.515 || ratio in 1.94..2.06
        return if (agrees) Result(precise.frequency, rough.clarity) else rough
    }

    private fun yin(buf: FloatArray, start: Int, lagLimit: Int): Result? {
        // 1. Difference function: how much the sound differs from itself shifted by tau samples.
        for (tau in 1..lagLimit + 1) {
            var sum = 0f
            for (i in 0 until windowSize) {
                val d = buf[start + i] - buf[start + i + tau]
                sum += d * d
            }
            diff[tau] = sum
        }

        // 2. Cumulative mean normalisation, so silence and loudness don't fool the search.
        diff[0] = 1f
        var running = 0f
        for (tau in 1..lagLimit + 1) {
            running += diff[tau]
            diff[tau] = if (running > 0f) diff[tau] * tau / running else 1f
        }

        // 3. First dip under the threshold (the shortest repeating period), then its bottom.
        var best = -1
        var tau = minLag
        while (tau <= lagLimit) {
            if (diff[tau] < threshold) {
                while (tau + 1 <= lagLimit && diff[tau + 1] < diff[tau]) tau++
                best = tau
                break
            }
            tau++
        }
        if (best < 1) return null
        // (Octave mistakes on the low strings are fixed afterwards by Tuner.resolve,
        // which knows what notes the instrument can actually play.)

        // 4. Parabolic interpolation between neighbouring lags for sub-sample accuracy.
        val x0 = diff[best - 1]
        val x1 = diff[best]
        val x2 = diff[best + 1]
        val denom = x0 - 2f * x1 + x2
        val shift = if (denom != 0f) (0.5f * (x0 - x2) / denom).coerceIn(-1f, 1f) else 0f
        val period = best + shift
        return Result(sampleRate / period.toDouble(), (1.0 - x1).coerceIn(0.0, 1.0))
    }

    /** Two cascaded low-pass biquads (12 dB/octave each) from [start] to the end, into [filtered]. */
    private fun lowPass(src: FloatArray, start: Int, cutoff: Double) {
        val w = 2 * PI * cutoff / sampleRate
        val alpha = sin(w) / (2 * 0.7071)
        val cw = cos(w)
        val a0 = 1 + alpha
        val b0 = ((1 - cw) / 2 / a0).toFloat()
        val b1 = ((1 - cw) / a0).toFloat()
        val b2 = b0
        val a1 = (-2 * cw / a0).toFloat()
        val a2 = ((1 - alpha) / a0).toFloat()

        for (stage in 0 until 2) {
            var x1 = 0f; var x2 = 0f; var y1 = 0f; var y2 = 0f
            for (i in 0 until requiredSamples) {
                val x = if (stage == 0) src[start + i] else filtered[i]
                val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                x2 = x1; x1 = x
                y2 = y1; y1 = y
                filtered[i] = y
            }
        }
    }
}
