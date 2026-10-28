package com.arisu.companion.ai

import com.arisu.companion.animation.PetState

/**
 * Streams arrive in chunks, so a mood tag like [[emotion:happy]] can be split
 * across chunks. This filter buffers anything that might still grow into a tag,
 * hides complete tags from the displayed text and reports them as [PetState]s.
 */
class EmotionTagFilter {

    private var pending = StringBuilder()

    /**
     * Feed one streamed chunk.
     * @return displayable text (may be empty) and any finished emotion detected.
     */
    fun feed(chunk: String): Pair<String, PetState?> {
        pending.append(chunk)
        var display = StringBuilder()
        var state: PetState? = null
        while (true) {
            val text = pending.toString()
            val open = text.indexOf(MARK_START)
            if (open == -1) {
                // No tag start: release everything except a possible partial prefix.
                val safe = text.length - possiblePartialSuffixLength(text)
                if (safe > 0) {
                    display.append(text.take(safe))
                    pending = StringBuilder(text.substring(safe))
                }
                return display.toString() to state
            }
            if (open > 0) {
                display.append(text.take(open))
                pending = StringBuilder(text.substring(open))
                continue
            }
            // pending starts with a possible tag; wait for the close or decide it is prosaic.
            val close = text.indexOf(MARK_END)
            if (close == -1) {
                // If this is definitely not going to be a tag, release a bit early.
                if (text.length > MAX_TAG_LENGTH) {
                    display.append(text)
                    pending = StringBuilder()
                    return display.toString() to state
                }
                return display.toString() to state
            }
            val inner = text.substring(MARK_START.length, close).trim()
            val mapped = fromTag(inner)
            if (mapped == null) {
                // Not an emotion tag — release it verbatim (prose can legitimately
                // use double-bracket text).
                display.append(MARK_START).append(text.substring(MARK_START.length, close + MARK_END.length))
            } else {
                state = mapped
                // Avoid a double space when text surrounds the removed tag.
                if (display.isNotEmpty() && display.last() == ' ' &&
                    close + MARK_END.length < text.length && text[close + MARK_END.length] == ' '
                ) {
                    display.setLength(display.length - 1)
                }
            }
            pending = StringBuilder(text.substring(close + MARK_END.length))
        }
    }

    /** Call at end-of-stream to flush anything still buffered. */
    fun flush(): String {
        val rest = pending.toString()
        pending = StringBuilder()
        return rest
    }

    private fun possiblePartialSuffixLength(text: String): Int {
        // Longest suffix of text that is a prefix of "[["
        if (text.endsWith("[")) return 1
        return 0
    }

    companion object {
        private const val MARK_START = "[["
        private const val MARK_END = "]]"
        private const val MAX_TAG_LENGTH = 64

        fun fromTag(tag: String): PetState? =
            when (tag.lowercase().trim().removePrefix("emotion:")) {
            "happy", ":happy:", "joy" -> PetState.HAPPY
            "think", "thinking", "hmm" -> PetState.THINKING
            "reply", "speaking", "talk" -> PetState.SPEAKING
            "sorry", "panic", "confused" -> PetState.PANIC
            "idle" -> PetState.IDLE
            else -> null
        }
    }
}
