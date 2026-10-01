package com.caamano.ccwearos.complication

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester

/** Asks every watch face showing our complication to re-request its data. */
object ComplicationUpdater {
    fun requestUpdate(context: Context) {
        try {
            val app = context.applicationContext
            ComplicationDataSourceUpdateRequester
                .create(app, ComponentName(app, StatusComplicationService::class.java))
                .requestUpdateAll()
        } catch (e: Exception) {
            Log.w("ccwearos-tile", "complication update request failed: ${e.message}")
        }
    }
}
