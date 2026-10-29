package com.arisu.companion.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Wire format for the Google AI Studio / Gemini API.
 *
 * Verified against the public API reference (October 2026):
 *  - `POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent`
 *  - `POST .../{model}:streamGenerateContent?alt=sse`
 *  - request: `contents[].role` is `user`/`model`, `systemInstruction.parts[].text`,
 *    `generationConfig.{temperature,maxOutputTokens,responseModalities}`
 *  - response: `candidates[].content.parts[].text` **and** `parts[].inlineData`
 *    (`mimeType`, base64 `data`) for image models, `finishReason`,
 *    `promptFeedback.blockReason`, `error.{code,status,message}`
 *
 * Pure functions only: fully unit-testable without a device or network.
 */
object GeminiProtocol {

    const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
    const val DEFAULT_CHAT_MODEL = "gemini-3.1-flash-lite"
    const val DEFAULT_IMAGE_MODEL = "gemini-3.1-flash-image"

    /** Verified stable chat models (docs: ai.google.dev/gemini-api/docs/models). */
    val chatModels = listOf(
        "gemini-3.1-flash-lite",
        "gemini-3.5-flash-lite",
        "gemini-3.8-flash",
        "gemini-3.6-flash",
        "gemini-3.1-pro-preview",
    )

    val imageModels = listOf(
        "gemini-3.1-flash-image",
        "gemini-3.1-flash-lite-image",
        "gemini-2.5-flash-image",
    )

    data class InlineImage(val mimeType: String, val dataBase64: String)

    data class Chunk(
        val text: String? = null,
        val images: List<InlineImage> = emptyList(),
        val finishReason: String? = null,
        val blockReason: String? = null,
    )

    /** Full URL for a call, including the `?alt=sse` streaming switch. */
    fun endpoint(baseUrl: String, modelId: String, streaming: Boolean): String {
        val base = baseUrl.ifBlank { DEFAULT_BASE_URL }.trim().removeSuffix("/")
        val model = normaliseModel(modelId)
        return if (streaming) {
            "$base/models/$model:streamGenerateContent?alt=sse"
        } else {
            "$base/models/$model:generateContent"
        }
    }

    fun normaliseModel(modelId: String): String =
        modelId.trim().removePrefix("models/").ifBlank { DEFAULT_CHAT_MODEL }

    /**
     * @param history role/content pairs; `assistant` is mapped to Gemini's `model`.
     */
    fun buildRequest(
        systemInstruction: String,
        history: List<Pair<String, String>>,
        temperature: Float?,
        maxTokens: Int?,
        imageModel: Boolean = false,
    ): String {
        val root = buildJsonObject {
            if (systemInstruction.isNotBlank()) {
                put(
                    "systemInstruction",
                    buildJsonObject {
                        put("parts", buildJsonArray { add(part(systemInstruction)) })
                    },
                )
            }
            put("contents", buildJsonArray {
                mergeTurns(history).forEach { (role, text) ->
                    add(
                        buildJsonObject {
                            put("role", JsonPrimitive(if (role == "assistant") "model" else "user"))
                            put("parts", buildJsonArray { add(part(text)) })
                        },
                    )
                }
            })
            put(
                "generationConfig",
                buildJsonObject {
                    temperature?.let { put("temperature", JsonPrimitive(it.coerceIn(0f, 2f))) }
                    maxTokens?.let { put("maxOutputTokens", JsonPrimitive(it.coerceIn(1, 65_536))) }
                    put("candidateCount", JsonPrimitive(1))
                    if (imageModel) {
                        put(
                            "responseModalities",
                            buildJsonArray { add("TEXT"); add("IMAGE") },
                        )
                    }
                },
            )
        }
        return root.toString()
    }

    /** Parses one SSE `data:` payload or a whole non-streaming body. */
    fun parseChunk(payload: String): Chunk? {
        val root = try {
            json.parseToJsonElement(payload).jsonObject
        } catch (e: Exception) {
            return null
        }
        val blockReason = root["promptFeedback"]?.jsonObject
            ?.get("blockReason")?.jsonPrimitive?.contentOrNull
        val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
        val finishReason = candidate?.get("finishReason")?.jsonPrimitive?.contentOrNull
        val parts = candidate?.get("content")?.jsonObject?.get("parts")?.jsonArray
        val text = StringBuilder()
        val images = mutableListOf<InlineImage>()
        parts?.forEach { element ->
            val part = element.jsonObject
            part["text"]?.jsonPrimitive?.contentOrNull?.let { text.append(it) }
            val inline = part["inlineData"]?.jsonObject ?: part["inline_data"]?.jsonObject
            if (inline != null) {
                val mime = inline["mimeType"]?.jsonPrimitive?.contentOrNull
                    ?: inline["mime_type"]?.jsonPrimitive?.contentOrNull
                    ?: "image/png"
                val data = inline["data"]?.jsonPrimitive?.contentOrNull
                if (!data.isNullOrBlank()) images += InlineImage(mime, data)
            }
        }
        if (text.isEmpty() && images.isEmpty() && finishReason == null && blockReason == null) return null
        return Chunk(
            text = text.toString().ifEmpty { null },
            images = images,
            finishReason = finishReason,
            blockReason = blockReason,
        )
    }

    /** Turns an HTTP error body into a message a normal user can act on. */
    fun mapError(httpCode: Int, body: String): String {
        val parsed = parseError(body)
        val raw = parsed.second
        val lower = raw.lowercase()
        return when {
            parsed.first == "RESOURCE_EXHAUSTED" || httpCode == 429 || "quota" in lower ->
                "Kuota Gemini habis atau permintaan terlalu cepat (HTTP $httpCode). Tunggu sebentar atau cek kuota di AI Studio."
            httpCode == 403 || parsed.first == "PERMISSION_DENIED" || "billing" in lower ->
                "Akses ditolak (HTTP 403). Pastikan API key aktif dan billing/kuota proyek Google Cloud-mu mengizinkan model ini."
            httpCode == 400 && ("api key" in lower || parsed.first == "INVALID_ARGUMENT") ->
                "API key Gemini tidak valid atau parameter ditolak. Tempel ulang key dari aistudio.google.com/apikey (HTTP 400)."
            httpCode == 401 || parsed.first == "UNAUTHENTICATED" ->
                "API key Gemini tidak dikenali (HTTP 401). Buat key baru di aistudio.google.com/apikey."
            httpCode == 404 || "not found" in lower ->
                "Model tidak ditemukan (HTTP 404). Cek ID model — contoh yang valid: ${DEFAULT_CHAT_MODEL}."
            httpCode == 413 || "too large" in lower ->
                "Riwayat percakapan terlalu panjang untuk model ini. Hapus sebagian riwayat lalu coba lagi."
            httpCode >= 500 ->
                "Server Gemini sedang bermasalah (HTTP $httpCode). Coba lagi beberapa saat."
            else -> "HTTP $httpCode: ${raw.take(220).ifBlank { "tanpa keterangan" }}"
        }
    }

    private fun parseError(body: String): Pair<String, String> = try {
        val root = json.parseToJsonElement(body).jsonObject
        val error = root["error"]
        if (error is JsonObject) {
            val status = error["status"]?.jsonPrimitive?.contentOrNull ?: ""
            val message = error["message"]?.jsonPrimitive?.contentOrNull ?: body
            status to message
        } else {
            "" to (root["message"]?.jsonPrimitive?.contentOrNull ?: body)
        }
    } catch (e: Exception) {
        "" to body
    }

    private fun mergeTurns(history: List<Pair<String, String>>): List<Pair<String, String>> {
        val merged = mutableListOf<Pair<String, String>>()
        history.forEach { (role, text) ->
            if (text.isBlank()) return@forEach
            val geminiRole = if (role == "assistant") "assistant" else "user"
            val last = merged.lastOrNull()
            if (last != null && last.first == geminiRole) {
                merged[merged.lastIndex] = geminiRole to (last.second + "\n" + text)
            } else {
                merged += geminiRole to text
            }
        }
        // Gemini requires the first content to be from the user.
        while (merged.firstOrNull()?.first == "assistant") merged.removeAt(0)
        return merged
    }

    private fun part(text: String): JsonObject = buildJsonObject { put("text", text) }

    private val json = Json { ignoreUnknownKeys = true }
}
