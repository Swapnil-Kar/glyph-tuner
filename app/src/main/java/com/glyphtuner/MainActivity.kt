package com.glyphtuner

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The main screen. The middle of it is a live copy of the back of the phone; below that the
 * note, how far off it is, the strings (tap one to lock it), and one big Start / Listening button.
 */
class MainActivity : Activity() {

    private lateinit var prefs: TunerPrefs
    private lateinit var matrix: MatrixView
    private lateinit var tuningLabel: TextView
    private lateinit var noteGroup: View
    private lateinit var letterView: DotTextView
    private lateinit var centsView: TextView
    private lateinit var wordView: TextView
    private lateinit var infoView: TextView
    private lateinit var bigGroup: View
    private lateinit var bigView: DotTextView
    private lateinit var subView: TextView
    private lateinit var chipRow: LinearLayout
    private lateinit var autoPill: TextView
    private lateinit var lockHint: TextView
    private lateinit var mainButton: LinearLayout
    private lateinit var recDot: View
    private lateinit var buttonLabel: TextView
    private lateinit var buttonSub: TextView
    private lateinit var footer: TextView

    private val chips = mutableListOf<TextView>()
    private val lockDots = mutableListOf<View>()
    private var chipStates = IntArray(0)
    private var chipKey = ""
    private var autoState = -2
    private var buttonState = -1
    private var look: TunerArt.Look? = null

    private val main = Handler(Looper.getMainLooper())
    private var sendToSettings = false
    private var startWhenAllowed = false
    private var autoStartPending = false
    /** When the microphone was last asked for, to tell "Android said no without asking" apart from "you said no". */
    private var askedAt = 0L
    private var notificationsOff = false

    private val frameLoop = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, FRAME_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = TunerPrefs(this)
        setContentView(buildScreen())
        if (savedInstanceState != null) {
            sendToSettings = savedInstanceState.getBoolean(KEY_SEND_TO_SETTINGS)
            startWhenAllowed = savedInstanceState.getBoolean(KEY_START_WHEN_ALLOWED)
            return
        }
        if (!prefs.tourDone) {
            // First launch: the tour explains things and asks for the microphone properly.
            openTour()
            return
        }
        // Opening the app from the launcher starts listening straight away (if switched on).
        if (intent?.action == Intent.ACTION_MAIN && prefs.autoStart && hasMic()) autoStartPending = true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SEND_TO_SETTINGS, sendToSettings)
        outState.putBoolean(KEY_START_WHEN_ALLOWED, startWhenAllowed)
    }

    override fun onResume() {
        super.onResume()
        notificationsOff = getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == false
        if (!TunerEngine.running) TunerEngine.reloadSettings(this)
        chipKey = "" // settings may have changed
        if (autoStartPending) {
            autoStartPending = false
            if (!TunerEngine.running) startTuner()
        }
        main.post(frameLoop)
    }

    override fun onPause() {
        main.removeCallbacks(frameLoop)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return
        if (hasMic()) {
            if (startWhenAllowed && !TunerEngine.running) startTuner()
        } else if (grantResults.isNotEmpty() && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            // Android won't ask again; the switch is in Settings now.
            sendToSettings = true
            // No dialog was even shown (it answered at once), so go straight to the switch.
            if (startWhenAllowed && android.os.SystemClock.elapsedRealtime() - askedAt < 400) openAppSettings()
        }
        startWhenAllowed = false
    }

    // ---------- Actions ----------

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun askPermissions() {
        if (sendToSettings) {
            openAppSettings()
            return
        }
        askedAt = android.os.SystemClock.elapsedRealtime()
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), REQ_PERMISSIONS)
    }

    private fun openAppSettings() =
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))

    private fun onMainButton() {
        when {
            TunerEngine.running -> TunerService.stop(this)
            !hasMic() -> {
                startWhenAllowed = true
                askPermissions()
            }
            else -> startTuner()
        }
    }

    private fun startTuner() {
        TunerService.lastProblem = null
        try {
            startForegroundService(Intent(this, TunerService::class.java))
        } catch (t: Throwable) {
            TunerService.lastProblem = "The tuner couldn't start. Try again."
        }
    }

    private fun openTour() = startActivity(Intent(this, TourActivity::class.java))

    private fun openGuide() = startActivity(Intent(this, GuideActivity::class.java))

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun onChip(index: Int) {
        TunerEngine.lockString(if (TunerEngine.lockedString == index) -1 else index)
    }

    // ---------- Drawing, about 30 times a second ----------

    private fun render() {
        val now = TunerEngine.now()
        val running = TunerEngine.running
        val tuner = TunerEngine.currentTuner
        val strings = tuner.strings
        val key = tuner.preset.id + "@" + tuner.referenceHz
        if (key != chipKey) {
            chipKey = key
            rebuildChips(strings)
            look = TunerArt.Look(strings)
            autoState = -2
            tuningLabel.text = "${tuner.preset.label.substringBefore(" (").uppercase(Locale.US)} · A${tuner.referenceHz.roundToInt()} ▾"
        }
        val l = look ?: TunerArt.Look(strings).also { look = it }
        matrix.show(TunerEngine.screenFrame(l) ?: TunerArt.resting(now, l))

        val s = if (running) TunerEngine.latest else null
        when {
            !running -> showBig(
                "READY",
                TunerService.lastProblem ?: if (hasMic()) "Tap start, then pluck any string." else "Tap start and allow the microphone."
            )
            s == null -> showBig("PLUCK", "Any string. Flip the phone to tune from the back.")
            s.paused -> showBig("PAUSED", "Another app is using the microphone. It carries on by itself.")
            s.blocked -> showBig("NO MIC", "Android is blocking the microphone. Stop and start again.")
            s.celebration != null -> showBig(s.celebration.text, "All ${strings.size} strings in tune.")
            (s.idle || s.stringIndex !in strings.indices) && s.tunedCount == strings.size ->
                showBig("ALL TUNED", "Switching off in ${ceil(TunerEngine.DONE_STOP_SECONDS - s.sinceNote).toInt().coerceAtLeast(1)} s. Pluck again to double-check.")
            s.idle || s.stringIndex !in strings.indices -> showBig(
                "PLUCK",
                if (notificationsOff) "Notifications are off, so stop the tuner here when you're done."
                else "Any string. Flip the phone to tune from the back."
            )
            else -> showNote(strings[s.stringIndex], s)
        }

        updateChips(s)
        updateAuto(strings)
        updateButton(running, now)
        val n = prefs.tuneUps
        footer.setIfChanged(
            when (n) {
                0 -> "NO ADS · NO ACCOUNT · OFFLINE"
                1 -> "1 TUNE-UP · NO ADS · OFFLINE"
                else -> "$n TUNE-UPS · NO ADS · OFFLINE"
            }
        )
    }

    private fun showBig(big: String, sub: String) {
        noteGroup.visibility = View.INVISIBLE
        bigGroup.visibility = View.VISIBLE
        bigView.text = big
        subView.setIfChanged(sub)
    }

    private fun showNote(g: GuitarString, s: TunerState.Snapshot) {
        noteGroup.visibility = View.VISIBLE
        bigGroup.visibility = View.INVISIBLE
        letterView.text = g.display()
        val c = s.cents.roundToInt()
        centsView.setIfChanged(if (c > 0) "+$c" else if (c < 0) "−${-c}" else "0")
        val (word, color) = when {
            s.inTune || s.showDone -> "IN TUNE" to Ui.WHITE
            !s.noteOn -> "PLUCK AGAIN" to Ui.GREY
            s.arrowUp -> "TIGHTEN ▲" to Ui.LIGHT
            else -> "LOOSEN ▼" to Ui.LIGHT
        }
        wordView.setIfChanged(word)
        wordView.setTextColor(color)
        infoView.setIfChanged(
            "${g.name.uppercase(Locale.US)} STRING · ${prettyNote(g.note)} · ${String.format(Locale.US, "%.1f", g.frequency)} HZ"
        )
    }

    private fun updateChips(s: TunerState.Snapshot?) {
        val locked = TunerEngine.lockedString
        for (k in chips.indices) {
            val done = s?.tuned?.getOrNull(k) == true
            val here = s != null && !s.idle && s.stringIndex == k
            val base = when {
                done -> 2
                here -> 1
                else -> 0
            }
            val state = base + if (locked == k) 10 else 0
            if (state == chipStates[k]) continue
            chipStates[k] = state
            chips[k].background = Ui.shape(
                if (base == 2) Ui.WHITE else Ui.BLACK,
                if (base >= 1) Ui.WHITE else Ui.DIM_LINE,
                dpF(1.5f).roundToInt(),
                oval = true
            )
            chips[k].setTextColor(
                when (base) {
                    2 -> Ui.BLACK
                    1 -> Ui.WHITE
                    else -> Ui.GREY
                }
            )
            lockDots[k].visibility = if (locked == k) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun updateAuto(strings: List<GuitarString>) {
        val locked = TunerEngine.lockedString
        if (locked == autoState) return
        autoState = locked
        val auto = locked !in strings.indices
        autoPill.setTextColor(if (auto) Ui.WHITE else Ui.GREY)
        autoPill.background = Ui.shape(Ui.BLACK, if (auto) Ui.WHITE else Ui.DIM_LINE, dp(1), dpF(14f))
        lockHint.text = if (auto) "Tap a string to lock it" else "Locked on ${strings[locked].display()}. Tap Auto to undo"
    }

    private fun updateButton(running: Boolean, now: Double) {
        val state = if (running) 1 else 0
        if (state != buttonState) {
            buttonState = state
            if (running) {
                mainButton.background = Ui.shape(Ui.BLACK, 0xFF3A3A3A.toInt(), dpF(1.5f).roundToInt(), dpF(32f))
                buttonLabel.text = "LISTENING"
                buttonLabel.setTextColor(Ui.WHITE)
                recDot.visibility = View.VISIBLE
                buttonSub.visibility = View.VISIBLE
                mainButton.contentDescription = "Listening. Tap to stop."
            } else {
                mainButton.background = Ui.shape(Ui.WHITE, radiusPx = dpF(32f))
                buttonLabel.text = "START"
                buttonLabel.setTextColor(Ui.BLACK)
                recDot.visibility = View.GONE
                buttonSub.visibility = View.GONE
                mainButton.contentDescription = "Start listening"
            }
        }
        // The red dot pulses like a recording light while the microphone is on.
        if (running) recDot.alpha = (0.45 + 0.55 * (0.5 + 0.5 * sin(now * PI * 1.8))).toFloat()
    }

    // ---------- Layout ----------

    private fun buildScreen(): View {
        val root = linear(this, vertical = true).apply { setBackgroundColor(Ui.BLACK) }
        root.padForSystemBars(dp(24), dp(8), dp(24), dp(20))

        // Top bar: tuning (tap for settings), tour, settings.
        val top = linear(this, vertical = false, gravity = Gravity.CENTER_VERTICAL)
        tuningLabel = label("", 13f, Ui.WHITE, 0.12f).apply {
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(44)
            isClickable = true
            isFocusable = true
            contentDescription = "Tuning. Tap to change."
            setOnClickListener { openSettings() }
        }
        top.addView(tuningLabel, lp(0, WRAP, 1f))
        top.addView(iconButton(R.drawable.ic_help, "How it works") { openGuide() })
        top.addView(iconButton(R.drawable.ic_settings, "Settings") { openSettings() })
        root.addView(top, lp(MATCH, dp(48)))

        // The live copy of the back of the phone.
        matrix = MatrixView(this)
        val box = FrameLayout(this)
        box.addView(matrix, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
        root.addView(box, lp(MATCH, 0, 1f, top = dp(12)))

        // The reading: either the note, or one big word.
        val readout = FrameLayout(this)
        noteGroup = buildNoteGroup()
        bigGroup = buildBigGroup()
        readout.addView(noteGroup, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
        readout.addView(bigGroup, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
        root.addView(readout, lp(MATCH, dp(150), top = dp(16)))

        // The strings, thickest first.
        chipRow = linear(this, vertical = false, gravity = Gravity.CENTER)
        root.addView(chipRow, lp(MATCH, WRAP, top = dp(8)))

        // Automatic, or locked on one string.
        val autoRow = linear(this, vertical = false, gravity = Gravity.CENTER)
        autoPill = label("AUTO", 11f, Ui.WHITE, 0.15f).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
        }
        val autoTouch = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "Automatic: listen for any string"
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                TunerEngine.lockString(-1)
            }
            addView(autoPill, FrameLayout.LayoutParams(WRAP, dp(28), Gravity.CENTER))
        }
        autoRow.addView(autoTouch, lp(WRAP, dp(44)))
        lockHint = body("", 13f, Ui.GREY)
        autoRow.addView(lockHint, lp(WRAP, WRAP, left = dp(6)))
        root.addView(autoRow, lp(MATCH, dp(44), top = dp(2)))

        mainButton = buildMainButton()
        root.addView(mainButton, lp(MATCH, dp(64), top = dp(16)))

        footer = label("", 11f, Ui.GREY, 0.15f).apply { gravity = Gravity.CENTER }
        root.addView(footer, lp(MATCH, WRAP, top = dp(14)))
        return root
    }

    private fun buildNoteGroup(): View {
        val group = linear(this, vertical = true, gravity = Gravity.CENTER_HORIZONTAL)
        val row = linear(this, vertical = false, gravity = Gravity.CENTER_VERTICAL)
        letterView = DotTextView(this).apply { maxPitchPx = dpF(13f) }
        row.addView(letterView, lp(WRAP, WRAP))
        row.addView(View(this).apply { setBackgroundColor(Ui.LINE) }, lp(dp(1), dp(76), left = dp(22), right = dp(22)))
        val column = linear(this, vertical = true)
        centsView = TextView(this).apply {
            typeface = Ui.SANS_MEDIUM
            setTextColor(Ui.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 46f)
            fontFeatureSettings = "tnum"
            includeFontPadding = false
        }
        column.addView(centsView, lp(WRAP, WRAP))
        wordView = label("", 13f, Ui.LIGHT, 0.15f)
        column.addView(wordView, lp(WRAP, WRAP, top = dp(8)))
        row.addView(column, lp(dp(140), WRAP))
        group.addView(row, lp(WRAP, WRAP))
        infoView = label("", 12f, Ui.GREY, 0.12f)
        group.addView(infoView, lp(WRAP, WRAP, top = dp(12)))
        return group
    }

    private fun buildBigGroup(): View {
        val group = linear(this, vertical = true, gravity = Gravity.CENTER_HORIZONTAL)
        bigView = DotTextView(this).apply { maxPitchPx = dpF(9f) }
        group.addView(bigView, lp(MATCH, WRAP))
        subView = body("", 15f, Ui.GREY).apply {
            gravity = Gravity.CENTER
            maxWidth = dp(300)
        }
        group.addView(subView, lp(WRAP, WRAP, top = dp(14)))
        return group
    }

    private fun buildMainButton(): LinearLayout {
        val button = linear(this, vertical = false, gravity = Gravity.CENTER).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onMainButton()
            }
        }
        recDot = View(this).apply { background = Ui.shape(Ui.RED, oval = true) }
        button.addView(recDot, lp(dp(12), dp(12), right = dp(12)))
        buttonLabel = TextView(this).apply {
            typeface = Ui.SANS_BOLD
            letterSpacing = 0.12f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        }
        button.addView(buttonLabel, lp(WRAP, WRAP))
        buttonSub = label("TAP TO STOP", 11f, Ui.GREY, 0.08f)
        button.addView(buttonSub, lp(WRAP, WRAP, left = dp(12)))
        return button
    }

    private fun rebuildChips(strings: List<GuitarString>) {
        chipRow.removeAllViews()
        chips.clear()
        lockDots.clear()
        strings.forEachIndexed { k, g ->
            val column = linear(this, vertical = true, gravity = Gravity.CENTER_HORIZONTAL)
            val chip = TextView(this).apply {
                text = g.display()
                gravity = Gravity.CENTER
                typeface = Typeface.create(Ui.MONO, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (g.flat) 14f else 17f)
                contentDescription = "${g.name} string, ${prettyNote(g.note)}. Tap to listen for this string only."
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onChip(k)
                }
            }
            column.addView(chip, lp(dp(46), dp(46)))
            val dot = View(this).apply {
                background = Ui.shape(Ui.WHITE, oval = true)
                visibility = View.INVISIBLE
            }
            column.addView(dot, lp(dp(4), dp(4), top = dp(6)))
            chipRow.addView(column, lp(WRAP, WRAP, left = if (k == 0) 0 else dp(10)))
            chips += chip
            lockDots += dot
        }
        chipStates = IntArray(strings.size) { -1 }
    }

    private companion object {
        const val REQ_PERMISSIONS = 1
        const val FRAME_MS = 33L
        const val KEY_SEND_TO_SETTINGS = "send_to_settings"
        const val KEY_START_WHEN_ALLOWED = "start_when_allowed"
    }
}
