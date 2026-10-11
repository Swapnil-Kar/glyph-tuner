package com.glyphtuner

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** The "Tuner" tile in the quick settings panel: tap to start or stop listening. */
class TunerTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (TunerEngine.running) {
            TunerService.stop(this)
            refresh(running = false)
        } else {
            // Starting needs a visible screen, so go through the invisible start screen.
            val start = Intent(this, StartActivity::class.java)
                .setAction(StartActivity.ACTION_START)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, start, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        }
    }

    private fun refresh(running: Boolean = TunerEngine.running) {
        val tile = qsTile ?: return
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = if (running) "Listening" else "Off"
        tile.updateTile()
    }
}
