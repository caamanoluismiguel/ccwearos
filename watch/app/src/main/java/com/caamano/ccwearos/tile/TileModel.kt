package com.caamano.ccwearos.tile

import com.caamano.ccwearos.presentation.permission.Risk
import com.caamano.ccwearos.presentation.permission.classifyRisk

import java.util.Locale

// Pure, Android-free model shared by the Status Tile and the complications.
// Everything here is unit tested under src/test/.../tile/.

/** Raw values read from RTDB in one pass. Null = path missing or unreadable. */
data class TileSnapshot(
    val status: String? = null,
    val activity: String? = null,
    val permissionPrompt: String? = null,
    val permissionPromptId: String? = null,
    val dailyTokens: Long? = null,
    val contextPct: Double? = null,
    /** /blocker present (any kind). Its hint is in [blockerHint]. */
    val blocked: Boolean = false,
    val blockerHint: String? = null,
    /** /outcome.ok, null when there is no outcome (running, or never ran). */
    val outcomeOk: Boolean? = null,
    /** /outcome.ts (Mac clock, ms). 0 when unknown. */
    val outcomeTs: Long = 0,
    val headline: String? = null,
    /** /progress.step: tool calls so far; 0 = thinking. Null = path absent. */
    val progressStep: Long? = null,
    /** /progress.label: Spanish step description ("Buscando archivos"). */
    val progressLabel: String? = null,
)

/** What the tile / complication renders. */
sealed class TileState {
    /** Anonymous auth missing: reads would be denied by the RTDB rules. */
    object SignedOut : TileState()

    /** RTDB unreachable from the watch (read timed out). */
    object NoSignal : TileState()

    object Offline : TileState()

    data class Idle(val dailyTokens: Long, val contextPct: Int?) : TileState()

    data class Running(
        val activity: String?,
        val contextPct: Int?,
        /** Step count from /progress; null when the daemon predates the contract. */
        val step: Int? = null,
        /** Step label ("Buscando archivos"); null when step is null. */
        val stepLabel: String? = null,
    ) : TileState()

    data class Awaiting(
        val promptPreview: String,
        val promptId: String?,
        /** True when Permitir / Rechazar may be offered directly on the tile. */
        val quickActions: Boolean,
    ) : TileState()

    /** Something only the Mac can fix (trust, login, crash, timeout). [hint] = first line. */
    data class Blocked(val hint: String?) : TileState()

    /** Idle right after a run: its real outcome and the headline's first line. */
    data class Done(val ok: Boolean, val headline: String?, val contextPct: Int?) : TileState()
}

/** State word for the SHORT_TEXT complication; the service resolves it to a string resource. */
enum class StateWord { OPEN, NO_SIGNAL, MAC_OFFLINE, READY, WORKING, PERMISSION, MAC, FAILED }

object TileMapper {
    /** A finished run stays on the tile this long, then the tile goes back to Idle. */
    const val DONE_FRESH_MS = 30 * 60 * 1000L

    fun map(
        snapshot: TileSnapshot?,
        signedIn: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): TileState {
        if (!signedIn) return TileState.SignedOut
        if (snapshot == null) return TileState.NoSignal
        val pct = snapshot.contextPct?.let { clampPct(it) }
        val status = snapshot.status
        // A pending prompt always wins: it is the one thing the user can act on.
        if (status == "AWAITING_PERMISSION") {
            val prompt = snapshot.permissionPrompt
            val id = snapshot.permissionPromptId
            return TileState.Awaiting(
                promptPreview = promptPreview(prompt),
                promptId = id,
                quickActions = !id.isNullOrBlank() && !RiskClassifier.isRisky(prompt) && fullyVisible(prompt),
            )
        }
        // A blocker only means something while the wrapper is alive; when it
        // is offline the blocker may be stale and "Sin conexión" is the truth.
        if (snapshot.blocked && (status == "IDLE" || status == "RUNNING")) {
            return TileState.Blocked(snapshot.blockerHint?.firstLine())
        }
        return when (status) {
            "IDLE" -> {
                val ok = snapshot.outcomeOk
                if (ok != null && isFresh(snapshot.outcomeTs, nowMs)) {
                    TileState.Done(ok, snapshot.headline?.firstLine(), pct)
                } else {
                    TileState.Idle(snapshot.dailyTokens ?: 0L, pct)
                }
            }
            "RUNNING" -> TileState.Running(
                activity = snapshot.activity?.firstLine(),
                contextPct = pct,
                step = snapshot.progressStep?.takeIf { it > 0 }?.toInt(),
                stepLabel = snapshot.progressLabel?.takeIf { it.isNotBlank() },
            )
            else -> TileState.Offline
        }
    }

    // ts <= 0: the wrapper didn't stamp it, so its age can't be judged; show it.
    // A ts in the future (Mac clock ahead of the watch) counts as fresh.
    private fun isFresh(ts: Long, nowMs: Long): Boolean = ts <= 0 || nowMs - ts <= DONE_FRESH_MS

    fun clampPct(raw: Double): Int? =
        if (raw.isNaN()) null else raw.coerceIn(0.0, 100.0).toInt()

    /**
     * True when the tile preview shows the whole prompt. Allow/Deny chips only
     * appear then: nothing gets approved from the tile without being readable
     * there. Longer prompts get "Abrir" for the full permission screen.
     */
    fun fullyVisible(prompt: String?): Boolean {
        val text = prompt.orEmpty()
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        return lines.isNotEmpty() && lines.size <= 2 && text.trim().length <= TILE_PROMPT_MAX_CHARS
    }

    const val TILE_PROMPT_MAX_CHARS = 90

    /** First two non-blank lines of the prompt, trimmed. */
    fun promptPreview(prompt: String?): String =
        prompt.orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(2)
            .joinToString("\n")

    /** 1234567 → "1,2 M", 45200 → "45 k", 4520 → "4,5 k", 812 → "812". Spanish decimal comma. */
    fun formatTokens(tokens: Long): String {
        val t = tokens.coerceAtLeast(0)
        return when {
            t >= 1_000_000 -> String.format(Locale.US, "%.1f M", t / 1_000_000.0).replace('.', ',')
            t >= 10_000 -> String.format(Locale.US, "%.0f k", t / 1_000.0)
            t >= 1_000 -> String.format(Locale.US, "%.1f k", t / 1_000.0).replace('.', ',')
            else -> t.toString()
        }
    }

    /** Spanish state word for the SHORT_TEXT complication. */
    fun stateWord(state: TileState): StateWord = when (state) {
        TileState.SignedOut -> StateWord.OPEN
        TileState.NoSignal -> StateWord.NO_SIGNAL
        TileState.Offline -> StateWord.MAC_OFFLINE
        is TileState.Idle -> StateWord.READY
        is TileState.Done -> if (state.ok) StateWord.READY else StateWord.FAILED
        is TileState.Running -> StateWord.WORKING
        is TileState.Awaiting -> StateWord.PERMISSION
        is TileState.Blocked -> StateWord.MAC
    }

    /** Waiting on the user (permission) or on the Mac (blocker): show the "!" glyph. */
    fun needsAttention(state: TileState): Boolean =
        state is TileState.Awaiting || state is TileState.Blocked

    /** Context % for the RANGED_VALUE complication and the tile arc, or null when unknown. */
    fun contextPct(state: TileState): Int? = when (state) {
        is TileState.Idle -> state.contextPct
        is TileState.Running -> state.contextPct
        is TileState.Done -> state.contextPct
        else -> null
    }

    private fun String.firstLine(): String? =
        lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
}

// Clickable ids for the tile's Permitir / Rechazar chips. Each render gets a
// fresh nonce so a tap is handled exactly once even if the system repeats
// lastClickableId on a later refresh.
object TileClicks {
    private const val ALLOW = "allow:"
    private const val DENY = "deny:"

    data class Click(val allow: Boolean, val promptId: String)

    fun encode(allow: Boolean, promptId: String, nonce: Long): String =
        (if (allow) ALLOW else DENY) + promptId + "|" + nonce

    fun decode(id: String?): Click? {
        if (id.isNullOrEmpty() || !id.contains('|')) return null
        val allow = when {
            id.startsWith(ALLOW) -> true
            id.startsWith(DENY) -> false
            else -> return null
        }
        val promptId = id.substringAfter(':').substringBeforeLast('|')
        return if (promptId.isBlank()) null else Click(allow, promptId)
    }
}

// Decides whether a permission prompt is safe enough to answer from the tile
// (or a notification action) without opening the full review screen.
// Conservative on purpose: anything we can't read, or that matches a
// destructive / remote-code pattern, is risky and only gets "Abrir".
object RiskClassifier {
    private val patterns: List<Regex> = listOf(
        """(^|[\s;&|(`'"])rm(\s|$)""",              // rm, rm -rf
        """\bgit\s+push\b""",
        """--force\b""",
        """\bgit\s+reset\s+--hard\b|\breset\s+--hard\b""",
        """\bsudo\b""",
        """\bchmod\b""",
        """\bchown\b""",
        """\b(curl|wget)\b[^\n]*\|\s*(sudo\s+)?(ba|z)?sh\b""", // curl ... | sh
        """\bkill\s+-(9|kill|sigkill)\b""",
        """\bkillall\b|\bpkill\b""",
        """\bmkfs\b|\bdd\s+if=""",
        """\bgit\s+clean\s+-[a-z]*f""",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    // Union with the full-screen classifier so the tile is never more
    // permissive than the permission screen (which also catches writes
    // outside the project and unreadable prompts).
    fun isRisky(prompt: String?): Boolean {
        if (prompt.isNullOrBlank()) return true
        if (classifyRisk(prompt) == Risk.RISKY) return true
        return patterns.any { it.containsMatchIn(prompt) }
    }
}
