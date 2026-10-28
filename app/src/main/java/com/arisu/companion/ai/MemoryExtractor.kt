package com.arisu.companion.ai

/**
 * Finds candidate sentences the user likely wants remembered. Nothing is stored
 * without explicit user approval — the extractor only proposes candidates.
 */
object MemoryExtractor {

    private val triggerWords =
        Regex("""\b(remember that|remember this|keep in mind|don'?t forget|note that)\b""", RegexOption.IGNORE_CASE)
    private val sentenceSplit = Regex("""(?<=[.!?…])\s+|\n+""")

    fun extract(userText: String): List<String> {
        if (!triggerWords.containsMatchIn(userText)) return emptyList()
        return sentenceSplit.split(userText)
            .map { it.trim() }
            .filter { it.isNotEmpty() && triggerWords.containsMatchIn(it) }
            .map { it.replace(Regex("""\bremember (that|this)\b[,:]? ?""", RegexOption.IGNORE_CASE), "") }
            .map { it.replace(Regex("""\bdon'?t forget (that|to)\b[,:]? ?""", RegexOption.IGNORE_CASE), "should ") }
            .map { it.replace(Regex("""\bkeep in mind\b[,:]? ?""", RegexOption.IGNORE_CASE), "") }
            .map { it.replace(Regex("""\bnote that\b[,:]? ?""", RegexOption.IGNORE_CASE), "") }
            .filter { it.isBlank().not() }
            .map { it.trimEnd('.', '!', '?', ',', ' ') }
            .map { it.replaceFirstChar { c -> c.uppercase() } }
            .distinct()
    }
}
