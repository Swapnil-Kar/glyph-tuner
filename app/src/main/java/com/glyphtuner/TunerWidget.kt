package com.glyphtuner

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews

/**
 * The home screen widget: a grey pill like the launcher's search bar.
 * Off, it reads "TUNER · Tap to start". While listening it reads "LISTENING · Tap to stop" and
 * each string's dot lights up once it is in tune. It goes through the same invisible screen as
 * the quick settings tile, because Android only lets the microphone start from something you tapped.
 */
class TunerWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        lastShown = null
        for (id in appWidgetIds) push(context, manager, id, current(context))
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        push(context, manager, appWidgetId, current(context))
    }

    /** What the widget shows: everything that can change, so unchanged updates can be skipped. */
    private data class Face(
        val running: Boolean,
        val letters: List<String>,
        val tuned: List<Boolean>,
    ) {
        val allTuned get() = running && tuned.isNotEmpty() && tuned.all { it }
    }

    companion object {
        /** Narrower than this (in dp) and there's no room for the strings, so they hide. */
        private const val STRINGS_MIN_WIDTH_DP = 230
        private const val MAX_STRINGS = 6
        private val LETTER_IDS = intArrayOf(R.id.widget_l0, R.id.widget_l1, R.id.widget_l2, R.id.widget_l3, R.id.widget_l4, R.id.widget_l5)
        private val DOT_IDS = intArrayOf(R.id.widget_d0, R.id.widget_d1, R.id.widget_d2, R.id.widget_d3, R.id.widget_d4, R.id.widget_d5)
        private val STRING_IDS = intArrayOf(R.id.widget_s0, R.id.widget_s1, R.id.widget_s2, R.id.widget_s3, R.id.widget_s4, R.id.widget_s5)
        private const val LETTER_DONE = 0xFFFFFFFF.toInt()
        private const val LETTER_TODO = 0xFF6A6A6A.toInt()

        /** The last face pushed to the widgets, so the tuner's status updates don't redraw it for nothing. */
        private var lastShown: Face? = null

        /** Redraws every Tuner widget to match the tuner: off, listening, and which strings are done. */
        fun refresh(context: Context) {
            try {
                val face = current(context)
                if (face == lastShown) return
                val manager = AppWidgetManager.getInstance(context) ?: return
                val ids = manager.getAppWidgetIds(ComponentName(context, TunerWidget::class.java))
                for (id in ids) push(context, manager, id, face)
                lastShown = face
            } catch (_: Throwable) {}
        }

        /** Asks the launcher to place the widget on the home screen. False if it can't. */
        fun requestPin(activity: Activity): Boolean = try {
            val manager = AppWidgetManager.getInstance(activity)
            manager.isRequestPinAppWidgetSupported &&
                manager.requestPinAppWidget(ComponentName(activity, TunerWidget::class.java), null, null)
        } catch (_: Throwable) {
            false
        }

        private fun current(context: Context): Face {
            val running = TunerEngine.running
            val strings = if (running) TunerEngine.currentTuner.strings else TunerPrefs(context).tuner().strings
            val snap = if (running) TunerEngine.latest else null
            val tuned = strings.indices.map { i -> snap != null && i < snap.tuned.size && snap.tuned[i] }
            return Face(running, strings.map { it.display() }, tuned)
        }

        private fun push(context: Context, manager: AppWidgetManager, id: Int, face: Face) {
            try {
                val width = manager.getAppWidgetOptions(id)?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
                val roomForStrings = width == 0 || width >= STRINGS_MIN_WIDTH_DP
                manager.updateAppWidget(id, views(context, face, roomForStrings))
            } catch (_: Throwable) {}
        }

        private fun views(context: Context, face: Face, roomForStrings: Boolean): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_tuner)
            v.setImageViewResource(R.id.widget_icon, if (face.running) R.drawable.widget_ring_on else R.drawable.widget_ring_off)
            v.setImageViewResource(
                R.id.widget_title,
                when {
                    face.allTuned -> R.drawable.widget_title_done
                    face.running -> R.drawable.widget_title_listening
                    else -> R.drawable.widget_title_tuner
                }
            )
            v.setTextViewText(R.id.widget_sub, context.getString(if (face.running) R.string.widget_tap_stop else R.string.widget_tap_start))

            v.setViewVisibility(R.id.widget_strings, if (roomForStrings) View.VISIBLE else View.GONE)
            for (i in 0 until MAX_STRINGS) {
                if (i >= face.letters.size) {
                    v.setViewVisibility(STRING_IDS[i], View.GONE)
                    continue
                }
                val done = face.tuned.getOrElse(i) { false }
                v.setViewVisibility(STRING_IDS[i], View.VISIBLE)
                v.setTextViewText(LETTER_IDS[i], face.letters[i])
                v.setTextColor(LETTER_IDS[i], if (done) LETTER_DONE else LETTER_TODO)
                v.setImageViewResource(DOT_IDS[i], if (done) R.drawable.widget_dot_on else R.drawable.widget_dot_off)
            }

            val tunedCount = face.tuned.count { it }
            v.setContentDescription(
                R.id.widget_bar,
                if (face.running) "${context.getString(R.string.widget_stop)}. $tunedCount of ${face.letters.size} strings in tune."
                else context.getString(R.string.widget_start)
            )
            val intent = Intent(context, StartActivity::class.java)
                .setAction(if (face.running) StartActivity.ACTION_STOP else StartActivity.ACTION_START)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            v.setOnClickPendingIntent(
                R.id.widget_bar,
                PendingIntent.getActivity(context, 7, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
            return v
        }
    }
}
