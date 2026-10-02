package com.caamano.ccwearos.presentation.result

import com.caamano.ccwearos.data.RunOutcome
import com.caamano.ccwearos.data.TaskKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResultContentTest {

    @Test
    fun `tldr line removed from body, headline wins`() {
        val r = prepareResultText("**TL;DR:** Corto.\n\nDetalle largo.", headline = "Del wrapper")
        assertEquals("Del wrapper", r.tldr)
        assertEquals("Detalle largo.", r.body)
    }

    @Test
    fun `tldr from text when no headline, markers stripped`() {
        val r = prepareResultText("TL;DR: El **parser** falla\n\n## Causa\nRegex.", headline = null)
        assertEquals("El parser falla", r.tldr)
        assertEquals("## Causa\nRegex.", r.body)
    }

    @Test
    fun `followups block is stripped`() {
        val r = prepareResultText(
            "**TL;DR:** x\n\nCuerpo.\n\n**Sugerencias:**\n- Uno\n- Dos\n",
            headline = null,
        )
        assertEquals("Cuerpo.", r.body)
    }

    @Test
    fun `followups header without bullets is kept`() {
        val r = prepareResultText("Cuerpo largo con varias cosas.\n\nFollowups:\nnada aquí", headline = "h")
        assertEquals("Cuerpo largo con varias cosas.\n\nFollowups:\nnada aquí", r.body)
    }

    @Test
    fun `short plain answer is promoted to tldr`() {
        val r = prepareResultText("Listo: tests en **verde** (72/72).", headline = null)
        assertEquals("Listo: tests en verde (72/72).", r.tldr)
        assertEquals("", r.body)
    }

    @Test
    fun `short answer with list is not promoted`() {
        val r = prepareResultText("- uno\n- dos", headline = null)
        assertNull(r.tldr)
        assertEquals("- uno\n- dos", r.body)
    }

    @Test
    fun `blank headline is ignored`() {
        val r = prepareResultText("TL;DR: real\n\nbody body body body", headline = "  ")
        assertEquals("real", r.tldr)
    }

    @Test
    fun `status comes from outcome not text`() {
        assertEquals(ResultStatus.DONE, resultStatus(RunOutcome(ok = true), TaskKind.ACTION))
        assertEquals(ResultStatus.FAILED, resultStatus(RunOutcome(ok = false, exitCode = 1), TaskKind.INFO))
        assertEquals(ResultStatus.INFO, resultStatus(null, TaskKind.INFO))
        assertEquals(ResultStatus.INFO, resultStatus(null, null))
        assertEquals(ResultStatus.NONE, resultStatus(null, TaskKind.ACTION))
    }

    @Test
    fun `tool kinds`() {
        assertEquals(ToolKind.COMMAND, toolKind("Bash"))
        assertEquals(ToolKind.EDIT, toolKind("Multi Edit"))
        assertEquals(ToolKind.READ, toolKind("Read"))
        assertEquals(ToolKind.SEARCH, toolKind("Glob"))
        assertEquals(ToolKind.WEB, toolKind("WebSearch"))
        assertEquals(ToolKind.AGENT, toolKind("Task"))
        assertEquals(ToolKind.OTHER, toolKind("mcp__x__y"))
    }

    @Test
    fun `tool args shorten paths by tail`() {
        assertNull(shortToolArg(null))
        assertNull(shortToolArg("  "))
        assertEquals("npm test", shortToolArg("npm   test"))
        assertEquals("…/parser.test.ts", shortToolArg("/Users/luis/projects/CCWEAROS/wrapper/src/parser.test.ts"))
        val long = shortToolArg("git commit -m \"a very long message that goes on\"")!!
        assert(long.length <= 28)
        assert(long.endsWith("…"))
    }

    @Test
    fun `response key changes with content`() {
        assertEquals(responseKey("a", "b"), responseKey("a", "b"))
        assertNotEquals(responseKey("a", "b"), responseKey("a", "c"))
    }
}
