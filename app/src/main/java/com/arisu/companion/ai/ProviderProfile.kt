package com.arisu.companion.ai

import kotlinx.serialization.Serializable

/**
 * Bring-your-own-key provider configuration. Everything is user-editable:
 * we implement the widely supported OpenAI-compatible chat completions
 * format ("/chat/completions", "Authorization: Bearer <key>",
 * messages array, `delta.content` streaming chunks).
 */
@Serializable
data class ProviderProfile(
    val id: String,
    val name: String,
    /** OpenAI-compatible base URL, WITHOUT trailing slash, no "/chat/completions" suffix. */
    val baseUrl: String,
    /** Stored encrypted at rest (AES/GCM, Android Keystore). */
    val apiKeyCipher: String = "",
    val modelId: String = "",
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val streaming: Boolean = true,
    /** Same cipher as the header value would be; custom endpoints may need it. */
    val authScheme: AuthScheme = AuthScheme.BEARER,
) {
    @Serializable
    enum class AuthScheme { BEARER, NONE }

    fun clone(id: String = java.util.UUID.randomUUID().toString()): ProviderProfile =
        copy(id = id, name = "$name (copy)")

    companion object {
        /**
         * Built-in provider catalog. Each entry documents exactly what is verified;
         * unverifiable ones ship with manual configuration nudge text.
         */
        fun builtins(): List<ProviderProfile> = listOf(
            ProviderProfile(
                id = "nvidia",
                name = "NVIDIA API",
                baseUrl = "https://integrate.api.nvidia.com/v1",
                modelId = "meta/llama-3.1-8b-instruct",
            ),
            ProviderProfile(
                id = "tokenharbor",
                name = "TokenHarbor",
                // OpenAI-compatible layout assumed but NOT independently verified —
                // keep the base URL editable and confirm the docs of your account.
                baseUrl = "https://api.tokenharbor.com/v1",
                modelId = "",
            ),
            ProviderProfile(
                id = "openrouter",
                name = "OpenRouter",
                baseUrl = "https://openrouter.ai/api/v1",
                modelId = "openai/gpt-4o-mini",
            ),
            ProviderProfile(
                id = "custom",
                name = "Custom OpenAI-compatible",
                baseUrl = "http://localhost:1234/v1",
                modelId = "",
            ),
        )

        fun default(): ProviderProfile = builtins().first().clone(id = "default")
    }

    fun validate(): List<String> {
        val problems = mutableListOf<String>()
        val url = baseUrl.trim().removeSuffix("/")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            problems += "Base URL must start with http:// or https://"
        }
        if (url.endsWith("/chat/completions")) {
            problems += "Base URL already ends with /chat/completions — remove that suffix"
        }
        if (apiKeyCipher.isBlank()) problems += "API key is missing in this profile"
        if (modelId.isBlank()) problems += "Model ID is empty"
        return problems
    }
}
