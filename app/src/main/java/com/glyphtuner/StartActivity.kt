package com.glyphtuner

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast

/**
 * An invisible screen behind the "Start tuner" / "Stop tuner" shortcuts and the quick settings
 * tile. Android only lets listening mode start from something you can see, so this flashes
 * open, starts or stops the tuner, and closes again.
 */
class StartActivity : Activity() {

    private var done = false

    // Acting in onResume, when the screen is officially in front, keeps Android happy about
    // starting the microphone.
    override fun onResume() {
        super.onResume()
        if (done) return
        done = true
        when {
            intent?.action == ACTION_STOP -> {
                TunerService.stop(this)
                Toast.makeText(this, "Tuner stopped", Toast.LENGTH_SHORT).show()
            }
            TunerEngine.running -> {
                Toast.makeText(this, "Already listening", Toast.LENGTH_SHORT).show()
            }
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> {
                // First time: the app screen asks for the microphone.
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            else -> {
                TunerService.lastProblem = null
                try {
                    startForegroundService(Intent(this, TunerService::class.java))
                    Toast.makeText(this, "Listening. Flip your phone and pluck a string.", Toast.LENGTH_SHORT).show()
                } catch (t: Throwable) {
                    TunerService.lastProblem = "The tuner couldn't start: ${t.message}"
                    Toast.makeText(this, "The tuner couldn't start", Toast.LENGTH_SHORT).show()
                }
            }
        }
        // Fixes a widget left showing "listening" if the app was closed without stopping cleanly.
        TunerWidget.refresh(this)
        finish()
    }

    companion object {
        const val ACTION_START = "com.glyphtuner.action.START"
        const val ACTION_STOP = "com.glyphtuner.action.STOP"
    }
}
