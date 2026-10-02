package com.caamano.ccwearos.presentation

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.PagerDefaults
import androidx.wear.compose.foundation.pager.PagerState
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.tooling.preview.devices.WearDevices
import com.caamano.ccwearos.R
import com.caamano.ccwearos.data.Blocker
import com.caamano.ccwearos.data.ClaudeStatus
import com.caamano.ccwearos.data.Metrics
import com.caamano.ccwearos.data.RecentSession
import com.caamano.ccwearos.data.RunOutcome
import com.caamano.ccwearos.data.SharedSessionMeta
import com.caamano.ccwearos.data.TaskKind
import com.caamano.ccwearos.data.ToolEvent
import com.caamano.ccwearos.data.WrapperStatus
import com.caamano.ccwearos.presentation.home.HomeCallbacks
import com.caamano.ccwearos.presentation.home.HomeEvent
import com.caamano.ccwearos.presentation.home.HomeMode
import com.caamano.ccwearos.presentation.home.HomePage
import com.caamano.ccwearos.presentation.home.HomePages
import com.caamano.ccwearos.presentation.home.HomeUi
import com.caamano.ccwearos.presentation.home.LastRun
import com.caamano.ccwearos.presentation.home.MetricsDialog
import com.caamano.ccwearos.presentation.home.SendState
import com.caamano.ccwearos.presentation.home.SessionsPage
import com.caamano.ccwearos.presentation.home.continuityTransition
import com.caamano.ccwearos.presentation.home.homeMode
import com.caamano.ccwearos.presentation.home.pageDirection
import com.caamano.ccwearos.presentation.home.pageProgress
import com.caamano.ccwearos.presentation.result.ResultPage
import com.caamano.ccwearos.presentation.theme.CCWEAROSTheme
import com.caamano.ccwearos.presentation.ui.CompletionRing
import com.caamano.ccwearos.presentation.ui.MascotState
import com.caamano.ccwearos.presentation.ui.Motion
import com.caamano.ccwearos.presentation.ui.rememberReducedMotion
import com.caamano.ccwearos.notifications.DeepLinks
import com.caamano.ccwearos.presentation.ui.rememberVoiceInput
import com.caamano.ccwearos.presentation.ui.toMascotState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// DASHBOARD — fixed 3-page pager. The pages never appear or disappear, so the
// page indicator is stable and "Resultado" is always one swipe away.
//
//   0 INICIO     the instrument (home/HomePage.kt)
//   1 RESULTADO  result/ResultPage.kt (lane A)
//   2 SESIONES   home/SessionsPage.kt
//
// Pages follow the finger with the continuity effect (home/Continuity.kt):
// the leaving page blurs and stretches in place instead of hard-cutting.
// The shell turns VM one-shot events into haptics + motion.
// ─────────────────────────────────────────────────────────────────────────────

/** Everything the pager renders, collected by WearApp from the ViewModel. */
data class DashboardState(
    val status: WrapperStatus = WrapperStatus.IDLE,
    val connected: Boolean = true,
    val send: SendState = SendState.None,
    val sharedSession: SharedSessionMeta? = null,
    val conversationActive: Boolean = false,
    val lastRun: LastRun? = null,
    val runStartedAt: Long? = null,
    val blocker: Blocker? = null,
    val activity: String? = null,
    val task: String? = null,
    val toolEvents: List<ToolEvent> = emptyList(),
    val claudeStatus: ClaudeStatus? = null,
    val metrics: Metrics = Metrics(),
    val headline: String? = null,
    val response: String? = null,
    val taskKind: TaskKind? = null,
    val outcome: RunOutcome? = null,
    val followups: List<String> = emptyList(),
    val recentSessions: List<RecentSession> = emptyList(),
)

data class DashboardActions(
    val onAsk: (String) -> Unit = {},
    val onAskWithReset: (String) -> Unit = {},
    val onCancelSend: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onForceReset: () -> Unit = {},
    val onClaim: (sessionId: String, cwd: String) -> Unit = { _, _ -> },
    val onClearLastRun: () -> Unit = {},
    val onBlockedContent: () -> Unit = {},
)

/** Settled mascot for the current mode; the transient Done hop is layered on top. */
internal fun mascotFor(mode: HomeMode, status: WrapperStatus, blocker: Blocker?): MascotState = when (mode) {
    is HomeMode.Idle -> when {
        mode.offline != null -> MascotState.Offline
        blocker != null -> MascotState.Blocked
        mode.failure != null -> MascotState.Error
        else -> MascotState.Idle
    }
    is HomeMode.Sending -> MascotState.Sending
    is HomeMode.SendFailed -> MascotState.Error
    HomeMode.Running -> MascotState.Running
    HomeMode.Waiting -> MascotState.Waiting
    is HomeMode.Shared -> status.toMascotState()
}

private val pageEnterSpec = tween<Float>(Motion.SLOW, easing = Motion.EnterEasing)

@Composable
fun DashboardScreen(
    state: DashboardState,
    actions: DashboardActions,
    events: Flow<HomeEvent> = emptyFlow(),
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reduced = rememberReducedMotion()
    val pagerState = rememberPagerState(initialPage = HomePages.INICIO) { HomePages.COUNT }

    val askVoice = rememberVoiceInput(onText = actions.onAsk)
    val resetVoice = rememberVoiceInput(onText = actions.onAskWithReset)
    val askPrompt = stringResource(if (state.conversationActive) R.string.voice_prompt_continue else R.string.voice_prompt_ask)
    val resetPrompt = stringResource(R.string.voice_prompt_reset)

    var doneFlash by remember { mutableStateOf(false) }
    var ringTrigger by remember { mutableIntStateOf(0) }
    var confirmReset by remember { mutableStateOf(false) }
    var metricsOpen by remember { mutableStateOf(false) }
    // True while the shell drives the pager, so settle haptics stay for swipes.
    var programmatic by remember { mutableStateOf(false) }

    fun goTo(page: Int) {
        if (pagerState.currentPage == page && pagerState.currentPageOffsetFraction == 0f) return
        scope.launch {
            programmatic = true
            try {
                if (reduced) pagerState.scrollToPage(page) else pagerState.animateScrollToPage(page, animationSpec = pageEnterSpec)
            } finally {
                programmatic = false
            }
        }
    }

    LaunchedEffect(doneFlash) {
        if (doneFlash) {
            delay(2_500)
            doneFlash = false
        }
    }

    // ¿salió bien? / ¿qué está pasando?: every VM moment gets its haptic here.
    LaunchedEffect(events) {
        events.collect { e ->
            when (e) {
                HomeEvent.Sent -> {
                    Haptics.tick(context)
                    // Sent from Resultado (Seguir / chip): come home to see it.
                    goTo(HomePages.INICIO)
                }
                HomeEvent.PickedUp -> Haptics.sent(context)
                HomeEvent.SendFailed -> Haptics.error(context)
                is HomeEvent.Finished -> if (e.ok) {
                    Haptics.done(context)
                    doneFlash = true
                    ringTrigger++
                    if (pagerState.currentPage == HomePages.INICIO) {
                        scope.launch {
                            delay(900) // let the hop + ring be seen first
                            goTo(HomePages.RESULTADO)
                        }
                    }
                } else {
                    Haptics.error(context)
                    doneFlash = false
                }
            }
        }
    }

    // Low-frequency swipe feedback: one light tap when a swipe settles on a page.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.drop(1).collect {
            if (!programmatic) Haptics.swipe(context)
        }
    }

    // Deep links from the tile, complication and notifications.
    LaunchedEffect(Unit) {
        DeepLinks.pending.collect { action ->
            when (action) {
                DeepLinks.ACTION_VOICE -> { goTo(HomePages.INICIO); askVoice.launch(askPrompt) }
                DeepLinks.ACTION_RESULT -> goTo(HomePages.RESULTADO)
                else -> return@collect
            }
            DeepLinks.pending.value = null
        }
    }

    val mode = homeMode(
        status = state.status,
        connected = state.connected,
        send = state.send,
        sharedSession = state.sharedSession,
        conversationActive = state.conversationActive,
        lastRun = state.lastRun,
    )
    val settled = mascotFor(mode, state.status, state.blocker)
    val mascot = if (doneFlash && mode is HomeMode.Idle && settled == MascotState.Idle) MascotState.Done else settled

    val homeUi = HomeUi(
        mode = mode,
        mascot = mascot,
        toolEvents = state.toolEvents,
        activity = state.activity,
        task = state.task,
        runStartedAt = state.runStartedAt,
        claudeStatus = state.claudeStatus,
        metrics = state.metrics,
        hasLastResponse = !state.response.isNullOrBlank() || state.taskKind != null,
        lastResponseAt = state.outcome?.ts?.takeIf { it > 0 },
        voiceUnavailable = askVoice.unavailable || resetVoice.unavailable,
    )
    val homeCallbacks = HomeCallbacks(
        onAsk = { askVoice.launch(askPrompt) },
        onCancelSend = actions.onCancelSend,
        onRetry = actions.onRetry,
        onStop = actions.onStop,
        onForceReset = actions.onForceReset,
        onOpenResult = { goTo(HomePages.RESULTADO) },
        onSeeDetail = {
            actions.onClearLastRun()
            goTo(HomePages.RESULTADO)
        },
        onOpenMetrics = { metricsOpen = true },
    )

    Box(modifier.fillMaxSize()) {
        HorizontalPagerScaffold(pagerState = pagerState) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                // The page settles with a soft spring when the finger lifts.
                flingBehavior = PagerDefaults.snapFlingBehavior(
                    state = pagerState,
                    snapAnimationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                ),
            ) { page ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .continuityTransition(
                            progress = { pagerProgress(pagerState, page) },
                            direction = { pageDirection(page, pagerState.currentPage, pagerState.currentPageOffsetFraction) },
                            reducedMotion = reduced,
                            followFinger = true,
                        ),
                ) {
                    when (page) {
                        HomePages.INICIO -> HomePage(ui = homeUi, callbacks = homeCallbacks)
                        HomePages.RESULTADO -> ResultPage(
                            headline = state.headline,
                            response = state.response,
                            taskKind = state.taskKind,
                            outcome = state.outcome,
                            toolEvents = state.toolEvents,
                            followups = state.followups,
                            conversationActive = state.conversationActive,
                            onFollowup = actions.onAsk,
                            onSpeak = { askVoice.launch(askPrompt) },
                            onNewConversation = { confirmReset = true },
                            onBlockedContent = actions.onBlockedContent,
                        )
                        HomePages.SESIONES -> SessionsPage(
                            sessions = state.recentSessions,
                            sharedSession = state.sharedSession,
                            onClaim = actions.onClaim,
                        )
                    }
                }
            }
        }
        CompletionRing(trigger = ringTrigger)
    }

    MetricsDialog(
        visible = metricsOpen,
        claudeStatus = state.claudeStatus,
        metrics = state.metrics,
        onDismiss = { metricsOpen = false },
    )

    // "Nueva conversación" always confirms before wiping the thread.
    AlertDialog(
        visible = confirmReset,
        onDismissRequest = { confirmReset = false },
        title = { Text(stringResource(R.string.home_reset_title), textAlign = TextAlign.Center) },
        text = { Text(stringResource(R.string.home_reset_body), textAlign = TextAlign.Center) },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(
                onClick = {
                    Haptics.tick(context)
                    confirmReset = false
                    resetVoice.launch(resetPrompt)
                },
            )
        },
        dismissButton = {
            AlertDialogDefaults.DismissButton(
                onClick = {
                    Haptics.tick(context)
                    confirmReset = false
                },
            )
        },
    )
}

private fun pagerProgress(state: PagerState, page: Int): Float =
    pageProgress(page, state.currentPage, state.currentPageOffsetFraction)

// ─── PREVIEWS ────────────────────────────────────────────────────────────────

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Dashboard · Inicio")
@Composable
private fun PreviewDashboard() {
    CCWEAROSTheme {
        DashboardScreen(
            state = DashboardState(
                claudeStatus = ClaudeStatus(sessionPct = 24.0, weeklyPct = 81.0),
                response = "Listo.",
                outcome = RunOutcome(ok = true, ts = System.currentTimeMillis() - 5 * 60_000),
            ),
            actions = DashboardActions(),
        )
    }
}
