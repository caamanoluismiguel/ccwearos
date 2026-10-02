package com.caamano.ccwearos.presentation.result

// Pure Kotlin markdown → blocks for a 480px round screen. Not CommonMark:
// it covers what Claude actually writes (headings, lists, fences, quotes,
// pipe tables, rules, bold/italic/code/links) and degrades every unknown or
// half-streamed construct to plain text instead of failing.

/** One run of inline text with its styling. Links are display-only. */
data class Span(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val link: Boolean = false,
)

sealed interface Block {
    data class Heading(val level: Int, val spans: List<Span>) : Block
    data class Paragraph(val spans: List<Span>) : Block

    /**
     * A list item. [marker] is "•" or "3." ; [level] is 0 or 1 (deeper
     * nesting is clamped to 1). The renderer hangs wrapped lines under the
     * text, not under the marker.
     */
    data class ListItem(val marker: String, val ordered: Boolean, val level: Int, val spans: List<Span>) : Block
    data class Code(val lang: String?, val lines: List<String>) : Block
    data class Quote(val spans: List<Span>) : Block

    /** Each row is a list of (column header, cell value) pairs. */
    data class Table(val rows: List<List<Pair<String, String>>>) : Block

    /** Too many columns to read on a watch. */
    data class TableTooWide(val columns: Int, val rows: Int) : Block
    data object Rule : Block
}

/** Plain text of a span list (for length budgets and accessibility). */
fun List<Span>.plainText(): String = joinToString("") { it.text }

/** Rough reading length of a block, used for the collapsed-body budget. */
fun Block.textLength(): Int = when (this) {
    is Block.Heading -> spans.plainText().length
    is Block.Paragraph -> spans.plainText().length
    is Block.ListItem -> spans.plainText().length + 2
    is Block.Code -> lines.sumOf { it.length + 1 }
    is Block.Quote -> spans.plainText().length
    is Block.Table -> rows.sumOf { r -> r.sumOf { it.first.length + it.second.length + 2 } }
    is Block.TableTooWide -> 30
    Block.Rule -> 1
}

/**
 * How many leading blocks fit in a collapsed view of about [budget]
 * characters. Always at least one block; returns [blocks].size when
 * everything fits.
 */
fun collapsedBlockCount(blocks: List<Block>, budget: Int = 600): Int {
    var used = 0
    blocks.forEachIndexed { i, b ->
        used += b.textLength()
        if (used > budget) return maxOf(1, i)
    }
    return blocks.size
}

object MarkdownBlocks {

    const val MAX_TABLE_COLUMNS = 4

    private val HEADING = Regex("^\\s{0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val FENCE = Regex("^\\s{0,3}(`{3,}|~{3,})\\s*([\\w+#.-]*)")
    private val RULE = Regex("^\\s{0,3}([-*_])(?:\\s*\\1){2,}\\s*$")
    private val BULLET = Regex("^(\\s*)[-*+•]\\s+(.*)$")
    private val ORDERED = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")
    private val QUOTE = Regex("^\\s{0,3}>\\s?(.*)$")
    private val TABLE_SEP = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")
    private val HTML_ONLY = Regex("^\\s*(<[^>]+>\\s*)+$")

    fun parse(markdown: String?): List<Block> {
        if (markdown.isNullOrBlank()) return emptyList()
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = mutableListOf<Block>()
        val para = mutableListOf<String>()

        fun flushPara() {
            if (para.isEmpty()) return
            val spans = parseInline(para.joinToString(" ") { it.trim() })
            if (spans.plainText().isNotBlank()) out += Block.Paragraph(spans)
            para.clear()
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]

            if (line.isBlank()) {
                flushPara(); i++; continue
            }

            val fenceMatch = FENCE.find(line)
            if (fenceMatch != null) {
                val m = fenceMatch
                flushPara()
                val fence = m.groupValues[1]
                val lang = m.groupValues[2].ifBlank { null }
                val body = mutableListOf<String>()
                i++
                while (i < lines.size) {
                    val l = lines[i]
                    if (l.trimStart().startsWith(fence.take(3)) && l.trim().all { it == fence[0] }) {
                        i++
                        break
                    }
                    body += l.trimEnd()
                    i++
                }
                // Unterminated fence (streaming): everything to EOF is code.
                while (body.isNotEmpty() && body.last().isBlank()) body.removeAt(body.lastIndex)
                out += Block.Code(lang, body)
                continue
            }

            val headingMatch = HEADING.find(line)
            if (headingMatch != null) {
                val m = headingMatch
                flushPara()
                val spans = parseInline(m.groupValues[2])
                if (spans.plainText().isNotBlank()) {
                    out += Block.Heading(m.groupValues[1].length, spans)
                }
                i++
                continue
            }

            if (RULE.matches(line)) {
                flushPara(); out += Block.Rule; i++; continue
            }

            if (isTableStart(lines, i)) {
                flushPara()
                val header = splitRow(lines[i])
                i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                    rows += splitRow(lines[i])
                    i++
                }
                out += buildTable(header, rows)
                continue
            }

            if (QUOTE.matches(line)) {
                flushPara()
                val quoted = mutableListOf<String>()
                while (i < lines.size) {
                    val q = QUOTE.find(lines[i]) ?: break
                    // Nested `> >` flattens to one level.
                    quoted += q.groupValues[1].trimStart('>', ' ')
                    i++
                }
                val spans = parseInline(quoted.filter { it.isNotBlank() }.joinToString(" ") { it.trim() })
                if (spans.plainText().isNotBlank()) out += Block.Quote(spans)
                continue
            }

            val bullet = BULLET.find(line)
            val ordered = if (bullet == null) ORDERED.find(line) else null
            if (bullet != null || ordered != null) {
                flushPara()
                val indent = (bullet ?: ordered)!!.groupValues[1].replace("\t", "    ").length
                val level = if (indent >= 2) 1 else 0
                val marker = if (bullet != null) "•" else "${ordered!!.groupValues[2]}."
                val text = StringBuilder(if (bullet != null) bullet.groupValues[2] else ordered!!.groupValues[3])
                i++
                // Lazy continuation: indented lines that aren't a new block.
                while (i < lines.size) {
                    val next = lines[i]
                    if (next.isBlank() || !next.startsWith(" ") && !next.startsWith("\t")) break
                    if (BULLET.matches(next) || ORDERED.matches(next) || FENCE.containsMatchIn(next) ||
                        HEADING.matches(next) || QUOTE.matches(next)
                    ) break
                    text.append(' ').append(next.trim())
                    i++
                }
                val spans = parseInline(text.toString())
                out += Block.ListItem(marker, ordered != null, level, spans)
                continue
            }

            if (HTML_ONLY.matches(line)) {
                flushPara(); i++; continue
            }

            para += line
            i++
        }
        flushPara()
        return out
    }

    private fun isTableStart(lines: List<String>, i: Int): Boolean {
        if (i + 1 >= lines.size) return false
        val head = lines[i]
        val sep = lines[i + 1]
        return head.contains('|') && sep.contains('-') && TABLE_SEP.matches(sep) &&
            (sep.contains('|') || head.trim().startsWith("|"))
    }

    private fun splitRow(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.drop(1)
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)
        val cells = mutableListOf<String>()
        val cur = StringBuilder()
        var k = 0
        while (k < s.length) {
            val c = s[k]
            if (c == '\\' && k + 1 < s.length && s[k + 1] == '|') {
                cur.append('|'); k += 2; continue
            }
            if (c == '|') {
                cells += cur.toString().trim(); cur.clear()
            } else {
                cur.append(c)
            }
            k++
        }
        cells += cur.toString().trim()
        return cells.map { parseInline(it).plainText().trim() }
    }

    private fun buildTable(header: List<String>, rows: List<List<String>>): Block {
        val columns = header.size
        if (columns > MAX_TABLE_COLUMNS) return Block.TableTooWide(columns, rows.size)
        val pairs = rows.map { row ->
            header.mapIndexedNotNull { c, h ->
                val value = row.getOrNull(c).orEmpty()
                if (value.isBlank()) null else (h.ifBlank { "Columna ${c + 1}" } to value)
            }
        }.filter { it.isNotEmpty() }
        return Block.Table(pairs)
    }

    // ─── Inline ──────────────────────────────────────────────────────────

    private val HTML_TAG = Regex("</?[A-Za-z][A-Za-z0-9-]*(?:\\s[^<>]*)?/?>")
    private val BR = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val IMAGE = Regex("!\\[[^\\]\\n]*]\\([^)\\n]*\\)")
    private val AUTOLINK = Regex("<(https?://[^>\\s]+)>")

    /**
     * Inline markdown → spans. Unterminated markers stay as literal text, so
     * a half-streamed "**negr" renders as "**negr", never crashes or eats
     * the rest of the paragraph.
     */
    fun parseInline(text: String): List<Span> {
        var s = BR.replace(text, " ")
        s = IMAGE.replace(s, "")
        s = AUTOLINK.replace(s) { "[${it.groupValues[1]}](${it.groupValues[1]})" }
        s = HTML_TAG.replace(s, "")
        s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&amp;", "&")
        val out = mutableListOf<Span>()
        parseRange(s, bold = false, italic = false, link = false, out = out)
        return mergeSpans(out)
    }

    private fun parseRange(s: String, bold: Boolean, italic: Boolean, link: Boolean, out: MutableList<Span>) {
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotEmpty()) {
                out += Span(buf.toString(), bold = bold, italic = italic, link = link); buf.clear()
            }
        }

        var i = 0
        while (i < s.length) {
            val c = s[i]

            // Backslash escape.
            if (c == '\\' && i + 1 < s.length && s[i + 1] in ESCAPABLE) {
                buf.append(s[i + 1]); i += 2; continue
            }

            // Inline code: `x` or ``x``.
            if (c == '`') {
                val ticks = s.countRun(i, '`')
                val delim = "`".repeat(ticks)
                val end = s.indexOf(delim, i + ticks)
                if (end != -1) {
                    flush()
                    val code = s.substring(i + ticks, end).trim()
                    if (code.isNotEmpty()) out += Span(code, bold = bold, italic = italic, code = true, link = link)
                    i = end + ticks
                    continue
                }
                buf.append(delim); i += ticks; continue
            }

            // Link: [text](url) → text ↗ (not tappable on a watch).
            if (c == '[' && !link) {
                val close = findClosingBracket(s, i)
                if (close != -1 && close + 1 < s.length && s[close + 1] == '(') {
                    val paren = s.indexOf(')', close + 2)
                    if (paren != -1) {
                        flush()
                        val label = s.substring(i + 1, close).ifBlank { s.substring(close + 2, paren) }
                        parseRange(label, bold, italic, link = true, out = out)
                        out += Span(" ↗", bold = bold, italic = italic, link = true)
                        i = paren + 1
                        continue
                    }
                }
            }

            // Bold: ** or __.
            if ((c == '*' || c == '_') && i + 1 < s.length && s[i + 1] == c && canOpen(s, i, 2)) {
                val delim = "$c$c"
                val end = findCloser(s, i + 2, delim)
                if (end != -1) {
                    flush()
                    parseRange(s.substring(i + 2, end), bold = true, italic = italic, link = link, out = out)
                    i = end + 2
                    continue
                }
            }

            // Italic: * or _ (underscore only at word boundaries: snake_case stays).
            if ((c == '*' || c == '_') && canOpen(s, i, 1) && !(i + 1 < s.length && s[i + 1] == c)) {
                val end = findCloser(s, i + 1, c.toString())
                if (end != -1) {
                    flush()
                    parseRange(s.substring(i + 1, end), bold = bold, italic = true, link = link, out = out)
                    i = end + 1
                    continue
                }
            }

            // Strikethrough ~~x~~ → plain text.
            if (c == '~' && i + 1 < s.length && s[i + 1] == '~') {
                val end = s.indexOf("~~", i + 2)
                if (end != -1) {
                    flush()
                    parseRange(s.substring(i + 2, end), bold, italic, link, out)
                    i = end + 2
                    continue
                }
            }

            buf.append(c)
            i++
        }
        flush()
    }

    private const val ESCAPABLE = "\\`*_{}[]()#+-.!|>~"

    private fun String.countRun(from: Int, ch: Char): Int {
        var n = 0
        while (from + n < length && this[from + n] == ch) n++
        return n
    }

    private fun findClosingBracket(s: String, open: Int): Int {
        var depth = 0
        for (k in open until s.length) {
            when (s[k]) {
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) return k }
                '\n' -> return -1
            }
        }
        return -1
    }

    /** An opener must be followed by non-space; `_` must not sit inside a word. */
    private fun canOpen(s: String, i: Int, len: Int): Boolean {
        val after = s.getOrNull(i + len) ?: return false
        if (after.isWhitespace()) return false
        if (s[i] == '_') {
            val before = s.getOrNull(i - 1)
            if (before != null && before.isLetterOrDigit()) return false
        }
        return true
    }

    private fun findCloser(s: String, from: Int, delim: String): Int {
        var k = from
        while (k < s.length) {
            val idx = s.indexOf(delim, k)
            if (idx == -1) return -1
            val before = s.getOrNull(idx - 1)
            val after = s.getOrNull(idx + delim.length)
            val single = delim.length == 1
            val okBefore = idx > from && before != null && !before.isWhitespace()
            // A single '*' that is half of a '**' is not an italic closer.
            val partOfDouble = single && (after == delim[0] || before == delim[0])
            val underscoreInWord = delim[0] == '_' && after != null && after.isLetterOrDigit()
            // "***" closing "**a *b***": the bold closer is the LAST two.
            if (okBefore && !single && !underscoreInWord && after == delim[0]) return idx + 1
            if (okBefore && !partOfDouble && !underscoreInWord) return idx
            k = idx + delim.length
        }
        return -1
    }

    private fun mergeSpans(spans: List<Span>): List<Span> {
        val out = mutableListOf<Span>()
        for (sp in spans) {
            if (sp.text.isEmpty()) continue
            val last = out.lastOrNull()
            if (last != null && last.bold == sp.bold && last.italic == sp.italic &&
                last.code == sp.code && last.link == sp.link
            ) {
                out[out.lastIndex] = last.copy(text = last.text + sp.text)
            } else {
                out += sp
            }
        }
        // Collapse whitespace runs left behind by stripped tags/images.
        return out.mapIndexed { idx, sp ->
            if (sp.code) {
                sp
            } else {
                var t = sp.text.replace(Regex("[ \\t]{2,}"), " ")
                if (idx == 0) t = t.trimStart()
                if (idx == out.lastIndex) t = t.trimEnd()
                sp.copy(text = t)
            }
        }.filter { it.text.isNotEmpty() }
    }
}
