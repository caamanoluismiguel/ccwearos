package com.caamano.ccwearos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerGateTest {
    @Test
    fun `claims each id once, release reopens it`() {
        val g = AnswerGate()
        assertTrue(g.tryClaim("a"))
        assertFalse(g.tryClaim("a"))
        g.release("a")
        assertTrue(g.tryClaim("a"))
        assertTrue(g.tryClaim("b"))
        g.release("a") // stale release must not clear "b"
        assertEquals("b", g.answeredId.value)
    }
}
