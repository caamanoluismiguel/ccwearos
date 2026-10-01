package com.caamano.ccwearos.data

import com.caamano.ccwearos.data.SharedSessionStaleness.STALE_AFTER_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedSessionStalenessTest {
    private val now = 10_000_000_000L
    private fun hook(startedAt: Long, heartbeatAt: Long? = null) =
        SharedSessionMeta(kind = "hook", startedAt = startedAt, heartbeatAt = heartbeatAt)

    @Test
    fun `heartbeat decides when present`() {
        assertFalse(SharedSessionStaleness.isStale(hook(now - 2 * STALE_AFTER_MS, now - 60_000), now))
        assertTrue(SharedSessionStaleness.isStale(hook(now - 60_000, now - STALE_AFTER_MS - 1), now))
        assertFalse(SharedSessionStaleness.isStale(hook(now - 60_000, now - STALE_AFTER_MS), now))
    }

    @Test
    fun `startedAt is the fallback without a heartbeat`() {
        assertTrue(SharedSessionStaleness.isStale(hook(now - STALE_AFTER_MS - 1), now))
        assertFalse(SharedSessionStaleness.isStale(hook(now - 60_000), now))
        assertTrue(SharedSessionStaleness.isStale(hook(now - STALE_AFTER_MS - 1, heartbeatAt = 0), now))
    }

    @Test
    fun `future timestamps from clock skew count as fresh`() {
        assertFalse(SharedSessionStaleness.isStale(hook(now + 10 * 60_000, now + 10 * 60_000), now))
    }

    @Test
    fun `no timestamp at all keeps the lock`() {
        assertFalse(SharedSessionStaleness.isStale(hook(0), now))
    }

    @Test
    fun `only hook sessions age out`() {
        val old = now - 10 * STALE_AFTER_MS
        assertFalse(SharedSessionStaleness.isStale(SharedSessionMeta(kind = "wrapper-pty", startedAt = old), now))
        assertFalse(SharedSessionStaleness.isStale(SharedSessionMeta(kind = "", startedAt = old), now))
    }

    @Test
    fun `visible drops only stale locks`() {
        val fresh = hook(now - 60_000)
        assertEquals(fresh, SharedSessionStaleness.visible(fresh, now))
        assertNull(SharedSessionStaleness.visible(hook(now - STALE_AFTER_MS - 1), now))
        assertNull(SharedSessionStaleness.visible(null, now))
    }
}
