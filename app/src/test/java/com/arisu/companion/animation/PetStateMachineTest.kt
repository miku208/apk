package com.arisu.companion.animation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Behavioural tests for the authoritative animation state machine. */
class PetStateMachineTest {

    private val clips = AnimationClips(
        listOf("Cafe_Idle", "Cafe_Walk", "Cafe_Reaction", "Victory_Start", "Vital_Panic"),
    )

    private fun machine(
        crossfade: Float = 0.2f,
        returnSeconds: Float = 0.3f,
        durations: Map<Int, Float> = mapOf(0 to 2f, 1 to 1f, 2 to 1f, 3 to 1f, 4 to 1f),
        clipsOverride: AnimationClips = clips,
    ) = PetStateMachine(
        clips = clipsOverride,
        durationOf = { durations[it] ?: 1f },
        crossfadeSeconds = crossfade,
        returnToIdleSeconds = returnSeconds,
    )

    @Test
    fun `starts idle and loops the idle clip`() {
        val m = machine()
        assertEquals(PetState.IDLE, m.state)
        repeat(5) { m.advance(0.2f) }
        assertEquals(1.0f, m.advance(0f).clipTime, 1e-4f)
        repeat(5) { m.advance(0.2f) }
        assertEquals("wraps after the 2 s idle clip", 0f, m.advance(0f).clipTime, 1e-4f)
    }

    @Test
    fun `a single frame never advances more than the clamp`() {
        val m = machine()
        val frame = m.advance(5f) // e.g. a GC pause reported as one frame
        assertEquals(PetStateMachine.MAX_STEP_SECONDS, m.elapsed, 1e-5f)
        assertEquals(PetStateMachine.MAX_STEP_SECONDS, frame.clipTime, 1e-5f)
    }

    @Test
    fun `negative and NaN deltas are ignored`() {
        val m = machine()
        m.advance(-1f)
        m.advance(Float.NaN)
        assertEquals(0f, m.elapsed, 1e-6f)
    }

    @Test
    fun `state changes cross-fade instead of snapping`() {
        val m = machine(crossfade = 0.2f)
        m.advance(0.2f)
        assertTrue(m.request(PetState.REACTION))
        val start = m.advance(0f)
        assertEquals(2, start.clipIndex)
        assertEquals("fade starts fully on the outgoing clip", 0f, start.crossfadeAlpha, 1e-4f)
        assertNotEquals(-1, start.previousIndex)
        assertEquals(0.5f, m.advance(0.1f).crossfadeAlpha, 1e-4f)
        val done = m.advance(0.1f)
        assertEquals(1f, done.crossfadeAlpha, 1e-4f)
        assertEquals(-1, done.previousIndex)
    }

    @Test
    fun `one shot settles through return to idle exactly once`() {
        val m = machine(returnSeconds = 0.3f)
        m.request(PetState.REACTION, force = true)
        repeat(4) { m.advance(0.25f) } // reaction lasts exactly 1.0 s
        assertEquals(PetState.RETURN_TO_IDLE, m.state)
        val settled = mutableListOf<Boolean>()
        repeat(4) { settled += m.advance(0.25f).settledToIdle }
        assertEquals(PetState.IDLE, m.state)
        assertEquals("reported the settle exactly once", 1, settled.count { it })
    }

    @Test
    fun `requesting the same looping state twice does nothing`() {
        val m = machine()
        repeat(2) { m.advance(0.1f) }
        assertFalse(m.request(PetState.IDLE))
        assertEquals(0.2f, m.elapsed, 1e-4f)
    }

    @Test
    fun `a repeated one shot restarts only after the guard window`() {
        val m = machine()
        m.request(PetState.GREETING, force = true)
        m.advance(0.1f)
        assertFalse("too early to restart", m.request(PetState.GREETING))
        repeat(3) { m.advance(0.1f) }
        assertTrue(m.request(PetState.GREETING))
        assertEquals(0f, m.elapsed, 1e-5f)
    }

    @Test
    fun `a model without animations reports no clip and keeps running`() {
        val m = machine(clipsOverride = AnimationClips(emptyList()))
        val frame = m.advance(0.2f)
        assertEquals(-1, frame.clipIndex)
        assertEquals(null, frame.clipName)
        assertFalse(m.hasClipForCurrentState)
        m.request(PetState.HAPPY, force = true)
        assertEquals(-1, m.advance(0.1f).clipIndex)
    }

    @Test
    fun `a zero length clip still returns to idle`() {
        val m = machine(durations = mapOf(0 to 1f, 4 to 0f))
        m.request(PetState.PANIC, force = true)
        m.advance(0.05f)
        assertEquals(PetState.RETURN_TO_IDLE, m.state)
    }

    @Test
    fun `per character overrides win over keyword mapping`() {
        val custom = AnimationClips(
            names = listOf("Idle", "Wave", "Sit"),
            overrides = mapOf(PetState.SULKING to "Sit", PetState.IDLE to "Idle"),
        )
        assertEquals("Sit", custom.bestFor(PetState.SULKING))
        assertEquals("Idle", custom.bestFor(PetState.IDLE))
        // A stale override that is not in the file falls back to keywords.
        val stale = AnimationClips(listOf("Idle"), mapOf(PetState.SULKING to "DeletedClip"))
        assertEquals("Idle", stale.bestFor(PetState.SULKING))
    }

    @Test
    fun `walk state prefers a movement clip`() {
        assertEquals("Cafe_Walk", clips.bestFor(PetState.WALK))
    }
}
