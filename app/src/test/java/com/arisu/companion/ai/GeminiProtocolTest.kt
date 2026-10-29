package com.arisu.companion.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiProtocolTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `builds the documented endpoints`() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent",
            GeminiProtocol.endpoint(GeminiProtocol.DEFAULT_BASE_URL, "gemini-3.1-flash-lite", streaming = false),
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:streamGenerateContent?alt=sse",
            GeminiProtocol.endpoint("https://generativelanguage.googleapis.com/v1beta/", "models/gemini-3.1-flash-lite", streaming = true),
        )
        // Blank base URL falls back to the official host.
        assertTrue(
            GeminiProtocol.endpoint("", "gemini-3.8-flash", streaming = false)
                .startsWith("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash"),
        )
    }

    @Test
    fun `builds a request with system instruction and alternating roles`() {
        val body = GeminiProtocol.buildRequest(
            systemInstruction = "You are Arisu.",
            history = listOf(
                "system" to "ignored",
                "user" to "halo",
                "assistant" to "hai!",
                "user" to "apa kabar",
            ),
            temperature = 0.7f,
            maxTokens = 256,
        )
        val root = json.parseToJsonElement(body).jsonObject

        val system = root["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray.single()
            .jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("You are Arisu.", system)

        val contents = root["contents"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "model", "user"), contents.map { it["role"]!!.jsonPrimitive.content })
        assertEquals(
            "hai!",
            contents[1]["parts"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content,
        )

        val config = root["generationConfig"]!!.jsonObject
        assertEquals(0.7f, config["temperature"]!!.jsonPrimitive.content.toFloat(), 1e-4f)
        assertEquals(256, config["maxOutputTokens"]!!.jsonPrimitive.content.toInt())
        assertNull("image modalities only for image models", config["responseModalities"])
    }

    @Test
    fun `merges consecutive turns and drops a leading model turn`() {
        val body = GeminiProtocol.buildRequest(
            systemInstruction = "",
            history = listOf(
                "assistant" to "awal",
                "user" to "satu",
                "user" to "dua",
            ),
            temperature = null,
            maxTokens = null,
        )
        val contents = json.parseToJsonElement(body).jsonObject["contents"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, contents.size)
        assertEquals("user", contents[0]["role"]!!.jsonPrimitive.content)
        assertEquals(
            "satu\ndua",
            contents[0]["parts"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `image models request both modalities`() {
        val body = GeminiProtocol.buildRequest("", listOf("user" to "gambar kucing"), null, 64, imageModel = true)
        val modalities = json.parseToJsonElement(body).jsonObject["generationConfig"]!!
            .jsonObject["responseModalities"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("TEXT", "IMAGE"), modalities)
    }

    @Test
    fun `parses streamed text chunks`() {
        val line = """{"candidates":[{"content":{"parts":[{"text":"Halo "}],"role":"model"},"index":0}]}"""
        val chunk = GeminiProtocol.parseChunk(line)!!
        assertEquals("Halo ", chunk.text)
        assertTrue(chunk.images.isEmpty())

        val more = GeminiProtocol.parseChunk(
            """{"candidates":[{"content":{"parts":[{"text":"dunia"},{"text":"!"}]},"finishReason":"STOP"}]}""",
        )!!
        assertEquals("dunia!", more.text)
        assertEquals("STOP", more.finishReason)
    }

    @Test
    fun `parses inline image parts instead of dropping them`() {
        val body = """
            {"candidates":[{"content":{"parts":[
              {"text":"Ini gambarnya:"},
              {"inlineData":{"mimeType":"image/png","data":"iVBORw0KGgo="}}
            ]},"finishReason":"STOP"}]}
        """.trimIndent()
        val chunk = GeminiProtocol.parseChunk(body)!!
        assertEquals("Ini gambarnya:", chunk.text)
        assertEquals(1, chunk.images.size)
        assertEquals("image/png", chunk.images[0].mimeType)
        assertEquals("iVBORw0KGgo=", chunk.images[0].dataBase64)
    }

    @Test
    fun `reads safety blocks and ignores junk`() {
        val blocked = GeminiProtocol.parseChunk("""{"promptFeedback":{"blockReason":"SAFETY"}}""")!!
        assertEquals("SAFETY", blocked.blockReason)
        assertNull(GeminiProtocol.parseChunk("not json"))
        assertNull(GeminiProtocol.parseChunk("""{"usageMetadata":{"totalTokenCount":3}}"""))
    }

    @Test
    fun `maps api errors to actionable messages`() {
        val quota = """{"error":{"code":429,"message":"Quota exceeded for quota metric","status":"RESOURCE_EXHAUSTED"}}"""
        assertTrue(GeminiProtocol.mapError(429, quota).contains("Kuota"))

        val key = """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}"""
        assertTrue(GeminiProtocol.mapError(400, key).contains("API key"))

        val billing = """{"error":{"code":403,"message":"Billing account not enabled","status":"PERMISSION_DENIED"}}"""
        assertTrue(GeminiProtocol.mapError(403, billing).lowercase().contains("billing"))

        val model = """{"error":{"code":404,"message":"models/nope is not found","status":"NOT_FOUND"}}"""
        assertTrue(GeminiProtocol.mapError(404, model).contains("Model tidak ditemukan"))

        assertTrue(GeminiProtocol.mapError(503, "upstream").contains("HTTP 503"))
        assertTrue(GeminiProtocol.mapError(418, "").contains("HTTP 418"))
    }
}
