package com.caamano.ccwearos.tile

import android.content.Context
import android.util.Log
import androidx.wear.tiles.TileService

/**
 * Asks the system to re-render the Status Tile now. Cheap and safe to call
 * on every /status change (the system throttles it). Intended caller: the
 * foreground service's RTDB listener.
 */
object TileUpdater {
    fun requestUpdate(context: Context) {
        try {
            TileService.getUpdater(context.applicationContext)
                .requestUpdate(StatusTileService::class.java)
        } catch (e: Exception) {
            Log.w("ccwearos-tile", "tile update request failed: ${e.message}")
        }
    }
}
