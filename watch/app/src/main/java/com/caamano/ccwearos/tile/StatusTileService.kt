package com.caamano.ccwearos.tile

import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.caamano.ccwearos.complication.ComplicationUpdater
import com.caamano.ccwearos.presentation.Haptics
import com.caamano.ccwearos.presentation.MainActivity
import com.google.common.util.concurrent.ListenableFuture

/**
 * Status Tile: Listo / Trabajando / Permiso / Necesita tu Mac / Listo ✓ (Done)
 * / Sin conexión. "Preguntar" deep-links into voice input and "Ver resultado"
 * into the Resultado page (see [com.caamano.ccwearos.notifications.DeepLinks]).
 *
 * A TileService can't hold a live listener, so each request does fresh
 * single reads (see [TileDataSource]) and asks to be refreshed every
 * [FRESHNESS_MS]. For instant updates the app's foreground listener should
 * call [TileUpdater.requestUpdate] whenever /status changes.
 *
 * Permitir / Rechazar chips use a LoadAction: the tap comes back here as a
 * new onTileRequest with lastClickableId = "allow:<promptId>|<nonce>" (see
 * [TileClicks]), handled once, and [TileDataSource.answer] writes /command
 * only if that promptId is still the live one.
 */
class StatusTileService : TileService() {

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> = SuspendToFutureAdapter.launchFuture {
        val clickedId = requestParams.currentState.lastClickableId
        val click = TileClicks.decode(clickedId)?.takeIf { markHandled(clickedId) }
        var feedback: TileLayouts.Feedback? = null
        if (click != null) {
            val ok = TileDataSource.answer(click.promptId, click.allow)
            feedback = when {
                !ok -> TileLayouts.Feedback.FAILED
                click.allow -> TileLayouts.Feedback.SENT_ALLOW
                else -> TileLayouts.Feedback.SENT_DENY
            }
            if (ok) Haptics.tick(this@StatusTileService) else Haptics.error(this@StatusTileService)
        }

        val state = TileDataSource.load()
        // Only show the confirmation while the answered prompt is still on screen
        // (the wrapper may not have consumed it yet); otherwise render live state.
        val shownFeedback = feedback?.takeIf { state is TileState.Awaiting || it == TileLayouts.Feedback.FAILED }
        val renderState = if (shownFeedback != null && state !is TileState.Awaiting) {
            TileState.Awaiting(promptPreview = "", promptId = null, quickActions = false)
        } else {
            state
        }
        if (feedback != null) ComplicationUpdater.requestUpdate(this@StatusTileService)

        val root = TileLayouts.root(
            this@StatusTileService,
            renderState,
            packageName = packageName,
            activityClass = MainActivity::class.java.name,
            feedback = shownFeedback,
            nonce = System.currentTimeMillis(),
        )
        TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            // After an answer, check back quickly so the tile leaves "Permiso".
            .setFreshnessIntervalMillis(if (feedback != null) 5_000L else FRESHNESS_MS)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
            .build()
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> = SuspendToFutureAdapter.launchFuture {
        ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build()
    }

    companion object {
        const val RESOURCES_VERSION = "1"
        const val FRESHNESS_MS = 60_000L

        private var lastHandledClick: String? = null

        /** True the first time a given clickable id is seen in this process. */
        @Synchronized
        private fun markHandled(id: String): Boolean {
            if (id == lastHandledClick) return false
            lastHandledClick = id
            return true
        }
    }
}
