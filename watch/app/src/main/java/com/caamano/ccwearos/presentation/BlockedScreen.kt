package com.caamano.ccwearos.presentation

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.ui.tooling.preview.WearPreviewLargeRound
import com.caamano.ccwearos.R
import com.caamano.ccwearos.data.BlockerKind
import com.caamano.ccwearos.presentation.theme.CCWEAROSTheme
import com.caamano.ccwearos.presentation.theme.CcPalette
import com.caamano.ccwearos.presentation.ui.MascotState
import com.caamano.ccwearos.presentation.ui.Motion
import com.caamano.ccwearos.presentation.ui.PixelMascot
import com.caamano.ccwearos.presentation.ui.rememberReducedMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// CONTRACT (owned by lane C; lane B routes to it). Keep this signature.
// Full-screen designed state for problems with one clear next action.
enum class BlockedVariant { MAC_OFFLINE, WATCH_OFFLINE, CLAUDE_CRASHED, NEEDS_MAC, NO_DICTATION }

/**
 * Full-screen "something is in the way" state: true black, still mascot, one
 * title, one line, one coral action (+ optional outlined "Cerrar" when
 * [onDismiss] is given). Copy per variant lives in [blockedCopy].
 *
 * For NEEDS_MAC, [blockerKind] picks the body: TRUST names the folder from
 * [cwd], LOGIN asks to sign in, anything else shows the wrapper's [hint].
 * For CLAUDE_CRASHED the [hint] (if any) replaces the default line.
 */
@Composable
fun BlockedScreen(
    variant: BlockedVariant,
    blockerKind: BlockerKind? = null,
    hint: String? = null,
    cwd: String? = null,
    onPrimary: () -> Unit,
    onDismiss: (() -> Unit)? = null,
) {
    val copy = remember(variant, blockerKind, hint, cwd) { blockedCopy(variant, blockerKind, hint, cwd) }
    val body = when (val b = copy.body) {
        is BlockedBody.Res -> if (b.arg != null) stringResource(b.id, b.arg) else stringResource(b.id)
        is BlockedBody.Literal -> b.text
    }
    BlockedLayout(
        mascot = copy.mascot,
        title = stringResource(copy.title),
        body = body,
        primaryLabel = stringResource(copy.primary),
        onPrimary = onPrimary,
        secondaryLabel = if (onDismiss != null) stringResource(R.string.blocked_close) else null,
        onSecondary = onDismiss,
        haptic = copy.haptic,
    )
}

// ─── Copy (pure, unit tested) ───────────────────────────────────────────────

internal enum class BlockedHaptic { NONE, TICK, ERROR }

internal sealed interface BlockedBody {
    data class Res(@StringRes val id: Int, val arg: String? = null) : BlockedBody
    data class Literal(val text: String) : BlockedBody
}

internal data class BlockedCopy(
    @StringRes val title: Int,
    val body: BlockedBody,
    @StringRes val primary: Int,
    val mascot: MascotState,
    val haptic: BlockedHaptic,
)

internal fun blockedCopy(
    variant: BlockedVariant,
    blockerKind: BlockerKind?,
    hint: String?,
    cwd: String?,
): BlockedCopy {
    val cleanHint = hint?.trim()?.takeIf { it.isNotEmpty() }
    return when (variant) {
        BlockedVariant.MAC_OFFLINE -> BlockedCopy(
            title = R.string.blocked_mac_offline_title,
            body = BlockedBody.Res(R.string.blocked_mac_offline_body),
            primary = R.string.blocked_mac_offline_action,
            mascot = MascotState.Offline,
            haptic = BlockedHaptic.NONE,
        )
        BlockedVariant.WATCH_OFFLINE -> BlockedCopy(
            title = R.string.blocked_watch_offline_title,
            body = BlockedBody.Res(R.string.blocked_watch_offline_body),
            primary = R.string.blocked_watch_offline_action,
            mascot = MascotState.Offline,
            haptic = BlockedHaptic.NONE,
        )
        BlockedVariant.CLAUDE_CRASHED -> BlockedCopy(
            title = R.string.blocked_crashed_title,
            body = cleanHint?.let { BlockedBody.Literal(it) } ?: BlockedBody.Res(R.string.blocked_crashed_body),
            primary = R.string.blocked_crashed_action,
            mascot = MascotState.Error,
            haptic = BlockedHaptic.ERROR,
        )
        BlockedVariant.NEEDS_MAC -> BlockedCopy(
            title = R.string.blocked_needs_mac_title,
            body = needsMacBody(blockerKind, cleanHint, cwd),
            primary = R.string.blocked_needs_mac_action,
            mascot = MascotState.Blocked,
            haptic = BlockedHaptic.TICK,
        )
        BlockedVariant.NO_DICTATION -> BlockedCopy(
            title = R.string.blocked_no_dictation_title,
            body = BlockedBody.Res(R.string.blocked_no_dictation_body),
            primary = R.string.blocked_no_dictation_action,
            mascot = MascotState.Blocked,
            haptic = BlockedHaptic.NONE,
        )
    }
}

private fun needsMacBody(kind: BlockerKind?, hint: String?, cwd: String?): BlockedBody {
    if (kind == BlockerKind.TRUST) {
        val folder = cwd?.trim()?.takeIf { it.isNotEmpty() }?.let(::projectBasename)
        return if (folder != null) {
            BlockedBody.Res(R.string.blocked_needs_mac_trust_body, folder)
        } else {
            BlockedBody.Res(R.string.blocked_needs_mac_trust_body_no_cwd)
        }
    }
    if (hint != null) return BlockedBody.Literal(hint)
    return if (kind == BlockerKind.LOGIN) {
        BlockedBody.Res(R.string.blocked_needs_mac_login_body)
    } else {
        BlockedBody.Res(R.string.blocked_needs_mac_body)
    }
}

// ─── Layout (shared with MainActivity's sign-in error) ──────────────────────

@Composable
internal fun BlockedLayout(
    mascot: MascotState,
    title: String,
    body: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    haptic: BlockedHaptic = BlockedHaptic.NONE,
) {
    val context = LocalContext.current
    val inPreview = LocalInspectionMode.current
    val reduced = rememberReducedMotion()

    LaunchedEffect(title) {
        if (inPreview) return@LaunchedEffect
        when (haptic) {
            BlockedHaptic.ERROR -> Haptics.error(context)
            BlockedHaptic.TICK -> Haptics.tick(context)
            BlockedHaptic.NONE -> Unit
        }
    }

    // Arrival: the mascot settles in, then title, line and actions fade up in
    // sequence. Reduced motion shows everything at rest immediately.
    val mascotScale = remember(title) { Animatable(if (reduced) 1f else 0.6f) }
    val titleIn = remember(title) { Animatable(if (reduced) 1f else 0f) }
    val bodyIn = remember(title) { Animatable(if (reduced) 1f else 0f) }
    val actionsIn = remember(title) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(title, reduced) {
        if (reduced) return@LaunchedEffect
        launch { mascotScale.animateTo(1f, Motion.settle()) }
        launch {
            delay(Motion.FAST.toLong())
            titleIn.animateTo(1f, Motion.enter())
        }
        launch {
            delay((Motion.FAST + Motion.STAGGER * 2).toLong())
            bodyIn.animateTo(1f, Motion.enter())
        }
        launch {
            delay((Motion.FAST + Motion.STAGGER * 4).toLong())
            actionsIn.animateTo(1f, Motion.enter())
        }
    }

    val scrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val side = (LocalConfiguration.current.screenWidthDp * 0.12f).dp

    // Opaque so lane B can also draw this as an overlay above the pager.
    Box(
        Modifier
            .fillMaxSize()
            .background(CcPalette.Black),
    ) {
        ScreenScaffold(scrollState = scrollState) { padding ->
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val minHeight = maxHeight
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .rotaryScrollable(RotaryScrollableDefaults.behavior(scrollState), focusRequester)
                        .verticalScroll(scrollState),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = minHeight)
                            .padding(padding)
                            .padding(horizontal = side, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        PixelMascot(
                            state = mascot,
                            size = 40.dp,
                            modifier = Modifier.graphicsLayer {
                                scaleX = mascotScale.value
                                scaleY = mascotScale.value
                            },
                        )
                        Text(
                            text = title,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .fadeUp(titleIn)
                                .semantics {
                                    heading()
                                    liveRegion = LiveRegionMode.Polite
                                },
                        )
                        Text(
                            text = body,
                            color = CcPalette.TextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .fadeUp(bodyIn),
                        )
                        Spacer(Modifier.height(4.dp))
                        PrimaryAction(
                            label = primaryLabel,
                            onClick = onPrimary,
                            modifier = Modifier.fadeUp(actionsIn),
                        )
                        if (secondaryLabel != null && onSecondary != null) {
                            SecondaryAction(
                                label = secondaryLabel,
                                onClick = onSecondary,
                                modifier = Modifier.fadeUp(actionsIn),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.fadeUp(progress: Animatable<Float, *>): Modifier = graphicsLayer {
    val p = progress.value
    alpha = p
    translationY = (1f - p) * 6.dp.toPx()
}

@Composable
private fun PrimaryAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val source = remember { MutableInteractionSource() }
    val scale by animatePressScale(source)
    Button(
        onClick = {
            Haptics.tick(context)
            onClick()
        },
        interactionSource = source,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SecondaryAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val source = remember { MutableInteractionSource() }
    val scale by animatePressScale(source)
    OutlinedButton(
        onClick = {
            Haptics.tick(context)
            onClick()
        },
        interactionSource = source,
        border = BorderStroke(1.dp, CcPalette.Outline),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Press response from the feedback contract (Motion.kt, step 1): a pressed
 * control eases to [Motion.PRESSED_SCALE]. Read the value inside a
 * graphicsLayer block so only the layer redraws.
 */
@Composable
internal fun animatePressScale(source: InteractionSource): State<Float> {
    val pressed by source.collectIsPressedAsState()
    return animateFloatAsState(
        targetValue = if (pressed) Motion.PRESSED_SCALE else 1f,
        animationSpec = Motion.standard(Motion.FAST),
        label = "pressScale",
    )
}

// ─── Previews ───────────────────────────────────────────────────────────────

@WearPreviewLargeRound
@Composable
private fun PreviewBlockedMacOffline() {
    CCWEAROSTheme { BlockedScreen(variant = BlockedVariant.MAC_OFFLINE, onPrimary = {}) }
}

@WearPreviewLargeRound
@Composable
private fun PreviewBlockedWatchOffline() {
    CCWEAROSTheme { BlockedScreen(variant = BlockedVariant.WATCH_OFFLINE, onPrimary = {}) }
}

@WearPreviewLargeRound
@Composable
private fun PreviewBlockedCrashed() {
    CCWEAROSTheme { BlockedScreen(variant = BlockedVariant.CLAUDE_CRASHED, onPrimary = {}, onDismiss = {}) }
}

@WearPreviewLargeRound
@Composable
private fun PreviewBlockedNeedsMacTrust() {
    CCWEAROSTheme {
        BlockedScreen(
            variant = BlockedVariant.NEEDS_MAC,
            blockerKind = BlockerKind.TRUST,
            cwd = "/Users/me/projects/CCWEAROS",
            onPrimary = {},
        )
    }
}

@WearPreviewLargeRound
@Composable
private fun PreviewBlockedNoDictation() {
    CCWEAROSTheme { BlockedScreen(variant = BlockedVariant.NO_DICTATION, onPrimary = {}, onDismiss = {}) }
}
