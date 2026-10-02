package com.caamano.ccwearos.presentation.result

// Pure Kotlin (no Android imports) so it runs in JVM unit tests.
//
// The wrapper scrapes Claude's answer out of a pty. Most of the time that is
// clean markdown, but when Claude Code shows an interactive TUI (the "Do you
// trust this folder?" dialog, a numbered menu) the scrape can capture the
// dialog instead of an answer. Rendering that on the wrist is worse than
// rendering nothing, so the Result page asks this sanitizer first:
//   - strip terminal escapes and control characters,
//   - and if what's left still smells like a TUI, report Blocked so the shell
//     shows BlockedScreen ("Claude necesita algo en tu Mac").

sealed interface SanitizeResult {
    data class Clean(val text: String) : SanitizeResult
    data object Blocked : SanitizeResult
}

object ResponseSanitizer {

    /** How many distinct TUI signals make a response junk. */
    const val BLOCK_THRESHOLD = 2

    // Cursor-forward (`ESC [ n C`) is how TUIs draw spaces between words.
    // Turned into a single space BEFORE the generic CSI strip so "Accessing
    // workspace" doesn't collapse into "Accessingworkspace".
    private val CURSOR_FORWARD = Regex("\u001B\\[\\d*C")

    // Cursor moves to a new line / absolute position also separate words.
    private val CURSOR_LINE = Regex("\u001B\\[\\d*(?:;\\d*)?[EFHf]")

    // CSI: ESC [ params intermediates final.
    private val CSI = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")

    // OSC: ESC ] ... (BEL | ESC \). Unterminated OSC runs to end of line.
    private val OSC = Regex("\u001B\\][^\u0007\u001B\n]*(?:\u0007|\u001B\\\\)?")

    // Charset designation: ESC ( B, ESC ) 0, etc.
    private val CHARSET = Regex("\u001B[()*+\\-./][ -~]")

    // Any other two-byte escape (ESC 7, ESC =, ESC M...), then lone ESC.
    private val ESC_OTHER = Regex("\u001B[ -~]?")

    // C0 controls except \t and \n, plus DEL and C1 controls.
    private val CONTROLS = Regex("[\u0000-\u0008\u000B-\u001F\u007F\u0080-\u009F]")

    private val BOX_RUN = Regex("[─-╿]{3,}")
    private val MENU = Regex("[12]\\.\\s*(?:Yes|No)(?![a-z])")
    private val SELECT_LINE = Regex("(?m)^\\s*(?:❯\\s*)?Select\\b")

    /** Strips escapes and control characters, keeps \n and \t. */
    fun stripControls(raw: String): String {
        var s = raw
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        s = CURSOR_FORWARD.replace(s, " ")
        s = CURSOR_LINE.replace(s, "\n")
        s = OSC.replace(s, "")
        s = CSI.replace(s, "")
        s = CHARSET.replace(s, "")
        s = ESC_OTHER.replace(s, "")
        s = CONTROLS.replace(s, "")
        // Non-breaking spaces from TUIs read as plain spaces.
        s = s.replace(' ', ' ')
        return s
            .lines()
            .joinToString("\n") { it.trimEnd() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    /**
     * Distinct TUI signals present in [text]. Phrases are matched with all
     * whitespace removed, because a scraped TUI often lost its spaces
     * ("Entertoconfirm·Esctocancel").
     */
    fun junkSignals(text: String): Set<String> {
        val squashed = text.filterNot { it.isWhitespace() }.lowercase()
        val out = mutableSetOf<String>()
        if (BOX_RUN.containsMatchIn(text)) out += "box"
        if (text.contains('❯')) out += "prompt"
        if (MENU.containsMatchIn(text)) out += "menu"
        if ("doyoutrust" in squashed || "itrustthisfolder" in squashed) out += "trust"
        if ("entertoconfirm" in squashed) out += "enter-confirm"
        if ("esctocancel" in squashed) out += "esc-cancel"
        if ("pressenter" in squashed) out += "press-enter"
        if (SELECT_LINE.containsMatchIn(text)) out += "select"
        return out
    }

    fun sanitize(raw: String?): SanitizeResult {
        if (raw.isNullOrBlank()) return SanitizeResult.Clean("")
        val text = stripControls(raw)
        return if (junkSignals(text).size >= BLOCK_THRESHOLD) {
            SanitizeResult.Blocked
        } else {
            SanitizeResult.Clean(text)
        }
    }
}
