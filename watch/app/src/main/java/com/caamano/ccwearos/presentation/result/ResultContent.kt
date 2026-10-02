package com.caamano.ccwearos.presentation.result

import com.caamano.ccwearos.data.RunOutcome
import com.caamano.ccwearos.data.TaskKind

// Pure helpers that turn the raw (already sanitized) response into what the
// Result page shows. Kept free of Compose so they're JVM-testable.

/** The TL;DR for the card, and the markdown body below it. */
data class ResultText(val tldr: String?, val body: String)

private val TLDR_LINE = Regex(
    "^\\s*\\*{0,2}_{0,2}TL;?DR:?\\*{0,2}_{0,2}\\s*[:—–-]?\\s*(.*)$",
    RegexOption.IGNORE_CASE,
)
private val FOLLOWUPS_HEADER = Regex(
    "^\\s*[#>]*\\s*\\*{0,2}(?:Followups?|Sugerencias|Sigamos|What\\s+next)[\\s*:]*$",
    RegexOption.IGNORE_CASE,
)
private val BULLET_LINE = Regex("^\\s*(?:[-*•·]|\\d+[.)])\\s+.+$")
private val BLOCK_SYNTAX = Regex("(?m)^\\s*(?:#|[-*+•]\\s|\\d+[.)]\\s|>|```|~~~|\\|)")

/** Strips inline markdown markers from a one-line TL;DR. */
internal fun plainInline(text: String): String =
    MarkdownBlocks.parseInline(text).plainText().trim()

/**
 * Splits [clean] into TL;DR + body:
 *  - The first `**TL;DR:** …` line is removed from the body. The card text
 *    prefers the wrapper's [headline], else that line.
 *  - Claude's trailing `Sugerencias:` / `Followups:` block is removed (the
 *    chips already show it).
 *  - With no TL;DR at all, a short single-paragraph answer becomes the TL;DR
 *    so the card is never empty for "Listo: tests en verde".
 */
fun prepareResultText(clean: String, headline: String?): ResultText {
    val lines = clean.lines().toMutableList()

    var tldrFromText: String? = null
    val firstIdx = lines.indexOfFirst { it.isNotBlank() }
    if (firstIdx >= 0) {
        TLDR_LINE.find(lines[firstIdx])?.let { m ->
            tldrFromText = m.groupValues[1].trim().ifBlank { null }
            lines.removeAt(firstIdx)
        }
    }

    stripFollowupsBlock(lines)
    var body = lines.joinToString("\n").trim()

    var tldr = headline?.trim()?.ifBlank { null }?.let(::plainInline)
        ?: tldrFromText?.let(::plainInline)?.ifBlank { null }

    if (tldr == null && body.isNotEmpty() && body.length <= 160 &&
        !body.contains("\n\n") && !BLOCK_SYNTAX.containsMatchIn(body)
    ) {
        tldr = plainInline(body.replace('\n', ' '))
        body = ""
    }
    return ResultText(tldr, body)
}

private fun stripFollowupsBlock(lines: MutableList<String>) {
    val header = lines.indexOfLast { FOLLOWUPS_HEADER.matches(it) }
    if (header < 0) return
    var end = header + 1
    var sawBullet = false
    while (end < lines.size) {
        val l = lines[end]
        when {
            BULLET_LINE.matches(l) -> sawBullet = true
            l.isBlank() -> if (sawBullet) break
            else -> break
        }
        end++
    }
    if (!sawBullet) return
    for (k in end - 1 downTo header) lines.removeAt(k)
}

/** What the status line says. Driven by the run outcome, never by text. */
enum class ResultStatus { DONE, FAILED, INFO, NONE }

fun resultStatus(outcome: RunOutcome?, taskKind: TaskKind?): ResultStatus = when {
    outcome != null && outcome.ok -> ResultStatus.DONE
    outcome != null -> ResultStatus.FAILED
    taskKind != TaskKind.ACTION -> ResultStatus.INFO
    else -> ResultStatus.NONE
}

/** Tool families, mapped to a Spanish label in the UI. */
enum class ToolKind { COMMAND, EDIT, READ, SEARCH, WEB, AGENT, OTHER }

fun toolKind(tool: String): ToolKind = when (tool.filterNot { it.isWhitespace() }) {
    "Bash", "BashOutput", "KillShell" -> ToolKind.COMMAND
    "Edit", "Write", "MultiEdit", "NotebookEdit" -> ToolKind.EDIT
    "Read" -> ToolKind.READ
    "Grep", "Glob" -> ToolKind.SEARCH
    "WebFetch", "WebSearch" -> ToolKind.WEB
    "Task", "Agent" -> ToolKind.AGENT
    else -> ToolKind.OTHER
}

/** Shortens a tool argument for one watch line: paths keep their tail. */
fun shortToolArg(arg: String?, max: Int = 28): String? {
    val a = arg?.trim()?.replace(Regex("\\s+"), " ")?.ifBlank { null } ?: return null
    if (a.length <= max) return a
    if (!a.contains(' ') && a.contains('/')) {
        val tail = a.substringAfterLast('/')
        val short = "…/$tail"
        return if (short.length <= max) short else "…" + tail.takeLast(max - 1)
    }
    return a.take(max - 1).trimEnd() + "…"
}

/** Stable key for "a new response arrived" (reveal animation, effects). */
fun responseKey(headline: String?, response: String?): Int =
    31 * (headline?.hashCode() ?: 0) + (response?.hashCode() ?: 0)

/** Code block lines shown before "Ver código completo". */
const val CODE_PREVIEW_LINES = 8

/** Tool trail entries shown before "Ver los N pasos". */
const val TOOL_PREVIEW_COUNT = 5

/** Collapsed body budget in characters. */
const val BODY_BUDGET_CHARS = 600
