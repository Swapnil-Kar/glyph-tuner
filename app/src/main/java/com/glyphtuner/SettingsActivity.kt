package com.glyphtuner

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/** Settings: tuning, reference pitch, how it feels, how the back looks, and the shortcuts. */
class SettingsActivity : Activity() {

    private lateinit var prefs: TunerPrefs
    private val tuningCards = mutableListOf<Pair<String, LinearLayout>>()
    private val referenceButtons = mutableListOf<Pair<Int, TextView>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = TunerPrefs(this)
        setContentView(buildScreen())
        refresh()
    }

    private fun changed() {
        TunerEngine.reloadSettings(this)
        // The widget shows the tuning's string names.
        TunerWidget.refresh(this)
        refresh()
    }

    private fun refresh() {
        tuningCards.forEach { (id, card) ->
            val on = id == prefs.tuningId
            card.background = Ui.shape(Ui.BLACK, if (on) Ui.WHITE else Ui.LINE, dpF(1.5f).roundToInt(), dpF(18f))
            card.isSelected = on
        }
        referenceButtons.forEach { (hz, button) ->
            val on = hz == prefs.referenceHz
            button.background = if (on) Ui.shape(Ui.WHITE, radiusPx = dpF(22f)) else null
            button.setTextColor(if (on) Ui.BLACK else Ui.LIGHT)
            button.isSelected = on
        }
    }

    // ---------- Layout ----------

    private fun buildScreen(): View {
        val column = linear(this, vertical = true)
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Ui.BLACK)
            addView(column)
        }
        scroll.padForSystemBars(dp(24), dp(8), dp(24), dp(28))

        val header = linear(this, vertical = false, gravity = Gravity.CENTER_VERTICAL)
        header.addView(iconButton(R.drawable.ic_back, "Back", Ui.WHITE) { finish() })
        header.addView(DotTextView(this).apply {
            text = "SETTINGS"
            maxPitchPx = dpF(4.2f)
            centered = false
        }, lp(WRAP, WRAP, left = dp(8)))
        column.addView(header, lp(MATCH, dp(48)))

        // Tuning: six cards, two per row.
        column.addView(sectionLabel("TUNING"))
        Tunings.ALL.chunked(2).forEachIndexed { i, pair ->
            val row = linear(this, vertical = false)
            pair.forEachIndexed { j, preset -> row.addView(tuningCard(preset), lp(0, dp(76), 1f, left = if (j == 0) 0 else dp(10))) }
            column.addView(row, lp(MATCH, WRAP, top = if (i == 0) 0 else dp(10)))
        }

        // Reference pitch: three side by side.
        column.addView(sectionLabel("REFERENCE PITCH · A ="))
        val segment = linear(this, vertical = false).apply {
            background = Ui.shape(Ui.SURFACE, radiusPx = dpF(26f))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        listOf(432, 440, 442).forEach { hz ->
            val button = label("$hz HZ", 13f, Ui.LIGHT, 0.08f).apply {
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = "A equals $hz hertz"
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    prefs.referenceHz = hz
                    changed()
                }
            }
            segment.addView(button, lp(0, dp(44), 1f))
            referenceButtons += hz to button
        }
        column.addView(segment, lp(MATCH, WRAP))

        column.addView(sectionLabel("LISTENING"))
        column.addView(toggleRow("Start when the app opens", "Listening begins as soon as you open it", prefs.autoStart) {
            prefs.autoStart = it
        })
        column.addView(toggleRow("Click when a string locks", "One crisp tap from the phone", prefs.vibrate) {
            prefs.vibrate = it
            changed()
        })
        column.addView(toggleRow("Closer ticks", "Light taps at 20, 10 and 5 cents", prefs.closerTicks) {
            prefs.closerTicks = it
        })

        column.addView(sectionLabel("BACK OF THE PHONE"))
        column.addView(toggleRow("Dim at night", "10 pm to 6 am", prefs.dimAtNight) {
            prefs.dimAtNight = it
            changed()
        })
        column.addView(toggleRow("Upside down", "If the letter looks flipped to you", prefs.rotate180) {
            prefs.rotate180 = it
            changed()
        })

        column.addView(sectionLabel("START FASTER"))
        column.addView(actionRow("Quick settings tile", "Swipe down, tap Tuner", "ADD") { button ->
            AppShortcuts.addTile(this) { markAdded(button) }
        }.first)
        column.addView(actionRow("Home screen widget", "One tap to start, one to stop", "ADD") { button ->
            if (TunerWidget.requestPin(this)) markAdded(button)
            else android.widget.Toast.makeText(this, "Long-press your home screen, tap Widgets, and find Tuner", android.widget.Toast.LENGTH_LONG).show()
        }.first)
        column.addView(actionRow("Glyph Toys", "Long-press the Glyph Button on the back", "OPEN") {
            AppShortcuts.openGlyphToys(this)
        }.first)

        column.addView(sectionLabel("ABOUT"))
        column.addView(body("No ads. No account. No internet permission, so what the mic hears stays on your phone.", 15f, Ui.LIGHT))
        column.addView(textButton("HOW IT WORKS", Ui.WHITE) {
            startActivity(Intent(this, GuideActivity::class.java))
        }.apply { setPadding(0, 0, dp(8), 0) }, lp(WRAP, WRAP, top = dp(8)))
        column.addView(textButton("REPLAY THE TOUR", Ui.WHITE) {
            startActivity(Intent(this, TourActivity::class.java))
        }.apply { setPadding(0, 0, dp(8), 0) }, lp(WRAP, WRAP))
        column.addView(textButton("REPORT A PROBLEM OR AN IDEA", Ui.WHITE) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ISSUES_URL)))
            } catch (_: Exception) {
                Toast.makeText(this, ISSUES_URL, Toast.LENGTH_LONG).show()
            }
        }.apply { setPadding(0, 0, dp(8), 0) }, lp(WRAP, WRAP))
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { null }
        column.addView(label("GLYPH TUNER ${version ?: ""}".trim(), 11f, Ui.GREY, 0.15f), lp(WRAP, WRAP, top = dp(4)))
        return scroll
    }

    private fun sectionLabel(text: String) = label(text, 11f, Ui.GREY, 0.18f).apply {
        layoutParams = lp(MATCH, WRAP, top = dp(28), bottom = dp(12))
    }

    private fun tuningCard(preset: TuningPreset): LinearLayout {
        val title = preset.label.substringBefore(" (")
        val notes = preset.strings().joinToString(" ") { it.display() }
        return linear(this, vertical = true, gravity = Gravity.CENTER_VERTICAL).apply {
            setPadding(dp(14), 0, dp(14), 0)
            isClickable = true
            isFocusable = true
            contentDescription = "$title tuning"
            addView(TextView(context).apply {
                text = title
                typeface = Ui.SANS_BOLD
                setTextColor(Ui.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            })
            addView(label(notes, 11f, Ui.GREY, 0.04f), lp(WRAP, WRAP, top = dp(6)))
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                prefs.tuningId = preset.id
                changed()
            }
            tuningCards += preset.id to this
        }
    }

    private fun rowText(title: String, sub: String): LinearLayout = linear(this, vertical = true).apply {
        addView(TextView(context).apply {
            text = title
            typeface = Ui.SANS
            setTextColor(Ui.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        addView(body(sub, 13f, Ui.GREY), lp(WRAP, WRAP, top = dp(2)))
    }

    /** One setting with an on/off switch. The whole row can be tapped. */
    private fun toggleRow(title: String, sub: String, initial: Boolean, onChange: (Boolean) -> Unit): View {
        val switch = PillSwitch(this).apply {
            isChecked = initial
            contentDescription = title
            this.onChange = onChange
        }
        val row = linear(this, vertical = false, gravity = Gravity.CENTER_VERTICAL).apply {
            minimumHeight = dp(64)
            setOnClickListener { switch.performClick() }
        }
        row.addView(rowText(title, sub), lp(0, WRAP, 1f))
        row.addView(switch, lp(WRAP, WRAP, left = dp(12)))
        return withDivider(row)
    }

    /** One shortcut with a small pill button on the right. Returns the row and its button. */
    private fun actionRow(title: String, sub: String, action: String, onClick: (TextView) -> Unit): Pair<View, TextView> {
        val button = label(action, 12f, Ui.BLACK, 0.12f).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(16), 0)
            background = Ui.shape(Ui.WHITE, radiusPx = dpF(22f))
            isClickable = true
            isFocusable = true
            contentDescription = "$action: $title"
        }
        button.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onClick(button)
        }
        val row = linear(this, vertical = false, gravity = Gravity.CENTER_VERTICAL).apply { minimumHeight = dp(64) }
        row.addView(rowText(title, sub), lp(0, WRAP, 1f))
        row.addView(button, lp(WRAP, dp(44), left = dp(12)))
        return withDivider(row) to button
    }

    private fun markAdded(button: TextView) {
        button.text = "ADDED ✓"
        button.setTextColor(Ui.WHITE)
        button.background = Ui.shape(Ui.BLACK, Ui.DIM_LINE, dpF(1.5f).roundToInt(), dpF(22f))
    }

    private fun withDivider(row: View): View = linear(this, vertical = true).apply {
        addView(row, lp(MATCH, WRAP))
        addView(View(context).apply { setBackgroundColor(Ui.FAINT) }, lp(MATCH, dp(1)))
    }

    private companion object {
        /** Where people report problems and ideas: the GitHub issues page. */
        const val ISSUES_URL = "https://github.com/Swapnil-Kar/glyph-tuner/issues"
    }
}
