package com.caamano.ccwearos.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.TimeText
import com.caamano.ccwearos.R
import androidx.lifecycle.viewmodel.compose.viewModel
import com.caamano.ccwearos.data.WrapperStatus

// Which top-level screen is showing, plus the status it was entered with.
// AnimatedContent keys on `screen` only (see contentKey below), while
// `status` is what DashboardScreen renders for that content.
private enum class Screen { PERMISSION, OFFLINE, DASHBOARD }

private data class Route(val screen: Screen, val status: WrapperStatus)

private fun route(status: WrapperStatus, prompt: String?): Route = Route(
    screen = when {
        // A status without prompt text (e.g. the cached status arriving
        // before /permissionPrompt on wake) stays on the dashboard rather
        // than showing an empty permission modal.
        status == WrapperStatus.AWAITING_PERMISSION && !prompt.isNullOrBlank() -> Screen.PERMISSION
        status == WrapperStatus.OFFLINE -> Screen.OFFLINE
        else -> Screen.DASHBOARD
    },
    status = status,
)

@Composable
fun WearApp(vm: CcwearosViewModel = viewModel()) {
    // collectAsStateWithLifecycle (not collectAsState): collection stops when
    // the activity drops below STARTED, so the WhileSubscribed(5s) flows in
    // the ViewModel actually release their Firebase listeners in background.
    // Only routing state is collected here; each screen collects its own.
    val status by vm.status.collectAsStateWithLifecycle()
    val prompt by vm.permissionPrompt.collectAsStateWithLifecycle()
    val confirmingClaim by vm.confirmingClaim.collectAsStateWithLifecycle()
    val claimResult by vm.claimResult.collectAsStateWithLifecycle()
    val lastError by vm.lastError.collectAsStateWithLifecycle()

    // App-level scaffold: one curved TimeText shared by every page (pages pass
    // timeText = null to their ScreenScaffold and inherit this one).
    AppScaffold(timeText = { TimeText() }) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AnimatedContent(
            targetState = route(status, prompt),
            transitionSpec = {
                // Permission entrance is more impactful — slide up + fade.
                // Everything else just crossfades.
                if (targetState.screen == Screen.PERMISSION) {
                    (slideInVertically(animationSpec = tween(260)) { it / 3 } +
                            fadeIn(animationSpec = tween(260))) togetherWith
                        fadeOut(animationSpec = tween(180))
                } else {
                    fadeIn(animationSpec = tween(220)) togetherWith
                        fadeOut(animationSpec = tween(180))
                }
            },
            // CRITICAL (Sprint 4q): collapse IDLE / RUNNING / others into one
            // "dashboard" content key so DashboardScreen is NOT re-created on
            // every IDLE↔RUNNING flip. Without this, the v7 task-completion
            // SharedFlow subscriber (inside TaskCompletionHandler) gets
            // cancelled mid-emission and the haptic + auto-nav to Page 2 are
            // silently lost. The PagerState would also reset to initialPage=0
            // on every transition, breaking user navigation. PermissionScreen
            // and OfflineScreen still get their own keys so the AnimatedContent
            // crossfade still fires when entering/leaving those screens.
            contentKey = { it.screen },
            label = "screen",
        ) { r ->
            when (r.screen) {
                Screen.PERMISSION -> PermissionRoute(vm)
                Screen.OFFLINE -> OfflineScreen()
                Screen.DASHBOARD -> DashboardRoute(vm, r.status)
            }
        }

        // Sprint 4n — overlays drawn ABOVE the AnimatedContent screen
        // routing, so they sit on top of any status (IDLE/RUNNING/etc).
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

// Dashboard-only flows are collected here, so their WhileSubscribed
// listeners pause while the permission or offline screen is showing.
@Composable
private fun DashboardRoute(vm: CcwearosViewModel, status: WrapperStatus) {
    val metrics by vm.metrics.collectAsStateWithLifecycle()
    val activity by vm.activity.collectAsStateWithLifecycle()
    val task by vm.task.collectAsStateWithLifecycle()
    val response by vm.response.collectAsStateWithLifecycle()
    val claudeStatus by vm.claudeStatus.collectAsStateWithLifecycle()
    val taskKind by vm.taskKind.collectAsStateWithLifecycle()
    val headline by vm.headline.collectAsStateWithLifecycle()
    val toolEvents by vm.toolEvents.collectAsStateWithLifecycle()
    val followups by vm.followups.collectAsStateWithLifecycle()
    val sentInSession by vm.sentInSession.collectAsStateWithLifecycle()
    val sharedSession by vm.sharedSession.collectAsStateWithLifecycle()
    val recentSessions by vm.recentSessions.collectAsStateWithLifecycle()
    DashboardScreen(
        status = status,
        metrics = metrics,
        activity = activity,
        task = task,
        response = response,
        claudeStatus = claudeStatus,
        taskKind = taskKind,
        headline = headline,
        toolEvents = toolEvents,
        followups = followups,
        sentInSession = sentInSession,
        sharedSession = sharedSession,
        recentSessions = recentSessions,
        onAsk = vm::sendPrompt,
        onAskWithReset = vm::askWithReset,
        onStop = vm::stop,
        onForceReset = vm::forceReset,
        onClaim = vm::requestClaimConfirmation,
        taskCompleted = vm.taskCompleted,
    )
}
