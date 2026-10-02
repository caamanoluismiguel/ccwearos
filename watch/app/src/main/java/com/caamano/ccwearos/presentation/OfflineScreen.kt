package com.caamano.ccwearos.presentation

import androidx.compose.runtime.Composable
import androidx.wear.compose.ui.tooling.preview.WearPreviewLargeRound
import com.caamano.ccwearos.presentation.theme.CCWEAROSTheme

// The wrapper on the Mac isn't reachable. Same designed state as
// BlockedScreen(MAC_OFFLINE): grey still mascot, "Tu Mac no responde", one
// "Reintentar" action. [onRetry] defaults to a no-op so existing callers
// (`OfflineScreen()`) keep compiling; the button still answers with a tick.
@Composable
fun OfflineScreen(onRetry: () -> Unit = {}) {
    BlockedScreen(variant = BlockedVariant.MAC_OFFLINE, onPrimary = onRetry)
}

@WearPreviewLargeRound
@Composable
private fun PreviewOffline() {
    CCWEAROSTheme { OfflineScreen() }
}
