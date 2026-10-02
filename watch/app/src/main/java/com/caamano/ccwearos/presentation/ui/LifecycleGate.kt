package com.caamano.ccwearos.presentation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

/**
 * True while the host activity is RESUMED (on screen and interactive).
 *
 * The foreground service keeps the process alive with the screen off, and a
 * composition's LaunchedEffect keeps running after the activity stops. Every
 * `while (true) { delay(...) }` loop in the UI (mascot motion, tickers) must
 * key on this so the CPU is not woken several times a second for frames
 * nobody sees. Frame-clock animations (withFrameNanos / infinite transitions)
 * already pause with the window; delay loops do not.
 *
 * Previews count as resumed.
 */
@Composable
fun rememberIsResumed(): Boolean {
    if (LocalInspectionMode.current) return true
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    return state.isAtLeast(Lifecycle.State.RESUMED)
}
