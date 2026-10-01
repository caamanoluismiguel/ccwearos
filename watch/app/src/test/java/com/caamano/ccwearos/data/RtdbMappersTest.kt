package com.caamano.ccwearos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// (d) mappers fed malformed data never throw and fall back to defaults.
class RtdbMappersTest {

    private val garbage: List<Any?> = listOf(
        null, 42L, 1.5, Double.NaN, true, "texto", emptyList<Any>(),
        listOf(1, 2), mapOf("x" to listOf(1)), Any(),
    )

    @Test
    fun `every mapper survives every garbage shape`() {
        for (g in garbage) {
            RtdbMappers.status(g)
            RtdbMappers.string(g)
            RtdbMappers.bool(g)
            RtdbMappers.metrics(g)
            RtdbMappers.claudeStatus(g)
            RtdbMappers.toolEvent(g)
            RtdbMappers.sharedSession(g)
            RtdbMappers.recentSession(g)
            RtdbMappers.claimResult(g)
        }
        RtdbMappers.list(garbage, RtdbMappers::toolEvent)
        RtdbMappers.followups(garbage)
    }

    @Test
    fun `float written into a Long field is coerced, not thrown`() {
        val m = RtdbMappers.metrics(
            mapOf(
                "dailyTokens" to 1234.9,
                "weeklyTokens" to "5000",
                "monthlyTokens" to Double.POSITIVE_INFINITY,
                "updatedAt" to mapOf("nested" to 1),
            ),
        )
        assertEquals(Metrics(dailyTokens = 1234, weeklyTokens = 5000, monthlyTokens = 0, updatedAt = 0), m)
    }

    @Test
    fun `wrong-typed fields fall back per field`() {
        val s = RtdbMappers.claudeStatus(
            mapOf("model" to 7L, "contextPct" to "42.5", "sessionPct" to true, "monthlyCost" to "$3"),
        )!!
        assertNull(s.model)
        assertEquals(42.5, s.contextPct!!, 0.0)
        assertNull(s.sessionPct)
        assertEquals("$3", s.monthlyCost)

        val r = RtdbMappers.recentSession(mapOf("active" to "yes", "mtime" to 10, "cwd" to "/tmp"))!!
        assertFalse(r.active)
        assertEquals(10L, r.mtime)
        assertEquals("/tmp", r.cwd)
    }

    @Test
    fun `status falls back to OFFLINE`() {
        assertEquals(WrapperStatus.OFFLINE, RtdbMappers.status("NOPE"))
        assertEquals(WrapperStatus.OFFLINE, RtdbMappers.status(3L))
        assertEquals(WrapperStatus.RUNNING, RtdbMappers.status("RUNNING"))
    }

    @Test
    fun `lists drop bad entries and followups cap at three`() {
        val events = RtdbMappers.list(
            listOf(mapOf("tool" to "Bash", "ts" to 1L), "basura", null, mapOf("tool" to 5)),
            RtdbMappers::toolEvent,
        )
        assertEquals(listOf(ToolEvent("Bash", null, 1L), ToolEvent("", null, 0L)), events)
        assertEquals(listOf("a", "b", "c"), RtdbMappers.followups(listOf("a", " ", 3L, "b", "c", "d")))
    }

    @Test
    fun `connected flag only true for boolean true`() {
        assertTrue(RtdbMappers.bool(true))
        assertFalse(RtdbMappers.bool("true"))
        assertFalse(RtdbMappers.bool(null))
    }
}
