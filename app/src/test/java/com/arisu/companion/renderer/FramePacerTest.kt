package com.arisu.companion.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for the stutter that the old render loop produced:
 * `if (now - lastRendered < frameInterval) return` skipped every second vsync
 * on a 60 Hz display with a 60 fps cap.
 */
class FramePacerTest {

    private val vsync = 1_000_000_000L / 60L // 16_666_666 ns

    @Test
    fun `60 fps cap renders every vsync instead of every other one`() {
        val pacer = FramePacer(intervalNanos = 1_000_000_000L / 60L)
        assertNull("first frame has no delta", pacer.onFrame(0))
        var now = 0L
        var rendered = 0
        repeat(60) {
            now += vsync
            if (pacer.onFrame(now) != null) rendered++
        }
        assertEquals("all 60 vsyncs must render", 60, rendered)
    }

    @Test
    fun `30 fps cap renders every second vsync on a 60 Hz panel`() {
        val pacer = FramePacer(intervalNanos = 1_000_000_000L / 30L)
        pacer.onFrame(0)
        var now = 0L
        val pattern = mutableListOf<Boolean>()
        repeat(10) {
            now += vsync
            pattern += pacer.onFrame(now) != null
        }
        // Strict alternation: skip, render, skip, render...
        assertEquals(
            listOf(false, true, false, true, false, true, false, true, false, true),
            pattern,
        )
    }

    @Test
    fun `a long stall is clamped to one interval`() {
        val pacer = FramePacer(intervalNanos = 1_000_000_000L / 60L)
        pacer.onFrame(0)
        val dt = pacer.onFrame(2_000_000_000L) // 2 s gap, e.g. after a GC pause
        assertNotNull(dt)
        assertEquals(vsync, dt!!)
    }

    @Test
    fun `no infinite fast-forward after a clamped stall`() {
        val pacer = FramePacer(intervalNanos = 1_000_000_000L / 60L)
        pacer.onFrame(0)
        pacer.onFrame(5_000_000_000L)
        // The next vsync still has to wait its turn.
        assertNull(pacer.onFrame(5_000_000_000L + 1_000_000L))
    }

    @Test
    fun `out of order timestamps are ignored`() {
        val pacer = FramePacer()
        pacer.onFrame(1_000)
        assertNull(pacer.onFrame(500))
        assertNull(pacer.onFrame(1_000))
    }

    @Test
    fun `changing the interval resets the remainder`() {
        val pacer = FramePacer(intervalNanos = 1_000_000_000L / 60L)
        pacer.onFrame(0)
        pacer.onFrame(vsync)
        pacer.setIntervalNanos(1_000_000_000L / 15L)
        assertEquals(0, pacer.remainderNanos)
        assertNull("needs a full 66 ms budget", pacer.onFrame(vsync + vsync))
        assertNotNull(pacer.onFrame(vsync + 4 * vsync))
    }
}
