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
         * Built-in provider catalog.
         *
         * The app ships exactly one provider: Google AI Studio's native Gemini
         * API (`https://aistudio.google.com/apikey`). Every account uses its own
         * key, and nothing else is preconfigured, so there is no ambiguity about
         * which endpoint a chat goes to.
         */
        fun builtins(): List<ProviderProfile> = listOf(
            ProviderProfile(
                id = GEMINI_ID,
                name = "Google AI Studio",
                baseUrl = GeminiProtocol.DEFAULT_BASE_URL,
                modelId = GeminiProtocol.DEFAULT_CHAT_MODEL,
                imageModelId = GeminiProtocol.DEFAULT_IMAGE_MODEL,
                kind = ProviderKind.GEMINI,
                authScheme = AuthScheme.NONE,
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
