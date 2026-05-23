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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.caamano.ccwearos.data.WrapperStatus

@Composable
fun WearApp(vm: CcwearosViewModel = viewModel()) {
    val status by vm.status.collectAsState()
    val metrics by vm.metrics.collectAsState()
    val prompt by vm.permissionPrompt.collectAsState()
    val activity by vm.activity.collectAsState()
    val task by vm.task.collectAsState()
    val response by vm.response.collectAsState()
    val claudeStatus by vm.claudeStatus.collectAsState()
    val taskKind by vm.taskKind.collectAsState()
    val headline by vm.headline.collectAsState()
    val toolEvents by vm.toolEvents.collectAsState()
    val followups by vm.followups.collectAsState()
    val sentInSession by vm.sentInSession.collectAsState()
    val sharedSession by vm.sharedSession.collectAsState()
    val recentSessions by vm.recentSessions.collectAsState()
    val confirmingClaim by vm.confirmingClaim.collectAsState()
    val claimResult by vm.claimResult.collectAsState()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AnimatedContent(
            targetState = status,
            transitionSpec = {
                // Permission entrance is more impactful — slide up + fade.
                // Everything else just crossfades.
                if (targetState == WrapperStatus.AWAITING_PERMISSION) {
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
            contentKey = { status ->
                when (status) {
                    WrapperStatus.AWAITING_PERMISSION -> "permission"
                    WrapperStatus.OFFLINE -> "offline"
                    else -> "dashboard"
                }
            },
            label = "screen",
        ) { s ->
            when (s) {
                WrapperStatus.AWAITING_PERMISSION -> PermissionScreen(
                    prompt = prompt,
                    onAllow = vm::allow,
                    onDeny = vm::deny,
                )
                WrapperStatus.OFFLINE -> OfflineScreen()
                else -> DashboardScreen(
                    status = s,
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
        }

        // Sprint 4n — overlays drawn ABOVE the AnimatedContent screen
        // routing, so they sit on top of any status (IDLE/RUNNING/etc).
        // Confirmation dialog short-circuits the result banner: if the
        // user is still confirming, an older result shouldn't compete.
        val pendingClaim = confirmingClaim
        if (pendingClaim != null) {
            ConfirmClaimDialog(
                sessionId = pendingClaim.first,
                cwd = pendingClaim.second,
                onConfirm = vm::confirmClaim,
                onCancel = vm::cancelClaim,
            )
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
                        "sesión abierta en tu Mac"
                    } else {
                        result.reason ?: "no se pudo abrir"
                    },
                    onDismiss = vm::dismissClaimResult,
                )
            }
        }
    }
}
