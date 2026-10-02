package com.caamano.ccwearos.presentation.home

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.tooling.preview.devices.WearDevices
import com.caamano.ccwearos.presentation.theme.CCWEAROSTheme
import com.caamano.ccwearos.presentation.theme.CcPalette
import com.caamano.ccwearos.presentation.ui.MascotState
import com.caamano.ccwearos.presentation.ui.PixelMascot
import kotlin.math.abs
import kotlin.math.sign

// ─────────────────────────────────────────────────────────────────────────────
// CONTINUITY — screens never hard-cut. Content holds its place in space while
// it blurs and stretches into the next state, and the progress is driven live
// by the gesture (pager offset) or by a Motion-token animation (overlays).
//
// progress 0 = settled, 1 = fully away. Everything happens in one
// graphicsLayer whose block reads progress lazily, so a swipe only redraws the
// layer: no recomposition per frame. No gradients, glows or vignettes: only
// blur, scale, alpha and a translation lag.
// ─────────────────────────────────────────────────────────────────────────────

/** Max blur at progress 1. */
private val MAX_BLUR = 12.dp
private const val STRETCH_X = 0.06f
private const val SQUASH_Y = 0.02f
private const val FADE = 0.45f

/** Content travels ~85% of the finger, so it seems to stay put in space. */
private const val FINGER_LAG = 0.15f

/** Below this the layer is treated as settled: no RenderEffect at all. */
private const val SETTLED = 0.01f

/**
 * Continuity transition for a fixed progress value. [direction] is the side
 * the content travels toward as progress grows (+1 right, -1 left, 0 in place).
 * With reduced motion the layer only crossfades.
 */
fun Modifier.continuityTransition(
    progress: Float,
    direction: Int = 1,
    enabled: Boolean = true,
): Modifier = continuityTransition(
    progress = { progress },
    direction = { direction },
    enabled = enabled,
    reducedMotion = false,
    followFinger = false,
)

/**
 * Deferred-read variant: [progress] and [direction] are read inside the layer
 * block, so animating them never recomposes the content. [followFinger] adds
 * the translation lag for content that is already moved by a pager.
 */
fun Modifier.continuityTransition(
    progress: () -> Float,
    direction: () -> Int = { 1 },
    enabled: Boolean = true,
    reducedMotion: Boolean = false,
    followFinger: Boolean = false,
): Modifier = if (!enabled) this else graphicsLayer {
    applyContinuity(
        p = progress().coerceIn(0f, 1f),
        dir = direction(),
        reducedMotion = reducedMotion,
        followFinger = followFinger,
    )
}

private fun GraphicsLayerScope.applyContinuity(
    p: Float,
    dir: Int,
    reducedMotion: Boolean,
    followFinger: Boolean,
) {
    if (p <= SETTLED) {
        // Settled: identity layer, and crucially no RenderEffect.
        alpha = 1f
        scaleX = 1f
        scaleY = 1f
        translationX = 0f
        renderEffect = null
        return
    }
    if (p >= 1f - SETTLED) {
        // A full page away: off-screen for good. Nothing may peek in at the
        // bezel, and no RenderEffect is kept for an invisible layer.
        alpha = 0f
        translationX = 0f
        renderEffect = null
        return
    }
    if (reducedMotion) {
        alpha = 1f - p
        scaleX = 1f
        scaleY = 1f
        translationX = 0f
        renderEffect = null
        return
    }
    // Fade with the motion, and fully out over the last quarter so the
    // neighbouring page is invisible by the time it settles one page away.
    val tailFade = ((p - 0.75f) / 0.25f).coerceIn(0f, 1f)
    alpha = (1f - FADE * p) * (1f - tailFade * tailFade * (3f - 2f * tailFade))
    // Stretch along the motion, anchored on the side the content travels toward.
    transformOrigin = TransformOrigin(
        pivotFractionX = when {
            dir > 0 -> 1f
            dir < 0 -> 0f
            else -> 0.5f
        },
        pivotFractionY = 0.5f,
    )
    scaleX = 1f + STRETCH_X * p
    scaleY = 1f - SQUASH_Y * p
    // Lag peaks mid-swipe and returns to zero at both ends, so content never
    // rests partly on screen (a linear lag left 15% of the neighbour visible).
    translationX = if (followFinger) -dir * 4f * p * (1f - p) * size.width * FINGER_LAG else 0f
    renderEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val r = MAX_BLUR.toPx() * p
        BlurEffect(r, r, TileMode.Decal)
    } else {
        null
    }
}

// ─── Underlay below an overlay (WearApp) ─────────────────────────────────────

/** How far the pager recedes while an overlay is arriving or leaving. */
internal const val UNDERLAY_RECEDE = 0.6f

/**
 * Pager progress for overlay [reveal] 0..1. At 1 the opaque overlay covers
 * it completely, so progress drops to 0: no blur RenderEffect is kept alive
 * for a layer nobody can see (see [underlayHidden], which hides it).
 */
fun underlayProgress(reveal: Float): Float = if (underlayHidden(reveal)) 0f else UNDERLAY_RECEDE * reveal.coerceIn(0f, 1f)

/** True once the overlay is fully in: the pager draws nothing (alpha 0) but stays composed. */
fun underlayHidden(reveal: Float): Boolean = reveal >= 1f

/** Live progress for a pager page: 0 when centred, 1 one page away. */
fun pageProgress(page: Int, currentPage: Int, currentPageOffsetFraction: Float): Float =
    abs(pageOffset(page, currentPage, currentPageOffsetFraction)).coerceIn(0f, 1f)

/** Side a page sits on relative to the viewport (+1 right of centre, -1 left). */
fun pageDirection(page: Int, currentPage: Int, currentPageOffsetFraction: Float): Int =
    pageOffset(page, currentPage, currentPageOffsetFraction).sign.toInt()

private fun pageOffset(page: Int, currentPage: Int, fraction: Float): Float =
    page - (currentPage + fraction)

// ─── PREVIEWS: fixed progress samples ────────────────────────────────────────

@Composable
private fun ContinuitySample(progress: Float, direction: Int = 1) {
    CCWEAROSTheme {
        Box(
            Modifier
                .fillMaxSize()
                .background(CcPalette.Black),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                // Previews report reduced motion (inspection mode); this
                // sample always shows the full effect.
                modifier = Modifier.continuityTransition(progress = progress, direction = direction),
            ) {
                PixelMascot(state = MascotState.Idle, size = 40.dp, animate = false)
                Text("Listo", style = MaterialTheme.typography.titleMedium)
                Text("p = $progress", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Continuity · 0")
@Composable
private fun PreviewContinuity0() = ContinuitySample(0f)

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Continuity · 0.25")
@Composable
private fun PreviewContinuity25() = ContinuitySample(0.25f)

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Continuity · 0.5")
@Composable
private fun PreviewContinuity50() = ContinuitySample(0.5f)

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Continuity · 0.8 left")
@Composable
private fun PreviewContinuity80() = ContinuitySample(0.8f, direction = -1)
