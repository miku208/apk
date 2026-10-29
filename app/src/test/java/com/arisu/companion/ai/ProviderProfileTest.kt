package com.arisu.companion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderProfileTest {

    private fun profile(
        baseUrl: String = "https://integrate.api.nvidia.com/v1",
        modelId: String = "meta/llama-3.1-8b-instruct",
        keyCipher: String = "cipher",
    ) = ProviderProfile(
        id = "t",
        name = "Test",
        baseUrl = baseUrl,
        apiKeyCipher = keyCipher,
        modelId = modelId,
    )

    @Test
    fun `builtins exist and validate`() {
        val builtins = ProviderProfile.builtins()
        assertEquals(5, builtins.size)
        assertTrue("Gemini is a first-class provider", builtins.any { it.kind == ProviderKind.GEMINI })
        builtins.forEach { p ->
            assertTrue("builtin ${p.id} has a base url", p.baseUrl.startsWith("http"))
            if (p.id != "custom") {
                // Cloud providers must be verified https endpoints; "custom"
                // deliberately defaults to localhost (local LLM servers).
                assertTrue("builtin ${p.id} uses https", p.baseUrl.startsWith("https://"))
            }
        }
    }

    @Test
    fun `validation catches problems`() {
        assertTrue(profile(keyCipher = "", modelId = "").validate().size >= 2)
        assertTrue(profile(baseUrl = "not-a-url").validate().any { it.contains("http") })
        assertTrue(
            profile(baseUrl = "https://api.example.com/v1/chat/completions").validate()
                .any { it.contains("/chat/completions") },
        )
        assertTrue(profile().validate().isEmpty())
    }

    @Test
    fun `clone gets fresh id`() {
        val copy = profile().clone()
        assertTrue(copy.id != "t")
        assertTrue(copy.name.contains("(copy)"))
    }

    @Test
    fun `gemini profile validates its own endpoint shape`() {
        val gemini = ProviderProfile(
            id = "g",
            name = "Google Gemini",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            apiKeyCipher = "cipher",
            modelId = "gemini-3.1-flash-lite",
            kind = ProviderKind.GEMINI,
        )
        assertTrue(gemini.validate().isEmpty())
        assertTrue(
            gemini.copy(baseUrl = "https://generativelanguage.googleapis.com/v1beta/models/x:generateContent")
                .validate().any { it.contains("Gemini") },
        )
    }
}
