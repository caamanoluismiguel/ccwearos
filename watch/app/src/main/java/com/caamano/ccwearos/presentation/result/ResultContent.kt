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

// ─── Minimal first screenful ─────────────────────────────────────────────────

/** Bullets shown on the first screenful, before "Ver detalle". */
const val SUMMARY_BULLETS = 3

/** Follow-up chips on Resultado: at most this many, each label this short. */
const val MAX_CHIPS = 2
const val CHIP_LABEL_MAX = 24

/**
 * What Resultado shows before "Ver detalle": the TL;DR (or, without one, the
 * first paragraph) and at most [SUMMARY_BULLETS] top-level list items, only
 * when the answer has a list. [hasDetail]: "Ver detalle" has something more.
 */
data class FirstScreen(val tldr: String?, val bullets: List<String>, val hasDetail: Boolean)

fun firstScreenful(prepared: ResultText, blocks: List<Block>, toolCount: Int): FirstScreen {
    val tldr = prepared.tldr
        ?: blocks.firstNotNullOfOrNull { (it as? Block.Paragraph)?.spans?.plainText()?.trim()?.ifBlank { null } }
    val bullets = blocks
        .filterIsInstance<Block.ListItem>()
        .filter { it.level == 0 }
        .map { it.spans.plainText().trim() }
        .filter { it.isNotEmpty() }
        .take(SUMMARY_BULLETS)
    return FirstScreen(tldr = tldr, bullets = bullets, hasDetail = blocks.isNotEmpty() || toolCount > 0)
}

/** Claude's chips: at most [MAX_CHIPS], label ellipsized to [CHIP_LABEL_MAX], full text sent. */
fun chipLabels(followups: List<String>): List<Pair<String, String>> =
    followups
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .take(MAX_CHIPS)
        .map { full -> ellipsize(full, CHIP_LABEL_MAX) to full }

internal fun ellipsize(text: String, max: Int): String =
    if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"

/** Response turned into what Resultado (and Inicio's done line) render. */
data class PreparedResult(val blocked: Boolean, val text: ResultText, val blocks: List<Block>)

/**
 * One pipeline for the raw RTDB strings: sanitizer (TUI junk → blocked),
 * then [cleanAnswer] (chrome lines, doubled answers), then TL;DR split and
 * markdown blocks.
 */
fun prepareResult(headline: String?, response: String?): PreparedResult {
    val sanitized = ResponseSanitizer.sanitize(response)
    if (sanitized is SanitizeResult.Blocked) return PreparedResult(true, ResultText(null, ""), emptyList())
    val clean = cleanAnswer((sanitized as SanitizeResult.Clean).text)
    val safeHeadline = (ResponseSanitizer.sanitize(headline) as? SanitizeResult.Clean)
        ?.text?.let(::cleanAnswer)?.ifBlank { null }
    val text = prepareResultText(clean, safeHeadline)
    return PreparedResult(false, text, MarkdownBlocks.parse(text.body))
}

/** The TL;DR Inicio shows under "¡Listo!"; null when there is none (or junk). */
fun resultTldr(headline: String?, response: String?): String? {
    val p = prepareResult(headline, response)
    if (p.blocked) return null
    return firstScreenful(p.text, p.blocks, toolCount = 0).tldr
}

// ─── Defense in depth: TUI chrome the sanitizer let through ─────────────────

private val RULE_LINE = Regex("^[▔▁─━═\\-_\\s]{3,}$")
private val SPINNER_START = Regex("^[✻✽✶✳✢]")
private val CHROME_PHRASES = listOf(
    "churned for",
    "don't show again",
    "dont show again",
    "teach auto mode",
    "esc to interrupt",
    "shift+tab to cycle",
    "auto-accept edits",
)

/** A line that is Claude Code TUI chrome, never part of an answer. */
fun isTuiChrome(line: String): Boolean {
    val t = line.trim()
    if (t.isEmpty()) return false
    if (SPINNER_START.containsMatchIn(t)) return true
    if (t.length >= 3 && RULE_LINE.matches(t)) return true
    val lower = t.lowercase().replace('’', '\'')
    return CHROME_PHRASES.any { it in lower }
}

/**
 * Drops TUI chrome lines and, when the text is the same answer twice in a
 * row (a scrape that caught a redraw), keeps one copy. Runs on text the
 * sanitizer already called Clean.
 */
fun cleanAnswer(text: String): String {
    val kept = text.lines().filterNot(::isTuiChrome)
    return dedupeRepeatedHalf(kept).joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
}

/** If the non-blank lines are A followed by A again, returns the first A. */
internal fun dedupeRepeatedHalf(lines: List<String>): List<String> {
    val idx = lines.indices.filter { lines[it].isNotBlank() }
    val n = idx.size
    if (n < 2 || n % 2 != 0) return lines
    val half = n / 2
    for (k in 0 until half) {
        if (lines[idx[k]].trim() != lines[idx[k + half]].trim()) return lines
    }
    return lines.subList(0, idx[half - 1] + 1)
}
