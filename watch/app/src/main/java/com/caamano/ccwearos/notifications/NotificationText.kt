package com.caamano.ccwearos.notifications

import com.caamano.ccwearos.data.RunOutcome
import com.caamano.ccwearos.data.WrapperStatus
import com.caamano.ccwearos.presentation.permission.parsePrompt

// Pure, Android-free text and decision logic for the notifications and the
// foreground service's ongoing status. Unit tested under src/test/.../notifications/.

object NotificationText {
    private const val SEP = " · "

    // `Bash(git push origin main)`: the older Claude Code prompt shape.
    private val CALL_SHAPE = Regex("""^([A-Za-z][\w.\-]{0,63})\((.*)\)$""")
    private val SPACES = Regex("""\s+""")

    /**
     * One line for the notification body: `Herramienta · objetivo`.
     *   "Bash: git push origin main\nPush it" → "Bash · git push origin main"
     *   "Bash(npm test)"                       → "Bash · npm test"
     *   "Read"                                 → "Read"
     *   free-form text                         → its first non-blank line
     * Null when there is nothing to show.
     */
    fun promptSummary(prompt: String?): String? {
        val text = prompt?.trim().orEmpty()
        if (text.isEmpty()) return null
        val parsed = parsePrompt(text)
        if (parsed.tool != null) {
            return parsed.target?.let { parsed.tool + SEP + it.collapse() } ?: parsed.tool
        }
        val first = firstLine(text) ?: return null
        CALL_SHAPE.matchEntire(first)?.let { m ->
            val arg = m.groupValues[2].collapse()
            return if (arg.isEmpty()) m.groupValues[1] else m.groupValues[1] + SEP + arg
        }
        return first.collapse()
    }

    fun firstLine(text: String?): String? =
        text?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }

    private fun String.collapse(): String = trim().replace(SPACES, " ")
}

/** What the ongoing activity says on the watch face; resolved to a string by the service. */
enum class OngoingStatus { NO_CONNECTION, AWAITING, BLOCKED, RUNNING, MAC_OFFLINE, CONNECTED }

object OngoingStatusMapper {
    fun map(status: WrapperStatus, connected: Boolean, blocked: Boolean): OngoingStatus = when {
        !connected -> OngoingStatus.NO_CONNECTION
        status == WrapperStatus.AWAITING_PERMISSION -> OngoingStatus.AWAITING
        // Same rule as the tile: a blocker only counts while the wrapper is alive.
        blocked && (status == WrapperStatus.IDLE || status == WrapperStatus.RUNNING) -> OngoingStatus.BLOCKED
        status == WrapperStatus.RUNNING -> OngoingStatus.RUNNING
        status == WrapperStatus.OFFLINE -> OngoingStatus.MAC_OFFLINE
        else -> OngoingStatus.CONNECTED
    }
}

/**
 * Decides when a run just finished, so "Claude terminó" fires once per run.
 *
 * The first value seen is the baseline (an outcome that already existed when
 * the service started is old news). After that, a non-null outcome fires when
 * the previous value was null (a run cleared it, then wrote the new one) or
 * carried a different timestamp.
 */
class OutcomeGate {
    private var primed = false
    private var previous: RunOutcome? = null

    @Synchronized
    fun shouldNotify(outcome: RunOutcome?): Boolean {
        val prev = previous
        previous = outcome
        if (!primed) {
            primed = true
            return false
        }
        if (outcome == null) return false
        return prev == null || prev.ts != outcome.ts
    }
}
