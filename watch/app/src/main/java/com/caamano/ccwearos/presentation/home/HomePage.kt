package com.caamano.ccwearos.presentation.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.caamano.ccwearos.presentation.theme.CcPalette
import com.caamano.ccwearos.presentation.theme.StatusColors
import com.caamano.ccwearos.presentation.ui.MascotState
import com.caamano.ccwearos.presentation.ui.Motion
import com.caamano.ccwearos.presentation.ui.PixelIcons
import com.caamano.ccwearos.presentation.ui.PixelMascot
import com.caamano.ccwearos.presentation.ui.ProgressHalo
import com.caamano.ccwearos.presentation.ui.SpeechBubble
import com.caamano.ccwearos.presentation.ui.labelRes
import com.caamano.ccwearos.presentation.ui.rememberIsResumed
import com.caamano.ccwearos.presentation.ui.rememberReducedMotion
import com.caamano.ccwearos.presentation.ui.shake
import kotlinx.coroutines.delay

// ─────────────────────────────────────────────────────────────────────────────
// INICIO — the instrument, minimal on purpose: only what you need, and a
// button only when you have to step in.
//
//  Idle      mascot, "Listo", last-response link, Preguntar/Seguir, usage.
//  Sending   mascot, "Enviando…", what you said (≤2 lines). Cancelar shows
//            up only after 5s.
//  Running   mascot inside a ProgressHalo, ONE plain line from the latest
//            tool ("Editando parser.ts"), a small timer. No buttons: tap (or
//            long-press) the screen to reveal Detener for 4s. After 3 quiet
//            minutes "¿Sigue ahí?" + Detener + Reiniciar estado.
//  Done      the mascot celebrates (hop + confetti) and says "¡Listo!" in a
//            bubble, the TL;DR in ≤2 lines, then the shell slides to
//            Resultado.
//  Failed    "No se pudo" + one-line reason; Reintentar only when there is
//            something to retry (the Mac never took the prompt).
//
// Type scale: title 18sp semibold, body 14sp, meta 12sp #9A9A9A, 12dp gaps.
// ─────────────────────────────────────────────────────────────────────────────

/** Everything Inicio renders. Built by DashboardScreen from VM state. */
data class HomeUi(
    val mode: HomeMode,
    val mascot: MascotState,
    val toolEvents: List<ToolEvent> = emptyList(),
    val runStartedAt: Long? = null,
    val claudeStatus: ClaudeStatus? = null,
    val metrics: Metrics = Metrics(),
    val hasLastResponse: Boolean = false,
    /** Mac epoch ms of the last outcome; null when unknown. */
    val lastResponseAt: Long? = null,
    /** Bumped by the shell on a failure: the mascot shakes once (Modifier.shake). */
    val errorShake: Int = 0,
    /** What the mascot says right now; null = no bubble. */
    val bubble: MascotBubble? = null,
    /** TL;DR shown under "¡Listo!" while the done moment plays. */
    val doneTldr: String? = null,
)

/** Inicio callbacks. Haptics.tick is fired by the controls themselves. */
data class HomeCallbacks(
    val onAsk: () -> Unit = {},
    val onCancelSend: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onForceReset: () -> Unit = {},
    val onOpenResult: () -> Unit = {},
    val onOpenMetrics: () -> Unit = {},
)

/** Inicio type scale (owner rule: one weight hierarchy, scannable). */
private object HomeType {
    val title = 18.sp
    val titleLine = 22.sp
    val body = 14.sp
    val bodyLine = 18.sp
    val meta = 12.sp
    val metaLine = 16.sp
}

private const val STOP_REVEAL_MS = 4_000L
private const val CANCEL_DELAY_MS = 5_000L

@Composable
fun HomePage(
    ui: HomeUi,
    callbacks: HomeCallbacks,
    nowMs: () -> Long = System::currentTimeMillis,
) {
    // Progress clock for "¿Sigue ahí?": resets whenever the run shows life.
    var lastProgressAt by remember { mutableLongStateOf(nowMs()) }
    LaunchedEffect(ui.toolEvents, ui.runStartedAt) {
        lastProgressAt = nowMs()
    }
    val running = ui.mode is HomeMode.Running
    val now by tickingNow(enabled = running, nowMs = nowMs)
    val stale = running && isRunStale(now, lastProgressAt)

    // Detener is hidden while working; a tap reveals it for a few seconds.
    var stopReveal by remember { mutableIntStateOf(0) }
    var stopVisible by remember { mutableStateOf(false) }
    LaunchedEffect(stopReveal) {
        if (stopReveal > 0) {
            stopVisible = true
            delay(STOP_REVEAL_MS)
            stopVisible = false
        }
    }
    LaunchedEffect(running) { if (!running) stopVisible = false }

    val revealLabel = stringResource(R.string.home_reveal_stop)
    val tapToReveal = if (running && !stale) {
        Modifier.combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClickLabel = revealLabel,
            onClick = { stopReveal++ },
            onLongClick = { stopReveal++ },
        )
    } else {
        Modifier
    }

    ScreenScaffold { _ ->
        Box(Modifier.fillMaxSize().then(tapToReveal), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = roundInset()),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MascotStage(ui)
                Spacer(Modifier.height(6.dp))
                StateWord(ui)
                Spacer(Modifier.height(4.dp))
                DetailSlot(ui = ui, now = now, stale = stale, callbacks = callbacks)
                Spacer(Modifier.height(12.dp))
                ActionSlot(mode = ui.mode, stale = stale, stopVisible = stopVisible, callbacks = callbacks)
                val mode = ui.mode
                val plainIdle = mode is HomeMode.Idle && mode.failure == null &&
                    mode.offline == null && ui.mascot != MascotState.Done
                if (plainIdle) MetricsRow(ui.claudeStatus, ui.metrics, onClick = callbacks.onOpenMetrics)
            }
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

// ─── Mascot + bubble ─────────────────────────────────────────────────────────

@Composable
private fun MascotStage(ui: HomeUi) {
    val bubbleText = when (ui.bubble) {
        MascotBubble.DONE -> stringResource(R.string.bubble_done)
        MascotBubble.FAILED -> stringResource(R.string.bubble_failed)
        MascotBubble.STOPPED -> stringResource(R.string.bubble_stopped)
        MascotBubble.ASK -> stringResource(R.string.bubble_ask)
        null -> null
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Reserved slot so the bubble never pushes the layout around.
        Box(Modifier.height(24.dp), contentAlignment = Alignment.BottomCenter) {
            SpeechBubble(bubbleText)
        }
        // ¿qué está pasando?: a calm halo laps the mascot while it works.
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            ProgressHalo(active = ui.mode is HomeMode.Running, modifier = Modifier.fillMaxSize(), strokeWidth = 2.dp)
            PixelMascot(state = ui.mascot, size = 34.dp, modifier = Modifier.shake(ui.errorShake))
        }
    }
}

@Composable
private fun StateWord(ui: HomeUi) {
    val reduced = rememberReducedMotion()
    val stopped = (ui.mode as? HomeMode.Idle)?.failure?.stoppedByUser == true
    val res = if (stopped) R.string.state_stopped else ui.mascot.labelRes()
    AnimatedContent(
        targetState = res,
        transitionSpec = { crossfade(reduced) },
        label = "state-word",
    ) { r ->
        Text(
            text = stringResource(r),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = HomeType.title,
            lineHeight = HomeType.titleLine,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

// ─── Detail line ─────────────────────────────────────────────────────────────

private enum class DetailKind { NONE, LAST, DONE, FAILURE, SENDING, SEND_FAILED, RUNNING, STALE, SHARED }

@Composable
private fun DetailSlot(ui: HomeUi, now: Long, stale: Boolean, callbacks: HomeCallbacks) {
    val reduced = rememberReducedMotion()
    val mode = ui.mode
    val kind = when (mode) {
        is HomeMode.Idle -> when {
            ui.mascot == MascotState.Done && ui.doneTldr != null -> DetailKind.DONE
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
                    color = CcPalette.TextSecondary,
                )
                DetailKind.DONE -> BodyText(ui.doneTldr.orEmpty(), maxLines = 2)
                DetailKind.FAILURE -> {
                    val failure = (mode as? HomeMode.Idle)?.failure
                    MetaText(
                        text = failureReason(failure),
                        color = if (failure?.stoppedByUser == true) CcPalette.TextSecondary else StatusColors.error,
                    )
                }
                DetailKind.SENDING -> BodyText(
                    stringResource(R.string.home_sent_quote, (mode as? HomeMode.Sending)?.text.orEmpty()),
                    maxLines = 2,
                )
                DetailKind.SEND_FAILED -> BodyText(stringResource(R.string.home_send_failed), color = StatusColors.error, maxLines = 2)
                DetailKind.RUNNING -> RunningLine(ui, now)
                DetailKind.STALE -> BodyText(stringResource(R.string.home_stale_question), color = StatusColors.waiting)
                DetailKind.SHARED -> (mode as? HomeMode.Shared)?.let { SharedSessionBlock(it.meta) }
            }
        }
    }
}

@Composable
private fun BodyText(text: String, color: Color = MaterialTheme.colorScheme.onSurface, maxLines: Int = 1) {
    Text(
        text = text,
        color = color,
        fontSize = HomeType.body,
        lineHeight = HomeType.bodyLine,
        textAlign = TextAlign.Center,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun MetaText(text: String, color: Color = CcPalette.TextSecondary) {
    Text(
        text = text,
        color = color,
        fontSize = HomeType.meta,
        lineHeight = HomeType.metaLine,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun RunningLine(ui: HomeUi, now: Long) {
    val reduced = rememberReducedMotion()
    val line = workingLine(ui.toolEvents)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedContent(
            targetState = line,
            transitionSpec = { crossfade(reduced) },
            label = "live-line",
        ) { t -> BodyText(t) }
        ui.runStartedAt?.let { started ->
            val elapsed = formatElapsed(now - started)
            val cd = stringResource(R.string.home_elapsed_cd, elapsed)
            Text(
                text = elapsed,
                color = CcPalette.TextSecondary,
                fontSize = HomeType.meta,
                lineHeight = HomeType.metaLine,
                modifier = Modifier.semantics { contentDescription = cd },
            )
        }
    }
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

// ─── Actions: only when you have to step in ──────────────────────────────────

private enum class ActionKind { ASK, CONTINUE, OFFLINE_WATCH, OFFLINE_MAC, CANCEL, RETRY, STOP, STOP_STALE, NONE }

@Composable
private fun ActionSlot(mode: HomeMode, stale: Boolean, stopVisible: Boolean, callbacks: HomeCallbacks) {
    val reduced = rememberReducedMotion()
    // Cancelar only after a few seconds: most prompts are picked up at once.
    val sending = mode is HomeMode.Sending
    var cancelReady by remember { mutableStateOf(false) }
    LaunchedEffect(sending) {
        cancelReady = false
        if (sending) {
            delay(CANCEL_DELAY_MS)
            cancelReady = true
        }
    }
    val kind = when (mode) {
        is HomeMode.Idle -> when (mode.offline) {
            OfflineReason.WATCH -> ActionKind.OFFLINE_WATCH
            OfflineReason.MAC -> ActionKind.OFFLINE_MAC
            null -> if (mode.conversationActive) ActionKind.CONTINUE else ActionKind.ASK
        }
        is HomeMode.Sending -> if (cancelReady) ActionKind.CANCEL else ActionKind.NONE
        is HomeMode.SendFailed -> ActionKind.RETRY
        HomeMode.Running -> when {
            stale -> ActionKind.STOP_STALE
            stopVisible -> ActionKind.STOP
            else -> ActionKind.NONE
        }
        HomeMode.Waiting, is HomeMode.Shared -> ActionKind.NONE
    }
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
                    height = 40.dp,
                )
                ActionKind.RETRY -> {
                    HomeButton(
                        label = stringResource(R.string.home_retry),
                        style = HomeButtonStyle.PRIMARY,
                        icon = PixelIcons.Refresh,
                        onClick = callbacks.onRetry,
                    )
                    HomeLink(
                        text = stringResource(R.string.home_cancel),
                        onClick = callbacks.onCancelSend,
                        color = CcPalette.TextSecondary,
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
                        height = 44.dp,
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
                ActionKind.NONE -> Spacer(Modifier.height(4.dp))
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
        AnimatedVisibility(visible = explaining) {
            Text(
                text = stringResource(explainRes),
                color = CcPalette.TextSecondary,
                fontSize = HomeType.meta,
                lineHeight = HomeType.metaLine,
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
                fontSize = HomeType.body,
                lineHeight = HomeType.bodyLine,
                fontWeight = FontWeight.Medium,
            )
        }
        Text(
            text = meta.cwd.substringAfterLast("/").ifBlank { meta.cwd },
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = HomeType.meta,
            fontFamily = MonoFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        MetaText(stringResource(hint))
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
        color = CcPalette.TextSecondary,
        modifier = Modifier.semantics { contentDescription = cd },
    )
}

internal fun formatPct(v: Double): String {
    val rounded = if (v >= 10.0) v.toInt().toString() else "%.1f".format(v).trimEnd('0').trimEnd('.')
    return "$rounded%"
}

// ─── PREVIEWS: the minimal states ────────────────────────────────────────────

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
    HomeUi(HomeMode.Sending("arregla el test del parser y corre todo"), MascotState.Sending),
)

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Inicio · No la tomó")
@Composable
private fun PreviewSendFailed() = PreviewHome(
    HomeUi(HomeMode.SendFailed("arregla el test del parser"), MascotState.Error),
)

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Inicio · Trabajando (sin botones)")
@Composable
private fun PreviewRunning() = PreviewHome(
    HomeUi(
        HomeMode.Running,
        MascotState.Running,
        toolEvents = listOf(ToolEvent("Edit", "wrapper/src/parser.ts")),
        runStartedAt = System.currentTimeMillis() - 247_000,
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

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Inicio · ¿Me dejas?")
@Composable
private fun PreviewWaiting() = PreviewHome(HomeUi(HomeMode.Waiting, MascotState.Waiting, bubble = MascotBubble.ASK))

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Inicio · ¡Listo!")
@Composable
private fun PreviewDone() = PreviewHome(
    HomeUi(
        idle(conversationActive = true),
        MascotState.Done,
        hasLastResponse = true,
        bubble = MascotBubble.DONE,
        doneTldr = "Los 42 tests pasan y el parser ya ignora el spinner.",
    ),
)

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Inicio · No se pudo")
@Composable
private fun PreviewFailed() = PreviewHome(
    HomeUi(idle(failure = LastRun(ok = false, exitCode = 1, stoppedByUser = false)), MascotState.Error, bubble = MascotBubble.FAILED),
)

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Inicio · Detenido")
@Composable
private fun PreviewStopped() = PreviewHome(
    HomeUi(idle(failure = LastRun(ok = false, exitCode = 130, stoppedByUser = true)), MascotState.Idle, bubble = MascotBubble.STOPPED),
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
