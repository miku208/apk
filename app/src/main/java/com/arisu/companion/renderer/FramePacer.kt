package com.arisu.companion.renderer

/**
 * Frame pacing for the render loop.
 *
 * The previous loop used `if (now - last < interval) return`, which on a 60 Hz
 * panel with a 60 fps limit skipped every second vsync (16.66 ms < 16.67 ms)
 * and produced a 30 fps stutter. This class keeps the remainder in an
 * accumulator, so the average rate is exact and no frame is lost.
 *
 * It also clamps long stalls (GC pause, overlay losing/regaining focus, screen
 * on) to one interval, so the animation can never be fast-forwarded by a gap.
 *
 * Pure Kotlin, covered by [com.arisu.companion.renderer.FramePacerTest].
 */
class FramePacer(
    intervalNanos: Long = 1_000_000_000L / 60L,
    private val maxGapNanos: Long = 250_000_000L,
) {

    private var interval = intervalNanos.coerceAtLeast(1L)
    private var lastFrameNanos = 0L
    private var accumulator = 0L
    private var started = false

    /** Kept for diagnostics/tests. */
    val remainderNanos: Long get() = accumulator

    val intervalNanos: Long get() = interval

    fun setIntervalNanos(value: Long) {
        val next = value.coerceAtLeast(1L)
        if (next == interval) return
        interval = next
        accumulator = 0L
    }

    /** Call when the loop restarts so the next frame has no delta. */
    fun reset() {
        started = false
        lastFrameNanos = 0L
        accumulator = 0L
    }

    /**
     * @return the delta (nanoseconds) animation should advance by, or null when
     *         this vsync is skipped by the FPS cap.
     */
    fun onFrame(nowNanos: Long): Long? {
        if (!started) {
            started = true
            lastFrameNanos = nowNanos
            return null
        }
        if (nowNanos <= lastFrameNanos) return null
        var delta = nowNanos - lastFrameNanos
        lastFrameNanos = nowNanos
        if (delta > maxGapNanos) delta = interval

        accumulator += delta
        // 1 % tolerance so a 16.666 ms vsync satisfies a 60 fps (16.667 ms)
        // budget instead of being skipped forever.
        val tolerance = (interval / 100).coerceAtLeast(1L)
        if (accumulator + tolerance < interval) return null

        val frame = minOf(accumulator, interval * 2)
        accumulator -= interval
        if (accumulator < 0L) accumulator = 0L
        if (accumulator > interval * 4) accumulator = interval
        return frame
    }
}
