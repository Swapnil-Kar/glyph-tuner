package com.glyphtuner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import android.service.quicksettings.TileService
import android.util.Log

/**
 * "Listening mode". Android only lets an app hear the microphone in the background through a
 * foreground service with a notification, started while the app is open. This is that service:
 * it keeps the tuner running with the screen locked and the phone face-down.
 */
class TunerService : Service() {

    private var holdingGlyph = false
    private var alive = false
    /** Stopping on our own: the goodbye plays and the back is let go while still in listening mode. */
    private var stopping = false

    /** Notes screen on/off in the system log, to line up with what the back of the phone did. */
    private val screenLog = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "screen ${if (intent.action == Intent.ACTION_SCREEN_OFF) "off" else "on"}, tuner running=${TunerEngine.running}")
        }
    }
    private var screenLogOn = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "start command action=${intent?.action}, already running=${TunerEngine.running}")
        if (intent?.action == ACTION_STOP) {
            stopGracefully()
            return START_NOT_STICKY
        }
        if (TunerEngine.running) return START_NOT_STICKY
        // Started again during the goodbye: carry on instead of stopping at its end.
        stopping = false

        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (t: Throwable) {
            Log.w(TAG, "Android refused listening mode", t)
            lastProblem = "Android didn't allow the tuner to start. Open the app and tap Start."
            stopSelf()
            return START_NOT_STICKY
        }
        if (!TunerEngine.start(this)) {
            lastProblem = "The microphone couldn't start. Check the microphone permission."
            stopSelf()
            return START_NOT_STICKY
        }
        lastProblem = null
        alive = true
        TunerEngine.onAutoStop = { stopGracefully() }
        TunerEngine.onStatus = { text ->
            if (alive) {
                updateNotification(text)
                // The widget lights up each string's dot as it gets in tune.
                TunerWidget.refresh(this)
            }
        }
        holdingGlyph = true
        GlyphLink.acquire(this) { if (alive) TunerEngine.appActive = true }
        if (!screenLogOn) {
            try {
                registerReceiver(screenLog, IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                })
                screenLogOn = true
            } catch (_: Throwable) {}
        }
        refreshTile()
        return START_NOT_STICKY
    }

    /**
     * Stops listening, plays the goodbye, lets go of the back, and only then leaves listening mode.
     * Staying in listening mode until the end keeps Android from pausing the app halfway through.
     */
    private fun stopGracefully() {
        if (stopping) return
        Log.i(TAG, "stopping")
        stopping = true
        teardown {
            if (stopping) {
                try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
                stopSelf()
            }
        }
        refreshTile()
    }

    /** Stops the tuner; after the goodbye animation, lets go of the back and runs [after]. */
    private fun teardown(after: () -> Unit) {
        alive = false
        TunerEngine.onAutoStop = null
        TunerEngine.onStatus = null
        val holding = holdingGlyph
        holdingGlyph = false
        TunerEngine.stop {
            if (holding) {
                if (!TunerEngine.running) {
                    TunerEngine.appActive = false
                    GlyphLink.clearApp()
                }
                GlyphLink.release()
            }
            after()
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "destroyed (graceful=$stopping)")
        if (screenLogOn) {
            try { unregisterReceiver(screenLog) } catch (_: Throwable) {}
            screenLogOn = false
        }
        // Stopped from outside (Android, or a plain stopService): let go now.
        if (!stopping) teardown {}
        refreshTile()
        super.onDestroy()
    }

    /** Lets the quick settings tile and the home screen widget show on/off correctly. */
    private fun refreshTile() {
        TunerWidget.refresh(this)
        try {
            TileService.requestListeningState(this, ComponentName(this, TunerTileService::class.java))
        } catch (_: Throwable) {}
    }

    /** Shows the current string and progress in the listening notification. */
    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
        } catch (t: Throwable) {
            Log.w(TAG, "Couldn't update the notification", t)
        }
    }

    private fun notification(text: String = getString(R.string.listening_text)): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, TunerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.listening_title))
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_notification),
                    getString(R.string.stop), stop
                ).build()
            )
            .build()
    }

    companion object {
        private const val TAG = "TunerService"
        private const val CHANNEL_ID = "listening"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.glyphtuner.STOP"

        /** Asks the tuner to stop the gentle way (goodbye, then let go of the back). */
        fun stop(context: Context) {
            val intent = Intent(context, TunerService::class.java)
            if (!TunerEngine.running) {
                context.stopService(intent)
                return
            }
            try {
                context.startService(intent.setAction(ACTION_STOP))
            } catch (t: Throwable) {
                context.stopService(Intent(context, TunerService::class.java))
            }
        }

        /** Why the last start failed, for the app screen. Null when fine. */
        @Volatile var lastProblem: String? = null
    }
}
