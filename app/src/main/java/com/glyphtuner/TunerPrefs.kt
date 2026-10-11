package com.glyphtuner

import android.content.Context
import java.util.Calendar

/** The tuner's settings and its small memory of past celebrations, saved on the phone. */
class TunerPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("tuner_settings", Context.MODE_PRIVATE)

    var tuningId: String
        get() = prefs.getString("tuning", Tunings.STANDARD.id) ?: Tunings.STANDARD.id
        set(v) { prefs.edit().putString("tuning", v).apply() }

    /** Reference pitch for A4, in Hz. */
    var referenceHz: Int
        get() = prefs.getInt("reference", 440)
        set(v) { prefs.edit().putInt("reference", v).apply() }

    /** Start listening as soon as the app opens. */
    var autoStart: Boolean
        get() = prefs.getBoolean("auto_start", true)
        set(v) { prefs.edit().putBoolean("auto_start", v).apply() }

    /** A short buzz when each string is done. */
    var vibrate: Boolean
        get() = prefs.getBoolean("vibrate", true)
        set(v) { prefs.edit().putBoolean("vibrate", v).apply() }

    /** Light taps at 20, 10 and 5 cents while tuning in. */
    var closerTicks: Boolean
        get() = prefs.getBoolean("closer_ticks", true)
        set(v) { prefs.edit().putBoolean("closer_ticks", v).apply() }

    /** Dimmer Glyph Matrix from 10 pm to 6 am. */
    var dimAtNight: Boolean
        get() = prefs.getBoolean("dim_at_night", false)
        set(v) { prefs.edit().putBoolean("dim_at_night", v).apply() }

    /** Turn the picture upside down, for viewing from the other side. */
    var rotate180: Boolean
        get() = prefs.getBoolean("rotate_180", false)
        set(v) { prefs.edit().putBoolean("rotate_180", v).apply() }

    /** The first-run tour has been seen (finished or skipped). */
    var tourDone: Boolean
        get() = prefs.getBoolean("tour_done", false)
        set(v) { prefs.edit().putBoolean("tour_done", v).apply() }

    /** How many full tune-ups have been celebrated so far. */
    val tuneUps: Int get() = prefs.getInt("celebrations", 0)

    fun tuner() = Tuner(Tunings.byId(tuningId), referenceHz.toDouble())

    /** Brightness for the Glyph Matrix right now. */
    fun brightness(calendar: Calendar = Calendar.getInstance()): Double {
        if (!dimAtNight) return 1.0
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        return if (hour >= 22 || hour < 6) NIGHT_BRIGHTNESS else 1.0
    }

    fun look(tuner: Tuner) = TunerArt.Look(tuner.strings, rotate180, brightness())

    /**
     * The line for this celebration.
     * Counts celebrations so milestones like "TUNE-UP #10" can appear.
     */
    @Synchronized
    fun nextCelebration(): String? {
        val now = System.currentTimeMillis()
        val count = prefs.getInt("celebrations", 0) + 1
        val lastAt = prefs.getLong("last_celebration_at", 0L)
        val minutesSince = if (lastAt == 0L) null else (now - lastAt) / 60000.0
        val text = Messages.choose(count, minutesSince) ?: return null
        prefs.edit().putInt("celebrations", count).putLong("last_celebration_at", now).apply()
        return text
    }

    companion object {
        const val NIGHT_BRIGHTNESS = 0.4
        val REFERENCES = listOf(440, 442, 432)
    }
}
