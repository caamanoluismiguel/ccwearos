package com.caamano.ccwearos.presentation.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Unit tests for pure long-task logic. No Android, no Compose — runs on JVM.
 * Each test covers a state the owner asked about: "¿qué está pasando? ¿va bien?
 * ¿falta mucho? ¿puedo irme?"
 */
class ProgressLogicTest {

    // ─── livenessState ───────────────────────────────────────────────────────

    @Test fun `liveness fresh when lastEventAt is zero`() {
        assertEquals(LivenessState.FRESH, livenessState(0L, 5 * 60_000L))
    }

    @Test fun `liveness fresh under 30 seconds`() {
        val now = 100_000L
        assertEquals(LivenessState.FRESH, livenessState(now - 29_000, now))
    }

    @Test fun `liveness stale between 90 seconds and 3 minutes`() {
        val now = 200_000L
        assertEquals(LivenessState.STALE, livenessState(now - 90_001, now))
        assertEquals(LivenessState.STALE, livenessState(now - 179_000, now))
    }

    @Test fun `liveness very_stale at or above 3 minutes`() {
        val now = 400_000L
        assertEquals(LivenessState.VERY_STALE, livenessState(now - 180_000, now))
        assertEquals(LivenessState.VERY_STALE, livenessState(now - 300_000, now))
    }

    @Test fun `liveness fresh when signal is in the future (clock skew)`() {
        val now = 100_000L
        // lastEventAt ahead of now (watch clock behind the Mac): treat as fresh.
        assertEquals(LivenessState.FRESH, livenessState(now + 5_000, now))
    }

    // ─── signalAbsentMinutes ─────────────────────────────────────────────────

    @Test fun `absent minutes zero when lastEventAt is zero`() {
        assertEquals(0, signalAbsentMinutes(0L, 999_999L))
    }

    @Test fun `absent minutes is at least 1 when stale`() {
        val now = 200_000L
        assertEquals(1, signalAbsentMinutes(now - 91_000, now))
    }

    @Test fun `absent minutes rounds down to full minutes`() {
        val now = 500_000L
        // 2min 30sec ago = 2 full minutes elapsed
        assertEquals(2, signalAbsentMinutes(now - 2 * 60_000 - 30_000, now))
    }

    // ─── stepMetaLine ────────────────────────────────────────────────────────

    @Test fun `step meta is null when step is zero`() {
        assertNull(stepMetaLine(0, "Buscando archivos", null))
    }

    @Test fun `step meta is null when label is blank`() {
        assertNull(stepMetaLine(3, "  ", null))
    }

    @Test fun `step meta without detail`() {
        assertEquals("Paso 3 · Buscando archivos", stepMetaLine(3, "Buscando archivos", null))
    }

    @Test fun `step meta with detail strips trailing slash`() {
        assertEquals("Paso 1 · Editando · parser.ts", stepMetaLine(1, "Editando", "parser.ts/"))
    }

    @Test fun `step meta with detail`() {
        assertEquals("Paso 5 · Leyendo archivos · src/main", stepMetaLine(5, "Leyendo archivos", "src/main"))
    }

    @Test fun `step meta ignores blank detail`() {
        assertEquals("Paso 2 · Corriendo un comando", stepMetaLine(2, "Corriendo un comando", "  "))
    }

    // ─── stepWhyCopy ─────────────────────────────────────────────────────────

    @Test fun `no explanation under 20 seconds`() {
        assertNull(stepWhyCopy("Buscando archivos", 19_999))
    }

    @Test fun `files reason for search and read labels`() {
        val why = stepWhyCopy("Buscando archivos", 25_000)!!
        assertEquals(StepReason.FILES, why.reason)
        assertFalse(why.wristDown)
    }

    @Test fun `command reason for run labels`() {
        val why = stepWhyCopy("Corriendo un comando", 20_001)!!
        assertEquals(StepReason.COMMAND, why.reason)
    }

    @Test fun `web reason for web labels`() {
        val why = stepWhyCopy("Buscando en la web", 30_000)!!
        assertEquals(StepReason.WEB, why.reason)
    }

    @Test fun `thinking reason for pensando label`() {
        val why = stepWhyCopy("Pensando…", 25_000)!!
        assertEquals(StepReason.THINKING, why.reason)
    }

    @Test fun `wristDown true at 60 seconds or more`() {
        val why = stepWhyCopy("Buscando archivos", 60_000)!!
        assertTrue(why.wristDown)
    }

    @Test fun `generic reason for unknown labels`() {
        val why = stepWhyCopy("Usando una herramienta", 25_000)!!
        assertEquals(StepReason.GENERIC, why.reason)
    }

    // ─── recapText ───────────────────────────────────────────────────────────

    @Test fun `recap null for zero new steps`() {
        assertNull(recapText(0))
    }

    @Test fun `recap null for negative`() {
        assertNull(recapText(-1))
    }

    @Test fun `recap singular for one new step`() {
        assertEquals("Mientras no mirabas: 1 paso más", recapText(1))
    }

    @Test fun `recap plural for multiple new steps`() {
        assertEquals("Mientras no mirabas: 3 pasos más", recapText(3))
    }

    // ─── requiresStopConfirm ─────────────────────────────────────────────────

    @Test fun `no confirm needed for short runs`() {
        assertFalse(requiresStopConfirm(9_999))
    }

    @Test fun `confirm needed at threshold`() {
        assertTrue(requiresStopConfirm(10_000))
    }

    @Test fun `confirm needed for long runs`() {
        assertTrue(requiresStopConfirm(5 * 60_000))
    }

    // ─── Preview scenario coverage ───────────────────────────────────────────

    @Test fun `thinking state - step 0, no label, no detail`() {
        // "Pensando el siguiente paso" - step 0 → no meta line
        assertNull(stepMetaLine(0, "Pensando…", null))
        // Not yet explaining
        assertNull(stepWhyCopy("Pensando…", 5_000))
    }

    @Test fun `fresh step 1 - step just started`() {
        // First tool call, step 1, fresh
        assertEquals("Paso 1 · Buscando archivos", stepMetaLine(1, "Buscando archivos", null))
        assertNull(stepWhyCopy("Buscando archivos", 3_000))
        assertEquals(LivenessState.FRESH, livenessState(System.currentTimeMillis() - 2_000, System.currentTimeMillis()))
    }

    @Test fun `long step 25s - files variant`() {
        val why = stepWhyCopy("Buscando archivos", 25_000)!!
        assertEquals(StepReason.FILES, why.reason)
        assertFalse(why.wristDown)
    }

    @Test fun `very long step 70s - wrist down hint`() {
        val why = stepWhyCopy("Buscando archivos", 70_000)!!
        assertEquals(StepReason.FILES, why.reason)
        assertTrue(why.wristDown)
    }

    @Test fun `stale 2min - liveness signal gone`() {
        val now = 1_000_000L
        val last = now - 3 * 60_000 // 3 minutes ago -> VERY_STALE
        assertEquals(LivenessState.VERY_STALE, livenessState(last, now))
        assertEquals(3, signalAbsentMinutes(last, now))
    }

    @Test fun `recap after 2 steps while wrist down`() {
        assertEquals("Mientras no mirabas: 2 pasos más", recapText(2))
    }
}
