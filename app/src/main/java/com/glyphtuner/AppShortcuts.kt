package com.glyphtuner

import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.widget.Toast

/** The two faster ways to start the tuner: the quick settings tile and the Glyph Toy. */
object AppShortcuts {

    /** Asks Android to add the Tuner tile. [onAdded] runs (main thread) if it's there afterwards. */
    fun addTile(activity: Activity, onAdded: () -> Unit) {
        try {
            activity.getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(activity, TunerTileService::class.java),
                activity.getString(R.string.tile_label),
                Icon.createWithResource(activity, R.drawable.ic_notification),
                { activity.runOnUiThread(it) },
                { result ->
                    if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                        result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                    ) onAdded()
                }
            )
        } catch (_: Throwable) {
            Toast.makeText(activity, "Pull down quick settings, tap the pencil, and drag Tuner in", Toast.LENGTH_LONG).show()
        }
    }

    /** Opens Nothing's Glyph Toys list, where Tuner can be switched on. */
    fun openGlyphToys(activity: Activity) {
        try {
            activity.startActivity(
                Intent()
                    .setComponent(ComponentName("com.nothing.thirdparty", "com.nothing.thirdparty.matrix.toys.manager.ToysManagerActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            Toast.makeText(activity, "Open Settings → Glyph Interface → Glyph Toys", Toast.LENGTH_LONG).show()
        }
    }
}
