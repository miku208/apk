package com.arisu.companion.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Incremental parser for OpenAI-compatible `text/event-stream` replies.
 * Feed complete lines (without the trailing newline); receive text deltas.
 * Also supports non-streaming bodies through [parseNonStreaming][parseFullCompletion].
 */
class SseParser {
    private var sawDone = false

    /** true once the `data: [DONE]` sentinel arrived. */
    val isDone: Boolean get() = sawDone

    /**
     * Consume one SSE line.
     * @return assistant text chunk carried by this line, or null.
     */
    fun feed(line: String): String? {
        if (sawDone) return null
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith("data:")) return null
        val payload = trimmed.removePrefix("data:").trim()
        if (payload == DONE) {
            sawDone = true
            return null
        }
        return extractDelta(payload)
    }

    companion object {
        const val DONE = "[DONE]"
        private val json = Json { ignoreUnknownKeys = true }

        fun extractDelta(payload: String): String? = try {
            val root = json.parseToJsonElement(payload).jsonObject
            val choices = root["choices"]?.jsonArray
            val delta = (choices?.firstOrNull()?.jsonObject) ?: return null
            // Streaming format: delta.content; non-stream: message.content.
            val content = (delta["delta"]?.jsonObject?.get("content")
                ?: delta["message"]?.jsonObject?.get("content"))
                ?.jsonPrimitive?.contentOrNull
            content
        } catch (_: Exception) {
            null
        }

        /** Extracts the complete assistant message from a non-streaming JSON body. */
        fun fullMessage(body: String): String? = extractDelta(body)

        /** Extracts provider error text like {"error":{"message":"..."}} from an error body. */
        fun errorMessage(body: String): String? = try {
            val root = json.parseToJsonElement(body).jsonObject
            val err = root["error"]
            when {
                err is JsonObject -> err["message"]?.jsonPrimitive?.contentOrNull
                err != null -> err.jsonPrimitive.contentOrNull
                else -> root["message"]?.jsonPrimitive?.contentOrNull
            }
        } catch (_: Exception) {
            null
        }
    }
}
