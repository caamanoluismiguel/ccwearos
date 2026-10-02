package com.caamano.ccwearos.presentation.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.tooling.preview.devices.WearDevices
import com.caamano.ccwearos.R
import com.caamano.ccwearos.data.RecentSession
import com.caamano.ccwearos.data.SharedSessionMeta
import com.caamano.ccwearos.presentation.Haptics
import com.caamano.ccwearos.presentation.MonoFamily
import com.caamano.ccwearos.presentation.theme.CCWEAROSTheme
import com.caamano.ccwearos.presentation.theme.CcPalette
import com.caamano.ccwearos.presentation.theme.StatusColors
import com.caamano.ccwearos.presentation.ui.MonoLabel
import com.caamano.ccwearos.presentation.ui.PixelMascot
import com.caamano.ccwearos.presentation.ui.MascotState
import com.caamano.ccwearos.presentation.ui.StatusDot

// SESIONES — always present (fixed pager slot 2). Every Claude Code session
// the wrapper scanner found, grouped by project, newest first. Coral = shared
// via cc, green = active process, grey = past. Past sessions are tappable
// (resume in a new Terminal on the Mac, after a confirmation dialog).

private sealed interface SessionEntry {
    data class Header(val project: String) : SessionEntry
    data class Item(val session: RecentSession) : SessionEntry
}

@Composable
fun SessionsPage(
    sessions: List<RecentSession>,
    sharedSession: SharedSessionMeta?,
    onClaim: (sessionId: String, cwd: String) -> Unit,
) {
    if (sessions.isEmpty()) {
        EmptySessions()
        return
    }
    val entries = remember(sessions) {
        sessions.sortedByDescending { it.mtime }
            .groupBy { it.projectName }
            .flatMap { (project, list) ->
                listOf(SessionEntry.Header(project)) + list.map { SessionEntry.Item(it) }
            }
    }
    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics(mergeDescendants = true) { heading() },
                    horizontalArrangement = Arrangement.Center,
                ) {
                    MonoLabel(stringResource(R.string.sessions_title), color = MaterialTheme.colorScheme.primary)
                    MonoLabel(" · ${sessions.size}")
                }
            }
            items(entries) { entry ->
                when (entry) {
                    is SessionEntry.Header -> Text(
                        text = entry.project,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = MonoFamily,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .semantics { heading() },
                    )
                    is SessionEntry.Item -> {
                        val sess = entry.session
                        val isShared = sess.sessionId == sharedSession?.sessionId
                        SessionRow(
                            session = sess,
                            isShared = isShared,
                            // Active / shared sessions hold the file lock, so
                            // `claude --resume` would fail: not claimable.
                            onTap = if (sess.active || isShared) null else {
                                { onClaim(sess.sessionId, sess.cwd) }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptySessions() {
    ScreenScaffold { _ ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = roundInset()),
            ) {
                PixelMascot(state = MascotState.Idle, size = 24.dp, color = CcPalette.TextSecondary)
                Text(
                    text = stringResource(R.string.home_sessions_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: RecentSession,
    isShared: Boolean,
    onTap: (() -> Unit)?,
) {
    val context = LocalContext.current
    val interaction = remember { MutableInteractionSource() }
    val (dotColor, stateRes) = when {
        isShared -> CcPalette.Coral to R.string.session_shared
        session.active -> StatusColors.running to R.string.session_active
        else -> StatusColors.idle to R.string.session_past
    }
    val stateWord = stringResource(stateRes)
    val ago = timeAgoShort(session.mtime)
    val rowDescription = listOfNotNull(stateWord, ago, session.lastUserMessage).joinToString(", ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (onTap != null) Modifier.pressScale(interaction) else Modifier)
            .clip(RoundedCornerShape(12.dp))
            .let {
                if (onTap != null) {
                    it.clickable(interactionSource = interaction, indication = null) {
                        Haptics.tick(context)
                        onTap()
                    }
                } else {
                    it
                }
            }
            .clearAndSetSemantics {
                contentDescription = rowDescription
                if (onTap != null) role = Role.Button
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        StatusDot(
            color = dotColor,
            description = null,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = "$stateWord · $ago",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
            session.lastUserMessage?.let { msg ->
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// "hace 2 min", "hace 1 h", "ayer", "hace 3 d".
@Composable
private fun timeAgoShort(mtime: Long): String {
    val diffSec = ((System.currentTimeMillis() - mtime) / 1000L).coerceAtLeast(0)
    return when {
        diffSec < 60 -> stringResource(R.string.time_now)
        diffSec < 3600 -> stringResource(R.string.time_minutes, (diffSec / 60).toInt())
        diffSec < 86_400 -> stringResource(R.string.time_hours, (diffSec / 3600).toInt())
        diffSec < 172_800 -> stringResource(R.string.time_yesterday)
        else -> stringResource(R.string.time_days, (diffSec / 86_400).toInt())
    }
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Sesiones · vacío")
@Composable
private fun PreviewSessionsEmpty() {
    CCWEAROSTheme { SessionsPage(sessions = emptyList(), sharedSession = null, onClaim = { _, _ -> }) }
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Sesiones")
@Composable
private fun PreviewSessions() {
    val now = System.currentTimeMillis()
    CCWEAROSTheme {
        SessionsPage(
            sessions = listOf(
                RecentSession("a", "/p/ccwearos", "CCWEAROS", now - 60_000, active = true, lastUserMessage = "arregla el parser"),
                RecentSession("b", "/p/ccwearos", "CCWEAROS", now - 3_600_000, lastUserMessage = "sube la versión"),
                RecentSession("c", "/p/uimo", "imagine3d", now - 90_000_000, lastUserMessage = "revisa el checkout"),
            ),
            sharedSession = null,
            onClaim = { _, _ -> },
        )
    }
}
