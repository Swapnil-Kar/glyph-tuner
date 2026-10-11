package com.glyphtuner

import android.content.Context
import android.os.VibrationEffect
import android.os.VibrationEffect.Composition
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Short, crisp haptics. Uses the phone's built-in haptic "primitives" (a real click, a rise)
 * when it has them, and plain buzzes otherwise.
 */
object Haptics {
    private const val TAG = "Haptics"

    /** A string just locked in tune: one crisp click. Returns roughly how long it lasts (ms). */
    fun lock(context: Context): Long = play(context, 30) { v ->
        if (v.areAllPrimitivesSupported(Composition.PRIMITIVE_CLICK)) {
            VibrationEffect.startComposition()
                .addPrimitive(Composition.PRIMITIVE_CLICK, 1.0f)
                .compose()
        } else {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        }
    }

    /** Every string is done: a quick rising swell, then two clicks. Returns roughly how long it lasts (ms). */
    fun celebrate(context: Context): Long = play(context, 450) { v ->
        if (v.areAllPrimitivesSupported(Composition.PRIMITIVE_QUICK_RISE, Composition.PRIMITIVE_CLICK)) {
            VibrationEffect.startComposition()
                .addPrimitive(Composition.PRIMITIVE_QUICK_RISE, 0.7f)
                .addPrimitive(Composition.PRIMITIVE_CLICK, 1.0f, 60)
                .addPrimitive(Composition.PRIMITIVE_CLICK, 0.6f, 110)
                .compose()
        } else {
            VibrationEffect.createWaveform(longArrayOf(0, 60, 90, 60, 90, 180), -1)
        }
    }

    /** A very light tap, for getting closer to in tune. [strength] 0..1. Returns roughly how long it lasts (ms). */
    fun tick(context: Context, strength: Float): Long = play(context, 15) { v ->
        if (v.areAllPrimitivesSupported(Composition.PRIMITIVE_TICK)) {
            VibrationEffect.startComposition()
                .addPrimitive(Composition.PRIMITIVE_TICK, strength.coerceIn(0.1f, 1f))
                .compose()
        } else {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        }
    }

    private inline fun play(context: Context, millis: Long, effect: (Vibrator) -> VibrationEffect): Long {
        return try {
            val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return 0
            if (!vibrator.hasVibrator()) return 0
            vibrator.vibrate(effect(vibrator))
            millis
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed", e)
            0
        }
    }
}
