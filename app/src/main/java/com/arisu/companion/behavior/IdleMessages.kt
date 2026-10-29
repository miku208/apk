package com.arisu.companion.behavior

/**
 * Local, pre-written idle lines. They are intentionally *not* produced by the
 * AI: a sulking bubble must never cost an API call, work offline, and never
 * fire a notification.
 */
object IdleMessages {

    val defaults: List<String> = listOf(
        "Arisu dicuekin lagi...",
        "Master, masih sibuk?",
        "Hmmph! Arisu ngambek!",
        "Arisu nungguin dari tadi...",
        "Halo? Masih di sana?",
        "Arisu bosan nih...",
    )

    /**
     * Picks a line for [petName], using [messages] when the character has its
     * own list. [seed] is normally a monotonically increasing counter so the
     * same line is not repeated twice in a row.
     */
    fun pick(petName: String, messages: List<String>, seed: Int): String {
        val pool = messages.map { it.trim() }.filter { it.isNotBlank() }
            .ifEmpty { defaults.map { it.replace("Arisu", petName) } }
        if (pool.isEmpty()) return "$petName ngambek..."
        val index = ((seed % pool.size) + pool.size) % pool.size
        return pool[index]
    }
}
