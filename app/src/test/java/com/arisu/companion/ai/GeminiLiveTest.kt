package com.arisu.companion.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Live tests against the real Google AI Studio endpoint.
 *
 * These talk to the network, so they are skipped unless `GEMINI_LIVE_TESTS=1`.
 * The invalid-key case needs no secret and proves the request shape, the header
 * auth and [GeminiProtocol.mapError] against the real server; the rest need
 * `GEMINI_API_KEY` (and `GEMINI_IMAGE_TEST=1` for the paid image model).
 *
 *     GEMINI_LIVE_TESTS=1 GEMINI_API_KEY=... ./gradlew :app:testReleaseUnitTest
 */
class GeminiLiveTest {

    private val profile = ProviderProfile.builtins().single()

    private fun live() = assumeTrue(
        "set GEMINI_LIVE_TESTS=1 to run network tests",
        System.getenv("GEMINI_LIVE_TESTS") == "1",
    )

    private fun key(): String {
        val value = System.getenv("GEMINI_API_KEY").orEmpty()
        assumeTrue("set GEMINI_API_KEY to run this test", value.isNotBlank())
        return value
    }

    @Test
    fun `invalid key is reported as an actionable error by the real server`() = runBlocking {
        live()
        val result = GeminiClient().chat(
            profile = profile,
            apiKeyPlain = "AIzaSyFAKEKEY_this_is_not_a_real_key",
            systemInstruction = "ping",
            messages = listOf("user" to "ping"),
            temperature = 0f,
            maxTokens = 8,
            streaming = false,
            onDelta = {},
        )
        assertTrue("expected a failure, got $result", result is GeminiClient.Result.Failure)
        val message = (result as GeminiClient.Result.Failure).message
        println("invalid-key message: $message")
        assertTrue(
            "message should point at the key, was: $message",
            message.contains("API key", ignoreCase = true),
        )
        assertTrue("message should include the HTTP status, was: $message", message.contains("400"))
    }

    @Test
    fun `unknown model is reported as not found`() = runBlocking {
        live()
        val result = GeminiClient().chat(
            profile = profile.copy(modelId = "gemini-does-not-exist-9.9"),
            apiKeyPlain = key(),
            systemInstruction = "ping",
            messages = listOf("user" to "ping"),
            temperature = 0f,
            maxTokens = 8,
            streaming = false,
            onDelta = {},
        )
        assertTrue("expected a failure, got $result", result is GeminiClient.Result.Failure)
        val message = (result as GeminiClient.Result.Failure).message
        println("bad-model message: $message")
        assertTrue(
            "message should mention the model/404, was: $message",
            message.contains("Model", ignoreCase = true) || message.contains("404"),
        )
    }

    @Test
    fun `real key connects streams text and keeps the key out of the url`() = runBlocking {
        live()
        val apiKey = key()

        val (ok, message) = GeminiClient().testConnection(profile, apiKey)
        println("testConnection: ok=$ok message=$message")
        assertTrue("Test Connection failed: $message", ok)

        val deltas = mutableListOf<String>()
        val endpointUsed = StringBuilder()
        val result = GeminiClient().chat(
            profile = profile,
            apiKeyPlain = apiKey,
            systemInstruction = "Jawab dengan satu kata saja.",
            messages = listOf("user" to "Balas: pong"),
            temperature = 0f,
            maxTokens = 32,
            streaming = true,
            onDelta = { deltas += it },
        )
        endpointUsed.append(GeminiProtocol.endpoint(profile.baseUrl, profile.modelId, streaming = true))

        when (result) {
            is GeminiClient.Result.Success -> {
                val text = deltas.joinToString("")
                println("streamed ${deltas.size} chunks: $text")
                assertTrue("stream produced no text", text.isNotBlank())
            }
            is GeminiClient.Result.Failure -> throw AssertionError(
                "streaming chat failed: ${result.message} (partial=${result.partialText})",
            )
        }

        // The key travels in x-goog-api-key; the URL must stay clean so it can
        // never end up in a proxy log or an exception message.
        assertFalse(
            "endpoint must not contain the key",
            endpointUsed.toString().contains(apiKey),
        )
        assertTrue(
            "streaming endpoint shape changed",
            endpointUsed.toString().endsWith(":streamGenerateContent?alt=sse"),
        )
    }

    @Test
    fun `every advertised chat model answers a real request`() = runBlocking {
        live()
        val apiKey = key()
        val failures = linkedMapOf<String, String>()
        val answered = mutableListOf<String>()
        // Shorter than the app's 120 s: a model that never streams must not stall
        // the whole run for two minutes per iteration.
        val quickClient = GeminiClient(
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build(),
        )

        for (model in GeminiProtocol.chatModels) {
            val chunks = mutableListOf<String>()
            val started = System.currentTimeMillis()
            val outcome = try {
                quickClient.chat(
                    profile = profile.copy(modelId = model),
                    apiKeyPlain = apiKey,
                    systemInstruction = "Jawab dengan satu kata saja.",
                    messages = listOf("user" to "Balas: pong"),
                    temperature = 0f,
                    maxTokens = 24,
                    streaming = true,
                    onDelta = { chunks += it },
                )
            } catch (e: java.io.IOException) {
                GeminiClient.Result.Failure("network: ${e.javaClass.simpleName}: ${e.message}", "")
            }
            val elapsed = System.currentTimeMillis() - started
            when (outcome) {
                is GeminiClient.Result.Success -> {
                    val text = chunks.joinToString("").trim()
                    println("$model -> OK (${chunks.size} chunks, ${elapsed}ms): $text")
                    if (text.isBlank()) failures[model] = "empty reply" else answered += model
                }
                is GeminiClient.Result.Failure -> {
                    println("$model -> FAIL (${elapsed}ms): ${outcome.message}")
                    failures[model] = outcome.message
                }
            }
            // Stay under the per-minute free-tier rate limit while sampling them all.
            kotlinx.coroutines.delay(1_000)
        }

        // A per-model quota of zero, a billing wall, or a transient 503 is a
        // property of the account or the moment, not of our request shape; those
        // are reported in the log but do not fail the run. Anything else is a bug.
        val tolerated = failures.filterValues { message ->
            message.contains("429") || message.contains("403") ||
                message.contains("Kuota", ignoreCase = true) || message.contains("503") ||
                message.contains("high demand", ignoreCase = true) ||
                // Observed on gemini-3.8-flash: the server accepts the request and
                // then sends nothing (a separate curl run saw the same hang, another
                // got 503). Not something the client can fix; still fatal if every
                // model hangs, which the check below enforces.
                message.contains("SocketTimeoutException")
        }.keys
        val realFailures = failures.filterKeys { it !in tolerated }
        assertTrue("models rejected the request: $realFailures", realFailures.isEmpty())
        assertTrue("no chat model answered at all: $failures", answered.isNotEmpty())
        println("answered: $answered")
        println("limited by account/server: $tolerated")
    }

    @Test
    fun `image model returns inline image parts`() = runBlocking {
        live()
        assumeTrue("set GEMINI_IMAGE_TEST=1 (costs quota)", System.getenv("GEMINI_IMAGE_TEST") == "1")
        assumeTrue("profile has no image model", profile.imageModelId.isNotBlank())

        val images = mutableListOf<GeminiProtocol.InlineImage>()
        val result = GeminiClient().chat(
            profile = profile,
            apiKeyPlain = key(),
            systemInstruction = "",
            messages = listOf("user" to "A plain red circle on white background."),
            temperature = null,
            maxTokens = null,
            streaming = false,
            modelOverride = profile.imageModelId,
            onDelta = {},
            onImage = { images += it },
        )
        println("image result: $result images=${images.size}")
        when (result) {
            is GeminiClient.Result.Success -> {
                assertTrue("no inline image part came back", images.isNotEmpty())
                assertTrue("mime type missing", images.first().mimeType.startsWith("image/"))
                assertTrue("image payload empty", images.first().dataBase64.isNotBlank())
            }
            is GeminiClient.Result.Failure -> {
                // Image models are not on the free tier (the API answers
                // 429 with `limit: 0` for generate_content_free_tier_requests),
                // so this test can only run on a billed key. Anything else is a
                // genuine defect and must fail.
                if (result.message.contains("429") || result.message.contains("Kuota", ignoreCase = true)) {
                    println("image model unavailable on this key's plan: ${result.message}")
                    assumeTrue("model gambar butuh billing aktif (429, kuota free tier = 0)", false)
                }
                throw AssertionError("image request failed: ${result.message}")
            }
        }
    }
}
