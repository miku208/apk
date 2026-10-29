package com.arisu.companion.pet

import com.arisu.companion.animation.PetState
import kotlinx.serialization.Serializable

/**
 * Per-character personality and behaviour. Every imported pet carries its own
 * copy, so switching characters never leaks one pet's prompt, language or
 * history into another.
 */
@Serializable
data class PetPersonality(
    /** Blank means "use the global personality from the Persona tab". */
    val systemInstruction: String = "",
    /** Empty means "use the global preferred language". */
    val preferredLanguage: String = "",
    val greetings: List<String> = emptyList(),
    val idleMessages: List<String> = emptyList(),
) {
    val isCustomised: Boolean
        get() = systemInstruction.isNotBlank() || preferredLanguage.isNotBlank() ||
            greetings.isNotEmpty() || idleMessages.isNotEmpty()
}

/**
 * One installed 3D character.
 *
 * [modelFile] is either the asset name for the bundled default character or
 * the file name inside the character's private directory
 * (`filesDir/pets/<id>/<modelFile>`). Imported files are copied there and are
 * never read from the SAF URI again, so the pet keeps working after the source
 * document is deleted or the SD card is removed.
 */
@Serializable
data class PetProfile(
    val id: String,
    val displayName: String,
    val modelFile: String,
    val bundled: Boolean = false,
    /** Clips discovered at import time, for the mapping editor. */
    val clipNames: List<String> = emptyList(),
    /** PetState name -> clip name. Only edits this character's mapping. */
    val animationOverrides: Map<String, String> = emptyMap(),
    /** Per-character playback speed multiplier (0.25 .. 3). */
    val animationSpeed: Float = 1f,
    val personality: PetPersonality = PetPersonality(),
    val modelBytes: Long = 0,
    val importedAt: Long = 0,
) {
    /** Session bucket for this character's chat history. */
    val sessionId: String get() = "pet-$id"

    fun overridesAsStates(): Map<PetState, String> = buildMap {
        animationOverrides.forEach { (key, clip) ->
            runCatching { PetState.valueOf(key) }.getOrNull()?.let { put(it, clip) }
        }
    }

    fun withOverride(state: PetState, clip: String?): PetProfile {
        val next = animationOverrides.toMutableMap()
        if (clip.isNullOrBlank()) next.remove(state.name) else next[state.name] = clip
        return copy(animationOverrides = next)
    }

    companion object {
        const val BUNDLED_ID = "bundled-arisu"
        const val BUNDLED_ASSET = "Arisu (Maid).glb"

        val BUNDLED_CLIPS = listOf(
            "Cafe_Reaction", "Cafe_Idle", "Cafe_Walk", "Formation_Idle", "Formation_Pickup",
            "Exs_Cutin", "Exs", "Exs_Rootmotion", "Victory_End", "Victory_End_Random",
            "Victory_Start", "Victory_Start_Random", "Normal_Idle", "Normal_Attack_Delay",
            "Normal_Attack_End", "Normal_Attack_Ing", "Normal_Attack_Start", "Normal_Callsign",
            "Normal_Callsign_Random", "Normal_Reload", "Normal_Reload_Random",
            "Move_End_Normal", "Move_Ing", "Vital_Death", "Vital_Dying_Ing", "Vital_Panic",
            "Vital_Retreat", "Public01", "ST_Attack_Ing", "ST_Death", "ST_Idle", "ST_Move_Ing",
        )

        /** The shipped character; always present, never deletable. */
        fun bundled(): PetProfile = PetProfile(
            id = BUNDLED_ID,
            displayName = "Arisu",
            modelFile = BUNDLED_ASSET,
            bundled = true,
            clipNames = BUNDLED_CLIPS,
            personality = PetPersonality(
                greetings = listOf("Halo Master~ Arisu siap menemani!"),
                idleMessages = listOf(
                    "Arisu dicuekin lagi...",
                    "Master, masih sibuk?",
                    "Hmmph! Arisu ngambek!",
                    "Arisu nungguin dari tadi...",
                ),
            ),
        )
    }
}
