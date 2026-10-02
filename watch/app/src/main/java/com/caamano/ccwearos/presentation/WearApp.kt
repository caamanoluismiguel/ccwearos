package com.caamano.ccwearos.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.TimeText
import com.caamano.ccwearos.R
import com.caamano.ccwearos.presentation.home.HINT_WAITING_ON_MAC
import com.caamano.ccwearos.presentation.home.Overlay
import com.caamano.ccwearos.notifications.DeepLinks
import com.caamano.ccwearos.presentation.home.continuityTransition
import com.caamano.ccwearos.presentation.home.underlayHidden
import com.caamano.ccwearos.presentation.home.underlayProgress
import com.caamano.ccwearos.presentation.ui.Motion
import com.caamano.ccwearos.presentation.ui.rememberReducedMotion

// ─────────────────────────────────────────────────────────────────────────────
// Shell: the pager (DashboardScreen) is ALWAYS composed, and every other
// state is an overlay drawn above it. Nothing tears the pager down, so its
// page, scroll and completion collector survive IDLE ↔ RUNNING ↔ prompt.
//
// Overlay order (see home/HomeModel.kt routeOverlay):
//   PermissionScreen  › BlockedScreen(blocker) › BlockedScreen(MAC_OFFLINE)
//   › BlockedScreen(NEEDS_MAC, "Claude espera algo en tu Mac")
//   › BlockedScreen(NEEDS_MAC) for TUI junk flagged by ResultPage.
// WATCH_OFFLINE is a compact pill under the TimeText (WatchOfflineBanner),
// not an overlay: it covers nothing and takes no input.
// Dismissals are local (VM, blocker ts persisted), never written to RTDB.
//
// Every full-screen overlay swallows input and is its own TalkBack traversal
// group while the pager below is cleared from semantics. Haptics: a blocker's
// error buzz comes from the VM (HomeEvent.Blocked, deduped); this shell only
// ticks for the non-error NEEDS_MAC screens. BlockedScreen never buzzes.
//
// Continuity: an overlay arrives from progress 1 → 0 (blurred, stretched,
// faded) over Motion.SLOW with EnterEasing while the pager recedes 0 → 0.6;
// reverse on exit with ExitEasing. Once the opaque overlay is fully in, the
// pager stops drawing (no blur RenderEffect, alpha 0) but stays composed, so
// its state survives. Only graphicsLayer changes, so nothing jumps.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun WearApp(vm: CcwearosViewModel = viewModel()) {
    // collectAsStateWithLifecycle (not collectAsState): collection stops when
    // the activity drops below STARTED, so the WhileSubscribed(5s) flows in
    // the ViewModel actually release their Firebase listeners in background.
    val overlay by vm.overlay.collectAsStateWithLifecycle()
    val watchOfflineBanner by vm.watchOfflineBanner.collectAsStateWithLifecycle()
    val confirmingClaim by vm.confirmingClaim.collectAsStateWithLifecycle()
    val claimResult by vm.claimResult.collectAsStateWithLifecycle()
    val lastError by vm.lastError.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val reduced = rememberReducedMotion()

    // `shown` is what is composed in the overlay layer; it lags `overlay`
    // by one exit animation so a leaving screen animates out before the
    // next one comes in. `reveal` 0 = hidden, 1 = fully shown.
    var shown by remember { mutableStateOf<Overlay>(Overlay.None) }
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(overlay) {
        val target = overlay
        if (target.key != shown.key) {
            if (shown != Overlay.None) {
                if (reduced) reveal.snapTo(0f) else reveal.animateTo(0f, tween(Motion.MEDIUM, easing = Motion.ExitEasing))
            }
            // Non-error arrivals only; a blocker's error buzz is the VM's.
            if (target is Overlay.Blocked && target.blockerTs == null &&
                blockedCopy(target.variant, target.blockerKind, target.hint, target.cwd).haptic == BlockedHaptic.TICK
            ) {
                Haptics.tick(context)
            }
        }
        shown = target
        if (target != Overlay.None) {
            if (reduced) reveal.snapTo(1f) else reveal.animateTo(1f, tween(Motion.SLOW, easing = Motion.EnterEasing))
        }
    }

    // App-level scaffold: one curved TimeText shared by every page (pages pass
    // timeText = null to their ScreenScaffold and inherit this one).
    AppScaffold(timeText = { TimeText() }) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val covered = shown != Overlay.None
            DashboardRoute(
                vm = vm,
                modifier = Modifier
                    // Fully covered: draw nothing (and no blur) but stay composed.
                    .graphicsLayer { alpha = if (underlayHidden(reveal.value)) 0f else 1f }
                    .continuityTransition(
                        progress = { underlayProgress(reveal.value) },
                        direction = { 0 },
                        reducedMotion = reduced,
                    )
                    // Hidden from TalkBack while an overlay covers it.
                    .then(if (covered) Modifier.clearAndSetSemantics { } else Modifier),
            )

            if (covered) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = reveal.value }
                        .continuityTransition(
                            progress = { 1f - reveal.value },
                            direction = { 0 },
                            reducedMotion = reduced,
                        )
                        .background(Color.Black)
                        // Swallow taps/swipes so nothing reaches the pager below.
                        .pointerInput(Unit) { detectTapGestures { } }
                        // TalkBack stays inside the overlay.
                        .semantics { isTraversalGroup = true },
                ) {
                    when (val o = shown) {
                        Overlay.Permission -> PermissionRoute(vm)
                        is Overlay.Blocked -> BlockedScreen(
                            variant = o.variant,
                            blockerKind = o.blockerKind,
                            hint = if (o.hint == HINT_WAITING_ON_MAC) stringResource(R.string.home_waiting_mac_hint) else o.hint,
                            cwd = o.cwd,
                            onPrimary = {
                                vm.dismissOverlay(o.dismissKey)
                                // "Ver sugerencias": the chips live on Resultado.
                                if (o.variant == BlockedVariant.NO_DICTATION) {
                                    DeepLinks.pending.value = DeepLinks.ACTION_RESULT
                                }
                            },
                            onDismiss = { vm.dismissOverlay(o.dismissKey) },
                        )
                        Overlay.None -> Unit
                    }
                }
            }

            // Non-blocking pill: the pager stays readable and usable under it;
            // network actions are refused by the VM and Inicio shows "Sin
            // conexión" (explains on tap).
            AnimatedVisibility(
                visible = watchOfflineBanner && !covered,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn(Motion.enter(Motion.MEDIUM)) + slideInVertically(Motion.enter(Motion.MEDIUM)) { -it / 2 },
                exit = fadeOut(Motion.exit(Motion.FAST)) + slideOutVertically(Motion.exit(Motion.FAST)) { -it / 2 },
            ) {
                WatchOfflineBanner()
            }

            // Sprint 4n — dialogs / banners above everything.
            // Confirmation dialog short-circuits the result banner: if the
            // user is still confirming, an older result shouldn't compete.
            val pendingClaim = confirmingClaim
            val error = lastError
            if (pendingClaim != null) {
                ConfirmClaimDialog(
                    sessionId = pendingClaim.first,
                    cwd = pendingClaim.second,
                    onConfirm = vm::confirmClaim,
                    onCancel = vm::cancelClaim,
                )
            } else if (error != null) {
                // A failed write (allow/deny, stop, prompt, claim). Reuses the
                // claim banner: red, auto-dismisses after 4s.
                ClaimResultBanner(ok = false, message = error, onDismiss = vm::clearError)
            } else {
                // Only render the banner if the result is fresh (<10s old).
                // Stale results from a previous claim shouldn't pop back up
                // when the user wakes the watch hours later.
                val freshResult = claimResult?.takeIf {
                    System.currentTimeMillis() - it.ts < 10_000L
                }
                freshResult?.let { result ->
                    ClaimResultBanner(
                        ok = result.ok,
                        message = if (result.ok) {
                            stringResource(R.string.claim_ok)
                        } else {
                            result.reason ?: stringResource(R.string.claim_failed)
                        },
                        onDismiss = vm::dismissClaimResult,
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionRoute(vm: CcwearosViewModel) {
    val prompt by vm.permissionPrompt.collectAsStateWithLifecycle()
    val connected by vm.connected.collectAsStateWithLifecycle()
    val answered by vm.answered.collectAsStateWithLifecycle()
    PermissionScreen(
        prompt = prompt,
        onAllow = vm::allow,
        onDeny = vm::deny,
        connected = connected,
        answered = answered,
    )
}

@Composable
private fun DashboardRoute(vm: CcwearosViewModel, modifier: Modifier) {
    val status by vm.status.collectAsStateWithLifecycle()
    val connected by vm.connected.collectAsStateWithLifecycle()
    val send by vm.sendState.collectAsStateWithLifecycle()
    val sharedSession by vm.sharedSession.collectAsStateWithLifecycle()
    val conversationActive by vm.conversationActive.collectAsStateWithLifecycle()
    val lastRun by vm.lastRun.collectAsStateWithLifecycle()
    val runStartedAt by vm.runStartedAt.collectAsStateWithLifecycle()
    val blocker by vm.blocker.collectAsStateWithLifecycle()
    val activity by vm.activity.collectAsStateWithLifecycle()
    val task by vm.task.collectAsStateWithLifecycle()
    val toolEvents by vm.toolEvents.collectAsStateWithLifecycle()
    val claudeStatus by vm.claudeStatus.collectAsStateWithLifecycle()
    val metrics by vm.metrics.collectAsStateWithLifecycle()
    val headline by vm.headline.collectAsStateWithLifecycle()
    val response by vm.response.collectAsStateWithLifecycle()
    val taskKind by vm.taskKind.collectAsStateWithLifecycle()
    val outcome by vm.outcome.collectAsStateWithLifecycle()
    val followups by vm.followups.collectAsStateWithLifecycle()
    val recentSessions by vm.recentSessions.collectAsStateWithLifecycle()

    val actions = remember(vm) {
        DashboardActions(
            onAsk = vm::sendPrompt,
            onContinue = vm::continueConversation,
            onCancelSend = vm::cancelSend,
            onRetry = vm::retrySend,
            onStop = vm::stop,
            onForceReset = vm::forceReset,
            onClaim = vm::requestClaimConfirmation,
            onClearLastRun = vm::clearLastRun,
            onBlockedContent = vm::reportBlockedContent,
        )
    }
    DashboardScreen(
        state = DashboardState(
            status = status,
            connected = connected,
            send = send,
            sharedSession = sharedSession,
            conversationActive = conversationActive,
            lastRun = lastRun,
            runStartedAt = runStartedAt,
            blocker = blocker,
            activity = activity,
            task = task,
            toolEvents = toolEvents,
            claudeStatus = claudeStatus,
            metrics = metrics,
            headline = headline,
            response = response,
            taskKind = taskKind,
            outcome = outcome,
            followups = followups,
            recentSessions = recentSessions,
        ),
        actions = actions,
        events = vm.events,
        modifier = modifier,
    )
}
