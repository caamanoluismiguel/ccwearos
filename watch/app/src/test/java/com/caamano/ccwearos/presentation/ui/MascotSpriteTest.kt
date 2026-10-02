package com.caamano.ccwearos.presentation.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MascotSpriteTest {

    /** Every frame each state's animation loop can produce. */
    private fun framesFor(state: MascotState): List<MascotFrame> {
        val base = listOf(MascotFrame.resting(state, animate = true), MascotFrame.resting(state, animate = false))
        val extra = when (state) {
            MascotState.Idle -> listOf(MascotFrame(breath = 1), MascotFrame(blink = true, breath = 1))
            MascotState.Sending -> (0..2).map { MascotFrame(lean = 1, beat = it) } + MascotFrame(lean = 1, blink = true)
            MascotState.Running -> (0..3).map {
                MascotFrame(gaze = 1, walkFrame = it, lift = if (it % 2 == 1) -1 else 0, dust = it)
            }
            MascotState.Waiting -> listOf(-2, -1, 0).map { MascotFrame(lift = it) } + MascotFrame(lift = -2, blink = true)
            MascotState.Done -> listOf(-3, -1, 0).map { MascotFrame(lift = it) } +
                listOf(MascotFrame(celebrating = false, breath = 1), MascotFrame(celebrating = false, blink = true))
            MascotState.Blocked -> listOf(MascotFrame(gaze = 0), MascotFrame(gaze = 1, blink = true))
            MascotState.Offline -> (0..2).map { MascotFrame(zzz = it) }
            MascotState.Error -> emptyList()
        }
        return base + extra
    }

    @Test fun every_frame_of_every_state_stays_inside_the_grid() {
        for (state in MascotState.entries) for (f in framesFor(state)) {
            for (p in mascotPixels(state, f)) {
                assertTrue("$state $f $p x", p.x >= 0 && p.x + p.w <= MascotGrid.COLS)
                assertTrue("$state $f $p y", p.y >= 0 && p.y + p.h <= MascotGrid.ROWS)
            }
        }
    }

    @Test fun static_frames_are_distinct_per_state() {
        val shapes = MascotState.entries.associateWith {
            mascotPixels(it, MascotFrame.resting(it, animate = false)).toSet()
        }
        assertEquals("every state reads differently in a still frame", MascotState.entries.size, shapes.values.toSet().size)
    }

    @Test fun reduced_motion_keeps_props_readable() {
        fun inks(s: MascotState) = mascotPixels(s, MascotFrame.resting(s, animate = false)).map { it.ink }.toSet()
        assertTrue("sending shows signal", Ink.Accent in inks(MascotState.Sending))
        assertTrue("waiting shows !", Ink.Accent in inks(MascotState.Waiting))
        assertTrue("blocked shows ? + laptop", Ink.Muted in inks(MascotState.Blocked))
        assertTrue("offline shows z", Ink.Muted in inks(MascotState.Offline))
    }

    @Test fun done_eyes_settle_back_to_idle_after_celebrating() {
        val celebrating = mascotPixels(MascotState.Done, MascotFrame()).filter { it.ink == Ink.Eye }
        val settled = mascotPixels(MascotState.Done, MascotFrame(celebrating = false)).filter { it.ink == Ink.Eye }
        val idle = mascotPixels(MascotState.Idle, MascotFrame()).filter { it.ink == Ink.Eye }
        assertNotEquals(celebrating, settled)
        assertEquals(idle, settled)
    }

    @Test fun sending_beats_move_outward() {
        val tops = (0..2).map { b ->
            mascotPixels(MascotState.Sending, MascotFrame(lean = 1, beat = b)).filter { it.ink == Ink.Accent }.minOf { it.y }
        }
        assertTrue("each beat is higher than the last: $tops", tops[0] > tops[1] && tops[1] > tops[2])
    }
}
