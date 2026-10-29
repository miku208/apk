package com.arisu.companion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderProfileTest {

    private fun profile(
        baseUrl: String = "https://api.example.com/v1",
        modelId: String = "some-model",
        keyCipher: String = "cipher",
    ) = ProviderProfile(
        id = "t",
        name = "Test",
        baseUrl = baseUrl,
        apiKeyCipher = keyCipher,
        modelId = modelId,
    )

    @Test
    fun `google ai studio is the only builtin provider`() {
        val builtins = ProviderProfile.builtins()
        assertEquals(1, builtins.size)
        val studio = builtins.single()
        assertEquals(ProviderProfile.GEMINI_ID, studio.id)
        assertEquals("Google AI Studio", studio.name)
        assertEquals(ProviderKind.GEMINI, studio.kind)
        assertTrue("base url is the AI Studio API root", studio.baseUrl.startsWith("https://generativelanguage.googleapis.com"))
        assertEquals(GeminiProtocol.DEFAULT_CHAT_MODEL, studio.modelId)
        assertEquals(GeminiProtocol.DEFAULT_IMAGE_MODEL, studio.imageModelId)
        // Gemini authenticates with ?key= inside the URL, never a Bearer header.
        assertEquals(ProviderProfile.AuthScheme.NONE, studio.authScheme)
    }

    @Test
    fun `default profile is a gemini profile`() {
        val default = ProviderProfile.default()
        assertEquals(ProviderKind.GEMINI, default.kind)
        assertTrue(default.isGemini)
        assertTrue(default.baseUrl.startsWith("https://generativelanguage.googleapis.com"))
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
