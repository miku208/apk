package com.arisu.companion.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Talks directly to the user-configured OpenAI-compatible endpoint.
 * Keys travel only in the Authorization header. Requests are cancellable;
 * cancelling closes the socket and stops billing.
 */
class AiClient(
    client: OkHttpClient = defaultClient(),
) {
    private val http: OkHttpClient = client.newBuilder().build()
    private val jsonMediaType = "application/json;charset=utf-8".toMediaType()

    /** Outcome of one turn. [partialText] holds whatever streamed in before the failure. */
    sealed class Result {
        data object Success : Result()
        data class Failure(val message: String, val partialText: String) : Result()
    }

    /**
     * Streams a chat completion. [onDelta] is called on the calling (IO-ish) context
     * whenever a chunk arrives except for trailing blank chunks.
     */
    suspend fun chatCompletion(
        profile: ProviderProfile,
        apiKeyPlain: String,
        messages: List<Pair<String, String>>, // role -> content
        temperature: Float?,
        maxTokens: Int?,
        streaming: Boolean,
        onDelta: (String) -> Unit,
    ): Result {
        val url = profile.baseUrl.trim().removeSuffix("/") + "/chat/completions"
        if (apiKeyPlain.isBlank()) {
            return Result.Failure("This provider profile has no API key. Open AI Settings and add one.", "")
        }
        val bodyJson = JSONObject().apply {
            put("model", profile.modelId)
            put("messages", JSONArray().apply {
                messages.forEach { (role, content) ->
                    put(JSONObject().put("role", role).put("content", content))
                }
            })
            put("stream", streaming)
            (temperature ?: profile.temperature)?.let { put("temperature", it.toDouble()) }
            (maxTokens ?: profile.maxTokens)?.let { put("max_tokens", it) }
        }
        val requestBuilder = Request.Builder().url(url).post(bodyJson.toString().toRequestBody(jsonMediaType))
        if (profile.authScheme == ProviderProfile.AuthScheme.BEARER && apiKeyPlain.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $apiKeyPlain")
        }
        val job = coroutineContext[Job]
        val call = http.newCall(requestBuilder.build())

        return suspendCancellableCoroutine { cont ->
            job?.invokeOnCompletion { cause ->
                if (cause != null) call.cancel()
            }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (job?.isCancelled == true) {
                        cont.cancel()
                    } else {
                        cont.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use { r ->
                            if (!r.isSuccessful) {
                                val errBody = r.body?.string().orEmpty()
                                val msg = SseParser.errorMessage(errBody)
                                cont.resume(
                                    Result.Failure("HTTP ${r.code}: ${msg ?: errBody.take(240).ifBlank { "no body" }}", ""),
                                )
                                return@use
                            }
                            val result = consumeBody(r, streaming, onDelta, job)
                            cont.resume(result)
                        }
                    } catch (e: Exception) {
                        if (job?.isCancelled == true) cont.cancel() else cont.resumeWithException(e)
                    }
                }
            })
        }
    }

    private fun consumeBody(response: Response, streaming: Boolean, onDelta: (String) -> Unit, job: Job?): Result {
        val source = response.body?.source() ?: return ResponseIsBlankFailure
        val parser = SseParser()
        val sb = StringBuilder()
        try {
            while (true) {
                // Cancellation blows up here with an IO exception or our own flag.
                job?.ensureActive()
                val line = source.readUtf8Line() ?: break
                if (streaming) {
                    val delta = parser.feed(line)
                    if (!delta.isNullOrEmpty()) {
                        sb.append(delta)
                        onDelta(delta)
                    }
                    if (parser.isDone) break
                } else {
                    sb.append(line)
                }
            }
        } catch (e: IOException) {
            return Result.Failure("Network error while reading response: ${e.message ?: e.javaClass.simpleName}", sb.toString())
        }
        if (!streaming) {
            val full = SseParser.fullMessage(sb.toString())
            if (full != null) {
                onDelta(full)
                return Result.Success
            }
            return Result.Failure("Could not parse non-streaming response body", "")
        }
        val text = sb.toString()
        return if (text.isBlank()) Result.Failure("Provider returned an empty stream", "") else Result.Success
    }

    private object ResponseIsBlankFailure : Result() {
        override fun equals(other: Any?) = other === this
        override fun hashCode() = System.identityHashCode(this)
    }

    /** Quick smoke test used by the "Test connection" button. */
    suspend fun testConnection(profile: ProviderProfile, apiKeyPlain: String?): Pair<Boolean, String> {
        return withContext(Dispatchers.IO) {
            if (apiKeyPlain.isNullOrBlank()) return@withContext false to "No API key"
            try {
                chatCompletion(
                    profile = profile.copy(streaming = false, temperature = 0f, maxTokens = 8, modelId = profile.modelId.ifBlank { "gpt-3.5-turbo" }),
                    apiKeyPlain = apiKeyPlain,
                    messages = listOf("user" to "ping"),
                    temperature = 0f,
                    maxTokens = 8,
                    streaming = false,
                    onDelta = {},
                ).let {
                    when (it) {
                        is Result.Success -> true to "Connection OK"
                        is Result.Failure -> false to it.message
                        is ResponseIsBlankFailure -> false to "Empty response"
                        else -> false to "Unknown result"
                    }
                }
            } catch (e: Exception) {
                false to (e.message ?: e.javaClass.simpleName)
            }
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        fun randomId(): String = ByteArray(8).also {
            SecureRandom().nextBytes(it)
        }.joinToString("") { "%02x".format(it) }
    }
}
