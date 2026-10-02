package com.caamano.ccwearos.presentation.result

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.caamano.ccwearos.data.RunOutcome
import com.caamano.ccwearos.data.TaskKind
import com.caamano.ccwearos.data.ToolEvent

// CONTRACT (owned by lane A; lane B calls it). Keep this signature.
// The always-present "Resultado" page: TL;DR card, status line, markdown
// blocks, tool trail, follow-up chips, Hablar / Nueva conversación.
// `onBlockedContent` fires when the response looks like TUI junk, so the
// shell can show the BlockedScreen instead of rendering it.
@Composable
fun ResultPage(
    headline: String?,
    response: String?,
    taskKind: TaskKind?,
    outcome: RunOutcome?,
    toolEvents: List<ToolEvent>,
    followups: List<String>,
    conversationActive: Boolean,
    onFollowup: (String) -> Unit,
    onSpeak: () -> Unit,
    onNewConversation: () -> Unit,
    onBlockedContent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Stub: lane A replaces the body.
}
