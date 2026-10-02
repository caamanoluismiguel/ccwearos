package com.caamano.ccwearos.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticGateTest {
    @Test fun drops_equal_or_lower_priority_inside_the_window() {
        val g = HapticGate(60)
        assertTrue(g.tryAcquire(1_000, 1))
        assertFalse(g.tryAcquire(1_030, 1))
        assertFalse(g.tryAcquire(1_059, 0))
        assertTrue(g.tryAcquire(1_060, 1))
    }

    @Test fun higher_priority_interrupts_inside_the_window() {
        val g = HapticGate(60)
        assertTrue(g.tryAcquire(1_000, 1)) // tick
        assertTrue(g.tryAcquire(1_010, 4)) // permission is never swallowed
        assertFalse(g.tryAcquire(1_020, 3)) // but done right after it is
    }

    @Test fun first_haptic_always_plays() {
        assertTrue(HapticGate(60).tryAcquire(0, 0))
    }
}
