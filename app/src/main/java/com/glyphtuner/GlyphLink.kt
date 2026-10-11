package com.glyphtuner

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.nothing.ketchum.GlyphMatrixManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * One shared connection to the Glyph Matrix for the whole app. Nothing's manager is a single
 * instance per app, so the listening service and the Glyph Toy share it instead of each
 * connecting (and disconnecting) on their own. Call from the main thread.
 *
 * Every call into Nothing's Glyph service runs on one background thread ("GlyphIO"). They are
 * waiting calls into another process, and that process was once seen stuck on closeAppMatrix
 * with the screen off: on the main thread that froze the whole app halfway through letting go of
 * the back, and the phone's clock then took the back over until the Glyph Button was pressed.
 * Now the app keeps running, and if the service takes too long to close, the app disconnects
 * from it anyway. Frames are coalesced: if the service is slow, only the newest one is sent.
 */
object GlyphLink {
    private const val TAG = "GlyphLink"
    /** How long to wait for the Glyph service to finish closing before disconnecting anyway. */
    private const val CLOSE_TIMEOUT_MS = 1500L

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var io: ExecutorService = newIo()

    @Volatile private var manager: GlyphMatrixManager? = null
    private var users = 0
    @Volatile private var ready = false
    /** Something was drawn through the Glyph Toy channel since connecting. */
    @Volatile private var drewAsToy = false
    /** The app channel has a picture up that hasn't been closed yet. */
    @Volatile private var appShown = false
    private val waiting = mutableListOf<() -> Unit>()
    /** The previous session's disconnect, while it waits for the service to finish closing. */
    private var pendingDisconnect: (() -> Unit)? = null

    private class Frame(val pixels: IntArray, val asToy: Boolean)
    private val nextFrame = AtomicReference<Frame?>(null)
    private val sendFrame = Runnable {
        val f = nextFrame.getAndSet(null) ?: return@Runnable
        val m = manager ?: return@Runnable
        try {
            if (f.asToy) m.setMatrixFrame(f.pixels) else m.setAppMatrixFrame(f.pixels)
        } catch (t: Throwable) {
            Log.w(TAG, "frame failed", t)
        }
    }

    private fun newIo(): ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "GlyphIO").apply { isDaemon = true }
    }

    private val callback = object : GlyphMatrixManager.Callback {
        override fun onServiceConnected(name: ComponentName?) {
            val m = manager ?: return
            io.execute {
                try {
                    m.register(GlyphDevice.id())
                } catch (t: Throwable) {
                    Log.w(TAG, "register failed", t)
                }
                main.post {
                    if (manager !== m) return@post
                    ready = true
                    Log.i(TAG, "connected")
                    val todo = waiting.toList()
                    waiting.clear()
                    todo.forEach { it() }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.i(TAG, "disconnected by the system")
            ready = false
        }
    }

    /** Starts using the matrix. [onReady] runs once it's connected. Pair every call with [release]. */
    fun acquire(context: Context, onReady: () -> Unit) {
        users++
        // Finish letting go of the last session first, so its disconnect can't cut off this one.
        pendingDisconnect?.invoke()
        if (manager == null) {
            val m = try {
                GlyphMatrixManager.getInstance(context.applicationContext)
            } catch (t: Throwable) {
                Log.w(TAG, "Couldn't reach the Glyph Matrix", t)
                null
            } ?: run {
                Log.w(TAG, "No Glyph Matrix on this phone")
                return
            }
            manager = m
            ready = false
            try {
                m.init(callback)
            } catch (t: Throwable) {
                Log.w(TAG, "Couldn't connect to the Glyph Matrix", t)
                manager = null
                return
            }
        }
        if (ready) onReady() else waiting += onReady
    }

    fun release() {
        users--
        Log.i(TAG, "release, users left=$users, drewAsToy=$drewAsToy")
        if (users > 0) return
        users = 0
        waiting.clear()
        val m = manager
        val toy = drewAsToy
        val close = appShown
        manager = null
        ready = false
        drewAsToy = false
        appShown = false
        nextFrame.set(null)
        if (m == null) return

        val closed = AtomicBoolean(false)
        val disconnected = AtomicBoolean(false)
        lateinit var timeout: Runnable
        val disconnect: () -> Unit = {
            if (disconnected.compareAndSet(false, true)) {
                main.removeCallbacks(timeout)
                pendingDisconnect = null
                // A close still stuck in the service: leave that thread behind and start a fresh one.
                if (!closed.get()) io = newIo()
                try { m.unInit() } catch (_: Throwable) {}
                Log.i(TAG, "let go of the Glyph Matrix (closed cleanly=${closed.get()})")
            }
        }
        timeout = Runnable {
            Log.w(TAG, "Glyph service slow to close, disconnecting anyway")
            disconnect()
        }
        pendingDisconnect = disconnect
        io.execute {
            if (close) try { m.closeAppMatrix() } catch (_: Throwable) {}
            // turnOff() sends a blank frame through the Glyph Toy channel. Only a toy may do that:
            // from the app it leaves a blank toy frame behind that hides the app's own pictures
            // (which rank lower than toys) until the screen turns off. So only when a toy drew.
            if (toy) try { m.turnOff() } catch (_: Throwable) {}
            closed.set(true)
            main.post { disconnect() }
        }
        main.postDelayed(timeout, CLOSE_TIMEOUT_MS)
    }

    /** Draw as the active Glyph Toy. */
    fun showAsToy(frame: IntArray) {
        if (!ready) return
        drewAsToy = true
        send(Frame(frame, asToy = true))
    }

    /** Draw from the app (used while listening with no Glyph Toy selected). */
    fun showFromApp(frame: IntArray) {
        if (!ready) return
        appShown = true
        send(Frame(frame, asToy = false))
    }

    private fun send(frame: Frame) {
        // Only queue a send if none is waiting; a waiting one picks up the newest frame.
        if (nextFrame.getAndSet(frame) == null) io.execute(sendFrame)
    }

    /** Stop drawing from the app (the connection stays, e.g. for the Glyph Toy). */
    fun clearApp() {
        Log.i(TAG, "clearApp ready=$ready shown=$appShown")
        if (!ready || !appShown) return
        val m = manager ?: return
        appShown = false
        nextFrame.set(null)
        io.execute { try { m.closeAppMatrix() } catch (_: Throwable) {} }
    }
}
