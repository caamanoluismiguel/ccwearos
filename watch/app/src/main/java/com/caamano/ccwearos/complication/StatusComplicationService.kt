package com.caamano.ccwearos.complication

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.caamano.ccwearos.R
import com.caamano.ccwearos.presentation.MainActivity
import com.caamano.ccwearos.tile.TileDataSource
import com.caamano.ccwearos.tile.TileMapper
import com.caamano.ccwearos.tile.TileState

/**
 * Watch-face complication: SHORT_TEXT (state word, or context "18%" when idle,
 * plus the monochrome mascot) and RANGED_VALUE (context % 0..100).
 * Tap opens MainActivity. Refresh: UPDATE_PERIOD_SECONDS in the manifest, or
 * [ComplicationUpdater.requestUpdate] from the app's listener.
 */
class StatusComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        build(this, request.complicationType, TileDataSource.load())

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(this, type, TileState.Idle(dailyTokens = 45_200, contextPct = 18))

    private fun build(context: Context, type: ComplicationType, state: TileState): ComplicationData? {
        val tap = openApp(context)
        val description = PlainComplicationText.Builder(TileMapper.description(state)).build()
        val icon = MonochromaticImage.Builder(
            Icon.createWithResource(context, R.drawable.ic_complication_mascot),
        ).build()
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                PlainComplicationText.Builder(TileMapper.shortLabel(state)).build(),
                description,
            )
                .setMonochromaticImage(icon)
                .setTapAction(tap)
                .build()

            ComplicationType.RANGED_VALUE -> {
                val pct = TileMapper.contextPct(state)
                RangedValueComplicationData.Builder(
                    value = (pct ?: 0).toFloat(),
                    min = 0f,
                    max = 100f,
                    contentDescription = description,
                )
                    .setText(PlainComplicationText.Builder(pct?.let { "$it%" } ?: TileMapper.shortLabel(state)).build())
                    .setMonochromaticImage(icon)
                    .setTapAction(tap)
                    .build()
            }

            else -> NoDataComplicationData()
        }
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
