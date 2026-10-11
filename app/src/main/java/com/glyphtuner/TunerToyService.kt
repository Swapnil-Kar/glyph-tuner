package com.glyphtuner

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import com.nothing.ketchum.GlyphToy

/**
 * The "Tuner" Glyph Toy, picked with the Glyph Button. While listening mode is on, it shows
 * the tuner, and a long press stops listening. Otherwise it shows a play sign, and a long
 * press tries to start listening.
 */
class TunerToyService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var bound = false

    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what != GlyphToy.MSG_GLYPH_TOY) {
                super.handleMessage(msg)
                return
            }
            val event = msg.data?.getString(GlyphToy.MSG_GLYPH_TOY_DATA)
            Log.i(TAG, "toy event $event")
            if (event == GlyphToy.EVENT_CHANGE) onLongPress()
        }
    })

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "toy shown (bound), tuner running=${TunerEngine.running}")
        bound = true
        GlyphLink.acquire(this) {
            if (!bound) return@acquire
            TunerEngine.toyActive = true
            if (!TunerEngine.running) GlyphLink.showAsToy(TunerArt.startHint(TunerEngine.look(this)))
        }
        return messenger.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "toy hidden (unbound)")
        bound = false
        main.removeCallbacksAndMessages(null)
        TunerEngine.toyActive = false
        GlyphLink.release()
        return false
    }

    private fun onLongPress() {
        if (TunerEngine.running) {
            TunerService.stop(this)
            return
        }
        try {
            startForegroundService(Intent(this, TunerService::class.java))
        } catch (t: Throwable) {
            Log.w(TAG, "Can't start listening from the toy", t)
        }
        // Android won't let a Glyph Toy switch the microphone on by itself. If it refused,
        // say what to do instead, then go back to the play sign.
        main.postDelayed({ if (bound && !TunerEngine.running) showHint(HINT) }, 1500)
    }

    /** Scrolls [text] across the matrix once, then shows the play sign again. */
    private fun showHint(text: String) {
        val look = TunerEngine.look(this)
        val startedAt = TunerEngine.now()
        val frame = object : Runnable {
            override fun run() {
                if (!bound || TunerEngine.running) return
                val t = TunerEngine.now() - startedAt
                if (t >= TunerArt.messageSeconds(text)) {
                    GlyphLink.showAsToy(TunerArt.startHint(look))
                    return
                }
                GlyphLink.showAsToy(TunerArt.message(t, text, look))
                main.postDelayed(this, 40)
            }
        }
        main.post(frame)
    }

    private companion object {
        const val TAG = "TunerToy"
        /** What the matrix says when a long press can't start listening. */
        const val HINT = "OPEN APP"
    }
}
