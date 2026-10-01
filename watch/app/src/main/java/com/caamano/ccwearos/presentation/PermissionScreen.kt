package com.caamano.ccwearos.presentation

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
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
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import androidx.wear.compose.ui.tooling.preview.WearPreviewLargeRound
import com.caamano.ccwearos.presentation.permission.HoldRing
import com.caamano.ccwearos.presentation.permission.Risk
import com.caamano.ccwearos.presentation.permission.classifyRisk
import com.caamano.ccwearos.presentation.permission.parsePrompt
import com.caamano.ccwearos.presentation.permission.rememberHoldToConfirm
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

                val description = parsed.description
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
                            text = "Claude necesita tu permiso.",
                            color = PermissionColors.textPrimary,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                if (!canAnswer) {
                    item(key = "state") {
                        val offline = !connected
                        Text(
                            text = if (offline) "Sin conexión: no se puede responder" else "Respuesta enviada",
                            color = if (offline) PermissionColors.waiting else PermissionColors.textSecondary,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                        )
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
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                heading()
                contentDescription = if (risky) "Permiso, comando riesgoso" else "Permiso"
            },
    ) {
        Text(
            text = "PERMISO",
            color = PermissionColors.waiting,
            fontFamily = MonoFamily,
            fontSize = 12.sp,
            letterSpacing = 1.sp,
            fontWeight = FontWeight.Medium,
        )
        if (risky) {
            Text(
                text = "RIESGO",
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
    // Full text, no maxLines: the user must be able to read every character
    // of what they are approving. The list scrolls instead.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(PermissionColors.surface, RoundedCornerShape(8.dp))
            .border(BorderStroke(1.dp, PermissionColors.outline), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Comando: $command" },
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
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = PermissionColors.danger,
            disabledContentColor = PermissionColors.disabled,
        ),
        border = BorderStroke(1.dp, PermissionColors.outline),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .semantics {
                contentDescription = "Rechazar comando"
                if (!enabled) stateDescription = "Desactivado"
            },
    ) {
        Text(
            text = "Rechazar",
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
            contentDescription = if (risky) "Permitir comando. Mantén presionado para confirmar" else "Permitir comando"
            if (!enabled) stateDescription = "Desactivado"
            // TalkBack's double tap never produces a press, so a hold can't be
            // timed. Expose the long-press action instead (double tap and hold).
            if (risky && enabled) {
                onLongClick(label = "Permitir comando") {
                    onAccessibleHoldConfirm()
                    true
                }
            }
        },
    ) {
        Text(
            text = if (risky) "Mantén para permitir" else "Permitir",
            fontSize = 15.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
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
