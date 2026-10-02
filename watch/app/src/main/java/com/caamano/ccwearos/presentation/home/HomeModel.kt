package com.caamano.ccwearos.presentation.home

import com.caamano.ccwearos.data.Blocker
import com.caamano.ccwearos.data.BlockerKind
import com.caamano.ccwearos.data.RunOutcome
import com.caamano.ccwearos.data.SharedSessionMeta
import com.caamano.ccwearos.data.ToolEvent
import com.caamano.ccwearos.data.WrapperStatus
import com.caamano.ccwearos.presentation.BlockedVariant

// Pure, Android-free model for the home shell (Inicio + routing). Everything
// here is unit-tested in presentation/HomeModelTest.kt.

/** Fixed pager layout: these never move, so the page indicator is stable. */
object HomePages {
    const val INICIO = 0
    const val RESULTADO = 1
    const val SESIONES = 2
    const val COUNT = 3
}

/** A voice prompt between "spoken" and "the Mac picked it up". */
sealed interface SendState {
    data object None : SendState

    /**
     * Written (or being written) to /prompt. [outcomeAtSend] lets the VM spot a
     * pickup even when the run starts and ends between two status emissions.
     */
    data class Sending(
        val text: String,
        val wire: String,
        val outcomeAtSend: RunOutcome?,
    ) : SendState

    /** Not picked up in time (or the write failed). Offers Reintentar / Cancelar. */
    data class Failed(val text: String, val wire: String) : SendState
}

/** One-shot moments the UI turns into haptics + motion. */
sealed interface HomeEvent {
    /** Prompt handed to Firebase: Haptics.tick. */
    data object Sent : HomeEvent

    /** Status flipped to working: Haptics.sent. */
    data object PickedUp : HomeEvent

    /** Not picked up in 15s or write failed: Haptics.error. */
    data object SendFailed : HomeEvent

    /** A run finished; [ok] comes from /outcome, never from response text. */
    data class Finished(val ok: Boolean) : HomeEvent
}

/** How the last run ended, kept so Inicio can show a failure line. */
data class LastRun(val ok: Boolean, val exitCode: Long, val stoppedByUser: Boolean)

enum class OfflineReason { WATCH, MAC }

/** What Inicio is showing right now. Exactly one primary action per mode. */
sealed interface HomeMode {
    data class Idle(
        val offline: OfflineReason?,
        val conversationActive: Boolean,
        val failure: LastRun?,
    ) : HomeMode

    data class Sending(val text: String) : HomeMode
    data class SendFailed(val text: String) : HomeMode
    data object Running : HomeMode
    data object Waiting : HomeMode
    data class Shared(val meta: SharedSessionMeta) : HomeMode
}

fun homeMode(
    status: WrapperStatus,
    connected: Boolean,
    send: SendState,
    sharedSession: SharedSessionMeta?,
    conversationActive: Boolean,
    lastRun: LastRun?,
): HomeMode = when {
    send is SendState.Sending -> HomeMode.Sending(send.text)
    send is SendState.Failed -> HomeMode.SendFailed(send.text)
    // A cc/hook session owns the Mac pty: asking would clobber it.
    sharedSession != null -> HomeMode.Shared(sharedSession)
    status == WrapperStatus.RUNNING -> HomeMode.Running
    status == WrapperStatus.AWAITING_PERMISSION -> HomeMode.Waiting
    else -> HomeMode.Idle(
        offline = when {
            !connected -> OfflineReason.WATCH
            status == WrapperStatus.OFFLINE -> OfflineReason.MAC
            else -> null
        },
        conversationActive = conversationActive,
        failure = lastRun?.takeUnless { it.ok },
    )
}

// ─── Live line ("Editando parser.ts") ───────────────────────────────────────

enum class ToolVerb { EDIT, WRITE, READ, RUN, SEARCH, FETCH, WEB, DELEGATE, OTHER }

/** What the running line says: a tool verb + short target, or the activity. */
sealed interface LiveLine {
    data class Tool(val verb: ToolVerb, val target: String?, val toolName: String) : LiveLine
    data class Activity(val text: String) : LiveLine
    data object None : LiveLine
}

fun liveLine(toolEvents: List<ToolEvent>, activity: String?): LiveLine {
    val last = toolEvents.lastOrNull()
    if (last != null && last.tool.isNotBlank()) {
        val name = last.tool.replace("\\s+".toRegex(), "")
        val verb = when (name) {
            "Edit", "MultiEdit", "NotebookEdit" -> ToolVerb.EDIT
            "Write" -> ToolVerb.WRITE
            "Read" -> ToolVerb.READ
            "Bash" -> ToolVerb.RUN
            "Grep", "Glob" -> ToolVerb.SEARCH
            "WebFetch" -> ToolVerb.FETCH
            "WebSearch" -> ToolVerb.WEB
            "Task", "Agent" -> ToolVerb.DELEGATE
            else -> ToolVerb.OTHER
        }
        return LiveLine.Tool(verb, shortTarget(verb, last.arg), name)
    }
    val act = activity?.trim()
    return if (!act.isNullOrEmpty()) LiveLine.Activity(act) else LiveLine.None
}

/** File paths shrink to their basename; commands to their first 28 chars. */
internal fun shortTarget(verb: ToolVerb, arg: String?): String? {
    val a = arg?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when (verb) {
        ToolVerb.EDIT, ToolVerb.WRITE, ToolVerb.READ ->
            a.trimEnd('/').substringAfterLast('/').ifEmpty { a }
        else -> if (a.length > 28) a.take(27).trimEnd() + "…" else a
    }
}

// ─── Time formatting ─────────────────────────────────────────────────────────

/** "04:07" for elapsed run time; "1:02:03" past an hour. */
fun formatElapsed(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** No new activity / tool / task for this long while RUNNING → "¿Sigue ahí?". */
const val STALE_RUN_MS = 3 * 60_000L

fun isRunStale(nowMs: Long, lastProgressAtMs: Long): Boolean = nowMs - lastProgressAtMs >= STALE_RUN_MS

// ─── Overlay routing ─────────────────────────────────────────────────────────

/** What sits above the pager. The pager itself is never torn down. */
sealed interface Overlay {
    /** Stable identity for transitions: same key = same screen, data refresh only. */
    val key: String

    data object None : Overlay {
        override val key = "none"
    }

    data object Permission : Overlay {
        override val key = "permission"
    }

    data class Blocked(
        val variant: BlockedVariant,
        val blockerKind: BlockerKind? = null,
        val hint: String? = null,
        val cwd: String? = null,
        /** What a local dismissal is keyed on (blocker ts, episode, response). */
        val dismissKey: String,
    ) : Overlay {
        override val key = "blocked:${variant.name}"
    }
}

/** Marker for the AWAITING_PERMISSION-without-text case; the UI fills the hint string. */
const val HINT_WAITING_ON_MAC = "@waiting_on_mac"

data class RoutingInput(
    val status: WrapperStatus,
    val permissionPrompt: String?,
    val blocker: Blocker?,
    /** status == OFFLINE, held long enough to not be the cold-start default. */
    val macOfflineStable: Boolean,
    /** AWAITING_PERMISSION with no prompt text, held long enough to not be a wake race. */
    val awaitingWithoutPromptStable: Boolean,
    /** ResultPage flagged the current response as TUI junk. */
    val blockedContentKey: String?,
    val dismissed: Set<String>,
)

fun BlockerKind.toVariant(): BlockedVariant = when (this) {
    BlockerKind.CRASH, BlockerKind.TIMEOUT -> BlockedVariant.CLAUDE_CRASHED
    BlockerKind.TRUST, BlockerKind.LOGIN, BlockerKind.OTHER -> BlockedVariant.NEEDS_MAC
}

fun blockerDismissKey(b: Blocker) = "blocker:${b.ts}:${b.kind}"

/**
 * Priority: a live permission prompt always wins (it is the one thing the
 * wrist can act on), then Mac-side blockers, then Mac offline, then the
 * "Claude waits on your Mac" case, then junk content from ResultPage.
 * WATCH_OFFLINE is not here: it is a non-blocking banner (see WearApp).
 */
fun routeOverlay(input: RoutingInput): Overlay {
    val d = input.dismissed
    if (input.status == WrapperStatus.AWAITING_PERMISSION && !input.permissionPrompt.isNullOrBlank()) {
        return Overlay.Permission
    }
    input.blocker?.let { b ->
        val key = blockerDismissKey(b)
        if (key !in d) {
            return Overlay.Blocked(
                variant = b.kind.toVariant(),
                blockerKind = b.kind,
                hint = b.hint.takeIf { it.isNotBlank() },
                cwd = b.cwd,
                dismissKey = key,
            )
        }
    }
    if (input.macOfflineStable && MAC_OFFLINE_KEY !in d) {
        return Overlay.Blocked(BlockedVariant.MAC_OFFLINE, dismissKey = MAC_OFFLINE_KEY)
    }
    if (input.status == WrapperStatus.AWAITING_PERMISSION && input.awaitingWithoutPromptStable &&
        AWAITING_KEY !in d
    ) {
        return Overlay.Blocked(BlockedVariant.NEEDS_MAC, hint = HINT_WAITING_ON_MAC, dismissKey = AWAITING_KEY)
    }
    val junk = input.blockedContentKey
    if (junk != null && junk !in d) {
        return Overlay.Blocked(BlockedVariant.NEEDS_MAC, dismissKey = junk)
    }
    return Overlay.None
}

// Episode keys: the VM drops them from the dismissed set when the episode ends,
// so the next OFFLINE / waiting episode shows the screen again.
const val MAC_OFFLINE_KEY = "mac-offline"
const val AWAITING_KEY = "awaiting-no-prompt"
const val WATCH_OFFLINE_KEY = "watch-offline"

fun blockedContentKey(response: String): String = "content:${response.hashCode()}"
