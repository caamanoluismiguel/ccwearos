package com.caamano.ccwearos.presentation

import com.caamano.ccwearos.R
import com.caamano.ccwearos.data.BlockerKind
import com.caamano.ccwearos.presentation.ui.MascotState
import org.junit.Assert.assertEquals
import org.junit.Test

class BlockedCopyTest {

    @Test
    fun `every variant has its own title and action`() {
        val copies = BlockedVariant.entries.map { blockedCopy(it, null, null, null) }
        assertEquals(BlockedVariant.entries.size, copies.map { it.title }.toSet().size)
        assertEquals(R.string.blocked_mac_offline_action, copies[0].primary)
        assertEquals(R.string.blocked_no_dictation_action, copies[4].primary)
    }

    @Test
    fun `offline variants use the still grey mascot and no haptic`() {
        for (v in listOf(BlockedVariant.MAC_OFFLINE, BlockedVariant.WATCH_OFFLINE)) {
            val c = blockedCopy(v, null, null, null)
            assertEquals(MascotState.Offline, c.mascot)
            assertEquals(BlockedHaptic.NONE, c.haptic)
        }
    }

    @Test
    fun `crash shows the hint when present, default line otherwise, and buzzes error`() {
        val withHint = blockedCopy(BlockedVariant.CLAUDE_CRASHED, BlockerKind.CRASH, "  exit 137  ", null)
        assertEquals(BlockedBody.Literal("exit 137"), withHint.body)
        assertEquals(BlockedHaptic.ERROR, withHint.haptic)
        assertEquals(MascotState.Error, withHint.mascot)

        val blank = blockedCopy(BlockedVariant.CLAUDE_CRASHED, null, "   ", null)
        assertEquals(BlockedBody.Res(R.string.blocked_crashed_body), blank.body)
    }

    @Test
    fun `trust names the folder basename, ignoring the hint`() {
        val c = blockedCopy(BlockedVariant.NEEDS_MAC, BlockerKind.TRUST, "trust this folder", "/Users/me/projects/CCWEAROS/")
        assertEquals(BlockedBody.Res(R.string.blocked_needs_mac_trust_body, "CCWEAROS"), c.body)
        assertEquals(BlockedHaptic.TICK, c.haptic)
        assertEquals(MascotState.Blocked, c.mascot)
    }

    @Test
    fun `trust without cwd uses the generic folder line`() {
        val c = blockedCopy(BlockedVariant.NEEDS_MAC, BlockerKind.TRUST, null, " ")
        assertEquals(BlockedBody.Res(R.string.blocked_needs_mac_trust_body_no_cwd), c.body)
    }

    @Test
    fun `needs mac prefers the wrapper hint, then a kind specific default`() {
        assertEquals(
            BlockedBody.Literal("Corre claude login"),
            blockedCopy(BlockedVariant.NEEDS_MAC, BlockerKind.LOGIN, "Corre claude login", null).body,
        )
        assertEquals(
            BlockedBody.Res(R.string.blocked_needs_mac_login_body),
            blockedCopy(BlockedVariant.NEEDS_MAC, BlockerKind.LOGIN, null, null).body,
        )
        assertEquals(
            BlockedBody.Res(R.string.blocked_needs_mac_body),
            blockedCopy(BlockedVariant.NEEDS_MAC, BlockerKind.OTHER, "", null).body,
        )
    }

    @Test
    fun `project basename trims trailing slash and falls back`() {
        assertEquals("ccwearos", projectBasename("/a/b/ccwearos"))
        assertEquals("ccwearos", projectBasename("/a/b/ccwearos/"))
        assertEquals("plain", projectBasename("plain"))
        assertEquals("?", projectBasename("/"))
    }
}
