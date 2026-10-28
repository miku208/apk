package com.arisu.companion.animation

/**
 * Behavioral states the 3D character can be driven into. The available GLB
 * animation clips decide which state can actually use which clip — see
 * [AnimationMapper]. The model (Blue Archive "Aris (Maid)") ships clips with
 * these names: Cafe_Reaction, Cafe_Idle, Cafe_Walk, Formation_Idle,
 * Formation_Pickup, Exs_Cutin, Exs, Exs_Rootmotion, Victory_End,
 * Victory_End_Random, Victory_Start, Victory_Start_Random, Normal_Idle,
 * Normal_Attack_Delay, Normal_Attack_End, Normal_Attack_Ing,
 * Normal_Attack_Start, Normal_Callsign, Normal_Callsign_Random,
 * Normal_Reload, Normal_Reload_Random, Move_End_Normal, Move_Ing,
 * Vital_Death, Vital_Dying_Ing, Vital_Panic, Vital_Retreat, Public01,
 * ST_Attack_Ing, ST_Death, ST_Idle, ST_Move_Ing.
 */
enum class PetState {
    IDLE,
    GREETING,
    THINKING,
    SPEAKING,
    HAPPY,
    PANIC,
}

/**
 * Maps a state to the best existing GLB clip by keyword scoring, falling back
 * to whatever clips actually exist (procedural flourish is the renderer's job).
 */
data class AnimationClips(val names: List<String>) {

    fun bestFor(state: PetState): String? = when (state) {
        PetState.IDLE -> pick(IDLE_KEYWORDS) ?: pick(ANY)
        PetState.GREETING -> pick(GREETING_KEYWORDS) ?: pick(IDLE_KEYWORDS) ?: pick(ANY)
        PetState.THINKING -> pick(THINKING_KEYWORDS) ?: pick(IDLE_KEYWORDS) ?: pick(ANY)
        PetState.SPEAKING -> pick(SPEAKING_KEYWORDS) ?: pick(IDLE_KEYWORDS) ?: pick(ANY)
        PetState.HAPPY -> pick(HAPPY_KEYWORDS) ?: pick(GREETING_KEYWORDS) ?: pick(ANY)
        PetState.PANIC -> pick(PANIC_KEYWORDS) ?: pick(IDLE_KEYWORDS) ?: pick(ANY)
    }

    /** Keywords are tried in priority order; within a keyword, clips keep their order. */
    private fun pick(keywords: List<String>): String? =
        keywords.firstNotNullOfOrNull { keyword ->
            names.firstOrNull { name -> keyword in name.lowercase() }
        }

    companion object {
        private val IDLE_KEYWORDS = listOf(
            "cafe_idle", "formation_idle", "normal_idle", "st_idle",
        ) + listOf("idle")
        private val GREETING_KEYWORDS = listOf("callsign", "pickup", "reaction", "greet", "wave")
        private val THINKING_KEYWORDS = listOf("pick", "reload", "think")
        private val SPEAKING_KEYWORDS = listOf("callsign", "public", "reaction")
        private val HAPPY_KEYWORDS = listOf("victory_start", "victory", "cutin", "cheer")
        private val PANIC_KEYWORDS = listOf("panic", "dying", "retreat", "sorry")
        private val FALLBACK_SOFT = listOf("exs")
        private val ANY = listOf("")
    }
}
