package com.arisu.companion.animation

/**
 * The single authoritative animation state machine of the pet.
 *
 * Why it exists: the previous renderer re-resolved a clip and restarted the
 * animator on *every* rendered frame, using wall-clock time to pick the
 * playhead. That caused stutter (a dropped frame fast-forwarded the clip),
 * random "strange movements" (an unresolved clip fell back to index 0, which
 * for the shipped asset is an attack animation) and state changes that nobody
 * could observe.
 *
 * Rules enforced here:
 *  - time only advances by the frame delta handed in, never by a wall clock;
 *  - a state change happens once, not once per frame;
 *  - one-shot clips end by themselves and settle through [PetState.RETURN_TO_IDLE];
 *  - transitions report a cross-fade alpha so the renderer can blend;
 *  - the playhead never jumps because of stalled or resumed rendering.
 *
 * The class is pure Kotlin: no Android, no Filament, fully unit-testable.
 */
class PetStateMachine(
    private val clips: AnimationClips,
    private val durationOf: (Int) -> Float = { 1f },
    private val crossfadeSeconds: Float = 0.18f,
    private val returnToIdleSeconds: Float = 0.28f,
) {

    /** Everything the renderer needs to apply one frame. */
    data class Frame(
        val state: PetState,
        /** -1 when the model has no clip for this state (keep the current pose). */
        val clipIndex: Int,
        val clipName: String?,
        val clipTime: Float,
        val previousIndex: Int,
        val previousTime: Float,
        /** 1 means "fully the new clip"; below that the renderer cross-fades. */
        val crossfadeAlpha: Float,
        val enteredState: Boolean,
        val settledToIdle: Boolean,
    )

    var state: PetState = PetState.IDLE
        private set

    /** Seconds since [state] was entered. */
    var elapsed: Float = 0f
        private set

    private var previousIndex = -1
    private var previousTime = 0f
    private var fadeRemaining = 0f
    private var fadeTotal = 0f

    val clipNames: List<String> get() = clips.names

    /** Requests [state]. Returns true when a transition actually happened. */
    fun request(next: PetState, force: Boolean = false): Boolean {
        if (next == state && !force) {
            if (!next.isOneShot()) return false
            // A repeated one-shot (double tap) may restart, but not on every
            // pointer event of the same gesture.
            if (elapsed < RESTART_GUARD_SECONDS) return false
        }
        rememberOutgoing()
        state = next
        elapsed = 0f
        return true
    }

    /**
     * Advances the machine by [rawDt] seconds. The delta is clamped: a long
     * stall (GC pause, overlay focus change, screen on) must never teleport
     * the animation forward.
     */
    fun advance(rawDt: Float): Frame {
        val dt = if (rawDt.isNaN() || rawDt <= 0f) 0f else rawDt.coerceAtMost(MAX_STEP_SECONDS)
        elapsed += dt
        if (fadeRemaining > 0f) {
            previousTime += dt
            fadeRemaining = (fadeRemaining - dt).coerceAtLeast(0f)
        }

        var entered = false
        var settled = false
        if (state.isOneShot()) {
            if (state == PetState.RETURN_TO_IDLE) {
                if (elapsed >= returnToIdleSeconds) {
                    entered = request(PetState.IDLE, force = true)
                    settled = true
                }
            } else {
                val duration = safeDuration(currentIndex())
                if (elapsed >= duration) {
                    // The settle state fades from the finished one-shot pose
                    // into the idle pose instead of snapping.
                    entered = request(PetState.RETURN_TO_IDLE, force = true)
                }
            }
        }
        val index = currentIndex()
        val alpha = if (fadeTotal <= 0f) 1f else (1f - fadeRemaining / fadeTotal).coerceIn(0f, 1f)
        val frame = Frame(
            state = state,
            clipIndex = index,
            clipName = clips.nameFor(state),
            clipTime = if (state == PetState.RETURN_TO_IDLE) 0f else playhead(),
            previousIndex = if (alpha >= 1f) -1 else previousIndex,
            previousTime = previousTime,
            crossfadeAlpha = alpha,
            enteredState = entered,
            settledToIdle = settled,
        )
        if (alpha >= 1f) {
            previousIndex = -1
            fadeRemaining = 0f
        }
        return frame
    }

    /** Forces a clean restart of the current state (used after a model swap). */
    fun reset(next: PetState = PetState.IDLE) {
        previousIndex = -1
        previousTime = 0f
        fadeRemaining = 0f
        fadeTotal = 0f
        state = next
        elapsed = 0f
    }

    /** True while no clip could be resolved for the current state. */
    val hasClipForCurrentState: Boolean get() = currentIndex() >= 0

    private fun rememberOutgoing() {
        val outgoing = currentIndex()
        if (outgoing < 0) return
        previousIndex = outgoing
        previousTime = playhead()
        fadeTotal = crossfadeSeconds
        fadeRemaining = crossfadeSeconds
    }

    private fun currentIndex(): Int = clips.indexFor(state)

    private fun playhead(): Float {
        if (state == PetState.RETURN_TO_IDLE) return 0f
        val index = currentIndex()
        if (index < 0) return 0f
        val duration = safeDuration(index)
        return if (state.isOneShot()) elapsed.coerceAtMost(duration) else elapsed % duration
    }

    private fun safeDuration(index: Int): Float {
        if (index < 0) return MIN_DURATION
        val raw = durationOf(index)
        if (raw.isNaN() || raw.isInfinite() || raw <= MIN_DURATION) return MIN_DURATION
        return raw.coerceAtMost(MAX_DURATION)
    }

    companion object {
        /**
         * A single frame may never advance animation by more than 250 ms: at
         * 4 fps the playback speed stays correct, while a multi-second stall
         * (GC, overlay focus change) cannot teleport the clip.
         */
        const val MAX_STEP_SECONDS = 0.25f
        private const val RESTART_GUARD_SECONDS = 0.25f
        private const val MIN_DURATION = 0.001f
        private const val MAX_DURATION = 600f
    }
}
