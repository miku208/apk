package com.arisu.companion.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayGeometryTest {

    @Test
    fun `clamp keeps the pet on screen`() {
        assertEquals(0 to 0, OverlayGeometry.clamp(-50, -50, 300, 1080, 2400))
        assertEquals(780 to 2100, OverlayGeometry.clamp(2000, 2600, 300, 1080, 2400))
        // Smaller screen than the window: clamps to zero.
        assertEquals(0 to 0, OverlayGeometry.clamp(100, 100, 1000, 800, 900))
    }

    @Test
    fun `default position prefers right-lower area`() {
        val (x, y) = OverlayGeometry.defaultPosition(300, 1080, 2400)
        assertTrue(x in 0..(1080 - 300))
        assertTrue(y in 0..(2400 - 300))
        assertTrue("right side", x > 1000 - 600)
    }

    @Test
    fun `snap docks near horizontal edges only`() {
        assertEquals(0, OverlayGeometry.snapToEdge(10, 300, 1080, 56))
        assertEquals(780, OverlayGeometry.snapToEdge(900, 300, 1080, 56))
        assertEquals(400, OverlayGeometry.snapToEdge(400, 300, 1080, 56))
    }

    @Test
    fun `drag detection uses slop`() {
        assertFalse(OverlayGeometry.isDrag(3, 2, 8))
        assertTrue(OverlayGeometry.isDrag(0, 9, 8))
        assertTrue(OverlayGeometry.isDrag(-9, 0, 8))
    }

    @Test
    fun `touchable region is a tight band around the character`() {
        val (left, top, right, bottom) = OverlayGeometry.touchableRegion(300, 300)
        // Float math: allow one pixel of truncation slack.
        assertTrue(left in 47..49)
        assertTrue(right in 251..253)
        assertTrue(top in 0..10)
        assertTrue(bottom in 290..300)
        // Never the whole window, and never empty.
        assertTrue(right - left < 300)
        assertTrue(bottom - top > 200)
        // Degenerate sizes must not crash the insets callback.
        assertEquals(listOf(0, 0, 0, 0), OverlayGeometry.touchableRegion(0, 0).toList())
    }

    @Test
    fun `chat panel stays inside screen`() {
        val (x, y, w, h) = OverlayGeometry.chatPanelBounds(1080, 2400, 2.625f, expanded = false)
        assertTrue(w in 1..1080)
        assertTrue(h in 1..2400)
        assertTrue(x >= 0 && x + w <= 1080)
        assertTrue(y >= 0 && y + h <= 2400)
    }
}
