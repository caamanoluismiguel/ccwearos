package com.caamano.ccwearos.presentation.permission

// Pure parsing + risk classification for the permission prompt text.
// No Android imports on purpose: everything here is plain Kotlin so it can be
// unit-tested on the JVM without Robolectric.

enum class Risk { NORMAL, RISKY }

/**
 * The wrapper sends `Tool: target` on the first line and an optional
 * description after it, e.g. `Bash: git push origin main\nPush the branch`.
 * Anything that doesn't look like that (older wrapper, free-form text) lands
 * in [description] with [tool] and [target] null, so the screen still shows
 * every character it was given.
 */
data class ParsedPrompt(
    val tool: String?,
    val target: String?,
    val description: String?,
)

private val TOOL_LINE = Regex("""^([A-Za-z][\w.\-]{0,63}):\s*(.*)$""")

fun parsePrompt(prompt: String?): ParsedPrompt {
    val text = prompt?.trim().orEmpty()
    if (text.isEmpty()) return ParsedPrompt(null, null, null)
    val firstLine = text.substringBefore('\n').trim()
    val rest = text.substringAfter('\n', missingDelimiterValue = "").trim().ifEmpty { null }
    val m = TOOL_LINE.matchEntire(firstLine)
        ?: return ParsedPrompt(tool = null, target = null, description = text)
    val tool = m.groupValues[1]
    val target = m.groupValues[2].trim().ifEmpty { null }
    return ParsedPrompt(tool = tool, target = target, description = rest)
}

/**
 * The description line worth showing, or null when it adds nothing: blank,
 * or just the command again (same words, ignoring case, quotes, backticks and
 * punctuation, or one containing the other).
 */
fun visibleDescription(parsed: ParsedPrompt): String? {
    val desc = parsed.description?.trim()?.ifEmpty { null } ?: return null
    val target = parsed.target ?: return desc
    val d = normalizeForCompare(desc)
    val t = normalizeForCompare(target)
    if (d.isEmpty()) return null
    if (d == t || d in t || (t.isNotEmpty() && t in d && d.length <= t.length + 12)) return null
    return desc
}

private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

private fun normalizeForCompare(s: String): String =
    s.lowercase().replace(NON_WORD, " ").trim()

// Shell patterns that can destroy work, escalate privileges or rewrite shared
// history. Matched against the whole prompt text. Word boundaries keep
// "perform", "git add" and friends from tripping `rm`/`dd`. False positives are
// acceptable (they only cost a 1.2s hold); false negatives are not.
private val RISKY_SHELL: List<Regex> = listOf(
    Regex("""\brm\s"""), // rm file, rm -rf, rm -- …
    Regex("""\bgit\s+push\b"""),
    Regex("""\bgit\s+reset\s+--hard\b"""),
    Regex("""\b(push|reset)\b[^\n]*(--force\b|--force-with-lease\b|\s-f\b)"""),
    Regex("""\bsudo\b"""),
    Regex("""\bchmod\b"""),
    Regex("""\bchown\b"""),
    Regex("""\bmkfs(\.\w+)?\b"""),
    Regex("""\bdd\s"""),
    Regex("""\b(curl|wget)\b[^\n|]*\|\s*(sudo\s+)?(ba|z|da|k)?sh\b"""),
    // Redirecting into a device. /dev/null, stdout and stderr are routine.
    Regex(""">\s*/dev/(?!null\b|stdout\b|stderr\b|tty\b)"""),
    Regex("""\bdrop\s+(table|database)\b""", RegexOption.IGNORE_CASE),
    Regex("""\bkill\s+(-9|-KILL|-SIGKILL)\b"""),
)

private val WRITE_TOOLS = setOf("Edit", "Write", "MultiEdit", "NotebookEdit")

// Where code normally lives. A write anywhere else (dotfiles, /etc, ~/Library)
// is treated as leaving the project.
private val PROJECT_ROOT = Regex(
    """^(/(Users|home)/[^/]+|~)/(projects|Projects|code|Code|dev|Dev|Developer|src|repos|workspace|work|git|github|GitHub)/""",
)
private val TEMP_ROOT = Regex("""^/(tmp|private/tmp|private/var/folders|var/folders)/""")

private fun isWriteOutsideProject(parsed: ParsedPrompt): Boolean {
    if (parsed.tool !in WRITE_TOOLS) return false
    val path = parsed.target?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: return false
    if (path.startsWith("~/.")) return true
    val absolute = path.startsWith("/") || path.startsWith("~/")
    if (!absolute) return false // relative paths are resolved inside the project
    return !PROJECT_ROOT.containsMatchIn(path) && !TEMP_ROOT.containsMatchIn(path)
}

// The wrapper sends this when it saw a permission box but couldn't read the
// command (wrapper/src/parser.ts PERMISSION_DETAILS_UNAVAILABLE). Approving
// something unseen always takes the deliberate hold.
const val DETAILS_UNAVAILABLE_MARKER = "details not visible"

fun classifyRisk(prompt: String?): Risk {
    if (prompt.isNullOrBlank()) return Risk.NORMAL
    if (prompt.contains(DETAILS_UNAVAILABLE_MARKER, ignoreCase = true)) return Risk.RISKY
    if (RISKY_SHELL.any { it.containsMatchIn(prompt) }) return Risk.RISKY
    if (isWriteOutsideProject(parsePrompt(prompt))) return Risk.RISKY
    return Risk.NORMAL
}
