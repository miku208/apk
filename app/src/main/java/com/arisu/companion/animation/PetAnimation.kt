package com.arisu.companion.animation

/**
 * Behavioral states the 3D character can be driven into.
 *
 * [IDLE], [WALK], [REACTION], [THINKING], [SPEAKING], [SULKING] and
 * [RETURN_TO_IDLE] are the authoritative states of [PetStateMachine].
 * [GREETING], [HAPPY] and [PANIC] are specialised reactions kept because the
 * chat mood tags and the existing gestures use them; they behave exactly like
 * [REACTION] (one shot, then back to idle) but prefer different clips.
 *
 * The model (Blue Archive "Aris (Maid)") ships clips with these names:
 * Cafe_Reaction, Cafe_Idle, Cafe_Walk, Formation_Idle, Formation_Pickup,
 * Exs_Cutin, Exs, Exs_Rootmotion, Victory_End, Victory_End_Random,
 * Victory_Start, Victory_Start_Random, Normal_Idle, Normal_Attack_Delay,
 * Normal_Attack_End, Normal_Attack_Ing, Normal_Attack_Start,
 * Normal_Callsign, Normal_Callsign_Random, Normal_Reload,
 * Normal_Reload_Random, Move_End_Normal, Move_Ing, Vital_Death,
 * Vital_Dying_Ing, Vital_Panic, Vital_Retreat, Public01, ST_Attack_Ing,
 * ST_Death, ST_Idle, ST_Move_Ing.
 *
 * Imported characters do NOT have to use those names: clip selection is
 * keyword based and every character can override the mapping per state.
 */
enum class PetState(private val oneShot: Boolean) {
    IDLE(false),
    WALK(false),
    THINKING(false),
    SPEAKING(true),
    SULKING(false),
    RETURN_TO_IDLE(true),
    REACTION(true),
    GREETING(true),
    HAPPY(true),
    PANIC(true),
    ;

    /** One-shot states play once and then settle back through [RETURN_TO_IDLE]. */
    fun isOneShot(): Boolean = oneShot

    /** Human label used by the animation mapping editor. */
    val label: String
        get() = when (this) {
            IDLE -> "Idle"
            WALK -> "Walk"
            THINKING -> "Thinking"
            SPEAKING -> "Speaking"
            SULKING -> "Sulking"
            RETURN_TO_IDLE -> "Return to idle"
            REACTION -> "Reaction"
            GREETING -> "Greeting"
            HAPPY -> "Happy"
            PANIC -> "Flustered"
        }

    companion object {
        /** States exposed in the mapping editor (RETURN_TO_IDLE follows IDLE). */
        val editable: List<PetState> = listOf(
            IDLE, WALK, THINKING, SPEAKING, SULKING, REACTION, GREETING, HAPPY, PANIC,
        )
    }
}

/**
 * Maps a state to the best existing GLB clip by keyword scoring, honouring
 * per-character [overrides] first (state name -> clip name).
 *
 * Nothing here assumes the Arisu clip names exist: unknown models fall back
 * through a chain of keywords and finally to "any clip", so a model without
 * animations simply reports `null` and the renderer keeps the bind pose.
 */
data class AnimationClips(
    val names: List<String>,
    val overrides: Map<PetState, String> = emptyMap(),
) {

    private val lowerNames: List<String> = names.map { it.lowercase() }

    /** Clip used for [state], or null when the model has no usable clip. */
    fun bestFor(state: PetState): String? {
        overrides[state]?.takeIf { it.isNotBlank() }?.let { wanted ->
            // Exact match first, then a case-insensitive one, then keep it only
            // if it really exists — a stale override must not silently break.
            names.firstOrNull { it == wanted }?.let { return it }
            names.firstOrNull { it.equals(wanted, ignoreCase = true) }?.let { return it }
        }
        return when (state) {
            PetState.IDLE -> pick(IDLE_KEYWORDS) ?: pick(ANY)
            PetState.WALK -> pick(WALK_KEYWORDS) ?: pick(IDLE_KEYWORDS) ?: pick(ANY)
            PetState.REACTION -> pick(REACTION_KEYWORDS) ?: pick(GREETING_KEYWORDS) ?: pick(IDLE_KEYWORDS) ?: pick(ANY)
            PetState.THINKING -> pick(THINKING_KEYWORDS) ?: pick(IDLE_KEYWORDS) ?: pick(ANY)
            PetState.SPEAKING -> pick(SPEAKING_KEYWORDS) ?: pick(IDLE_KEYWORDS) ?: pick(ANY)
            PetState.SULKING -> pick(SULKING_KEYWORDS) ?: pick(IDLE_KEYWORDS) ?: pick(ANY)
            // The settle state deliberately reuses the idle clip.
            PetState.RETURN_TO_IDLE -> pick(IDLE_KEYWORDS) ?: pick(ANY)
            PetState.GREETING -> pick(GREETING_KEYWORDS) ?: pick(IDLE_KEYWORDS) ?: pick(ANY)
            PetState.HAPPY -> pick(HAPPY_KEYWORDS) ?: pick(GREETING_KEYWORDS) ?: pick(ANY)
            PetState.PANIC -> pick(PANIC_KEYWORDS) ?: pick(IDLE_KEYWORDS) ?: pick(ANY)
        }
    }

    /** Index of [state]'s clip, or -1 when the asset has no animations at all. */
    fun indexFor(state: PetState): Int {
        val name = bestFor(state) ?: return -1
        return names.indexOf(name)
    }

    fun nameFor(state: PetState): String? = bestFor(state)

    /** Keywords are tried in priority order; within a keyword, clips keep their order. */
    private fun pick(keywords: List<String>): String? =
        keywords.firstNotNullOfOrNull { keyword ->
            val i = lowerNames.indexOfFirst { keyword in it }
            if (i >= 0) names[i] else null
        }

    companion object {
        private val IDLE_KEYWORDS = listOf(
            "cafe_idle", "formation_idle", "normal_idle", "st_idle", "idle",
        )
        private val WALK_KEYWORDS = listOf("cafe_walk", "walk", "move_ing", "st_move", "run")
        private val REACTION_KEYWORDS = listOf("cafe_reaction", "reaction", "callsign", "pickup", "wave")
        private val GREETING_KEYWORDS = listOf("callsign", "pickup", "reaction", "greet", "wave")
        private val THINKING_KEYWORDS = listOf("pick", "reload", "think")
        private val SPEAKING_KEYWORDS = listOf("callsign", "public", "reaction")
        private val HAPPY_KEYWORDS = listOf("victory_start", "victory", "cutin", "cheer")
        private val SULKING_KEYWORDS = listOf("sulk", "pout", "angry", "refuse", "protest", "huff")
        private val PANIC_KEYWORDS = listOf("panic", "dying", "retreat", "sorry")
        private val ANY = listOf("")
    }
}
