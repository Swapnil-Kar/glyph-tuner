package com.glyphtuner

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The running tuner: reads the microphone on a background thread, works out the pitch,
 * and about 30 times a second draws the Strobe Ring on the Glyph Matrix.
 * Started and stopped by [TunerService]. Call [start]/[stop]/[reloadSettings] on the main thread.
 */
object TunerEngine {
    private const val TAG = "TunerEngine"
    private const val PLUCK_TAG = "GlyphTunerPluck"
    private const val SAMPLE_RATE = 44100
    /** New samples per reading: about 23 ms. */
    private const val HOP = 1024
    private const val FRAME_MS = 33L
    /** Stops listening by itself after this long without a note. */
    const val AUTO_STOP_SECONDS = 300.0
    /** Every string in tune and nothing plucked for this long: done, switch off. */
    const val DONE_STOP_SECONDS = 30.0
    /** Ignore the microphone this long after a buzz, so the tuner doesn't hear its own vibration. */
    private const val BUZZ_MUTE_SECONDS = 0.35
    /** A tick is tiny, so the tuner only stops listening very briefly. */
    private const val TICK_MUTE_SECONDS = 0.12

    private val main = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { main.post(it) }
    private var appContext: Context? = null
    @Volatile private var record: AudioRecord? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var state = TunerState()
    private var prefs: TunerPrefs? = null
    private var look = TunerArt.Look(Tuner().strings)
    private var startedAt = 0.0
    private var lastTunedCount = 0
    private var lookCheckedAt = 0.0
    private var lastCelebration: TunerState.Celebration? = null
    @Volatile private var muteUntil = 0.0

    @Volatile var running = false
        private set

    /** The latest state, for the app screen. */
    @Volatile var latest: TunerState.Snapshot? = null
        private set

    /** The tuning currently in use, for the app screen. */
    @Volatile var currentTuner: Tuner = Tuner()
        private set

    /** Called (main thread) when the tuner has been quiet for [AUTO_STOP_SECONDS], or all strings are done. */
    var onAutoStop: (() -> Unit)? = null

    /** Called (main thread) with a one-line status for the notification, when it changes (at most once a second). */
    var onStatus: ((String) -> Unit)? = null
    private var lastStatus = ""
    private var lastStatusAt = 0.0

    /** The Tuner Glyph Toy is on screen: draw through it. Main thread only. */
    var toyActive = false
    /** No toy on screen: draw from the app. Main thread only. */
    var appActive = false

    fun now() = SystemClock.elapsedRealtime() / 1000.0

    fun start(context: Context): Boolean {
        if (running) return true
        val ctx = context.applicationContext
        appContext = ctx
        val rec = openMic(ctx) ?: return false
        reloadSettings(ctx)
        record = rec
        startedAt = now()
        running = true
        lastStatus = ""
        lastStatusAt = 0.0
        finishGoodbye()
        val audio = Thread({ audioLoop() }, "TunerAudio")
        thread = audio
        audio.start()
        main.post(ticker)
        return true
    }

    /**
     * Stops listening. The goodbye animation then plays on the matrix for a moment, and
     * [afterGoodbye] runs once it's over (or straight away if the tuner wasn't running).
     */
    fun stop(afterGoodbye: (() -> Unit)? = null) {
        if (!running) {
            afterGoodbye?.invoke()
            return
        }
        running = false
        main.removeCallbacks(ticker)
        thread?.join(500)
        thread = null
        closeMic()
        latest = null
        goodbyeDone = afterGoodbye
        goodbyeStart = now()
        goodbyeActive = true
        main.post(goodbye)
    }

    /** The picture for the app screen's mirror, drawn with [look]. Null when the tuner is off. */
    fun screenFrame(look: TunerArt.Look): IntArray? {
        val t = now()
        if (running) {
            val snap = latest ?: return null
            return TunerArt.render(snap, t, look, t - startedAt)
        }
        if (goodbyeActive) return TunerArt.goodbye(t - goodbyeStart, look)
        return null
    }

    // ---------- Goodbye animation ----------

    private var goodbyeStart = 0.0
    private var goodbyeActive = false
    private var goodbyeDone: (() -> Unit)? = null

    private val goodbye = object : Runnable {
        override fun run() {
            val t = now() - goodbyeStart
            if (running || t >= TunerArt.GOODBYE_SECONDS) {
                finishGoodbye()
                return
            }
            draw(TunerArt.goodbye(t, look))
            main.postDelayed(this, FRAME_MS)
        }
    }

    /** Ends the goodbye animation (also when listening starts again in the middle of it). */
    private fun finishGoodbye() {
        main.removeCallbacks(goodbye)
        if (!goodbyeActive) return
        goodbyeActive = false
        if (!running && toyActive) GlyphLink.showAsToy(TunerArt.startHint(look))
        val done = goodbyeDone
        goodbyeDone = null
        done?.invoke()
    }

    private fun draw(frame: IntArray) {
        when {
            toyActive -> GlyphLink.showAsToy(frame)
            appActive -> GlyphLink.showFromApp(frame)
        }
    }

    /** Picks up changed settings. A new tuning starts the strings over. */
    fun reloadSettings(context: Context) {
        val p = TunerPrefs(context)
        prefs = p
        val tuner = p.tuner()
        look = p.look(tuner)
        val sameTuning = tuner.preset.id == currentTuner.preset.id && tuner.referenceHz == currentTuner.referenceHz
        if (!sameTuning || !running) {
            if (!sameTuning || lockedString >= tuner.strings.size) lockedString = -1
            currentTuner = tuner
            state = TunerState(tuner) { p.nextCelebration() }.also { it.lockString(lockedString) }
            lastTunedCount = 0
            lastCelebration = null
            // A new tuning mid-session starts the quiet clock over, so it doesn't switch itself off at once.
            if (running) startedAt = now()
        }
    }

    /** The string locked from the app screen (only that one is listened for), or -1 for automatic. */
    var lockedString = -1
        private set

    /** Locks onto one string ([index]), or back to automatic (-1). Main thread. */
    fun lockString(index: Int) {
        lockedString = if (index in currentTuner.strings.indices) index else -1
        state.lockString(lockedString)
    }

    /** How the matrix should look right now (for the Glyph Toy's still pictures). */
    fun look(context: Context): TunerArt.Look {
        val p = TunerPrefs(context)
        return p.look(if (running) currentTuner else p.tuner())
    }

    // ---------- Microphone ----------

    @SuppressLint("MissingPermission") // checked first
    private fun openMic(context: Context): AudioRecord? {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return null
        val channel = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, channel, encoding)
        if (minBytes <= 0) return null
        val bytes = maxOf(minBytes, HOP * 2 * 8)

        // Prefer raw audio: the normal voice settings try to remove steady sounds, like a ringing string.
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val sources = mutableListOf<Int>()
        if (audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true") {
            sources += MediaRecorder.AudioSource.UNPROCESSED
        }
        sources += MediaRecorder.AudioSource.VOICE_RECOGNITION
        sources += MediaRecorder.AudioSource.MIC

        for (source in sources) {
            val rec = try {
                AudioRecord(source, SAMPLE_RATE, channel, encoding, bytes)
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord($source) failed", e)
                continue
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                continue
            }
            // Know when a call or another app takes the microphone, so we can show "paused".
            try {
                rec.registerAudioRecordingCallback(mainExecutor, silenceWatcher)
            } catch (t: Throwable) {
                Log.w(TAG, "Can't watch for microphone sharing", t)
            }
            try {
                rec.startRecording()
            } catch (e: Exception) {
                rec.release()
                continue
            }
            if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                Log.d(TAG, "Listening with audio source $source")
                return rec
            }
            rec.release()
        }
        return null
    }

    private fun closeMic() {
        record?.let {
            try { it.unregisterAudioRecordingCallback(silenceWatcher) } catch (_: Throwable) {}
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        record = null
        state.setPaused(false)
    }

    private val silenceWatcher = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
            val rec = record ?: return
            val mine = configs?.firstOrNull { it.clientAudioSessionId == rec.audioSessionId } ?: return
            state.setPaused(mine.isClientSilenced)
        }
    }

    private fun audioLoop() {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Throwable) {}
        val detector = PitchDetector(SAMPLE_RATE)
        val window = FloatArray(detector.requiredSamples)
        val hop = ShortArray(HOP)
        var filled = 0
        var errors = 0

        // Only the current session's thread keeps going: one that slept through a quick stop and
        // start would otherwise share the microphone with the new one.
        while (running && thread === Thread.currentThread()) {
            val rec = record
            val n = rec?.read(hop, 0, HOP) ?: -1
            if (n <= 0) {
                errors++
                if (errors > 20) {
                    // The microphone went away (e.g. taken by a call app). Try to get it back.
                    Log.w(TAG, "Microphone stopped answering ($n), reconnecting")
                    main.post { if (running) reconnect() }
                    Thread.sleep(1000)
                    errors = 0
                } else {
                    Thread.sleep(20)
                }
                continue
            }
            errors = 0

            // Slide the analysis window along and append the new samples.
            System.arraycopy(window, n, window, 0, window.size - n)
            var allZero = true
            var energy = 0.0
            val base = window.size - n
            for (i in 0 until n) {
                val v = hop[i] / 32768f
                window[base + i] = v
                if (hop[i].toInt() != 0) allZero = false
                energy += v * v
            }
            filled = min(window.size, filled + n)

            // Loudness and clarity limits are applied by TunerState (stricter for a new note).
            val t = now()
            var freq: Double? = null
            var clarity = 0.0
            val db = 10 * log10(energy / n + 1e-12)
            if (filled == window.size && db > TunerState.SUSTAIN_GATE_DB && t >= muteUntil) {
                val r = detector.detect(window)
                if (r != null && r.frequency in Tuner.MIN_HZ..Tuner.MAX_HZ) {
                    freq = r.frequency
                    clarity = r.clarity
                }
            }
            state.onAudio(t, freq, clarity, db, allZero)
        }
    }

    private fun reconnect() {
        val ctx = appContext ?: return
        closeMic()
        record = openMic(ctx)
    }

    // ---------- Drawing and buzzing ----------

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val t = now()
            val snap = state.snapshot(t)
            latest = snap

            buzzOnProgress(snap, t)
            notePluck(snap, t)
            tickOnApproach(snap, t)
            reportStatus(snap, t)

            // Night dimming follows the clock, so refresh the look now and then.
            if (t - lookCheckedAt > 30) {
                lookCheckedAt = t
                prefs?.let { look = it.look(currentTuner) }
            }

            draw(TunerArt.render(snap, t, look, t - startedAt))
            val quietFor = min(snap.sinceNote, t - startedAt)
            // All strings in tune and the celebration is over: you're done, so it switches itself off.
            val allTuned = snap.tuned.isNotEmpty() && snap.tuned.all { it }
            if (allTuned && snap.celebration == null && quietFor > DONE_STOP_SECONDS) {
                Log.d(TAG, "All strings tuned, stopping")
                onAutoStop?.invoke()
                return
            }
            if (quietFor > AUTO_STOP_SECONDS) {
                Log.d(TAG, "Quiet for ${AUTO_STOP_SECONDS.toInt()} s, stopping")
                onAutoStop?.invoke()
                return
            }
            main.postDelayed(this, FRAME_MS)
        }
    }

    // ---------- Pluck log ----------

    // One line in the system log per pluck (tag GlyphTunerPluck): which string, how long it rang,
    // how long it was in tune, whether it got marked done, and the reading every 0.1 s.
    // Handy for working out "it says in tune but won't mark it done" reports.
    private var pluckOn = false
    private var pluckString = -1
    private var pluckStart = 0.0
    private var pluckInTune = 0.0
    private var pluckLastT = 0.0
    private var pluckLastSample = 0.0
    private var pluckWasDone = false
    private val pluckCents = StringBuilder()

    private fun notePluck(snap: TunerState.Snapshot, t: Double) {
        val on = snap.noteOn && snap.stringIndex >= 0
        if (on && (!pluckOn || snap.stringIndex != pluckString)) {
            if (pluckOn) logPluck(snap, t)
            pluckOn = true
            pluckString = snap.stringIndex
            pluckStart = t
            pluckInTune = 0.0
            pluckLastSample = 0.0
            pluckWasDone = snap.tuned.getOrElse(pluckString) { false }
            pluckCents.setLength(0)
        } else if (pluckOn && on) {
            if (snap.inTune) pluckInTune += t - pluckLastT
            if (t - pluckLastSample >= 0.1 && pluckCents.length < 400) {
                pluckLastSample = t
                pluckCents.append(snap.cents.roundToInt()).append(' ')
            }
        } else if (pluckOn) {
            logPluck(snap, t)
            pluckOn = false
        }
        pluckLastT = t
    }

    private fun logPluck(snap: TunerState.Snapshot, t: Double) {
        val name = currentTuner.strings.getOrNull(pluckString)?.display() ?: "?"
        val done = snap.tuned.getOrElse(pluckString) { false }
        Log.i(
            PLUCK_TAG,
            "$name rang %.1fs, in tune %.2fs, done %s->%s, cents: %s".format(t - pluckStart, pluckInTune, pluckWasDone, done, pluckCents)
        )
    }

    // ---------- Notification line ----------

    private fun reportStatus(snap: TunerState.Snapshot, t: Double) {
        if (t - lastStatusAt < 1.0) return
        val text = statusText(snap)
        if (text == lastStatus) return
        lastStatus = text
        lastStatusAt = t
        onStatus?.invoke(text)
    }

    /** E.g. "A string · 4 of 6 done". */
    private fun statusText(snap: TunerState.Snapshot): String {
        val strings = currentTuner.strings
        val n = strings.size
        val done = snap.tunedCount
        return when {
            snap.paused -> "Paused while another app uses the microphone"
            snap.blocked -> "Android is blocking the microphone. Stop and start again."
            done == n -> "All $n strings in tune. Switching off soon."
            snap.idle || snap.stringIndex !in strings.indices ->
                if (done == 0) "Flip your phone and pluck a string" else "$done of $n done · pluck the next string"
            else -> "${strings[snap.stringIndex].display()} string · $done of $n done"
        }
    }

    // ---------- Closer ticks ----------

    private val tickSteps = doubleArrayOf(20.0, 10.0, 5.0)
    private var tickString = -1
    private var tickPassed = 0
    private var tickNoteOn = false

    /** A light tap each time you pass 20, 10 and 5 cents on the way to in tune. */
    private fun tickOnApproach(snap: TunerState.Snapshot, t: Double) {
        val off = abs(snap.cents)
        val newNote = snap.noteOn && (!tickNoteOn || snap.stringIndex != tickString)
        tickNoteOn = snap.noteOn
        tickString = snap.stringIndex
        if (newNote) {
            // A fresh pluck that's already close doesn't tick for the steps it skipped.
            tickPassed = tickSteps.count { off <= it }
            return
        }
        if (!snap.noteOn || snap.inTune || snap.showDone) return
        // Moving away again re-arms the steps (with a little slack so it doesn't chatter).
        while (tickPassed > 0 && off > tickSteps[tickPassed - 1] + 3) tickPassed--
        if (tickPassed >= tickSteps.size || off > tickSteps[tickPassed]) return
        while (tickPassed < tickSteps.size && off <= tickSteps[tickPassed]) tickPassed++
        if (prefs?.closerTicks != true) return
        val ctx = appContext ?: return
        val millis = Haptics.tick(ctx, 0.25f + 0.2f * tickPassed)
        if (millis > 0) muteUntil = maxOf(muteUntil, t + TICK_MUTE_SECONDS)
    }

    private fun buzzOnProgress(snap: TunerState.Snapshot, t: Double) {
        val celebrationStarted = snap.celebration != null && snap.celebration != lastCelebration
        val stringDone = snap.tunedCount > lastTunedCount
        lastTunedCount = snap.tunedCount
        lastCelebration = snap.celebration
        if (prefs?.vibrate != true) return
        val ctx = appContext ?: return
        val millis = when {
            celebrationStarted -> Haptics.celebrate(ctx)
            stringDone -> Haptics.lock(ctx)
            else -> return
        }
        // Don't let the tuner hear its own vibration.
        if (millis > 0) muteUntil = t + millis / 1000.0 + BUZZ_MUTE_SECONDS
    }
}
