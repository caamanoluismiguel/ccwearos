package com.caamano.ccwearos.presentation

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.ui.tooling.preview.WearPreviewLargeRound
import com.caamano.ccwearos.R
import com.caamano.ccwearos.presentation.theme.CCWEAROSTheme
import com.caamano.ccwearos.presentation.theme.CcPalette
import com.caamano.ccwearos.presentation.theme.StatusColors
import com.caamano.ccwearos.presentation.ui.Motion
import com.caamano.ccwearos.presentation.ui.PixelIcons
import com.caamano.ccwearos.presentation.ui.rememberReducedMotion
import kotlinx.coroutines.launch

// Result pill for /claimResult and failed writes, near the TOP of the round
// face so the content below stays readable. It slides in from the top, plays
// Haptics.done / Haptics.error once, and STAYS until the user taps it or
// swipes it up: a failure reason that vanishes before you look is useless.
@Composable
fun ClaimResultBanner(
    ok: Boolean,
    message: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val inPreview = LocalInspectionMode.current
    val reduced = rememberReducedMotion()
    val scope = rememberCoroutineScope()
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val hidden = with(LocalDensity.current) { -64.dp.toPx() }
    val swipeThreshold = with(LocalDensity.current) { 20.dp.toPx() }

    val offsetY = remember(ok, message) { Animatable(if (reduced) 0f else hidden) }
    val alpha = remember(ok, message) { Animatable(if (reduced) 1f else 0f) }
    var dismissing by remember(ok, message) { mutableStateOf(false) }

    LaunchedEffect(ok, message) {
        if (!inPreview) {
            if (ok) Haptics.done(context) else Haptics.error(context)
        }
        if (!reduced) {
            launch { offsetY.animateTo(0f, Motion.settle()) }
            alpha.animateTo(1f, Motion.enter())
        }
    }

    fun dismiss() {
        if (dismissing) return
        dismissing = true
        scope.launch {
            if (!reduced) {
                launch { alpha.animateTo(0f, Motion.exit(Motion.MEDIUM)) }
                offsetY.animateTo(hidden, Motion.exit(Motion.MEDIUM))
            }
            currentOnDismiss()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        // Inset from the bezel curve, above the dashboard header zone so the
        // glance lands on the pill first.
        contentAlignment = BiasAlignment(0f, -0.66f),
    ) {
        val tint = if (ok) StatusColors.running else StatusColors.error
        val shape = RoundedCornerShape(16.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth(fraction = 0.8f)
                .graphicsLayer {
                    translationY = offsetY.value
                    this.alpha = alpha.value
                }
                .clip(shape)
                .background(CcPalette.SurfaceHigh)
                .border(BorderStroke(1.dp, tint), shape)
                .pointerInput(ok, message) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (offsetY.value < -swipeThreshold) {
                                dismiss()
                            } else {
                                scope.launch { offsetY.animateTo(0f, Motion.settle()) }
                            }
                        },
                        onDragCancel = { scope.launch { offsetY.animateTo(0f, Motion.settle()) } },
                    ) { change, dragAmount ->
                        change.consume()
                        // Only upward travel; pulling down just resists at rest.
                        scope.launch { offsetY.snapTo((offsetY.value + dragAmount).coerceAtMost(0f)) }
                    }
                }
                .clickable(onClickLabel = stringResource(R.string.banner_dismiss_label)) { dismiss() }
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            Icon(
                imageVector = if (ok) PixelIcons.Check else PixelIcons.Cross,
                contentDescription = stringResource(if (ok) R.string.result_ok else R.string.result_failed),
                tint = tint,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = message,
                color = CcPalette.TextPrimary,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Start,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

@WearPreviewLargeRound
@Composable
private fun PreviewBannerOk() {
    CCWEAROSTheme { ClaimResultBanner(ok = true, message = "Sesión abierta en tu Mac", onDismiss = {}) }
}

@WearPreviewLargeRound
@Composable
private fun PreviewBannerFailed() {
    CCWEAROSTheme {
        ClaimResultBanner(ok = false, message = "No se pudo abrir: la sesión ya está activa", onDismiss = {})
    }
}
