package com.arisu.companion.behavior

/**
 * Counts how long the user has ignored the pet.
 *
 * Deliberately pure: the caller supplies a *monotonic* millisecond clock
 * (`SystemClock.elapsedRealtime()` on device, a fake in tests), so wall-clock
 * changes, timezone edits and NTP jumps can never trigger or postpone the
 * sulk. It also latches after firing, so one idle period produces exactly one
 * reaction instead of one per tick.
 */
class IdleTimer(timeoutMs: Long = DEFAULT_TIMEOUT_MS) {

    var timeoutMs: Long = timeoutMs.coerceAtLeast(MIN_TIMEOUT_MS)
        private set

    private var lastInteractionMs = UNKNOWN
    private var fired = false

    val hasFired: Boolean get() = fired

    fun setTimeoutMillis(timeoutMs: Long) {
        this.timeoutMs = timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
    }

    /** Call when the pet appears or the idle watch restarts. */
    fun start(nowMs: Long) {
        lastInteractionMs = nowMs
        fired = false
    }

    /** Call on tap, drag, chat message, settings change, screen on, … */
    fun onInteraction(nowMs: Long) {
        lastInteractionMs = nowMs
        fired = false
    }

    /** Suppresses a pending reaction without counting it as interaction. */
    fun cancel(nowMs: Long) {
        lastInteractionMs = nowMs
        fired = false
    }

    /** True exactly once per idle period, after [timeoutMs] without interaction. */
    fun due(nowMs: Long): Boolean {
        if (fired || lastInteractionMs == UNKNOWN) return false
        if (nowMs - lastInteractionMs < timeoutMs) return false
        fired = true
        return true
    }

    fun remainingMs(nowMs: Long): Long {
        if (lastInteractionMs == UNKNOWN) return timeoutMs
        return (timeoutMs - (nowMs - lastInteractionMs)).coerceAtLeast(0L)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val MIN_TIMEOUT_MS = 10_000L
        const val MAX_TIMEOUT_MS = 600_000L
        private const val UNKNOWN = Long.MIN_VALUE
    }
}

/** Local quiet-hours check; 23..7 spans midnight. */
object QuietHours {

    fun isQuiet(hourOfDay: Int, startHour: Int, endHour: Int): Boolean {
        val h = hourOfDay.coerceIn(0, 23)
        val start = startHour.coerceIn(0, 23)
        val end = endHour.coerceIn(0, 24)
        if (start == end) return false // equal bounds mean "off"
        return if (start < end) h >= start && h < end else h >= start || h < end
    }
}
