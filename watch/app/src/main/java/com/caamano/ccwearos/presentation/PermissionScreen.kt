package com.caamano.ccwearos.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import androidx.wear.compose.ui.tooling.preview.WearPreviewLargeRound
import com.caamano.ccwearos.R
import com.caamano.ccwearos.presentation.permission.HoldRing
import com.caamano.ccwearos.presentation.permission.Risk
import com.caamano.ccwearos.presentation.permission.classifyRisk
import com.caamano.ccwearos.presentation.permission.parsePrompt
import com.caamano.ccwearos.presentation.permission.visibleDescription
import com.caamano.ccwearos.presentation.permission.rememberHoldToConfirm
import com.caamano.ccwearos.presentation.theme.StatusColors
import com.caamano.ccwearos.presentation.ui.Motion
import com.caamano.ccwearos.presentation.ui.PixelIcons
import com.caamano.ccwearos.presentation.ui.pressFeedback
import com.caamano.ccwearos.presentation.ui.rememberReducedMotion
import kotlinx.coroutines.delay

// Permission v2 palette: quiet wrist instrument on true black. Flat solids only;
// depth comes from 1dp borders, never glows or gradients.
private object PermissionColors {
    val background = Color(0xFF000000)
    val primary = Color(0xFFCC785C) // coral
    val surface = Color(0xFF141414)
    val outline = Color(0xFF2A2A2A)
    val textPrimary = Color(0xFFFFFFFF)
    val textSecondary = Color(0xFF9A9A9A)
    val waiting = Color(0xFFFFB340)
    val danger = Color(0xFFFF6961)
    val disabled = Color(0xFF5E5E5E)
}

@Composable
fun PermissionScreen(
    prompt: String?,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    // false while the watch has no live Firebase connection (.info/connected).
    // Answers must not be tappable then: an offline write is queued and
    // replayed later, when it could land on a different prompt.
    connected: Boolean = true,
    // true once the user answered the current prompt (keyed on
    // /permissionPromptId by the ViewModel). Both buttons disable so a double
    // tap can't write /command twice.
    answered: Boolean = false,
) {
    val context = LocalContext.current
    LaunchedEffect(prompt) {
        // Debounce so two snapshots within 100ms during Firebase reconnect
        // don't fire a double-buzz that feels like a glitchy single vibration.
        // Also skip empty prompts — the watch sometimes wakes from ambient
        // with `prompt=null` cached before the real prompt arrives; firing
        // a haptic on that cached null is a phantom buzz.
        if (prompt.isNullOrBlank()) return@LaunchedEffect
        delay(120)
        Haptics.permission(context)
    }
    // Hardware back / swipe-right on Wear OS would close the app — surprising
    // for a modal asking for a critical decision. Swallow back so the user
    // has to explicitly answer.
    BackHandler(enabled = true) { /* no-op: prevent accidental dismiss */ }

    // UI-level double-tap guard. The ViewModel also guards by promptId, but
    // `answered` needs a round trip; this flag flips on the very first tap and
    // resets only when a new prompt arrives.
    var tapped by remember(prompt) { mutableStateOf(false) }
    val canAnswer = connected && !answered && !tapped

    val parsed = remember(prompt) { parsePrompt(prompt) }
    val risk = remember(prompt) { classifyRisk(prompt) }
    val risky = risk == Risk.RISKY

    fun allow() {
        if (!canAnswer) return
        tapped = true
        onAllow()
    }

    fun deny() {
        if (!canAnswer) return
        tapped = true
        Haptics.tick(context)
        onDeny()
    }

    val allowInteraction = remember { MutableInteractionSource() }
    val holdProgress = rememberHoldToConfirm(
        interactionSource = allowInteraction,
        enabled = risky && canAnswer,
        onConfirm = ::allow,
    )

    val listState = rememberTransformingLazyColumnState()
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val layoutDirection = LocalLayoutDirection.current

    Box(
        Modifier
            .fillMaxSize()
            .background(PermissionColors.background),
    ) {
        ScreenScaffold(
            scrollState = listState,
            edgeButton = {
                AllowEdgeButton(
                    risky = risky,
                    enabled = canAnswer,
                    interactionSource = allowInteraction,
                    onClick = { if (!risky) allow() },
                    onAccessibleHoldConfirm = ::allow,
                    modifier = Modifier.scrollable(
                        listState,
                        orientation = Orientation.Vertical,
                        reverseDirection = true,
                    ),
                )
            },
        ) { scaffoldPadding ->
            // Responsive: the scaffold supplies screen-relative vertical padding
            // (and room for the EdgeButton); widen the sides to ~9% of the
            // screen so monospace lines don't kiss the round bezel.
            val side = screenWidth * 0.09f
            val contentPadding = PaddingValues(
                start = max(scaffoldPadding.calculateStartPadding(layoutDirection), side),
                end = max(scaffoldPadding.calculateEndPadding(layoutDirection), side),
                top = scaffoldPadding.calculateTopPadding(),
                bottom = scaffoldPadding.calculateBottomPadding(),
            )
            TransformingLazyColumn(
                state = listState,
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Top),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "header") { PermissionHeader(risky = risky) }

                parsed.tool?.let { tool ->
                    item(key = "tool") {
                        Text(
                            text = tool,
                            color = PermissionColors.textPrimary,
                            fontSize = 18.sp,
                            lineHeight = 22.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                parsed.target?.let { target ->
                    item(key = "target") { CommandBox(command = target) }
                }

                // Hidden when it only repeats the command (owner: only what's needed).
                val description = visibleDescription(parsed)
                when {
                    description != null -> item(key = "description") {
                        // Free-form prompt (no `Tool:` line): it IS the content,
                        // so it reads as primary text instead of secondary.
                        val primary = parsed.tool == null
                        Text(
                            text = description,
                            color = if (primary) PermissionColors.textPrimary else PermissionColors.textSecondary,
                            fontSize = 14.sp,
                            lineHeight = 18.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    parsed.tool == null -> item(key = "fallback") {
                        Text(
                            text = stringResource(R.string.permission_fallback),
                            color = PermissionColors.textPrimary,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                if (!canAnswer) {
                    item(key = "state") {
                        if (!connected) {
                            Text(
                                text = stringResource(R.string.permission_offline),
                                color = PermissionColors.waiting,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                            )
                        } else {
                            AnsweredConfirmation()
                        }
                    }
                }

                item(key = "deny") {
                    DenyButton(enabled = canAnswer, onClick = ::deny)
                }
            }
        }

        // Drawn above everything so the fill traces the physical bezel.
        if (risky) {
            HoldRing(
                progress = holdProgress.value,
                color = PermissionColors.primary,
                trackColor = PermissionColors.outline,
            )
        }
    }
}

@Composable
private fun PermissionHeader(risky: Boolean) {
    // One pulse on arrival (1 → 1.08 → 1) so the eye finds the header, then
    // still. Keyed per composition: a new prompt remounts the screen.
    val reduced = rememberReducedMotion()
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        pulse.animateTo(1.08f, Motion.enter(Motion.MEDIUM))
        pulse.animateTo(1f, Motion.settle())
    }
    val headerCd = stringResource(if (risky) R.string.permission_header_risky_cd else R.string.permission_header)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = pulse.value
                scaleY = pulse.value
            }
            .semantics(mergeDescendants = true) {
                heading()
                contentDescription = headerCd
            },
    ) {
        Text(
            text = stringResource(R.string.permission_header).uppercase(),
            color = PermissionColors.waiting,
            fontFamily = MonoFamily,
            fontSize = 12.sp,
            letterSpacing = 1.sp,
            fontWeight = FontWeight.Medium,
        )
        if (risky) {
            Text(
                text = stringResource(R.string.permission_risk).uppercase(),
                color = PermissionColors.danger,
                fontFamily = MonoFamily,
                fontSize = 12.sp,
                letterSpacing = 1.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .border(BorderStroke(1.dp, PermissionColors.danger), RoundedCornerShape(50))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun CommandBox(command: String) {
    val commandCd = stringResource(R.string.permission_command_cd, command)
    // Full text, no maxLines: the user must be able to read every character
    // of what they are approving. The list scrolls instead.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(PermissionColors.surface, RoundedCornerShape(8.dp))
            .border(BorderStroke(1.dp, PermissionColors.outline), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = commandCd },
    ) {
        Text(
            text = command,
            color = PermissionColors.textPrimary,
            fontFamily = MonoFamily,
            fontSize = 14.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DenyButton(enabled: Boolean, onClick: () -> Unit) {
    val denyCd = stringResource(R.string.permission_deny_cd)
    val disabledState = stringResource(R.string.permission_disabled)
    val source = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = source,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = PermissionColors.danger,
            disabledContentColor = PermissionColors.disabled,
        ),
        border = BorderStroke(1.dp, PermissionColors.outline),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            // deny() ticks itself, so the press only scales.
            .pressFeedback(source, haptic = false)
            .semantics {
                contentDescription = denyCd
                if (!enabled) stateDescription = disabledState
            },
    ) {
        Text(
            text = stringResource(R.string.permission_deny),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AllowEdgeButton(
    risky: Boolean,
    enabled: Boolean,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
    onAccessibleHoldConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val allowCd = stringResource(if (risky) R.string.permission_allow_hold_cd else R.string.permission_allow_cd)
    val allowLabel = stringResource(R.string.permission_allow_cd)
    val disabledState = stringResource(R.string.permission_disabled)
    EdgeButton(
        onClick = onClick,
        enabled = enabled,
        buttonSize = if (risky) EdgeButtonSize.Large else EdgeButtonSize.Medium,
        interactionSource = interactionSource,
        colors = ButtonDefaults.buttonColors(
            containerColor = PermissionColors.primary,
            contentColor = PermissionColors.background,
            disabledContainerColor = PermissionColors.surface,
            disabledContentColor = PermissionColors.disabled,
        ),
        border = if (enabled) null else BorderStroke(1.dp, PermissionColors.outline),
        modifier = modifier.semantics {
            contentDescription = allowCd
            if (!enabled) stateDescription = disabledState
            // TalkBack's double tap never produces a press, so a hold can't be
            // timed. Expose the long-press action instead (double tap and hold).
            if (risky && enabled) {
                onLongClick(label = allowLabel) {
                    onAccessibleHoldConfirm()
                    true
                }
            }
        },
    ) {
        Text(
            text = stringResource(if (risky) R.string.permission_allow_hold else R.string.permission_allow),
            fontSize = 15.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

// "Respuesta enviada" with a check that pops in (scale settle + fade): the
// distinct ending from the feedback contract. Visual only; the haptic for the
// answer itself already fired on tap.
@Composable
private fun AnsweredConfirmation() {
    val reduced = rememberReducedMotion()
    val pop = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduced) pop.animateTo(1f, Motion.settle())
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Icon(
            imageVector = PixelIcons.Check,
            contentDescription = null,
            tint = StatusColors.running,
            modifier = Modifier
                .size(14.dp)
                .graphicsLayer {
                    val p = pop.value
                    scaleX = 0.4f + 0.6f * p
                    scaleY = 0.4f + 0.6f * p
                    alpha = p.coerceIn(0f, 1f)
                },
        )
        Text(
            text = stringResource(R.string.permission_answered),
            color = PermissionColors.textSecondary,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.graphicsLayer { alpha = pop.value.coerceIn(0f, 1f) },
        )
    }
}

// ---------------------------------------------------------------- previews

@WearPreviewDevices
@Composable
private fun PreviewPermissionNormal() {
    PermissionScreen(
        prompt = "Bash: npm run test -- --watch=false\nCorre la suite de pruebas",
        onAllow = {},
        onDeny = {},
    )
}

@WearPreviewLargeRound
@Composable
private fun PreviewPermissionRisky() {
    PermissionScreen(
        prompt = "Bash: rm -rf build/ node_modules/\nLimpia artefactos de compilación",
        onAllow = {},
        onDeny = {},
    )
}

@WearPreviewLargeRound
@Composable
private fun PreviewPermissionLongCommand() {
    PermissionScreen(
        prompt = "Bash: find . -type f -name '*.kt' -not -path './build/*' -exec grep -l 'PermissionScreen' {} + | xargs wc -l | sort -n\n" +
            "Cuenta las líneas de cada archivo que usa PermissionScreen",
        onAllow = {},
        onDeny = {},
    )
}

@WearPreviewLargeRound
@Composable
private fun PreviewPermissionDisconnected() {
    PermissionScreen(
        prompt = "Edit: src/foo.ts",
        onAllow = {},
        onDeny = {},
        connected = false,
    )
}

@WearPreviewLargeRound
@Composable
private fun PreviewPermissionAnswered() {
    PermissionScreen(
        prompt = "Bash: git status",
        onAllow = {},
        onDeny = {},
        answered = true,
    )
}
