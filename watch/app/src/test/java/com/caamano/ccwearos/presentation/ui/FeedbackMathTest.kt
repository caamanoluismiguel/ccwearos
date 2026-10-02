package com.caamano.ccwearos.presentation.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FeedbackMathTest {
    @Test fun elapsed_formats_minutes_and_hours() {
        assertEquals("00:00", formatElapsed(0))
        assertEquals("00:00", formatElapsed(-5_000))
        assertEquals("00:59", formatElapsed(59_999))
        assertEquals("01:05", formatElapsed(65_000))
        assertEquals("59:59", formatElapsed(3_599_000))
        assertEquals("1:00:00", formatElapsed(3_600_000))
        assertEquals("12:03:09", formatElapsed((12 * 3600 + 3 * 60 + 9) * 1000L))
    }

    @Test fun shake_starts_and_ends_at_rest_and_is_bounded() {
        assertEquals(0f, shakeOffset(0f, 3), 1e-4f)
        assertEquals(0f, shakeOffset(1f, 3), 1e-4f)
        var crossings = 0
        var prev = 0f
        for (i in 1 until 1000) {
            val v = shakeOffset(i / 1000f, 3)
            assertTrue(abs(v) <= 1f)
            if (prev != 0f && v != 0f && (v > 0) != (prev > 0)) crossings++
            prev = v
        }
        assertEquals("3 oscillations = 5 inner zero crossings", 5, crossings)
    }

    @Test fun stagger_is_index_based_and_capped() {
        assertEquals(0L, staggerDelayMs(0))
        assertEquals(Motion.STAGGER.toLong() * 3, staggerDelayMs(3))
        assertEquals(staggerDelayMs(STAGGER_MAX_STEPS), staggerDelayMs(50))
        assertEquals(0L, staggerDelayMs(-1))
    }
}
