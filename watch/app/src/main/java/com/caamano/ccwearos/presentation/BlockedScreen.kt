package com.caamano.ccwearos.presentation

import androidx.compose.runtime.Composable
import com.caamano.ccwearos.data.BlockerKind

// CONTRACT (owned by lane C; lane B routes to it). Keep this signature.
// Full-screen designed state for problems with one clear next action.
enum class BlockedVariant { MAC_OFFLINE, WATCH_OFFLINE, CLAUDE_CRASHED, NEEDS_MAC, NO_DICTATION }

@Composable
fun BlockedScreen(
    variant: BlockedVariant,
    blockerKind: BlockerKind? = null,
    hint: String? = null,
    cwd: String? = null,
    onPrimary: () -> Unit,
    onDismiss: (() -> Unit)? = null,
) {
    // Stub: lane C replaces the body.
}
