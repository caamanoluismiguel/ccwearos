package com.caamano.ccwearos.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.PagerState
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.tooling.preview.devices.WearDevices
import com.caamano.ccwearos.R
import com.caamano.ccwearos.data.ClaudeStatus
import com.caamano.ccwearos.data.Metrics
import com.caamano.ccwearos.data.RecentSession
import com.caamano.ccwearos.data.SharedSessionMeta
import com.caamano.ccwearos.data.TaskKind
import com.caamano.ccwearos.data.ToolEvent
import com.caamano.ccwearos.data.WrapperStatus
import com.caamano.ccwearos.presentation.theme.CCWEAROSTheme
import com.caamano.ccwearos.presentation.theme.CcPalette
import com.caamano.ccwearos.presentation.theme.StatusColors
import com.caamano.ccwearos.presentation.ui.ArcGauge
import com.caamano.ccwearos.presentation.ui.CompletionRing
import com.caamano.ccwearos.presentation.ui.HairlineDivider
import com.caamano.ccwearos.presentation.ui.MascotState
import com.caamano.ccwearos.presentation.ui.MonoLabel
import com.caamano.ccwearos.presentation.ui.PixelIcons
import com.caamano.ccwearos.presentation.ui.PixelMascot
import com.caamano.ccwearos.presentation.ui.StatusDot
import com.caamano.ccwearos.presentation.ui.labelRes
import com.caamano.ccwearos.presentation.ui.pixelIcon
import com.caamano.ccwearos.presentation.ui.rememberVoiceInput
import com.caamano.ccwearos.presentation.ui.statusColor
import com.caamano.ccwearos.presentation.ui.toMascotState
import com.caamano.ccwearos.presentation.ui.usageColor
import java.text.NumberFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow

// ─────────────────────────────────────────────────────────────────────────────
// DASHBOARD — up to 5-page horizontal Pager IA
//
// Page 0 — COMMAND: mascot (the living status) + state in words + activity +
//           one primary action ("Preguntar" / "Seguir" / "Detener").
// Page 1 — METRICS: session % and weekly % arc gauges, tokens today demoted.
// Page 2 — RESPONSE: last Claude reply (only when there is one).
// Page 3 — FOLLOWUP: contextual "¿Y ahora qué?" chips + reset.
// Page 4 — SESSIONS: Mac sessions grouped by project; tap to resume.
//
// Every scrolling page is a ScreenScaffold + TransformingLazyColumn, which
// brings rotary-crown scrolling and the system scroll indicator for free.
// TimeText comes from the AppScaffold in WearApp (ScreenScaffold's
// timeText = null means "use the app-level one").
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun DashboardScreen(
    status: WrapperStatus,
    metrics: Metrics,
    activity: String? = null,
    task: String? = null,
    response: String? = null,
    claudeStatus: ClaudeStatus? = null,
    taskKind: TaskKind? = null,
    headline: String? = null,
    toolEvents: List<ToolEvent> = emptyList(),
    followups: List<String> = emptyList(),
    sentInSession: Boolean = false,
    sharedSession: SharedSessionMeta? = null,
    recentSessions: List<RecentSession> = emptyList(),
    onAsk: (String) -> Unit = {},
    onAskWithReset: (String) -> Unit = {},
    onStop: () -> Unit = {},
    onForceReset: () -> Unit = {},
    // Invoked when a session row is tapped on the Sessions page. The
    // ViewModel raises the confirmation dialog.
    onClaim: (sessionId: String, cwd: String) -> Unit = { _, _ -> },
    // Fires once per task completion (detected in the ViewModel so it
    // survives AnimatedContent re-creations on status changes).
    taskCompleted: SharedFlow<Unit>? = null,
) {
    val hasResponse = !response.isNullOrBlank()
    val hasResult = hasResponse || taskKind != null
    val hasSessions = recentSessions.isNotEmpty()
    val pageCount = when {
        hasResult && hasSessions -> 5
        hasResult -> 4
        hasSessions -> 3 // Command + Metrics + Sessions
        else -> 2
    }

    // "Seguir" only when this app session sent a prompt, a response came
    // back, and the wrapper is at rest.
    val inConversation = sentInSession && hasResponse && status == WrapperStatus.IDLE

    val pagerState = rememberPagerState(initialPage = 0) { pageCount }

    // Transient "just finished" mascot state (Done / Error) + ring trigger.
    var finishState by remember { mutableStateOf<MascotState?>(null) }
    var ringTrigger by remember { mutableIntStateOf(0) }
    LaunchedEffect(finishState) {
        when (finishState) {
            MascotState.Done -> { delay(2_500); finishState = null }
            MascotState.Error -> { delay(4_000); finishState = null }
            else -> Unit
        }
    }

    TaskCompletionHandler(
        taskCompleted = taskCompleted,
        pagerState = pagerState,
        response = response,
        taskKind = taskKind,
        onFinished = { failed ->
            finishState = if (failed) MascotState.Error else MascotState.Done
            if (!failed) ringTrigger++
        },
    )

    val mascotState = when {
        status != WrapperStatus.IDLE -> status.toMascotState()
        finishState != null -> finishState!!
        else -> MascotState.Idle
    }

    Box(Modifier.fillMaxSize()) {
        HorizontalPagerScaffold(pagerState = pagerState) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                // With no result yet, Sessions slides into slot 2.
                val sessionsIndex = if (hasResult) 4 else 2
                when (page) {
                    0 -> CommandPage(
                        status = status,
                        mascotState = mascotState,
                        activity = activity,
                        task = task,
                        toolEvents = toolEvents,
                        inConversation = inConversation,
                        sharedSession = sharedSession,
                        onAsk = onAsk,
                        onStop = onStop,
                        onForceReset = onForceReset,
                    )
                    1 -> MetricsPage(metrics = metrics, claudeStatus = claudeStatus)
                    sessionsIndex -> SessionsPage(
                        sessions = recentSessions,
                        sharedSession = sharedSession,
                        onClaim = onClaim,
                    )
                    2 -> ResponsePage(
                        response = response,
                        taskKind = taskKind,
                        headline = headline,
                        toolEvents = toolEvents,
                    )
                    3 -> FollowupPage(
                        followups = followups,
                        taskKind = taskKind,
                        headline = headline,
                        onAsk = onAsk,
                        onAskWithReset = onAskWithReset,
                    )
                    else -> Box(Modifier.fillMaxSize())
                }
            }
        }
        CompletionRing(trigger = ringTrigger)
    }
}

// Horizontal inset for non-list pages: keeps text inside the round bezel at
// any screen size (≈12% of the screen width per side).
@Composable
private fun roundInset() = (LocalConfiguration.current.screenWidthDp * 0.12f).dp

// ─── PAGE 0: COMMAND ─────────────────────────────────────────────────────────

@Composable
private fun CommandPage(
    status: WrapperStatus,
    mascotState: MascotState,
    activity: String?,
    task: String?,
    toolEvents: List<ToolEvent>,
    inConversation: Boolean,
    sharedSession: SharedSessionMeta?,
    onAsk: (String) -> Unit,
    onStop: () -> Unit,
    onForceReset: () -> Unit,
) {
    ScreenScaffold { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = roundInset()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Small rounds (~192dp) have no room for the detail line.
            val roomy = LocalConfiguration.current.screenHeightDp >= 200
            Spacer(Modifier.height(if (roomy) 10.dp else 4.dp))
            PixelMascot(state = mascotState, size = 32.dp)
            Spacer(Modifier.height(6.dp))
            StateWord(mascotState)

            // Activity is gated on RUNNING: after a Wear OS freeze the last
            // emission can linger until Firebase reconnects.
            val running = status == WrapperStatus.RUNNING
            val latestTool = toolEvents.lastOrNull()
            if (running && !activity.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                ActivityLine(activity, latestTool)
                val detail = latestTool?.arg?.takeIf { it.isNotBlank() } ?: task?.takeIf { it.isNotBlank() }
                if (detail != null && roomy) {
                    Text(
                        text = detail.take(48),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = MonoFamily,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else if (running && !task.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = task,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    maxLines = if (roomy) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(10.dp))
            when {
                // Shared session: the ask button would clobber the wrapper's pty.
                sharedSession != null -> SharedSessionBlock(sharedSession)
                status == WrapperStatus.IDLE -> AskButton(inConversation = inConversation, onAsk = onAsk)
                status == WrapperStatus.RUNNING -> StopButton(onClick = onStop, onLongClick = onForceReset)
                else -> Spacer(Modifier.height(52.dp))
            }
            // Clears the pager's page indicator.
            Spacer(Modifier.height(if (roomy) 18.dp else 14.dp))
        }
    }
}

@Composable
private fun StateWord(state: MascotState) {
    val word = stringResource(state.labelRes())
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) { },
    ) {
        // Decorative: the word next to it already carries the meaning.
        StatusDot(color = state.statusColor(), description = null)
        Spacer(Modifier.width(6.dp))
        Text(
            text = word,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
        )
    }
}

@Composable
private fun ActivityLine(activity: String, tool: ToolEvent?) {
    AnimatedContent(
        targetState = activity to tool?.pixelIcon(),
        transitionSpec = {
            fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(180))
        },
        label = "activity",
    ) { (text, icon) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AskButton(inConversation: Boolean, onAsk: (String) -> Unit) {
    val voice = rememberVoiceInput(onText = onAsk)
    val label = stringResource(if (inConversation) R.string.action_continue else R.string.action_ask)
    val prompt = stringResource(if (inConversation) R.string.voice_prompt_continue else R.string.voice_prompt_ask)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = { voice.launch(prompt) },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            contentPadding = PaddingValues(horizontal = 14.dp),
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .height(52.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = PixelIcons.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(text = label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
        VoiceUnavailableNote(visible = voice.unavailable)
    }
}

@Composable
private fun VoiceUnavailableNote(visible: Boolean) {
    if (!visible) return
    Spacer(Modifier.height(4.dp))
    Text(
        text = stringResource(R.string.voice_unavailable),
        color = StatusColors.error,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun StopButton(onClick: () -> Unit, onLongClick: () -> Unit) {
    // Tap: SIGINT via /command (wrapper kills the runner cleanly).
    // Long-press (≥500ms): force-reset RTDB from the watch; the recovery path
    // when status=RUNNING is phantom because the wrapper died.
    val context = LocalContext.current
    val description = stringResource(R.string.action_stop_cd)
    val shape = RoundedCornerShape(26.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth(0.86f)
            .height(52.dp)
            .clip(shape)
            .border(BorderStroke(1.dp, StatusColors.error), shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    Haptics.error(context)
                    onLongClick()
                },
            )
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = PixelIcons.Stop,
                contentDescription = null,
                tint = StatusColors.error,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.action_stop),
                color = StatusColors.error,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun SharedSessionBlock(meta: SharedSessionMeta) {
    // cc (wrapper-pty): user is in the Terminal; permissions reach the watch
    // through the wrapper. hook (/ccwearos): standalone Claude, the
    // PreToolUse hook routes permissions here.
    val (header, hint) = when (meta.kind) {
        "hook" -> R.string.shared_hook_title to R.string.shared_hook_hint
        "wrapper-pty" -> R.string.shared_pty_title to R.string.shared_pty_hint
        else -> R.string.shared_pty_title to R.string.shared_hint
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.semantics(mergeDescendants = true) { },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = PixelIcons.Link,
                contentDescription = null,
                tint = StatusColors.waiting,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(header),
                color = StatusColors.waiting,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        Text(
            text = meta.cwd.substringAfterLast("/").ifBlank { meta.cwd },
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = MonoFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(hint),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

// ─── PAGE 1: METRICS ─────────────────────────────────────────────────────────

@Composable
private fun MetricsPage(metrics: Metrics, claudeStatus: ClaudeStatus?) {
    val listState = rememberTransformingLazyColumnState()
    val sessionPct = claudeStatus?.sessionPct
    val weeklyPct = claudeStatus?.weeklyPct
    val hasGauges = sessionPct != null || weeklyPct != null

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (hasGauges) {
                item { UsageGauges(sessionPct = sessionPct, weeklyPct = weeklyPct) }
                item { TokensToday(metrics.dailyTokens, prominent = false) }
            } else {
                item { TokensToday(metrics.dailyTokens, prominent = true) }
            }
            if (claudeStatus != null) {
                val modelLine = buildString {
                    claudeStatus.model?.let { append(it.lowercase()) }
                    claudeStatus.contextSize?.let {
                        if (isNotEmpty()) append(" · ")
                        append(it.lowercase()).append(" ctx")
                    }
                }
                if (modelLine.isNotEmpty()) {
                    item {
                        Text(
                            text = modelLine,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                claudeStatus.monthlyCost?.let { cost ->
                    item { CostBlock(cost = cost, resets = claudeStatus.monthlyResets) }
                }
            }
            if (!hasGauges) {
                item {
                    Text(
                        text = stringResource(
                            R.string.metrics_week_month,
                            shortNum(metrics.weeklyTokens),
                            shortNum(metrics.monthlyTokens),
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = MonoFamily,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun UsageGauges(sessionPct: Double?, weeklyPct: Double?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        sessionPct?.let { UsageGauge(label = stringResource(R.string.metrics_session), pct = it) }
        weeklyPct?.let { UsageGauge(label = stringResource(R.string.metrics_week), pct = it) }
    }
}

@Composable
private fun UsageGauge(label: String, pct: Double) {
    val value = formatPct(pct)
    ArcGauge(
        fraction = (pct / 100.0).toFloat(),
        valueText = value,
        label = label,
        color = usageColor(pct),
        description = stringResource(R.string.metrics_gauge_cd, label, value),
        diameter = 68.dp,
    )
}

@Composable
private fun TokensToday(value: Long, prominent: Boolean) {
    val target = value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val animated by animateIntAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "today-tokens",
    )
    // Tabular numerals (theme display/numeral styles) keep the count-up from
    // jittering. Past 100k, the compact form keeps 34sp inside the bezel.
    val text = if (animated >= 100_000) shortNum(animated.toLong())
    else NumberFormat.getIntegerInstance().format(animated)
    val label = "${stringResource(R.string.metrics_tokens)} ${stringResource(R.string.metrics_today)}"
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { },
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurface,
            style = if (prominent) MaterialTheme.typography.displayLarge
            else MaterialTheme.typography.numeralExtraSmall,
            maxLines = 1,
        )
        MonoLabel(text = label)
    }
}

@Composable
private fun CostBlock(cost: String, resets: String?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { },
    ) {
        Text(
            text = cost,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.displaySmall,
            maxLines = 1,
        )
        if (!resets.isNullOrBlank()) {
            Text(
                text = stringResource(R.string.metrics_resets, resets),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ─── PAGE 3: FOLLOWUP — "¿Y ahora qué?" ──────────────────────────────────────
// Claude-suggested chips (tap = send as next prompt, wrapper continues the
// session) + an explicit voice reset ("Nueva conversación").

@Composable
private fun FollowupPage(
    followups: List<String>,
    taskKind: TaskKind?,
    headline: String?,
    onAsk: (String) -> Unit,
    onAskWithReset: (String) -> Unit,
) {
    // Fallback chips when Claude didn't generate any. These are prompts sent
    // back to Claude, so they follow the conversation language (sniffed from
    // the headline), not the watch locale.
    val effectiveFollowups: List<String> = remember(followups, taskKind, headline) {
        if (followups.isNotEmpty()) return@remember followups
        val isSpanish = headline?.any { c -> c in "¿áéíóúñ" } == true
        when (taskKind) {
            TaskKind.ACTION -> if (isSpanish) listOf("Más detalles", "Otra cosa", "Deshacer")
            else listOf("More details", "Something else", "Undo")
            else -> if (isSpanish) listOf("Más detalles", "Otra cosa")
            else listOf("More details", "Something else")
        }
    }
    val voice = rememberVoiceInput(onText = onAskWithReset)
    val resetPrompt = stringResource(R.string.voice_prompt_reset)
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Text(
                    text = stringResource(R.string.followups_title),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { heading() },
                )
            }
            items(effectiveFollowups) { suggestion ->
                Button(
                    onClick = { onAsk(suggestion) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    transformation = SurfaceTransformation(spec),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .transformedHeight(this, spec),
                ) {
                    Text(
                        text = suggestion,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            item {
                Button(
                    onClick = { voice.launch(resetPrompt) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    transformation = SurfaceTransformation(spec),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .transformedHeight(this, spec),
                ) {
                    Icon(
                        imageVector = PixelIcons.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.followups_new),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
            if (voice.unavailable) {
                item { VoiceUnavailableNote(visible = true) }
            }
        }
    }
}

// ─── PAGE 4: SESSIONS ────────────────────────────────────────────────────────
// Every Claude Code session the wrapper scanner found, grouped by project,
// newest first. Coral = shared via cc, green = active process, grey = past.
// Past sessions are tappable (resume in a new Terminal on the Mac).

private sealed interface SessionEntry {
    data class Header(val project: String) : SessionEntry
    data class Item(val session: RecentSession) : SessionEntry
}

@Composable
private fun SessionsPage(
    sessions: List<RecentSession>,
    sharedSession: SharedSessionMeta?,
    onClaim: (sessionId: String, cwd: String) -> Unit,
) {
    val entries = remember(sessions) {
        sessions.sortedByDescending { it.mtime }
            .groupBy { it.projectName }
            .flatMap { (project, list) ->
                listOf(SessionEntry.Header(project)) + list.map { SessionEntry.Item(it) }
            }
    }
    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics(mergeDescendants = true) { heading() },
                    horizontalArrangement = Arrangement.Center,
                ) {
                    MonoLabel(stringResource(R.string.sessions_title), color = MaterialTheme.colorScheme.primary)
                    MonoLabel(" · ${sessions.size}")
                }
            }
            items(entries) { entry ->
                when (entry) {
                    is SessionEntry.Header -> Text(
                        text = entry.project,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = MonoFamily,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .semantics { heading() },
                    )
                    is SessionEntry.Item -> {
                        val sess = entry.session
                        val isShared = sess.sessionId == sharedSession?.sessionId
                        SessionRow(
                            session = sess,
                            isShared = isShared,
                            // Active / shared sessions hold the file lock, so
                            // `claude --resume` would fail: not claimable.
                            onTap = if (sess.active || isShared) null else {
                                { onClaim(sess.sessionId, sess.cwd) }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: RecentSession,
    isShared: Boolean,
    onTap: (() -> Unit)?,
) {
    val (dotColor, stateRes) = when {
        isShared -> CcPalette.Coral to R.string.session_shared
        session.active -> StatusColors.running to R.string.session_active
        else -> StatusColors.idle to R.string.session_past
    }
    val stateWord = stringResource(stateRes)
    val ago = timeAgoShort(session.mtime)
    val rowDescription = listOfNotNull(stateWord, ago, session.lastUserMessage).joinToString(", ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .let { if (onTap != null) it.combinedClickable(onClick = onTap) else it }
            .clearAndSetSemantics {
                contentDescription = rowDescription
                if (onTap != null) role = Role.Button
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        StatusDot(
            color = dotColor,
            description = null,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = "$stateWord · $ago",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
            session.lastUserMessage?.let { msg ->
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// "hace 2 min", "hace 1 h", "ayer", "hace 3 d".
@Composable
private fun timeAgoShort(mtime: Long): String {
    val diffSec = ((System.currentTimeMillis() - mtime) / 1000L).coerceAtLeast(0)
    return when {
        diffSec < 60 -> stringResource(R.string.time_now)
        diffSec < 3600 -> stringResource(R.string.time_minutes, (diffSec / 60).toInt())
        diffSec < 86_400 -> stringResource(R.string.time_hours, (diffSec / 3600).toInt())
        diffSec < 172_800 -> stringResource(R.string.time_yesterday)
        else -> stringResource(R.string.time_days, (diffSec / 86_400).toInt())
    }
}

// ─── PAGE 2: RESPONSE ────────────────────────────────────────────────────────

@Composable
private fun ResponsePage(
    response: String?,
    taskKind: TaskKind?,
    headline: String?,
    toolEvents: List<ToolEvent>,
) {
    when (taskKind) {
        TaskKind.ACTION -> ActionResultLayout(response, toolEvents)
        TaskKind.INFO -> InfoResultLayout(response, headline)
        null -> if (response.isNullOrBlank()) EmptyResultLayout() else InfoResultLayout(response, headline)
    }
}

private val FAILURE_PATTERN =
    Regex("\\b(error|failed|canceled|cancelled|denied|aborted)\\b", RegexOption.IGNORE_CASE)

/** Action runs that mention a failure word are treated as failed. */
private fun responseLooksFailed(response: String?): Boolean =
    response?.let { FAILURE_PATTERN.containsMatchIn(it) } ?: false

// "Did it work?": large pixel ✓ / ✗, one outcome line, tool breadcrumbs.
@Composable
private fun ActionResultLayout(response: String?, toolEvents: List<ToolEvent>) {
    val failed = remember(response) { responseLooksFailed(response) }
    val outcomeLine = remember(response) {
        response
            ?.split(Regex("\\n{2,}"))
            ?.map { it.trim() }
            ?.lastOrNull { it.isNotBlank() }
            ?.replace(Regex("^\\*?\\*?TL;?DR:?\\*?\\*?\\s*[:—-]?\\s*", RegexOption.IGNORE_CASE), "")
            ?.take(140)
    }
    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (failed) PixelIcons.Cross else PixelIcons.Check,
                        contentDescription = stringResource(if (failed) R.string.result_failed else R.string.result_ok),
                        tint = if (failed) StatusColors.error else StatusColors.running,
                        modifier = Modifier.size(56.dp),
                    )
                }
            }
            if (!outcomeLine.isNullOrBlank()) {
                item {
                    Text(
                        text = renderMarkdownInline(outcomeLine),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (toolEvents.isNotEmpty()) {
                item { ToolChipRow(toolEvents) }
            }
        }
    }
}

@Composable
private fun ToolChipRow(events: List<ToolEvent>) {
    // Decorative breadcrumbs, not buttons: plain bordered boxes.
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        items(events.size) { i ->
            val ev = events[i]
            Row(
                modifier = Modifier
                    .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = ev.pixelIcon(),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = ev.shortLabel(),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

// TL;DR-first: headline up top, scrollable body below.
@Composable
private fun InfoResultLayout(response: String?, headline: String?) {
    val finalHeadline = remember(headline, response) {
        when {
            !headline.isNullOrBlank() -> headline.take(120)
            response.isNullOrBlank() -> null
            else -> response
                .replace(Regex("^\\s*\\*{0,2}TL;?DR:?\\*{0,2}\\s*[:—-]?\\s*", RegexOption.IGNORE_CASE), "")
                .split(Regex("\\.\\s+|\\n\\n")).firstOrNull()?.trim()?.take(120)
        }
    }
    val paragraphs = remember(response) {
        response
            ?.replace(Regex("^\\s*\\*{0,2}TL;?DR:?\\*{0,2}\\s*[:—-]?\\s*[^\\n]+\\n?", RegexOption.IGNORE_CASE), "")
            ?.trim()
            .orEmpty()
            .split(Regex("\\n{2,}"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }
    // Short TL;DRs get a bigger focal point; long ones shrink to avoid the
    // 4-line truncation cliff.
    val headlineStyle = when {
        (finalHeadline?.length ?: 0) < 20 -> MaterialTheme.typography.titleLarge
        (finalHeadline?.length ?: 0) > 60 -> MaterialTheme.typography.titleSmall
        else -> MaterialTheme.typography.titleMedium
    }
    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            item { MonoLabel(stringResource(R.string.response_tldr), modifier = Modifier.fillMaxWidth()) }
            if (!finalHeadline.isNullOrBlank()) {
                item {
                    Text(
                        text = finalHeadline,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = headlineStyle,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { heading() },
                    )
                }
                if (paragraphs.isNotEmpty()) {
                    item { HairlineDivider(Modifier.padding(vertical = 4.dp)) }
                }
            }
            items(paragraphs) { paragraph ->
                Text(
                    text = renderMarkdownInline(paragraph),
                    color = WatchColors.textSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun EmptyResultLayout() {
    ScreenScaffold { _ ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.response_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ─── SHARED ──────────────────────────────────────────────────────────────────

private fun ToolEvent.shortLabel(): String = when (tool.replace("\\s+".toRegex(), "")) {
    "Bash" -> "bash"
    "Edit" -> "edit"
    "Write" -> "write"
    "Read" -> "read"
    "Grep" -> "grep"
    "Glob" -> "glob"
    "WebFetch" -> "fetch"
    "WebSearch" -> "search"
    "Task" -> "task"
    else -> tool.lowercase()
}

// Task completion: Haptics.done (or Haptics.error when an action run failed),
// the mascot hop / ring via [onFinished], then auto-nav to Response if the
// user is on Command or Metrics. Nav waits a beat so the moment is seen.
@Composable
private fun TaskCompletionHandler(
    taskCompleted: SharedFlow<Unit>?,
    pagerState: PagerState,
    response: String?,
    taskKind: TaskKind?,
    onFinished: (failed: Boolean) -> Unit,
) {
    if (taskCompleted == null) return
    val context = LocalContext.current
    val currentResponse by rememberUpdatedState(response)
    val currentKind by rememberUpdatedState(taskKind)
    val currentOnFinished by rememberUpdatedState(onFinished)
    LaunchedEffect(taskCompleted) {
        taskCompleted.collect {
            delay(120) // reconnect-echo guard, matches PermissionScreen
            // Only action runs are judged by failure words: an info answer
            // that *explains* an error isn't a failed run.
            val failed = currentKind == TaskKind.ACTION && responseLooksFailed(currentResponse)
            if (failed) Haptics.error(context) else Haptics.done(context)
            currentOnFinished(failed)
            if (pagerState.currentPage <= 1 && pagerState.pageCount > 2) {
                delay(900)
                pagerState.animateScrollToPage(2)
            }
        }
    }
}

// Minimal inline markdown: **bold**, *italic*, `code`. Compiled once.
private val MARKDOWN_PATTERN = Regex("""(\*\*([^*]+?)\*\*|\*([^*\n]+?)\*|`([^`\n]+?)`)""")

private fun renderMarkdownInline(text: String): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (m in MARKDOWN_PATTERN.findAll(text)) {
        if (m.range.first > cursor) append(text.substring(cursor, m.range.first))
        when {
            m.groupValues[2].isNotEmpty() ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[2]) }
            m.groupValues[3].isNotEmpty() ->
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(m.groupValues[3]) }
            m.groupValues[4].isNotEmpty() ->
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = CcPalette.SurfaceHigh)) {
                    append(" ${m.groupValues[4]} ")
                }
        }
        cursor = m.range.last + 1
    }
    if (cursor < text.length) append(text.substring(cursor))
}

private fun formatPct(v: Double): String {
    val rounded = if (v >= 10.0) v.toInt().toString() else "%.1f".format(v).trimEnd('0').trimEnd('.')
    return "$rounded%"
}

// ─── PREVIEWS ────────────────────────────────────────────────────────────────

@Composable
private fun PreviewCommand(status: WrapperStatus, mascotState: MascotState, inConversation: Boolean = false) {
    CCWEAROSTheme {
        CommandPage(
            status = status,
            mascotState = mascotState,
            activity = if (status == WrapperStatus.RUNNING) "Editando parser.ts" else null,
            task = if (status == WrapperStatus.RUNNING) "arregla el test del parser" else null,
            toolEvents = if (status == WrapperStatus.RUNNING) listOf(ToolEvent("Edit", "src/parser.ts")) else emptyList(),
            inConversation = inConversation,
            sharedSession = null,
            onAsk = {},
            onStop = {},
            onForceReset = {},
        )
    }
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Page 0 · Listo")
@Composable
private fun PreviewCommandIdle() = PreviewCommand(WrapperStatus.IDLE, MascotState.Idle)

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Page 0 · Trabajando")
@Composable
private fun PreviewCommandRunning() = PreviewCommand(WrapperStatus.RUNNING, MascotState.Running)

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Page 0 · Esperando permiso")
@Composable
private fun PreviewCommandWaiting() = PreviewCommand(WrapperStatus.AWAITING_PERMISSION, MascotState.Waiting)

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Page 0 · Terminado")
@Composable
private fun PreviewCommandDone() = PreviewCommand(WrapperStatus.IDLE, MascotState.Done, inConversation = true)

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Page 0 · Error")
@Composable
private fun PreviewCommandError() = PreviewCommand(WrapperStatus.IDLE, MascotState.Error)

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true, name = "Page 0 · Compartida (small)")
@Composable
private fun PreviewCommandShared() {
    CCWEAROSTheme {
        CommandPage(
            status = WrapperStatus.RUNNING,
            mascotState = MascotState.Running,
            activity = null,
            task = null,
            toolEvents = emptyList(),
            inConversation = false,
            sharedSession = SharedSessionMeta(cwd = "/Users/me/projects/CCWEAROS", kind = "hook"),
            onAsk = {},
            onStop = {},
            onForceReset = {},
        )
    }
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Metrics")
@Composable
private fun PreviewMetrics() {
    CCWEAROSTheme {
        MetricsPage(
            metrics = Metrics(dailyTokens = 48_210, weeklyTokens = 1_204_000, monthlyTokens = 4_800_000),
            claudeStatus = ClaudeStatus(
                model = "Opus",
                contextSize = "1M",
                sessionPct = 24.0,
                weeklyPct = 81.0,
                monthlyCost = "$12.40",
                monthlyResets = "1 nov",
            ),
        )
    }
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Response · info")
@Composable
private fun PreviewResponseInfo() {
    CCWEAROSTheme {
        ResponsePage(
            response = "**TL;DR:** El parser falla con tablas vacías.\n\nEl regex de celdas asume al menos una columna.\n\nLo arreglé con un guard.",
            taskKind = TaskKind.INFO,
            headline = "El parser falla con tablas vacías",
            toolEvents = emptyList(),
        )
    }
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Response · action")
@Composable
private fun PreviewResponseAction() {
    CCWEAROSTheme {
        ResponsePage(
            response = "Listo: tests en verde (72/72).",
            taskKind = TaskKind.ACTION,
            headline = null,
            toolEvents = listOf(ToolEvent("Read", "a.ts"), ToolEvent("Edit", "a.ts"), ToolEvent("Bash", "npm test")),
        )
    }
}
