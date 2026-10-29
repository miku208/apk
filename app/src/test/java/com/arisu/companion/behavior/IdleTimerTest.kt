package com.arisu.companion.behavior

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleTimerTest {

    @Test
    fun `fires once after the timeout`() {
        val timer = IdleTimer(60_000)
        timer.start(0)
        assertFalse(timer.due(59_999))
        assertTrue(timer.due(60_000))
        // Latch: never again in the same idle period.
        assertFalse(timer.due(61_000))
        assertFalse(timer.due(600_000))
        assertTrue(timer.hasFired)
    }

    @Test
    fun `interaction resets the countdown and re-arms`() {
        val timer = IdleTimer(60_000)
        timer.start(0)
        assertTrue(timer.due(60_000))
        timer.onInteraction(70_000)
        assertFalse(timer.due(120_000))
        assertTrue(timer.due(130_000))
    }

    @Test
    fun `cancel restarts the countdown without firing`() {
        val timer = IdleTimer(60_000)
        timer.start(0)
        timer.cancel(30_000) // screen off
        assertFalse(timer.due(89_999))
        assertTrue(timer.due(90_000))
    }

    @Test
    fun `does not fire before it is started`() {
        val timer = IdleTimer(1_000)
        assertFalse(timer.due(Long.MAX_VALUE / 2))
    }

    @Test
    fun `remaining time is clamped`() {
        val timer = IdleTimer(60_000)
        timer.start(0)
        assertEquals(60_000, timer.remainingMs(0))
        assertEquals(0, timer.remainingMs(120_000))
    }

    @Test
    fun `timeout bounds are enforced`() {
        val timer = IdleTimer(1)
        assertEquals(IdleTimer.MIN_TIMEOUT_MS, timer.timeoutMs)
        timer.setTimeoutMillis(99_999_999)
        assertEquals(IdleTimer.MAX_TIMEOUT_MS, timer.timeoutMs)
    }

    @Test
    fun `quiet hours handle midnight wrap`() {
        assertTrue(QuietHours.isQuiet(23, 23, 7))
        assertTrue(QuietHours.isQuiet(2, 23, 7))
        assertFalse(QuietHours.isQuiet(7, 23, 7))
        assertFalse(QuietHours.isQuiet(12, 23, 7))
        assertTrue(QuietHours.isQuiet(13, 12, 14))
        // equal bounds mean "disabled"
        assertFalse(QuietHours.isQuiet(3, 0, 0))
    }

    @Test
    fun `idle messages prefer the character's own lines`() {
        assertEquals("Aku ngambek", IdleMessages.pick("Miku", listOf("Aku ngambek"), 0))
        IdleMessages.defaults.indices.forEach { seed ->
            val fallback = IdleMessages.pick("Miku", emptyList(), seed)
            assertTrue("line $seed is not blank", fallback.isNotBlank())
            assertFalse("line $seed must not name another pet: $fallback", fallback.contains("Arisu"))
        }
    }
}
