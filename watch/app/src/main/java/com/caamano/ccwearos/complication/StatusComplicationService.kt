package com.caamano.ccwearos.complication

import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import androidx.annotation.StringRes
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
import com.caamano.ccwearos.notifications.DeepLinks
import com.caamano.ccwearos.tile.StateWord
import com.caamano.ccwearos.tile.TileDataSource
import com.caamano.ccwearos.tile.TileMapper
import com.caamano.ccwearos.tile.TileState

/**
 * Watch-face complication: SHORT_TEXT (Spanish state word: Listo, Trabajando,
 * Permiso, Mac, Sin red…) and RANGED_VALUE (context % 0..100). The icon is the
 * monochrome mascot, swapped for a pixel "!" while Claude waits on you
 * (permission) or on your Mac (blocker). Tap opens MainActivity (the Done
 * state opens the Resultado page). Refresh: UPDATE_PERIOD_SECONDS in the
 * manifest, or [ComplicationUpdater.requestUpdate] from the app's listener.
 */
class StatusComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        build(this, request.complicationType, TileDataSource.load())

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(this, type, TileState.Idle(dailyTokens = 45_200, contextPct = 18))

    private fun build(context: Context, type: ComplicationType, state: TileState): ComplicationData? {
        val tap = openApp(context, state)
        val word = context.getString(wordRes(TileMapper.stateWord(state)))
        val description = PlainComplicationText.Builder(description(context, state)).build()
        val iconRes = if (TileMapper.needsAttention(state)) R.drawable.ic_complication_alert else R.drawable.ic_complication_mascot
        val icon = MonochromaticImage.Builder(Icon.createWithResource(context, iconRes)).build()
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                PlainComplicationText.Builder(word).build(),
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
                    // Attention states show their word even when a % is known.
                    .setText(PlainComplicationText.Builder(pct?.takeUnless { TileMapper.needsAttention(state) }?.let { "$it%" } ?: word).build())
                    .setMonochromaticImage(icon)
                    .setTapAction(tap)
                    .build()
            }

            else -> NoDataComplicationData()
        }
    }

    private fun description(context: Context, state: TileState): String {
        val pct = TileMapper.contextPct(state)
        return when (val w = TileMapper.stateWord(state)) {
            StateWord.READY -> if (pct != null) {
                context.getString(R.string.complication_cd_ready_pct, pct)
            } else {
                context.getString(R.string.complication_cd_ready)
            }
            else -> context.getString(descriptionRes(w))
        }
    }

    private fun openApp(context: Context, state: TileState): PendingIntent {
        val action = if (state is TileState.Done) DeepLinks.ACTION_RESULT else null
        return PendingIntent.getActivity(
            context,
            if (action == null) 0 else 1,
            DeepLinks.intent(context, action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        @StringRes
        fun wordRes(word: StateWord): Int = when (word) {
            StateWord.OPEN -> R.string.complication_word_open
            StateWord.NO_SIGNAL -> R.string.complication_word_no_signal
            StateWord.MAC_OFFLINE -> R.string.complication_word_mac_offline
            StateWord.READY -> R.string.complication_word_ready
            StateWord.WORKING -> R.string.complication_word_working
            StateWord.PERMISSION -> R.string.complication_word_permission
            StateWord.MAC -> R.string.complication_word_mac
            StateWord.FAILED -> R.string.complication_word_failed
        }

        @StringRes
        fun descriptionRes(word: StateWord): Int = when (word) {
            StateWord.OPEN -> R.string.complication_cd_open
            StateWord.NO_SIGNAL -> R.string.complication_cd_no_signal
            StateWord.MAC_OFFLINE -> R.string.complication_cd_mac_offline
            StateWord.READY -> R.string.complication_cd_ready
            StateWord.WORKING -> R.string.complication_cd_working
            StateWord.PERMISSION -> R.string.complication_cd_permission
            StateWord.MAC -> R.string.complication_cd_mac
            StateWord.FAILED -> R.string.complication_cd_failed
        }
    }
}
