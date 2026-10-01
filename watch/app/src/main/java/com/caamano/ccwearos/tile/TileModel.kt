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
)

/** What the tile / complication renders. */
sealed class TileState {
    /** Anonymous auth missing: reads would be denied by the RTDB rules. */
    object SignedOut : TileState()

    /** RTDB unreachable from the watch (read timed out). */
    object NoSignal : TileState()

    object Offline : TileState()

    data class Idle(val dailyTokens: Long, val contextPct: Int?) : TileState()

    data class Running(val activity: String?, val contextPct: Int?) : TileState()

    data class Awaiting(
        val promptPreview: String,
        val promptId: String?,
        /** True when Permitir / Rechazar may be offered directly on the tile. */
        val quickActions: Boolean,
    ) : TileState()
}

object TileMapper {
    fun map(snapshot: TileSnapshot?, signedIn: Boolean): TileState {
        if (!signedIn) return TileState.SignedOut
        if (snapshot == null) return TileState.NoSignal
        val pct = snapshot.contextPct?.let { clampPct(it) }
        return when (snapshot.status) {
            "IDLE" -> TileState.Idle(snapshot.dailyTokens ?: 0L, pct)
            "RUNNING" -> TileState.Running(snapshot.activity?.firstLine(), pct)
            "AWAITING_PERMISSION" -> {
                val prompt = snapshot.permissionPrompt
                val id = snapshot.permissionPromptId
                TileState.Awaiting(
                    promptPreview = promptPreview(prompt),
                    promptId = id,
                    quickActions = !id.isNullOrBlank() && !RiskClassifier.isRisky(prompt) && fullyVisible(prompt),
                )
            }
            else -> TileState.Offline
        }
    }

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

    /** 1234567 → "1,2 M", 45200 → "45,2 k", 812 → "812". Spanish decimal comma. */
    fun formatTokens(tokens: Long): String {
        val t = tokens.coerceAtLeast(0)
        return when {
            t >= 1_000_000 -> String.format(Locale.US, "%.1f M", t / 1_000_000.0).replace('.', ',')
            t >= 10_000 -> String.format(Locale.US, "%.0f k", t / 1_000.0)
            t >= 1_000 -> String.format(Locale.US, "%.1f k", t / 1_000.0).replace('.', ',')
            else -> t.toString()
        }
    }

    /** ≤7-char label for a SHORT_TEXT complication. Idle shows context %. */
    fun shortLabel(state: TileState): String = when (state) {
        TileState.SignedOut -> "Abrir"
        TileState.NoSignal -> "--"
        TileState.Offline -> "Off"
        is TileState.Idle -> state.contextPct?.let { "$it%" } ?: "Listo"
        is TileState.Running -> "Activo"
        is TileState.Awaiting -> "Permiso"
    }

    /** Context % for the RANGED_VALUE complication, or null when unknown. */
    fun contextPct(state: TileState): Int? = when (state) {
        is TileState.Idle -> state.contextPct
        is TileState.Running -> state.contextPct
        else -> null
    }

    /** Screen-reader sentence for the complication. */
    fun description(state: TileState): String = when (state) {
        TileState.SignedOut -> "Abre la app para conectar"
        TileState.NoSignal -> "Sin señal"
        TileState.Offline -> "Sin conexión con tu Mac"
        is TileState.Idle -> "Claude listo" + (state.contextPct?.let { ", contexto $it por ciento" } ?: "")
        is TileState.Running -> "Claude trabajando"
        is TileState.Awaiting -> "Claude pide permiso"
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
// without opening the full review screen. Conservative on purpose: anything
// we can't read, or that matches a destructive / remote-code pattern, is
// risky and only gets the "Abrir" chip.
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
