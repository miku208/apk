package com.arisu.companion.ai

import kotlinx.serialization.Serializable

/**
 * Wire protocol a profile speaks.
 *
 * [OPENAI_COMPAT] is the existing format (`/chat/completions`,
 * `Authorization: Bearer <key>`, `delta.content` chunks). [GEMINI] is Google AI
 * Studio's native API, which has a different endpoint, auth header and body
 * shape — see [GeminiProtocol].
 */
@Serializable
enum class ProviderKind { OPENAI_COMPAT, GEMINI }

/**
 * Bring-your-own-key provider configuration. Everything is user-editable.
 * The API key is stored encrypted at rest (AES/GCM, Android Keystore).
 */
@Serializable
data class ProviderProfile(
    val id: String,
    val name: String,
    /**
     * Base URL without a trailing slash. For [ProviderKind.OPENAI_COMPAT] this
     * must NOT include "/chat/completions"; for [ProviderKind.GEMINI] it is the
     * API root, e.g. `https://generativelanguage.googleapis.com/v1beta`.
     */
    val baseUrl: String,
    /** Stored encrypted at rest. Never logged. */
    val apiKeyCipher: String = "",
    val modelId: String = "",
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val streaming: Boolean = true,
    /** Same cipher as the header value would be; custom endpoints may need it. */
    val authScheme: AuthScheme = AuthScheme.BEARER,
    val kind: ProviderKind = ProviderKind.OPENAI_COMPAT,
    /** Gemini only: model used for image requests (empty = disabled). */
    val imageModelId: String = "",
) {
    @Serializable
    enum class AuthScheme { BEARER, NONE }

    val isGemini: Boolean get() = kind == ProviderKind.GEMINI

    fun clone(id: String = java.util.UUID.randomUUID().toString()): ProviderProfile =
        copy(id = id, name = "$name (copy)")

    companion object {
        /**
         * Built-in provider catalog. Every https endpoint here is the provider's
         * documented API host; the two "unverified" entries keep a manual base
         * URL the user is expected to confirm.
         */
        fun builtins(): List<ProviderProfile> = listOf(
            ProviderProfile(
                id = "gemini",
                name = "Google Gemini",
                baseUrl = GeminiProtocol.DEFAULT_BASE_URL,
                modelId = GeminiProtocol.DEFAULT_CHAT_MODEL,
                imageModelId = GeminiProtocol.DEFAULT_IMAGE_MODEL,
                kind = ProviderKind.GEMINI,
                authScheme = AuthScheme.NONE,
            ),
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

        const val GEMINI_ID = "gemini"
        const val GEMINI_KEY_URL = "https://aistudio.google.com/apikey"
    }

    fun validate(): List<String> {
        val problems = mutableListOf<String>()
        val url = baseUrl.trim().removeSuffix("/")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            problems += "Base URL harus diawali http:// atau https://"
        }
        if (kind == ProviderKind.OPENAI_COMPAT) {
            if (url.endsWith("/chat/completions")) {
                problems += "Base URL sudah berakhiran /chat/completions — hapus bagian itu"
            }
        } else if (url.contains(":generateContent") || url.contains(":streamGenerateContent")) {
            problems += "Untuk Gemini, isi base URL tanpa metode (:generateContent)"
        }
        if (apiKeyCipher.isBlank()) problems += "API key belum diisi untuk profil ini"
        if (modelId.isBlank()) problems += "Model ID masih kosong"
        return problems
    }
}
