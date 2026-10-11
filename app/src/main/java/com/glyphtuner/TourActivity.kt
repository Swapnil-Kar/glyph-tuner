package com.glyphtuner

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * The first-run tour, two short steps: (1) what it is and why it needs the microphone,
 * explained before Android asks; (2) a real first try (flip the phone, pluck a string), with
 * a button to add the quick settings tile so next time the app isn't needed at all.
 * Shown once, and again from the "?" on the main screen. The tuning is chosen in Settings.
 */
class TourActivity : Activity(), SensorEventListener {

    private lateinit var prefs: TunerPrefs
    private var step = 0
    private var stepStartedAt = 0.0
    private var micDenied = false
    private var sendToSettings = false
    private var refreshMicStep = false

    // The "try it" step.
    private var matrix: MatrixView? = null
    private var look = TunerArt.Look(Tuner().strings)
    private var flipped = false
    private var heard = -1
    private var flipIcon: ImageView? = null
    private var flipStatus: TextView? = null
    private var heardIcon: ImageView? = null
    private var heardStatus: TextView? = null
    private var sensors: SensorManager? = null

    private val main = Handler(Looper.getMainLooper())
    private val frameLoop = object : Runnable {
        override fun run() {
            tick()
            main.postDelayed(this, 33)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = TunerPrefs(this)
        show((savedInstanceState?.getInt(KEY_STEP) ?: 0).coerceIn(0, STEPS - 1), fade = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_STEP, step)
    }

    override fun onResume() {
        super.onResume()
        // Back from Android's settings: the microphone may be allowed now.
        if (refreshMicStep && step == 0) show(0, fade = false)
        refreshMicStep = false
        if (step == 1) startSensor()
        main.post(frameLoop)
    }

    override fun onPause() {
        main.removeCallbacks(frameLoop)
        stopSensor()
        super.onPause()
    }

    override fun onDestroy() {
        stopSensor()
        super.onDestroy()
    }

    @Deprecated("Back goes to the previous step")
    override fun onBackPressed() {
        if (step > 0) show(step - 1) else done()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return
        if (hasMic()) {
            micDenied = false
            show(1)
        } else {
            micDenied = true
            sendToSettings = !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
            show(0, fade = false)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Face down: gravity pulls "out of the screen".
        if (event.values.size > 2 && event.values[2] < -7f) flipped = true
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun done() {
        prefs.tourDone = true
        stopSensor()
        finish()
    }

    // ---------- Steps ----------

    private fun show(n: Int, fade: Boolean = true) {
        if (step == 1 && n != 1) stopSensor()
        step = n
        stepStartedAt = TunerEngine.now()
        matrix = null
        val screen = if (n == 0) hello() else tryIt()
        setContentView(screen)
        if (fade) {
            screen.alpha = 0f
            screen.animate().alpha(1f).setDuration(220).start()
        }
        if (n == 1) startTrying()
    }

    private fun tick() {
        val m = matrix ?: return
        val now = TunerEngine.now()
        if (step == 0) {
            m.show(TunerArt.hello(now - stepStartedAt, look))
        } else {
            m.show(TunerEngine.screenFrame(look) ?: TunerArt.resting(now, look))
            updateTry()
        }
    }

    /** Step 1: what it is, and the microphone, explained before Android asks. */
    private fun hello(): View {
        look = TunerArt.Look(prefs.tuner().strings)
        val allowed = hasMic()
        val top = linear(this, vertical = false, gravity = Gravity.CENTER_VERTICAL)
        top.addView(label("GLYPH TUNER", 12f), lp(0, WRAP, 1f))
        top.addView(dots(0))
        top.addView(textButton("SKIP") { done() }.apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END }, lp(0, WRAP, 1f))

        val content = column()
        content.addView(matrixBox(200), lp(MATCH, dp(200), top = dp(12)))
        content.addView(headline("TUNE FROM\nTHE BACK"), lp(MATCH, WRAP, top = dp(28)))
        content.addView(body(
            if (micDenied && !allowed) "Without the microphone the tuner can't hear your guitar. You can allow it now, or later from the Start button."
            else "Flip your phone face down and pluck a string. The back shows which string it is and which way to turn the peg. It listens through the microphone:"
        ), lp(MATCH, WRAP, top = dp(12)))
        val rows = column()
        listOf(
            "Nothing is recorded or saved",
            "No internet permission. Sound can't leave your phone",
            "Listens only while the red dot is on",
        ).forEachIndexed { i, line ->
            val row = linear(this, vertical = false, gravity = Gravity.CENTER_VERTICAL).apply { minimumHeight = dp(40) }
            row.addView(ImageView(this).apply { setImageResource(R.drawable.ic_check); setColorFilter(Ui.WHITE) }, lp(dp(18), dp(18)))
            row.addView(body(line, 15f, Ui.WHITE), lp(0, WRAP, 1f, left = dp(12)))
            rows.addView(row, lp(MATCH, WRAP))
            if (i < 2) rows.addView(View(this).apply { setBackgroundColor(Ui.FAINT) }, lp(MATCH, dp(1)))
        }
        content.addView(rows, lp(MATCH, WRAP, top = dp(8)))

        val bottom = column()
        val note = when {
            allowed -> ""
            sendToSettings -> "Android won't ask again, so the switch is in Settings: Permissions, then Microphone."
            micDenied -> ""
            else -> "Android asks next. Tap \"While using the app\". Then allow notifications, so you can stop the tuner from there."
        }
        if (note.isNotEmpty()) bottom.addView(body(note, 13f, Ui.GREY), lp(MATCH, WRAP, bottom = dp(14)))
        bottom.addView(
            when {
                allowed -> bigButton("NEXT") { show(1) }
                sendToSettings -> bigButton("OPEN SETTINGS") {
                    refreshMicStep = true
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                }
                else -> bigButton("ALLOW MICROPHONE") {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), REQ_PERMISSIONS)
                }
            },
            lp(MATCH, dp(64))
        )
        if (!allowed) bottom.addView(textButton("NOT NOW") { done() }, lp(MATCH, dp(44), top = dp(6)))
        return scaffold(top, content, bottom)
    }

    /** Step 2: a real first try, and the tile so next time you don't need the app. */
    private fun tryIt(): View {
        val top = linear(this, vertical = false, gravity = Gravity.CENTER_VERTICAL)
        top.addView(iconButton(R.drawable.ic_back, "Back", Ui.WHITE) { show(0) })
        top.addView(dots(1), lp(0, WRAP, 1f))
        top.addView(View(this), lp(dp(44), dp(44)))

        val content = column()
        content.addView(headline("FLIP AND\nPLUCK"), lp(MATCH, WRAP, top = dp(20)))
        content.addView(body("Put the phone face down on your lap or a table, pluck any string, and watch the back."), lp(MATCH, WRAP, top = dp(12)))
        content.addView(matrixBox(200), lp(MATCH, dp(200), top = dp(20)))

        val card = column().apply {
            background = Ui.shape(Ui.BLACK, Ui.LINE, dp(1), dpF(20f))
            setPadding(dp(16), 0, dp(16), 0)
        }
        val (row1, icon1, status1) = checkRow("Phone face down")
        val (row2, icon2, status2) = checkRow("Pluck any string")
        card.addView(row1, lp(MATCH, dp(56)))
        card.addView(View(this).apply { setBackgroundColor(Ui.FAINT) }, lp(MATCH, dp(1)))
        card.addView(row2, lp(MATCH, dp(56)))
        flipIcon = icon1
        flipStatus = status1
        heardIcon = icon2
        heardStatus = status2
        content.addView(card, lp(MATCH, WRAP, top = dp(20)))

        val bottom = column()
        if (hasMic()) {
            bottom.addView(body("Next time, skip the app: swipe down, tap Tuner, flip. It switches itself off when every string is in tune.", 13f, Ui.GREY), lp(MATCH, WRAP, bottom = dp(14)))
            val tile = bigButton("ADD THE TUNER TILE", filled = false) {}
            tile.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                AppShortcuts.addTile(this) {
                    tile.text = "TILE ADDED ✓"
                    tile.setTextColor(Ui.GREY)
                }
            }
            bottom.addView(tile, lp(MATCH, dp(64)))
            bottom.addView(bigButton("START TUNING") { done() }, lp(MATCH, dp(64), top = dp(10)))
            bottom.addView(textButton("SHOW ME HOW IT WORKS", Ui.WHITE) {
                done()
                startActivity(Intent(this, GuideActivity::class.java))
            }, lp(MATCH, dp(44), top = dp(6)))
        } else {
            bottom.addView(body("The microphone is off, so the tuner can't listen yet. Allow it from the Start button any time.", 13f, Ui.GREY), lp(MATCH, WRAP, bottom = dp(14)))
            bottom.addView(bigButton("FINISH") { done() }, lp(MATCH, dp(64)))
        }
        return scaffold(top, content, bottom)
    }

    private fun checkRow(text: String): Triple<View, ImageView, TextView> {
        val row = linear(this, vertical = false, gravity = Gravity.CENTER_VERTICAL)
        val icon = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER }
        row.addView(icon, lp(dp(24), dp(24)))
        row.addView(body(text, 15f, Ui.WHITE), lp(0, WRAP, 1f, left = dp(12)))
        val status = label("", 11f, Ui.GREY, 0.12f)
        row.addView(status, lp(WRAP, WRAP, left = dp(8)))
        return Triple(row, icon, status)
    }

    private fun startTrying() {
        flipped = false
        heard = -1
        TunerEngine.reloadSettings(this)
        look = TunerArt.Look(TunerEngine.currentTuner.strings)
        flipIcon?.tag = null
        heardIcon?.tag = null
        if (hasMic() && !TunerEngine.running) {
            TunerService.lastProblem = null
            try {
                startForegroundService(Intent(this, TunerService::class.java))
            } catch (_: Throwable) {}
        }
        startSensor()
    }

    private fun updateTry() {
        val s = TunerEngine.latest
        val strings = TunerEngine.currentTuner.strings
        if (TunerEngine.running && s != null && !s.idle && s.stringIndex in strings.indices) heard = s.stringIndex
        setCheck(flipIcon, flipStatus, flipped, if (flipped) "FLIPPED" else "WAITING")
        val heardText = when {
            heard in strings.indices -> "HEARD YOUR ${strings[heard].display()}"
            !hasMic() -> "MIC IS OFF"
            TunerEngine.running -> "LISTENING"
            else -> "STARTING"
        }
        setCheck(heardIcon, heardStatus, heard >= 0, heardText)
    }

    private fun setCheck(icon: ImageView?, status: TextView?, done: Boolean, text: String) {
        status?.setIfChanged(text)
        status?.setTextColor(if (done) Ui.WHITE else Ui.GREY)
        if (icon == null || icon.tag == done) return
        icon.tag = done
        if (done) {
            icon.background = Ui.shape(Ui.WHITE, oval = true)
            icon.setImageResource(R.drawable.ic_check)
            icon.setColorFilter(Ui.BLACK)
            icon.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        } else {
            icon.background = Ui.shape(Ui.BLACK, 0xFF444444.toInt(), dpF(1.5f).roundToInt(), oval = true)
            icon.setImageDrawable(null)
        }
    }

    private fun startSensor() {
        if (sensors != null) return
        val manager = getSystemService(SensorManager::class.java) ?: return
        val sensor = manager.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        sensors = manager
    }

    private fun stopSensor() {
        sensors?.unregisterListener(this)
        sensors = null
    }

    // ---------- Building blocks ----------

    /** Top bar, a scrolling middle, and a bottom that stays put. */
    private fun scaffold(top: View, content: View, bottom: View): View {
        val root = linear(this, vertical = true).apply { setBackgroundColor(Ui.BLACK) }
        root.padForSystemBars(dp(24), dp(8), dp(24), dp(24))
        root.addView(top, lp(MATCH, dp(48)))
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(content)
        }
        root.addView(scroll, lp(MATCH, 0, 1f))
        root.addView(bottom, lp(MATCH, WRAP, top = dp(16)))
        return root
    }

    private fun dots(current: Int): View {
        val row = linear(this, vertical = false, gravity = Gravity.CENTER)
        row.contentDescription = "Step ${current + 1} of $STEPS"
        for (i in 0 until STEPS) {
            row.addView(View(this).apply {
                background = Ui.shape(if (i == current) Ui.WHITE else Ui.DIM_LINE, radiusPx = dpF(3f))
            }, lp(dp(if (i == current) 18 else 6), dp(6), left = if (i == 0) 0 else dp(6)))
        }
        return row
    }

    private fun headline(text: String) = DotTextView(this).apply {
        this.text = text
        maxPitchPx = dpF(6f)
        centered = false
    }

    private fun matrixBox(sizeDp: Int): View {
        val m = MatrixView(this).apply { maxSizePx = dp(sizeDp) }
        matrix = m
        return FrameLayout(this).apply { addView(m, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER)) }
    }

    private fun column() = linear(this, vertical = true)

    private companion object {
        const val REQ_PERMISSIONS = 2
        const val KEY_STEP = "step"
        const val STEPS = 2
    }
}
