package com.caamano.ccwearos.presentation.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.tooling.preview.devices.WearDevices
import com.caamano.ccwearos.R
import com.caamano.ccwearos.data.ClaudeStatus
import com.caamano.ccwearos.data.Metrics
import com.caamano.ccwearos.data.SharedSessionMeta
import com.caamano.ccwearos.data.ToolEvent
import com.caamano.ccwearos.presentation.Haptics
import com.caamano.ccwearos.presentation.MonoFamily
import com.caamano.ccwearos.presentation.shortNum
import com.caamano.ccwearos.presentation.theme.CCWEAROSTheme
import com.caamano.ccwearos.presentation.theme.StatusColors
import com.caamano.ccwearos.presentation.ui.MascotState
import com.caamano.ccwearos.presentation.ui.Motion
import com.caamano.ccwearos.presentation.ui.PixelIcons
import com.caamano.ccwearos.presentation.ui.PixelMascot
import com.caamano.ccwearos.presentation.ui.ProgressHalo
import com.caamano.ccwearos.presentation.ui.StatusDot
import com.caamano.ccwearos.presentation.ui.labelRes
import com.caamano.ccwearos.presentation.ui.pixelIcon
import com.caamano.ccwearos.presentation.ui.rememberIsResumed
import com.caamano.ccwearos.presentation.ui.rememberReducedMotion
import com.caamano.ccwearos.presentation.ui.shake
import com.caamano.ccwearos.presentation.ui.statusColor
import kotlinx.coroutines.delay

// ─────────────────────────────────────────────────────────────────────────────
// INICIO — the instrument. Pixel mascot (living state), the state in one
// word, a detail line, ONE primary action, and a small metrics row.
//
// Feedback contract (ui/Motion.kt) per mode:
//  ¿qué hice?        every tap: press scale + Haptics.tick (HomeButton/HomeLink)
//  ¿qué está pasando? Sending quotes the spoken text; Running shows the live
//                     tool line + a ticking mm:ss; the mascot always animates
//  ¿qué puedo hacer?  one primary per mode (Preguntar/Seguir, Reintentar);
//                     Cancelar / Detener are outlined
//  ¿salió bien?       Done hop + ring + auto-slide to Resultado, or Error eyes
//                     + one-line reason + Ver detalle (driven by DashboardScreen)
// ─────────────────────────────────────────────────────────────────────────────

/** Everything Inicio renders. Built by DashboardScreen from VM state. */
data class HomeUi(
    val mode: HomeMode,
    val mascot: MascotState,
    val toolEvents: List<ToolEvent> = emptyList(),
    val activity: String? = null,
    val task: String? = null,
    val runStartedAt: Long? = null,
    val claudeStatus: ClaudeStatus? = null,
    val metrics: Metrics = Metrics(),
    val hasLastResponse: Boolean = false,
    /** Mac epoch ms of the last outcome; null when unknown. */
    val lastResponseAt: Long? = null,
    val voiceUnavailable: Boolean = false,
    /** Bumped by the shell on a failure: the mascot shakes once (Modifier.shake). */
    val errorShake: Int = 0,
)

/** Inicio callbacks. Haptics.tick is fired by the controls themselves. */
data class HomeCallbacks(
    val onAsk: () -> Unit = {},
    val onCancelSend: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onForceReset: () -> Unit = {},
    val onOpenResult: () -> Unit = {},
    val onSeeDetail: () -> Unit = {},
    val onOpenMetrics: () -> Unit = {},
)

@Composable
fun HomePage(
    ui: HomeUi,
    callbacks: HomeCallbacks,
    nowMs: () -> Long = System::currentTimeMillis,
) {
    // Progress clock for "¿Sigue ahí?": resets whenever the run shows life.
    var lastProgressAt by remember { mutableLongStateOf(nowMs()) }
    LaunchedEffect(ui.activity, ui.toolEvents, ui.task, ui.runStartedAt) {
        lastProgressAt = nowMs()
    }
    val now by tickingNow(enabled = ui.mode is HomeMode.Running, nowMs = nowMs)
    val stale = ui.mode is HomeMode.Running && isRunStale(now, lastProgressAt)

    ScreenScaffold { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = roundInset()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(14.dp))
            // ¿qué está pasando?: a calm halo laps the mascot while it works.
            Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                ProgressHalo(active = ui.mode is HomeMode.Running, modifier = Modifier.matchParentSize(), strokeWidth = 2.dp)
                PixelMascot(state = ui.mascot, size = 32.dp, modifier = Modifier.shake(ui.errorShake))
            }
            Spacer(Modifier.height(2.dp))
            StateWord(ui.mascot, stopped = (ui.mode as? HomeMode.Idle)?.failure?.stoppedByUser == true)
            Spacer(Modifier.height(2.dp))
            DetailSlot(ui = ui, now = now, stale = stale, callbacks = callbacks)
            Spacer(Modifier.height(6.dp))
            ActionSlot(mode = ui.mode, stale = stale, callbacks = callbacks)
            if (ui.voiceUnavailable) {
                Text(
                    text = stringResource(R.string.voice_unavailable),
                    color = StatusColors.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
            val showMetrics = !stale && ui.mode !is HomeMode.SendFailed && ui.mode !is HomeMode.Shared
            if (showMetrics) {
                MetricsRow(ui.claudeStatus, ui.metrics, onClick = callbacks.onOpenMetrics)
            }
            // Clears the pager's page indicator.
            Spacer(Modifier.height(14.dp))
        }
    }
}

// Horizontal inset that keeps text inside the round bezel (≈12% per side).
@Composable
internal fun roundInset() = (LocalConfiguration.current.screenWidthDp * 0.12f).dp

/** Wall clock that ticks once a second while [enabled]; frozen otherwise. */
@Composable
private fun tickingNow(enabled: Boolean, nowMs: () -> Long) = remember { mutableLongStateOf(nowMs()) }.also { state ->
    // Gated on RESUMED: no 1 Hz wake-ups with the screen off.
    val resumed = rememberIsResumed()
    LaunchedEffect(enabled, resumed) {
        state.longValue = nowMs()
        while (enabled && resumed) {
            delay(1_000)
            state.longValue = nowMs()
        }
    }
}

private fun crossfade(reduced: Boolean): ContentTransform = if (reduced) {
    fadeIn(snap()) togetherWith fadeOut(snap())
} else {
    fadeIn(Motion.enter(Motion.MEDIUM)) togetherWith fadeOut(Motion.exit(Motion.FAST))
}

@Composable
private fun StateWord(state: MascotState, stopped: Boolean = false) {
    val reduced = rememberReducedMotion()
    AnimatedContent(
        targetState = state to stopped,
        transitionSpec = { crossfade(reduced) },
        contentKey = { (s, st) -> if (st) R.string.state_stopped else s.labelRes() },
        label = "state-word",
    ) { (s, st) ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        ) {
            StatusDot(color = s.statusColor(), description = null)
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(if (st) R.string.state_stopped else s.labelRes()),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )
        }
    }
}

// ─── Detail line ─────────────────────────────────────────────────────────────

private enum class DetailKind { NONE, LAST, FAILURE, SENDING, SEND_FAILED, RUNNING, STALE, SHARED }

@Composable
private fun DetailSlot(ui: HomeUi, now: Long, stale: Boolean, callbacks: HomeCallbacks) {
    val reduced = rememberReducedMotion()
    val mode = ui.mode
    val kind = when (mode) {
        is HomeMode.Idle -> when {
            mode.failure != null -> DetailKind.FAILURE
            ui.hasLastResponse -> DetailKind.LAST
            else -> DetailKind.NONE
        }
        is HomeMode.Sending -> DetailKind.SENDING
        is HomeMode.SendFailed -> DetailKind.SEND_FAILED
        HomeMode.Running -> if (stale) DetailKind.STALE else DetailKind.RUNNING
        HomeMode.Waiting -> DetailKind.NONE
        is HomeMode.Shared -> DetailKind.SHARED
    }
    AnimatedContent(
        targetState = kind,
        transitionSpec = { crossfade(reduced) using SizeTransform(clip = false) },
        label = "detail",
    ) { k ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when (k) {
                DetailKind.NONE -> Unit
                DetailKind.LAST -> HomeLink(
                    text = lastResponseLabel(ui.lastResponseAt, now),
                    onClick = callbacks.onOpenResult,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DetailKind.FAILURE -> {
                    val failure = (mode as? HomeMode.Idle)?.failure
                    Text(
                        text = failureReason(failure),
                        color = if (failure?.stoppedByUser == true) MaterialTheme.colorScheme.onSurfaceVariant else StatusColors.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    HomeLink(text = stringResource(R.string.home_see_detail), onClick = callbacks.onSeeDetail)
                }
                DetailKind.SENDING -> QuoteLine((mode as? HomeMode.Sending)?.text.orEmpty())
                DetailKind.SEND_FAILED -> Text(
                    text = stringResource(R.string.home_send_failed),
                    color = StatusColors.error,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
                DetailKind.RUNNING -> RunningLine(ui, now)
                DetailKind.STALE -> Text(
                    text = stringResource(R.string.home_stale_question),
                    color = StatusColors.waiting,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                DetailKind.SHARED -> (mode as? HomeMode.Shared)?.let { SharedSessionBlock(it.meta) }
            }
        }
    }
}

@Composable
private fun QuoteLine(text: String) {
    Text(
        text = stringResource(R.string.home_sent_quote, text),
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun RunningLine(ui: HomeUi, now: Long) {
    val reduced = rememberReducedMotion()
    val line = liveLine(ui.toolEvents, ui.activity)
    val text = when (line) {
        is LiveLine.Tool -> toolLineText(line)
        is LiveLine.Activity -> line.text
        LiveLine.None -> ui.task?.takeIf { it.isNotBlank() }
    }
    val icon = ui.toolEvents.lastOrNull()?.takeIf { line is LiveLine.Tool }?.pixelIcon()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedContent(
            targetState = text to icon,
            transitionSpec = { crossfade(reduced) },
            label = "live-line",
        ) { (t, i) ->
            if (t != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (i != null) {
                        Icon(
                            imageVector = i,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = t,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        ui.runStartedAt?.let { started ->
            val elapsed = formatElapsed(now - started)
            val cd = stringResource(R.string.home_elapsed_cd, elapsed)
            Text(
                text = elapsed,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.semantics { contentDescription = cd },
            )
        }
    }
}

@Composable
private fun toolLineText(line: LiveLine.Tool): String {
    val verb = when (line.verb) {
        ToolVerb.EDIT -> stringResource(R.string.home_tool_edit)
        ToolVerb.WRITE -> stringResource(R.string.home_tool_write)
        ToolVerb.READ -> stringResource(R.string.home_tool_read)
        ToolVerb.RUN -> stringResource(R.string.home_tool_run)
        ToolVerb.SEARCH -> stringResource(R.string.home_tool_search)
        ToolVerb.FETCH -> stringResource(R.string.home_tool_fetch)
        ToolVerb.WEB -> return stringResource(R.string.home_tool_web)
        ToolVerb.DELEGATE -> return stringResource(R.string.home_tool_delegate)
        ToolVerb.OTHER -> return stringResource(R.string.home_tool_other, line.toolName)
    }
    return if (line.target != null) "$verb ${line.target}" else verb
}

@Composable
private fun failureReason(failure: LastRun?): String = when {
    failure == null -> ""
    failure.stoppedByUser -> stringResource(R.string.home_fail_stopped)
    else -> stringResource(R.string.home_fail_exit, failure.exitCode.toInt())
}

@Composable
private fun lastResponseLabel(at: Long?, now: Long): String {
    if (at == null || at <= 0) return stringResource(R.string.home_last_response)
    val diffSec = ((now - at) / 1000L).coerceAtLeast(0)
    val ago = when {
        diffSec < 60 -> stringResource(R.string.time_now)
        diffSec < 3600 -> stringResource(R.string.time_minutes, (diffSec / 60).toInt())
        diffSec < 86_400 -> stringResource(R.string.time_hours, (diffSec / 3600).toInt())
        diffSec < 172_800 -> stringResource(R.string.time_yesterday)
        else -> stringResource(R.string.time_days, (diffSec / 86_400).toInt())
    }
    return stringResource(R.string.home_last_response_ago, ago)
}

// ─── Primary action ──────────────────────────────────────────────────────────

private enum class ActionKind { ASK, CONTINUE, OFFLINE_WATCH, OFFLINE_MAC, CANCEL, RETRY, STOP, STOP_STALE, NONE }

@Composable
private fun ActionSlot(mode: HomeMode, stale: Boolean, callbacks: HomeCallbacks) {
    val reduced = rememberReducedMotion()
    val kind = when (mode) {
        is HomeMode.Idle -> when (mode.offline) {
            OfflineReason.WATCH -> ActionKind.OFFLINE_WATCH
            OfflineReason.MAC -> ActionKind.OFFLINE_MAC
            null -> if (mode.conversationActive) ActionKind.CONTINUE else ActionKind.ASK
        }
        is HomeMode.Sending -> ActionKind.CANCEL
        is HomeMode.SendFailed -> ActionKind.RETRY
        HomeMode.Running -> if (stale) ActionKind.STOP_STALE else ActionKind.STOP
        HomeMode.Waiting, is HomeMode.Shared -> ActionKind.NONE
    }
    // The button morphs its label in place: scale-in + fade on the new label.
    AnimatedContent(
        targetState = kind,
        transitionSpec = {
            if (reduced) {
                fadeIn(snap()) togetherWith fadeOut(snap())
            } else {
                (fadeIn(Motion.enter(Motion.MEDIUM)) + scaleIn(Motion.enter(Motion.MEDIUM), initialScale = 0.92f)) togetherWith
                    fadeOut(Motion.exit(Motion.FAST))
            } using SizeTransform(clip = false)
        },
        label = "primary",
    ) { k ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            when (k) {
                ActionKind.ASK -> HomeButton(
                    label = stringResource(R.string.action_ask),
                    style = HomeButtonStyle.PRIMARY,
                    icon = PixelIcons.Mic,
                    onClick = callbacks.onAsk,
                )
                ActionKind.CONTINUE -> HomeButton(
                    label = stringResource(R.string.action_continue),
                    style = HomeButtonStyle.PRIMARY,
                    icon = PixelIcons.Mic,
                    onClick = callbacks.onAsk,
                )
                ActionKind.OFFLINE_WATCH -> OfflineButton(R.string.home_offline_watch_explain)
                ActionKind.OFFLINE_MAC -> OfflineButton(R.string.home_offline_mac_explain)
                ActionKind.CANCEL -> HomeButton(
                    label = stringResource(R.string.home_cancel),
                    style = HomeButtonStyle.OUTLINED,
                    onClick = callbacks.onCancelSend,
                )
                ActionKind.RETRY -> {
                    HomeButton(
                        label = stringResource(R.string.home_retry),
                        style = HomeButtonStyle.PRIMARY,
                        icon = PixelIcons.Refresh,
                        onClick = callbacks.onRetry,
                        height = 44.dp,
                    )
                    Spacer(Modifier.height(4.dp))
                    HomeButton(
                        label = stringResource(R.string.home_cancel),
                        style = HomeButtonStyle.OUTLINED,
                        onClick = callbacks.onCancelSend,
                        height = 36.dp,
                    )
                }
                ActionKind.STOP, ActionKind.STOP_STALE -> {
                    HomeButton(
                        label = stringResource(R.string.action_stop),
                        style = HomeButtonStyle.DANGER,
                        icon = PixelIcons.Stop,
                        onClick = callbacks.onStop,
                        // Power-user shortcut kept from v1; the visible path is
                        // "Reiniciar estado" below after 3 min of silence.
                        onLongClick = callbacks.onForceReset,
                        onLongClickLabel = stringResource(R.string.home_reset_state),
                        height = if (k == ActionKind.STOP_STALE) 44.dp else 48.dp,
                    )
                    if (k == ActionKind.STOP_STALE) {
                        Spacer(Modifier.height(4.dp))
                        HomeButton(
                            label = stringResource(R.string.home_reset_state),
                            style = HomeButtonStyle.OUTLINED,
                            icon = PixelIcons.Refresh,
                            onClick = callbacks.onForceReset,
                            height = 36.dp,
                        )
                    }
                }
                ActionKind.NONE -> Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/**
 * Looks disabled but still answers a tap: Haptics.error + why it is
 * disabled, for a few seconds. Never queues a prompt silently.
 */
@Composable
private fun OfflineButton(explainRes: Int) {
    val context = LocalContext.current
    var explaining by remember { mutableStateOf(false) }
    LaunchedEffect(explaining) {
        if (explaining) {
            delay(4_000)
            explaining = false
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        HomeButton(
            label = stringResource(R.string.home_offline_button),
            style = HomeButtonStyle.MUTED,
            onClick = {
                Haptics.error(context)
                explaining = true
            },
        )
        if (explaining) {
            Text(
                text = stringResource(explainRes),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                maxLines = 3,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

// ─── Shared session ──────────────────────────────────────────────────────────

@Composable
private fun SharedSessionBlock(meta: SharedSessionMeta) {
    // cc (wrapper-pty): user is in the Terminal; permissions reach the watch
    // through the wrapper. hook (/ccwearos): the PreToolUse hook routes them.
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

// ─── Metrics row ─────────────────────────────────────────────────────────────

@Composable
private fun MetricsRow(claudeStatus: ClaudeStatus?, metrics: Metrics, onClick: () -> Unit) {
    val session = claudeStatus?.sessionPct?.let(::formatPct)
    val week = claudeStatus?.weeklyPct?.let(::formatPct)
    val text = when {
        session != null && week != null -> stringResource(R.string.home_metrics_both, session, week)
        session != null -> stringResource(R.string.home_metrics_session, session)
        week != null -> stringResource(R.string.home_metrics_week, week)
        metrics.dailyTokens > 0 -> stringResource(R.string.home_metrics_tokens, shortNum(metrics.dailyTokens))
        else -> return
    }
    val cd = stringResource(R.string.home_metrics_cd, text)
    HomeLink(
        text = text,
        onClick = onClick,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { contentDescription = cd },
    )
}

internal fun formatPct(v: Double): String {
    val rounded = if (v >= 10.0) v.toInt().toString() else "%.1f".format(v).trimEnd('0').trimEnd('.')
    return "$rounded%"
}

// ─── PREVIEWS ────────────────────────────────────────────────────────────────

private val previewStatus = ClaudeStatus(sessionPct = 24.0, weeklyPct = 81.0)

@Composable
private fun PreviewHome(ui: HomeUi) {
    CCWEAROSTheme { HomePage(ui = ui, callbacks = HomeCallbacks()) }
}

private fun idle(conversationActive: Boolean = false, failure: LastRun? = null, offline: OfflineReason? = null) =
    HomeMode.Idle(offline = offline, conversationActive = conversationActive, failure = failure)

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Inicio · Listo")
@Composable
private fun PreviewIdle() = PreviewHome(HomeUi(idle(), MascotState.Idle, claudeStatus = previewStatus))

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Inicio · Seguir + última respuesta")
@Composable
private fun PreviewIdleContinue() = PreviewHome(
    HomeUi(
        idle(conversationActive = true),
        MascotState.Idle,
        claudeStatus = previewStatus,
        hasLastResponse = true,
        lastResponseAt = System.currentTimeMillis() - 7 * 60_000,
    ),
)

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Inicio · Enviando")
@Composable
private fun PreviewSending() = PreviewHome(
    HomeUi(HomeMode.Sending("arregla el test del parser"), MascotState.Sending, claudeStatus = previewStatus),
)

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Inicio · No la tomó")
@Composable
private fun PreviewSendFailed() = PreviewHome(
    HomeUi(HomeMode.SendFailed("arregla el test del parser"), MascotState.Error),
)

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Inicio · Trabajando")
@Composable
private fun PreviewRunning() = PreviewHome(
    HomeUi(
        HomeMode.Running,
        MascotState.Running,
        toolEvents = listOf(ToolEvent("Edit", "wrapper/src/parser.ts")),
        activity = "Crunching…",
        runStartedAt = System.currentTimeMillis() - 247_000,
        claudeStatus = previewStatus,
    ),
)

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Inicio · ¿Sigue ahí?")
@Composable
private fun PreviewRunningStale() {
    val base = System.currentTimeMillis()
    var calls = 0
    CCWEAROSTheme {
        HomePage(
            ui = HomeUi(HomeMode.Running, MascotState.Running, runStartedAt = base - 6 * 60_000),
            callbacks = HomeCallbacks(),
            // First read seeds the progress clock; later reads are 4 min on.
            nowMs = { if (calls++ == 0) base else base + 4 * 60_000 },
        )
    }
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Inicio · Esperando permiso")
@Composable
private fun PreviewWaiting() = PreviewHome(HomeUi(HomeMode.Waiting, MascotState.Waiting))

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Inicio · Terminado")
@Composable
private fun PreviewDone() = PreviewHome(
    HomeUi(idle(conversationActive = true), MascotState.Done, claudeStatus = previewStatus, hasLastResponse = true),
)

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Inicio · Falló")
@Composable
private fun PreviewFailed() = PreviewHome(
    HomeUi(idle(failure = LastRun(ok = false, exitCode = 1, stoppedByUser = false)), MascotState.Error),
)

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Inicio · Sin conexión")
@Composable
private fun PreviewOffline() = PreviewHome(HomeUi(idle(offline = OfflineReason.WATCH), MascotState.Offline))

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true, name = "Inicio · Compartida (small)")
@Composable
private fun PreviewShared() = PreviewHome(
    HomeUi(
        HomeMode.Shared(SharedSessionMeta(cwd = "/Users/me/projects/CCWEAROS", kind = "hook")),
        MascotState.Running,
    ),
)
