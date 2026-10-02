package com.caamano.ccwearos.presentation

import com.caamano.ccwearos.data.RunOutcome
import com.caamano.ccwearos.data.ToolEvent
import com.caamano.ccwearos.notifications.NotificationText
import com.caamano.ccwearos.presentation.home.RunEnd
import com.caamano.ccwearos.presentation.home.UNSEEN_REPLAY_WINDOW_MS
import com.caamano.ccwearos.presentation.home.UnseenCompletion
import com.caamano.ccwearos.presentation.home.WORKING_LINE_MAX
import com.caamano.ccwearos.presentation.home.WorkingCopy
import com.caamano.ccwearos.presentation.home.workingLine
import com.caamano.ccwearos.presentation.permission.ParsedPrompt
import com.caamano.ccwearos.presentation.permission.parsePrompt
import com.caamano.ccwearos.presentation.permission.visibleDescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MinimalHomeTest {

    // ─── tool → verb ─────────────────────────────────────────────────────────

    private fun line(tool: String, arg: String? = null) = workingLine(listOf(ToolEvent(tool, arg)))

    @Test
    fun `tools map to plain Spanish verbs`() {
        assertEquals("Leyendo parser.ts", line("Read", "wrapper/src/parser.ts"))
        assertEquals("Editando parser.ts", line("Edit", "/a/b/parser.ts"))
        assertEquals("Editando parser.ts", line("MultiEdit", "parser.ts"))
        assertEquals("Escribiendo notas.md", line("Write", "docs/notas.md"))
        assertEquals(WorkingCopy.READING, line("Read"))
        assertEquals(WorkingCopy.RUNNING, line("Bash", "rm -rf build && npm test"))
        assertEquals(WorkingCopy.SEARCHING, line("Grep", "splitRow"))
        assertEquals(WorkingCopy.WEB, line("WebSearch", "wear os tiles"))
        assertEquals(WorkingCopy.FETCHING, line("WebFetch", "https://x.y"))
        assertEquals(WorkingCopy.DELEGATING, line("Task", "explore"))
        assertEquals(WorkingCopy.OTHER, line("mcp__github__create_issue", "x"))
    }

    @Test
    fun `no tool yet reads as thinking, raw activity never leaks`() {
        assertEquals(WorkingCopy.THINKING, workingLine(emptyList()))
    }

    @Test
    fun `long file names are ellipsized to the max`() {
        val l = line("Edit", "src/a_really_long_component_file_name_for_tests.kt")
        assertTrue(l.length <= WORKING_LINE_MAX)
        assertTrue(l.startsWith("Editando "))
        assertTrue(l.endsWith("…"))
    }

    @Test
    fun `the latest tool event wins`() {
        val events = listOf(ToolEvent("Read", "a.kt"), ToolEvent("Bash", "npm test"))
        assertEquals(WorkingCopy.RUNNING, workingLine(events))
    }

    // ─── unseen completion ───────────────────────────────────────────────────

    @Test
    fun `a finish nobody saw replays once on a resume within 30s`() {
        val u = UnseenCompletion()
        u.onFinished(RunEnd.DONE, nowMs = 1_000, seen = false)
        assertEquals(RunEnd.DONE, u.onResume(1_000 + UNSEEN_REPLAY_WINDOW_MS))
        assertNull("only once", u.onResume(1_000 + UNSEEN_REPLAY_WINDOW_MS + 1))
    }

    @Test
    fun `a finish seen live, or too old, never replays`() {
        val seen = UnseenCompletion()
        seen.onFinished(RunEnd.FAILED, nowMs = 0, seen = true)
        assertNull(seen.onResume(5_000))

        val old = UnseenCompletion()
        old.onFinished(RunEnd.DONE, nowMs = 0, seen = false)
        assertNull(old.onResume(UNSEEN_REPLAY_WINDOW_MS + 1))
    }

    @Test
    fun `a new run clears an unseen ending`() {
        val u = UnseenCompletion()
        u.onFinished(RunEnd.STOPPED, nowMs = 0, seen = false)
        u.clear()
        assertNull(u.onResume(1_000))
    }

    // ─── notification copy ───────────────────────────────────────────────────

    @Test
    fun `done notification alerts once per ts, never while visible or after a stop`() {
        val ok = RunOutcome(ok = true, ts = 7)
        assertEquals(NotificationText.DoneCopy(true, "Tests en verde"), NotificationText.doneCopy(ok, "Tests en verde\nmás", false, null))
        assertNull(NotificationText.doneCopy(ok, "x", appVisible = true, alreadyAlertedTs = null))
        assertNull(NotificationText.doneCopy(ok, "x", appVisible = false, alreadyAlertedTs = 7))
        assertNull(NotificationText.doneCopy(RunOutcome(ok = false, ts = 8, stopped = true), null, false, null))
        assertEquals(NotificationText.DoneCopy(false, null), NotificationText.doneCopy(RunOutcome(ok = false, exitCode = 1, ts = 9), null, false, 7))
    }

    // ─── permission description ──────────────────────────────────────────────

    @Test
    fun `description hidden when it only repeats the command`() {
        assertNull(visibleDescription(parsePrompt("Bash: npm test\nnpm test")))
        assertNull(visibleDescription(parsePrompt("Bash: git push origin main\n`git push origin main`")))
        assertNull(visibleDescription(parsePrompt("Bash: npm test")))
        assertEquals("Push the branch", visibleDescription(parsePrompt("Bash: git push origin main\nPush the branch")))
        // Free-form prompt: the description IS the content.
        assertEquals("Permiso pedido", visibleDescription(ParsedPrompt(null, null, "Permiso pedido")))
        assertFalse(visibleDescription(parsePrompt("Edit: a.kt\nRefactor the parser")).isNullOrEmpty())
    }
}
