package com.glyphtuner

import java.util.Calendar

/** The short line that scrolls across the back once every string is tuned. */
object Messages {
    val ALWAYS = listOf(
        "GO PLAY", "LET IT RING", "MAKE MUSIC", "PLAY ON", "YOU'RE READY",
        "STRUM AWAY", "SOUNDS GOOD", "GO SHINE", "ALL YOURS", "ENJOY IT",
    )

    /** Rare lines for special occasions. */
    const val FIRST_EVER = "FIRST OF MANY"
    const val WELCOME_BACK = "HELLO AGAIN"
    val MILESTONES = setOf(10, 25, 50, 100, 250, 500, 1000)
    fun milestone(count: Int) = "TUNE-UP #$count"

    /** Away at least this long earns a "hello again". */
    const val WELCOME_BACK_DAYS = 7.0

    /** Extra lines that only appear at certain times. */
    fun forTime(calendar: Calendar): List<String> {
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val day = calendar.get(Calendar.DAY_OF_WEEK)
        val extra = mutableListOf<String>()
        if (hour >= 23 || hour < 5) extra += "PLAY SOFTLY"
        if ((day == Calendar.FRIDAY || day == Calendar.SATURDAY) && hour >= 17) extra += "WEEKEND JAM!"
        return extra
    }

    /**
     * Picks the celebration line. Every tune-up gets one (a session never celebrates twice;
     * TunerState makes sure of that).
     * [count] is this celebration's number (1 = first ever); [minutesSinceLast] is null if never.
     */
    fun choose(count: Int, minutesSinceLast: Double?, calendar: Calendar = Calendar.getInstance()): String? {
        if (count in MILESTONES) return milestone(count)
        if (count == 1) return FIRST_EVER
        if (minutesSinceLast != null && minutesSinceLast >= WELCOME_BACK_DAYS * 24 * 60) return WELCOME_BACK
        return pick(calendar)
    }

    private var last = ""

    /** A random everyday line, never the same one twice in a row. */
    @Synchronized
    fun pick(calendar: Calendar = Calendar.getInstance()): String {
        val pool = ALWAYS + forTime(calendar)
        var msg: String
        do {
            msg = pool.random()
        } while (msg == last && pool.size > 1)
        last = msg
        return msg
    }
}
