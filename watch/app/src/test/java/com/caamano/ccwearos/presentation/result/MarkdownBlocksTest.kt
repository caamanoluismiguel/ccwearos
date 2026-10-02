package com.caamano.ccwearos.presentation.result

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownBlocksTest {

    private fun parse(md: String) = MarkdownBlocks.parse(md)
    private fun inline(s: String) = MarkdownBlocks.parseInline(s)

    @Test
    fun `empty input gives no blocks`() {
        assertEquals(emptyList<Block>(), MarkdownBlocks.parse(null))
        assertEquals(emptyList<Block>(), parse("  \n\n "))
    }

    @Test
    fun `headings with levels and trailing hashes`() {
        val b = parse("# Uno\n### Tres ###")
        assertEquals(Block.Heading(1, listOf(Span("Uno"))), b[0])
        assertEquals(Block.Heading(3, listOf(Span("Tres"))), b[1])
    }

    @Test
    fun `hash without space is a paragraph`() {
        assertEquals(listOf(Block.Paragraph(listOf(Span("#nohead")))), parse("#nohead"))
    }

    @Test
    fun `paragraph lines join with spaces, blank line splits`() {
        val b = parse("uno\ndos\n\ntres")
        assertEquals(Block.Paragraph(listOf(Span("uno dos"))), b[0])
        assertEquals(Block.Paragraph(listOf(Span("tres"))), b[1])
    }

    @Test
    fun `bullets, numbered and nesting clamp to two levels`() {
        val b = parse("- a\n* b\n  - c\n      - d\n1. uno\n2) dos")
        assertEquals(Block.ListItem("•", false, 0, listOf(Span("a"))), b[0])
        assertEquals(Block.ListItem("•", false, 0, listOf(Span("b"))), b[1])
        assertEquals(Block.ListItem("•", false, 1, listOf(Span("c"))), b[2])
        assertEquals(Block.ListItem("•", false, 1, listOf(Span("d"))), b[3])
        assertEquals(Block.ListItem("1.", true, 0, listOf(Span("uno"))), b[4])
        assertEquals(Block.ListItem("2.", true, 0, listOf(Span("dos"))), b[5])
    }

    @Test
    fun `list item lazy continuation joins`() {
        val b = parse("- primera parte\n  sigue aquí\n- otro")
        assertEquals(Block.ListItem("•", false, 0, listOf(Span("primera parte sigue aquí"))), b[0])
        assertEquals(2, b.size)
    }

    @Test
    fun `fenced code keeps lines and language`() {
        val b = parse("```kotlin\nval a = 1\n  val b = **2**\n```\ndespués")
        assertEquals(Block.Code("kotlin", listOf("val a = 1", "  val b = **2**")), b[0])
        assertEquals(Block.Paragraph(listOf(Span("después"))), b[1])
    }

    @Test
    fun `unterminated fence runs to end`() {
        val b = parse("texto\n```\nline1\nline2\n")
        assertEquals(Block.Code(null, listOf("line1", "line2")), b[1])
    }

    @Test
    fun `tilde fence works`() {
        assertEquals(Block.Code("sh", listOf("ls")), parse("~~~sh\nls\n~~~")[0])
    }

    @Test
    fun `quote lines merge and nested quotes flatten`() {
        val b = parse("> uno\n> > dos\nfuera")
        assertEquals(Block.Quote(listOf(Span("uno dos"))), b[0])
        assertEquals(Block.Paragraph(listOf(Span("fuera"))), b[1])
    }

    @Test
    fun `rules`() {
        assertEquals(listOf(Block.Rule, Block.Rule, Block.Rule), parse("---\n\n***\n\n_ _ _"))
    }

    @Test
    fun `table becomes column value pairs`() {
        val b = parse("| Plan | Precio |\n|:--|--:|\n| Pro | **20** |\n| Max | 100 |")
        assertEquals(
            Block.Table(
                listOf(
                    listOf("Plan" to "Pro", "Precio" to "20"),
                    listOf("Plan" to "Max", "Precio" to "100"),
                ),
            ),
            b.single(),
        )
    }

    @Test
    fun `table without outer pipes and with missing cells`() {
        val b = parse("a | b\n--- | ---\n1 |\n")
        assertEquals(Block.Table(listOf(listOf("a" to "1"))), b.single())
    }

    @Test
    fun `escaped pipe stays in cell`() {
        val b = parse("| x |\n|---|\n| a \\| b |")
        assertEquals(Block.Table(listOf(listOf("x" to "a | b"))), b.single())
    }

    @Test
    fun `more than four columns is too wide`() {
        val b = parse("|a|b|c|d|e|\n|-|-|-|-|-|\n|1|2|3|4|5|\n|1|2|3|4|5|")
        assertEquals(Block.TableTooWide(5, 2), b.single())
    }

    @Test
    fun `pipe line without separator is a paragraph`() {
        assertTrue(parse("| a | b |")[0] is Block.Paragraph)
    }

    @Test
    fun `html only lines are dropped and inline tags stripped`() {
        val b = parse("<details>\n<summary>Hola</summary> mundo<br>fin\n</details>")
        assertEquals(listOf(Block.Paragraph(listOf(Span("Hola mundo fin")))), b)
    }

    @Test
    fun `mixed document order`() {
        val b = parse("**TL;DR:** x\n\n## Pasos\n1. a\n2. b\n\n```\nc\n```\n> q\n\n---\nfin")
        assertEquals(
            listOf(
                Block.Paragraph::class, Block.Heading::class, Block.ListItem::class, Block.ListItem::class,
                Block.Code::class, Block.Quote::class, Block.Rule::class, Block.Paragraph::class,
            ),
            b.map { it::class },
        )
    }

    // ─── Inline ──────────────────────────────────────────────────────────

    @Test
    fun `bold italic and code`() {
        assertEquals(
            listOf(
                Span("a "), Span("b", bold = true), Span(" "), Span("i", italic = true),
                Span(" "), Span("x()", code = true), Span(" "), Span("u", bold = true),
            ),
            inline("a **b** *i* `x()` __u__"),
        )
    }

    @Test
    fun `bold with nested italic`() {
        assertEquals(
            listOf(Span("a ", bold = true), Span("b", bold = true, italic = true)),
            inline("**a *b***").let { listOf(it[0], it[1]) },
        )
    }

    @Test
    fun `snake case underscores are literal`() {
        assertEquals(listOf(Span("use my_var_name now")), inline("use my_var_name now"))
    }

    @Test
    fun `lone asterisks and math stay literal`() {
        assertEquals(listOf(Span("2 * 3 * 4")), inline("2 * 3 * 4"))
    }

    @Test
    fun `unterminated markers stay literal`() {
        assertEquals(listOf(Span("**negr")), inline("**negr"))
        assertEquals(listOf(Span("`code")), inline("`code"))
        assertEquals(listOf(Span("[link](http://x")), inline("[link](http://x"))
    }

    @Test
    fun `links become text plus arrow`() {
        assertEquals(
            listOf(Span("ver "), Span("docs ↗", link = true), Span(" ya")),
            inline("ver [docs](https://x.y) ya"),
        )
    }

    @Test
    fun `autolinks become link text`() {
        assertEquals(listOf(Span("https://a.b ↗", link = true)), inline("<https://a.b>"))
    }

    @Test
    fun `images are stripped`() {
        assertEquals(listOf(Span("antes después")), inline("antes ![logo](a.png) después"))
    }

    @Test
    fun `backslash escapes`() {
        assertEquals(listOf(Span("*no* italic")), inline("\\*no\\* italic"))
    }

    @Test
    fun `double backtick code with inner backtick`() {
        assertEquals(listOf(Span("a`b", code = true)), inline("``a`b``"))
    }

    @Test
    fun `html entities decode`() {
        assertEquals(listOf(Span("a < b & c")), inline("a &lt; b &amp; c"))
    }

    @Test
    fun `strikethrough renders plain`() {
        assertEquals(listOf(Span("viejo nuevo")), inline("~~viejo~~ nuevo"))
    }

    // ─── Budget ──────────────────────────────────────────────────────────

    @Test
    fun `collapsed count respects budget and keeps at least one`() {
        val p = { n: Int -> Block.Paragraph(listOf(Span("x".repeat(n)))) }
        assertEquals(3, collapsedBlockCount(listOf(p(100), p(100), p(100)), 600))
        assertEquals(2, collapsedBlockCount(listOf(p(300), p(250), p(300)), 600))
        assertEquals(1, collapsedBlockCount(listOf(p(2000), p(10)), 600))
        assertEquals(0, collapsedBlockCount(emptyList(), 600))
    }
}
