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
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.PagerDefaults
import androidx.wear.compose.foundation.pager.PagerState
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.tooling.preview.devices.WearDevices
import com.caamano.ccwearos.R
import com.caamano.ccwearos.data.AppVisibility
import com.caamano.ccwearos.data.Blocker
import com.caamano.ccwearos.data.ClaudeStatus
import com.caamano.ccwearos.data.Metrics
import com.caamano.ccwearos.data.RecentSession
import com.caamano.ccwearos.data.RunOutcome
import com.caamano.ccwearos.data.RunProgress
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
import com.caamano.ccwearos.presentation.home.MascotBubble
import com.caamano.ccwearos.presentation.home.RunEnd
import com.caamano.ccwearos.presentation.home.UnseenCompletion
import com.caamano.ccwearos.presentation.home.MetricsDialog
import com.caamano.ccwearos.presentation.home.SendState
import com.caamano.ccwearos.presentation.home.SessionsPage
import com.caamano.ccwearos.presentation.home.continuityTransition
import com.caamano.ccwearos.presentation.home.homeMode
import com.caamano.ccwearos.presentation.home.pageDirection
import com.caamano.ccwearos.presentation.home.pageProgress
import com.caamano.ccwearos.presentation.result.ResultPage
import com.caamano.ccwearos.presentation.result.resultTldr
import com.caamano.ccwearos.presentation.theme.CCWEAROSTheme
import com.caamano.ccwearos.presentation.ui.CompletionRing
import com.caamano.ccwearos.presentation.ui.MascotState
import com.caamano.ccwearos.presentation.ui.Motion
import com.caamano.ccwearos.presentation.ui.rememberIsResumed
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
    /** True for ~6s after launch: suppresses the offline flash while Firebase connects. */
    val connectingGrace: Boolean = false,
    /**
     * Rich step-by-step progress from /progress. Null when idle or when the
     * daemon predates the /progress contract; degrades to toolEvents path.
     */
    val progress: RunProgress? = null,
)

data class DashboardActions(
    /** Inicio "Preguntar", tile, deep links: a NEW conversation. */
    val onAsk: (String) -> Unit = {},
    /** Resultado "Seguir esta conversación" and chips: CONTINUE the thread. */
    val onContinue: (String) -> Unit = {},
    val onCancelSend: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onForceReset: () -> Unit = {},
    val onClaim: (sessionId: String, cwd: String) -> Unit = { _, _ -> },
    /** Resultado was opened: the failure line on Inicio has been seen. */
    val onClearLastRun: () -> Unit = {},
    val onBlockedContent: () -> Unit = {},
    /** Preguntar found no speech recognizer: route to BlockedScreen(NO_DICTATION). */
    val onVoiceUnavailable: () -> Unit = {},
)

/** Settled mascot for the current mode; the transient Done hop is layered on top. */
internal fun mascotFor(mode: HomeMode, status: WrapperStatus, blocker: Blocker?): MascotState = when (mode) {
    is HomeMode.Idle -> when {
        mode.offline != null -> MascotState.Offline
        blocker != null -> MascotState.Blocked
        // Stopped on purpose: calm Idle with "Detenido", never the error look.
        mode.failure?.stoppedByUser == true -> MascotState.Idle
        mode.failure != null -> MascotState.Error
        else -> MascotState.Idle
    }
    is HomeMode.Sending -> MascotState.Sending
    is HomeMode.SendFailed -> MascotState.Error
    HomeMode.Running -> MascotState.Running
    HomeMode.Waiting -> MascotState.Waiting
    is HomeMode.Shared -> status.toMascotState()
    // Connecting: signal arcs pulsing upward read as "reaching out / booting up".
    HomeMode.Connecting -> MascotState.Sending
}

private val pageEnterSpec = tween<Float>(Motion.SLOW, easing = Motion.EnterEasing)

/** How long a run-end moment (bubble, done mascot) holds before it pops out. */
private const val MOMENT_MS = 2_500L

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

    val askVoice = rememberVoiceInput(onText = actions.onAsk, onUnavailable = actions.onVoiceUnavailable)
    val continueVoice = rememberVoiceInput(onText = actions.onContinue, onUnavailable = actions.onVoiceUnavailable)
    val askPrompt = stringResource(R.string.voice_prompt_ask)
    val continuePrompt = stringResource(R.string.voice_prompt_continue)

    var doneFlash by remember { mutableStateOf(false) }
    var ringTrigger by remember { mutableIntStateOf(0) }
    var shakeTrigger by remember { mutableIntStateOf(0) }
    var metricsOpen by remember { mutableStateOf(false) }
    // NOVA: recap state — track what step the user last saw so that on wrist
    // raise we can say "Mientras no mirabas: N pasos más".
    var recapFromStep by remember { mutableStateOf<Int?>(null) }
    val currentStep = state.progress?.step?.toInt() ?: 0
    // True while the shell drives the pager, so settle haptics stay for swipes.
    var programmatic by remember { mutableStateOf(false) }
    // The mascot's transient bubble ("¡Listo!", "Uy, falló", "Detenido").
    var transientBubble by remember { mutableStateOf<MascotBubble?>(null) }
    var bubbleKey by remember { mutableIntStateOf(0) }
    // A run that ended while the wrist was down replays once on wake.
    val unseen = remember { UnseenCompletion() }

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
            delay(MOMENT_MS)
            doneFlash = false
        }
    }
    LaunchedEffect(bubbleKey) {
        if (transientBubble != null) {
            delay(MOMENT_MS)
            transientBubble = null
        }
    }

    // The run-end moment. [haptic] is false on a wake replay: the "Claude
    // terminó" notification already buzzed while the wrist was down.
    fun playEnd(end: RunEnd, haptic: Boolean, buzzError: Boolean) {
        when (end) {
            RunEnd.DONE -> {
                // NOVA: signature motif (slow bloom → tick → click) synced to
                // the mascot's first hop. Replaces the generic done() so the
                // "it worked" moment has a distinct CCWEAROS identity.
                if (haptic) Haptics.signature(context)
                doneFlash = true
                ringTrigger++
                transientBubble = MascotBubble.DONE
                if (pagerState.currentPage == HomePages.INICIO) {
                    scope.launch {
                        delay(MOMENT_MS + 300L) // the bubble pops out first
                        goTo(HomePages.RESULTADO)
                    }
                }
            }
            RunEnd.STOPPED -> {
                // Detener did what it said: a calm tick, no error look.
                if (haptic) Haptics.tick(context)
                doneFlash = false
                transientBubble = MascotBubble.STOPPED
            }
            RunEnd.FAILED -> {
                if (haptic && buzzError) Haptics.error(context)
                doneFlash = false
                shakeTrigger++
                transientBubble = MascotBubble.FAILED
            }
        }
        bubbleKey++
    }

    // ¿salió bien? / ¿qué está pasando?: every VM moment gets its haptic here.
    // This is the ONLY place a run's error haptic plays; the VM already
    // de-duplicated blocker vs failed outcome (ErrorBuzzDedupe).
    LaunchedEffect(events) {
        events.collect { e ->
            when (e) {
                HomeEvent.Sent -> {
                    unseen.clear()
                    Haptics.tick(context)
                    // Sent from Resultado (Seguir / chip): come home to see it.
                    goTo(HomePages.INICIO)
                }
                HomeEvent.PickedUp -> {
                    unseen.clear()
                    Haptics.sent(context)
                }
                HomeEvent.SendFailed -> Haptics.error(context)
                is HomeEvent.Finished -> {
                    val end = when {
                        e.ok -> RunEnd.DONE
                        e.stopped -> RunEnd.STOPPED
                        else -> RunEnd.FAILED
                    }
                    // Same notion of "visible" as the notifier: when hidden,
                    // the notification alerts and the moment waits for a wake.
                    val seen = AppVisibility.foreground.value
                    unseen.onFinished(end, System.currentTimeMillis(), seen)
                    if (seen) playEnd(end, haptic = true, buzzError = e.buzzError)
                }
                HomeEvent.Blocked -> {
                    Haptics.error(context)
                    shakeTrigger++
                }
            }
        }
    }

    // Raised the wrist within 30s of a finish nobody saw: play it now, once.
    val resumed = rememberIsResumed()
    LaunchedEffect(resumed) {
        if (resumed) unseen.onResume(System.currentTimeMillis())?.let { playEnd(it, haptic = false, buzzError = false) }
    }

    // NOVA: recap on wrist raise. When the screen goes off (resumed=false)
    // while a run is in progress, remember the last step the user saw.
    // On the next wake (resumed=true), if steps advanced, HomePage shows
    // "Mientras no mirabas: N pasos más" for 2.5s before the live line.
    val running = state.status == WrapperStatus.RUNNING
    LaunchedEffect(resumed) {
        if (!resumed && running) {
            // Wrist went down: snapshot the current step.
            recapFromStep = currentStep
        } else if (resumed && running) {
            // Wrist came back up: if no new steps, clear the recap slot
            // so HomePage doesn't show a stale "0 pasos más".
            if (recapFromStep != null && currentStep <= (recapFromStep ?: 0)) {
                recapFromStep = null
            }
            // If steps advanced, leave recapFromStep set — HomePage reads it.
        } else {
            // Not running: clear.
            recapFromStep = null
        }
    }
    // Also clear recap after the run ends.
    LaunchedEffect(running) { if (!running) recapFromStep = null }

    // KAI: Signature motif fires ONCE on cold app open (not on resume from ambient).
    // Synced with the mascot's first blink sequence (~200ms after the screen is live).
    // Uses a one-shot flag so navigation or screen-off/on never re-fires it.
    var appOpenSignatureFired by remember { mutableStateOf(false) }
    LaunchedEffect(resumed) {
        if (resumed && !appOpenSignatureFired) {
            appOpenSignatureFired = true
            delay(200L)
            Haptics.signature(context)
        }
    }

    // Low-frequency swipe feedback: one light tap when a swipe settles on a page.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.drop(1).collect { page ->
            if (!programmatic) Haptics.swipe(context)
            // Seeing Resultado acknowledges a failure line on Inicio.
            if (page == HomePages.RESULTADO) actions.onClearLastRun()
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
        connectingGrace = state.connectingGrace,
    )
    val settled = mascotFor(mode, state.status, state.blocker)
    val mascot = if (doneFlash && mode is HomeMode.Idle && settled == MascotState.Idle) MascotState.Done else settled

    val doneTldr = remember(state.headline, state.response) { resultTldr(state.headline, state.response) }
    val homeUi = HomeUi(
        mode = mode,
        mascot = mascot,
        toolEvents = state.toolEvents,
        runStartedAt = state.runStartedAt,
        claudeStatus = state.claudeStatus,
        metrics = state.metrics,
        hasLastResponse = !state.response.isNullOrBlank() || state.taskKind != null,
        lastResponseAt = state.outcome?.ts?.takeIf { it > 0 },
        errorShake = shakeTrigger,
        // "¿Me dejas?" stays up while Claude waits on a decision.
        bubble = transientBubble ?: if (mode == HomeMode.Waiting) MascotBubble.ASK else null,
        doneTldr = doneTldr,
        progress = state.progress,
        recapFromStep = recapFromStep,
    )
    val homeCallbacks = HomeCallbacks(
        onAsk = { askVoice.launch(askPrompt) },
        onCancelSend = actions.onCancelSend,
        onRetry = actions.onRetry,
        onStop = actions.onStop,
        onForceReset = actions.onForceReset,
        onOpenResult = { goTo(HomePages.RESULTADO) },
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
                            onFollowup = actions.onContinue,
                            onContinue = { continueVoice.launch(continuePrompt) },
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
