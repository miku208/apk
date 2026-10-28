package com.arisu.companion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryExtractorTest {

    @Test
    fun `proposes sentences containing remember`() {
        val candidates = MemoryExtractor.extract(
            "Hey! Remember that I play on EU servers. Today was fine.",
        )
        assertTrue(candidates.single().contains("play on EU servers"))
    }

    @Test
    fun `does not propose without triggers`() {
        assertEquals(emptyList<String>(), MemoryExtractor.extract("Good morning, how are you?"))
    }

    @Test
    fun `cleans the trigger phrase`() {
        val candidates = MemoryExtractor.extract("don't forget to call Alex tomorrow")
        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.first().startsWith("Should"))
    }

    @Test
    fun `multi line messages yield distinct memories`() {
        val candidates = MemoryExtractor.extract(
            "remember that I hate horror movies\nnote that my cat is named Pixel",
        )
        assertEquals(2, candidates.size)
    }
}
