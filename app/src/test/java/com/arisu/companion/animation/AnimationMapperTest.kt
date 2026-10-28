package com.arisu.companion.animation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class AnimationMapperTest {

    private val arisuClips = listOf(
        "Cafe_Reaction", "Cafe_Idle", "Cafe_Walk", "Formation_Idle", "Formation_Pickup",
        "Exs_Cutin", "Exs", "Exs_Rootmotion", "Victory_End", "Victory_End_Random",
        "Victory_Start", "Victory_Start_Random", "Normal_Idle", "Normal_Attack_Delay",
        "Normal_Attack_End", "Normal_Attack_Ing", "Normal_Attack_Start", "Normal_Callsign",
        "Normal_Callsign_Random", "Normal_Reload", "Normal_Reload_Random", "Move_End_Normal",
        "Move_Ing", "Vital_Death", "Vital_Dying_Ing", "Vital_Panic", "Vital_Retreat",
        "Public01", "ST_Attack_Ing", "ST_Death", "ST_Idle", "ST_Move_Ing",
    )

    @Test
    fun `maps known states to the best available clips`() {
        val clips = AnimationClips(arisuClips)
        assertEquals("Cafe_Idle", clips.bestFor(PetState.IDLE))
        assertEquals("Normal_Callsign", clips.bestFor(PetState.GREETING))
        assertEquals("Formation_Pickup", clips.bestFor(PetState.THINKING))
        assertEquals("Victory_Start", clips.bestFor(PetState.HAPPY))
        assertEquals("Vital_Panic", clips.bestFor(PetState.PANIC))
        // SPEAKING falls back to wave-ish gestures, never to a death clip.
        val speaking = clips.bestFor(PetState.SPEAKING)!!
        assertTrue(!speaking.contains("Death"))
    }

    @Test
    fun `falls back gracefully when clips are missing`() {
        val clips = AnimationClips(listOf("Wave", "Jump"))
        assertEquals("Wave", clips.bestFor(PetState.IDLE))
        assertNull(AnimationClips(emptyList()).bestFor(PetState.IDLE))
    }

    @Test
    fun `emotion tags resolve to states`() {
        assertEquals(PetState.HAPPY, EmotionFromTag("happy"))
        assertEquals(PetState.THINKING, EmotionFromTag("think"))
        assertEquals(PetState.PANIC, EmotionFromTag("sorry"))
        assertNull(EmotionFromTag("banana"))
    }

    private fun EmotionFromTag(tag: String): PetState? =
        com.arisu.companion.ai.EmotionTagFilter.fromTag(tag)
}
