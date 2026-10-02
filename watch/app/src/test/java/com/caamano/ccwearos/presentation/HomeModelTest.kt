package com.caamano.ccwearos.presentation

import com.caamano.ccwearos.data.Blocker
import com.caamano.ccwearos.data.BlockerKind
import com.caamano.ccwearos.data.RunOutcome
import com.caamano.ccwearos.data.SharedSessionMeta
import com.caamano.ccwearos.data.ToolEvent
import com.caamano.ccwearos.data.WrapperStatus
import com.caamano.ccwearos.presentation.home.AWAITING_KEY
import com.caamano.ccwearos.presentation.home.HINT_WAITING_ON_MAC
import com.caamano.ccwearos.presentation.home.HomeMode
import com.caamano.ccwearos.presentation.home.LastRun
import com.caamano.ccwearos.presentation.home.LiveLine
import com.caamano.ccwearos.presentation.home.MAC_OFFLINE_KEY
import com.caamano.ccwearos.presentation.home.OfflineReason
import com.caamano.ccwearos.presentation.home.Overlay
import com.caamano.ccwearos.presentation.home.OutcomeCompletionDetector
import com.caamano.ccwearos.presentation.home.RoutingInput
import com.caamano.ccwearos.presentation.home.SendState
import com.caamano.ccwearos.presentation.home.ToolVerb
import com.caamano.ccwearos.presentation.home.blockerDismissKey
import com.caamano.ccwearos.presentation.home.formatElapsed
import com.caamano.ccwearos.presentation.home.homeMode
import com.caamano.ccwearos.presentation.home.isRunStale
import com.caamano.ccwearos.presentation.home.liveLine
import com.caamano.ccwearos.presentation.home.pageDirection
import com.caamano.ccwearos.presentation.home.pageProgress
import com.caamano.ccwearos.presentation.home.routeOverlay
import com.caamano.ccwearos.presentation.ui.MascotState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteOverlayTest {
    private fun input(
        status: WrapperStatus = WrapperStatus.IDLE,
        prompt: String? = null,
        blocker: Blocker? = null,
        macOffline: Boolean = false,
        awaitingStable: Boolean = false,
        junk: String? = null,
        dismissed: Set<String> = emptySet(),
    ) = RoutingInput(status, prompt, blocker, macOffline, awaitingStable, junk, dismissed)

    @Test
    fun `nothing to show`() = assertEquals(Overlay.None, routeOverlay(input()))

    @Test
    fun `permission prompt wins over everything`() {
        val o = routeOverlay(
            input(
                status = WrapperStatus.AWAITING_PERMISSION,
                prompt = "Bash: rm -rf build",
                blocker = Blocker(BlockerKind.CRASH, ts = 1),
                junk = "content:1",
            ),
        )
        assertEquals(Overlay.Permission, o)
    }

    @Test
    fun `blocker kinds map to variants`() {
        fun variant(k: BlockerKind) = (routeOverlay(input(blocker = Blocker(k, ts = 1))) as Overlay.Blocked).variant
        assertEquals(BlockedVariant.NEEDS_MAC, variant(BlockerKind.TRUST))
        assertEquals(BlockedVariant.NEEDS_MAC, variant(BlockerKind.LOGIN))
        assertEquals(BlockedVariant.NEEDS_MAC, variant(BlockerKind.OTHER))
        assertEquals(BlockedVariant.CLAUDE_CRASHED, variant(BlockerKind.CRASH))
        assertEquals(BlockedVariant.CLAUDE_CRASHED, variant(BlockerKind.TIMEOUT))
    }

    @Test
    fun `blocker carries hint and cwd, blank hint becomes null`() {
        val o = routeOverlay(input(blocker = Blocker(BlockerKind.TRUST, hint = " ", cwd = "/p/x", ts = 5))) as Overlay.Blocked
        assertNull(o.hint)
        assertEquals("/p/x", o.cwd)
        assertEquals(BlockerKind.TRUST, o.blockerKind)
    }

    @Test
    fun `dismissed blocker hides only that ts`() {
        val b = Blocker(BlockerKind.LOGIN, ts = 10)
        val dismissed = setOf(blockerDismissKey(b))
        assertEquals(Overlay.None, routeOverlay(input(blocker = b, dismissed = dismissed)))
        val newer = b.copy(ts = 11)
        assertTrue(routeOverlay(input(blocker = newer, dismissed = dismissed)) is Overlay.Blocked)
    }

    @Test
    fun `mac offline and its dismissal`() {
        val o = routeOverlay(input(status = WrapperStatus.OFFLINE, macOffline = true)) as Overlay.Blocked
        assertEquals(BlockedVariant.MAC_OFFLINE, o.variant)
        assertEquals(MAC_OFFLINE_KEY, o.dismissKey)
        assertEquals(Overlay.None, routeOverlay(input(macOffline = true, dismissed = setOf(MAC_OFFLINE_KEY))))
    }

    @Test
    fun `blocker outranks mac offline`() {
        val o = routeOverlay(input(macOffline = true, blocker = Blocker(BlockerKind.TIMEOUT, ts = 1))) as Overlay.Blocked
        assertEquals(BlockedVariant.CLAUDE_CRASHED, o.variant)
    }

    @Test
    fun `awaiting without prompt text needs the mac, only once stable`() {
        assertEquals(Overlay.None, routeOverlay(input(status = WrapperStatus.AWAITING_PERMISSION)))
        val o = routeOverlay(input(status = WrapperStatus.AWAITING_PERMISSION, awaitingStable = true)) as Overlay.Blocked
        assertEquals(BlockedVariant.NEEDS_MAC, o.variant)
        assertEquals(HINT_WAITING_ON_MAC, o.hint)
        assertEquals(
            Overlay.None,
            routeOverlay(input(status = WrapperStatus.AWAITING_PERMISSION, awaitingStable = true, dismissed = setOf(AWAITING_KEY))),
        )
    }

    @Test
    fun `junk content routes to NEEDS_MAC until dismissed`() {
        val o = routeOverlay(input(junk = "content:9")) as Overlay.Blocked
        assertEquals(BlockedVariant.NEEDS_MAC, o.variant)
        assertNull(o.blockerKind)
        assertEquals(Overlay.None, routeOverlay(input(junk = "content:9", dismissed = setOf("content:9"))))
    }

    @Test
    fun `same variant keeps the same transition key`() {
        val a = routeOverlay(input(blocker = Blocker(BlockerKind.TRUST, ts = 1)))
        val b = routeOverlay(input(blocker = Blocker(BlockerKind.LOGIN, ts = 2)))
        assertEquals(a.key, b.key)
    }
}

class HomeModeTest {
    private fun mode(
        status: WrapperStatus = WrapperStatus.IDLE,
        connected: Boolean = true,
        send: SendState = SendState.None,
        shared: SharedSessionMeta? = null,
        conversationActive: Boolean = false,
        lastRun: LastRun? = null,
    ) = homeMode(status, connected, send, shared, conversationActive, lastRun)

    @Test
    fun `sending outranks everything`() {
        assertEquals(HomeMode.Sending("x"), mode(status = WrapperStatus.RUNNING, send = SendState.Sending("x", "x", null)))
        assertEquals(HomeMode.SendFailed("x"), mode(send = SendState.Failed("x", "x")))
    }

    @Test
    fun `shared session replaces the ask button`() {
        val meta = SharedSessionMeta(kind = "hook")
        assertEquals(HomeMode.Shared(meta), mode(status = WrapperStatus.RUNNING, shared = meta))
    }

    @Test
    fun `running and waiting`() {
        assertEquals(HomeMode.Running, mode(status = WrapperStatus.RUNNING))
        assertEquals(HomeMode.Waiting, mode(status = WrapperStatus.AWAITING_PERMISSION))
    }

    @Test
    fun `offline reasons gate the ask button`() {
        assertEquals(OfflineReason.WATCH, (mode(connected = false) as HomeMode.Idle).offline)
        assertEquals(OfflineReason.WATCH, (mode(status = WrapperStatus.OFFLINE, connected = false) as HomeMode.Idle).offline)
        assertEquals(OfflineReason.MAC, (mode(status = WrapperStatus.OFFLINE) as HomeMode.Idle).offline)
        assertNull((mode() as HomeMode.Idle).offline)
    }

    @Test
    fun `only failed runs surface a failure line`() {
        assertNull((mode(lastRun = LastRun(true, 0, false)) as HomeMode.Idle).failure)
        val bad = LastRun(false, 1, false)
        assertEquals(bad, (mode(lastRun = bad) as HomeMode.Idle).failure)
    }

    @Test
    fun `mascot follows the mode`() {
        assertEquals(MascotState.Idle, mascotFor(mode(), WrapperStatus.IDLE, null))
        assertEquals(MascotState.Offline, mascotFor(mode(connected = false), WrapperStatus.IDLE, null))
        assertEquals(MascotState.Blocked, mascotFor(mode(), WrapperStatus.IDLE, Blocker(BlockerKind.TRUST)))
        assertEquals(MascotState.Error, mascotFor(mode(lastRun = LastRun(false, 1, false)), WrapperStatus.IDLE, null))
        assertEquals(MascotState.Sending, mascotFor(mode(send = SendState.Sending("a", "a", null)), WrapperStatus.IDLE, null))
        assertEquals(MascotState.Error, mascotFor(mode(send = SendState.Failed("a", "a")), WrapperStatus.IDLE, null))
        assertEquals(MascotState.Running, mascotFor(mode(status = WrapperStatus.RUNNING), WrapperStatus.RUNNING, null))
    }
}

class OutcomeCompletionDetectorTest {
    @Test
    fun `never fires without seeing work`() {
        val d = OutcomeCompletionDetector()
        assertNull(d.onUpdate(WrapperStatus.IDLE, RunOutcome(ok = true, ts = 1)))
        assertNull(d.onUpdate(WrapperStatus.IDLE, RunOutcome(ok = true, ts = 2)))
    }

    @Test
    fun `outcome before IDLE fires on IDLE`() {
        val d = OutcomeCompletionDetector()
        assertNull(d.onUpdate(WrapperStatus.RUNNING, null))
        assertNull(d.onUpdate(WrapperStatus.RUNNING, RunOutcome(ok = true, ts = 3)))
        assertEquals(RunOutcome(ok = true, ts = 3), d.onUpdate(WrapperStatus.IDLE, RunOutcome(ok = true, ts = 3)))
        assertNull(d.onUpdate(WrapperStatus.IDLE, RunOutcome(ok = true, ts = 3)))
    }

    @Test
    fun `same outcome as at run start waits for the new one`() {
        val d = OutcomeCompletionDetector()
        val old = RunOutcome(ok = true, ts = 1)
        d.onUpdate(WrapperStatus.RUNNING, old)
        assertNull(d.onUpdate(WrapperStatus.IDLE, old))
        val new = RunOutcome(ok = false, exitCode = 1, ts = 2)
        assertEquals(new, d.onUpdate(WrapperStatus.IDLE, new))
    }

    @Test
    fun `permission wait counts as work`() {
        val d = OutcomeCompletionDetector()
        d.onUpdate(WrapperStatus.AWAITING_PERMISSION, null)
        assertEquals(RunOutcome(ok = true, ts = 5), d.onUpdate(WrapperStatus.IDLE, RunOutcome(ok = true, ts = 5)))
    }

    @Test
    fun `wrapper dying mid run disarms`() {
        val d = OutcomeCompletionDetector()
        d.onUpdate(WrapperStatus.RUNNING, null)
        assertNull(d.onUpdate(WrapperStatus.OFFLINE, null))
        assertNull(d.onUpdate(WrapperStatus.IDLE, RunOutcome(ok = true, ts = 9)))
    }

    @Test
    fun `arm from a picked up prompt`() {
        val d = OutcomeCompletionDetector()
        val old = RunOutcome(ok = true, ts = 1)
        d.arm(old)
        val new = RunOutcome(ok = true, ts = 2)
        assertEquals(new, d.onUpdate(WrapperStatus.IDLE, new))
    }
}

class LiveLineTest {
    @Test
    fun `last tool wins with a short target`() {
        val line = liveLine(listOf(ToolEvent("Read", "a.ts"), ToolEvent("Edit", "wrapper/src/parser.ts")), "Crunching…")
        assertEquals(LiveLine.Tool(ToolVerb.EDIT, "parser.ts", "Edit"), line)
    }

    @Test
    fun `bash command is truncated`() {
        val line = liveLine(listOf(ToolEvent("Bash", "npm run test -- --coverage --watch=false src")), null) as LiveLine.Tool
        assertEquals(ToolVerb.RUN, line.verb)
        assertTrue(line.target!!.endsWith("…"))
        assertTrue(line.target!!.length <= 28)
    }

    @Test
    fun `unknown tool and missing arg`() {
        assertEquals(LiveLine.Tool(ToolVerb.OTHER, null, "mcp__x"), liveLine(listOf(ToolEvent("mcp__x")), null))
    }

    @Test
    fun `falls back to activity, then nothing`() {
        assertEquals(LiveLine.Activity("Crunching…"), liveLine(emptyList(), " Crunching… "))
        assertEquals(LiveLine.None, liveLine(emptyList(), "  "))
    }

    @Test
    fun `elapsed formatting`() {
        assertEquals("00:00", formatElapsed(-5))
        assertEquals("04:07", formatElapsed(247_000))
        assertEquals("1:02:03", formatElapsed(3_723_000))
    }

    @Test
    fun `stale after three quiet minutes`() {
        assertFalse(isRunStale(nowMs = 179_999, lastProgressAtMs = 0))
        assertTrue(isRunStale(nowMs = 180_000, lastProgressAtMs = 0))
    }
}

class ContinuityMathTest {
    @Test
    fun `page progress follows the finger and clamps`() {
        assertEquals(0f, pageProgress(page = 0, currentPage = 0, currentPageOffsetFraction = 0f), 0.0001f)
        assertEquals(0.3f, pageProgress(page = 0, currentPage = 0, currentPageOffsetFraction = 0.3f), 0.0001f)
        assertEquals(0.7f, pageProgress(page = 1, currentPage = 0, currentPageOffsetFraction = 0.3f), 0.0001f)
        assertEquals(1f, pageProgress(page = 2, currentPage = 0, currentPageOffsetFraction = 0.3f), 0.0001f)
    }

    @Test
    fun `direction is the side the page sits on`() {
        assertEquals(1, pageDirection(page = 1, currentPage = 0, currentPageOffsetFraction = 0.3f))
        assertEquals(-1, pageDirection(page = 0, currentPage = 0, currentPageOffsetFraction = 0.3f))
        assertEquals(0, pageDirection(page = 0, currentPage = 0, currentPageOffsetFraction = 0f))
    }
}
