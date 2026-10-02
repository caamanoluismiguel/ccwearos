package com.caamano.ccwearos.notifications

import com.caamano.ccwearos.data.RunOutcome
import com.caamano.ccwearos.data.WrapperStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTextTest {
    @Test fun tool_colon_target_becomes_tool_dot_target() {
        assertEquals("Bash · git push origin main", NotificationText.promptSummary("Bash: git push origin main\nPush the branch"))
    }

    @Test fun call_shape_becomes_tool_dot_target() {
        assertEquals("Bash · npm test", NotificationText.promptSummary("Bash(npm test)"))
        assertEquals("Read", NotificationText.promptSummary("Read()"))
    }

    @Test fun tool_without_target_is_just_the_tool() {
        assertEquals("Read", NotificationText.promptSummary("Read:"))
    }

    @Test fun whitespace_is_collapsed() {
        assertEquals("Edit · src/a.kt", NotificationText.promptSummary("  Edit:   src/a.kt  "))
        assertEquals("Bash · ls -la", NotificationText.promptSummary("Bash(ls    -la)"))
    }

    @Test fun free_form_text_uses_first_non_blank_line() {
        assertEquals(
            "Permission requested (details not visible, check the terminal)",
            NotificationText.promptSummary("\n\nPermission requested (details not visible, check the terminal)\nmore"),
        )
    }

    @Test fun blank_is_null() {
        assertNull(NotificationText.promptSummary(null))
        assertNull(NotificationText.promptSummary("  \n "))
    }

    @Test fun first_line() {
        assertEquals("hola", NotificationText.firstLine("\n  hola \nmundo"))
        assertNull(NotificationText.firstLine(" "))
        assertNull(NotificationText.firstLine(null))
    }
}

class OngoingStatusMapperTest {
    @Test fun offline_socket_wins() {
        assertEquals(OngoingStatus.NO_CONNECTION, OngoingStatusMapper.map(WrapperStatus.AWAITING_PERMISSION, false, true))
    }

    @Test fun awaiting_beats_blocked() {
        assertEquals(OngoingStatus.AWAITING, OngoingStatusMapper.map(WrapperStatus.AWAITING_PERMISSION, true, true))
    }

    @Test fun blocked_while_wrapper_alive() {
        assertEquals(OngoingStatus.BLOCKED, OngoingStatusMapper.map(WrapperStatus.IDLE, true, true))
        assertEquals(OngoingStatus.BLOCKED, OngoingStatusMapper.map(WrapperStatus.RUNNING, true, true))
        assertEquals(OngoingStatus.MAC_OFFLINE, OngoingStatusMapper.map(WrapperStatus.OFFLINE, true, true))
    }

    @Test fun plain_states() {
        assertEquals(OngoingStatus.RUNNING, OngoingStatusMapper.map(WrapperStatus.RUNNING, true, false))
        assertEquals(OngoingStatus.CONNECTED, OngoingStatusMapper.map(WrapperStatus.IDLE, true, false))
    }
}

class OutcomeGateTest {
    @Test fun existing_outcome_at_start_is_baseline() {
        val g = OutcomeGate()
        assertFalse(g.shouldNotify(RunOutcome(ok = true, ts = 1)))
        assertFalse(g.shouldNotify(RunOutcome(ok = true, ts = 1)))
    }

    @Test fun new_run_after_clear_notifies_once() {
        val g = OutcomeGate()
        assertFalse(g.shouldNotify(RunOutcome(ok = true, ts = 1)))
        assertFalse(g.shouldNotify(null)) // run started, outcome cleared
        assertTrue(g.shouldNotify(RunOutcome(ok = false, ts = 0)))
        assertFalse(g.shouldNotify(RunOutcome(ok = false, ts = 0)))
    }

    @Test fun new_timestamp_notifies() {
        val g = OutcomeGate()
        assertFalse(g.shouldNotify(null))
        assertTrue(g.shouldNotify(RunOutcome(ok = true, ts = 5)))
        assertTrue(g.shouldNotify(RunOutcome(ok = true, ts = 6)))
    }
}
