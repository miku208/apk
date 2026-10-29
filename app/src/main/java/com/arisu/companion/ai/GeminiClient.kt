package com.arisu.companion.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google AI Studio (Gemini) provider. BYOK: the key is supplied by the caller
 * and only ever travels in the `x-goog-api-key` header — never in a URL, never
 * logged, never stored here.
 */
class GeminiClient(
    client: OkHttpClient = defaultClient(),
) {
    private val http: OkHttpClient = client.newBuilder().build()
    private val jsonMediaType = "application/json;charset=utf-8".toMediaType()

    sealed class Result {
        data class Success(val images: List<GeminiProtocol.InlineImage> = emptyList()) : Result()
        data class Failure(
            val message: String,
            val partialText: String,
            val images: List<GeminiProtocol.InlineImage> = emptyList(),
        ) : Result()
    }

    /**
     * Streams (or fetches) a Gemini reply.
     *
     * @param onDelta streamed text, already free of image payloads
     * @param onImage called once per inline image part
     */
    suspend fun chat(
        profile: ProviderProfile,
        apiKeyPlain: String,
        systemInstruction: String,
        messages: List<Pair<String, String>>,
        temperature: Float?,
        maxTokens: Int?,
        streaming: Boolean,
        modelOverride: String? = null,
        onDelta: (String) -> Unit,
        onImage: (GeminiProtocol.InlineImage) -> Unit = {},
    ): Result {
        if (apiKeyPlain.isBlank()) {
            return Result.Failure(
                "Profil ini belum punya API key Gemini. Buka AI Settings dan tempel key dari aistudio.google.com/apikey.",
                "",
            )
        }
        val model = modelOverride ?: profile.modelId
        if (GeminiProtocol.normaliseModel(model).isBlank()) {
            return Result.Failure("Model Gemini belum diisi. Contoh: ${GeminiProtocol.DEFAULT_CHAT_MODEL}.", "")
        }
        val imageModel = profile.imageModelId.isNotBlank() && model == profile.imageModelId
        val url = GeminiProtocol.endpoint(profile.baseUrl, model, streaming)
        val body = GeminiProtocol.buildRequest(
            systemInstruction = systemInstruction,
            history = messages,
            temperature = temperature,
            maxTokens = maxTokens,
            imageModel = imageModel,
        )
        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKeyPlain)
            .header("Accept", if (streaming) "text/event-stream" else "application/json")
            .post(body.toRequestBody(jsonMediaType))
            .build()

        val job = coroutineContext[Job]
        val call = http.newCall(request)

        return suspendCancellableCoroutine { cont ->
            job?.invokeOnCompletion { cause -> if (cause != null) call.cancel() }
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (job?.isCancelled == true || !cont.isActive) {
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
                                if (cont.isActive) {
                                    cont.resume(
                                        Result.Failure(GeminiProtocol.mapError(r.code, errBody), ""),
                                    )
                                }
                                return
                            }
                            val result = consume(r, streaming, onDelta, onImage, job)
                            if (cont.isActive) cont.resume(result)
                        }
                    } catch (e: Exception) {
                        if (job?.isCancelled == true || !cont.isActive) cont.cancel()
                        else cont.resumeWithException(e)
                    }
                }
            })
        }
    }

    private fun consume(
        response: Response,
        streaming: Boolean,
        onDelta: (String) -> Unit,
        onImage: (GeminiProtocol.InlineImage) -> Unit,
        job: Job?,
    ): Result {
        val source = response.body?.source()
            ?: return Result.Failure("Gemini mengirim respons kosong.", "")
        val sb = StringBuilder()
        val images = mutableListOf<GeminiProtocol.InlineImage>()
        var blockReason: String? = null
        try {
            if (!streaming) {
                val body = source.readUtf8()
                val chunk = GeminiProtocol.parseChunk(body)
                    ?: return Result.Failure("Respons Gemini tidak bisa dibaca.", "")
                blockReason = chunk.blockReason
                chunk.text?.let { sb.append(it); onDelta(it) }
                chunk.images.forEach { images += it; onImage(it) }
            } else {
                while (true) {
                    job?.ensureActive()
                    val line = source.readUtf8Line() ?: break
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("data:")) continue
                    val payload = trimmed.removePrefix("data:").trim()
                    if (payload.isEmpty() || payload == "[DONE]") continue
                    val chunk = GeminiProtocol.parseChunk(payload) ?: continue
                    chunk.blockReason?.let { blockReason = it }
                    chunk.text?.let { text ->
                        sb.append(text)
                        onDelta(text)
                    }
                    chunk.images.forEach { images += it; onImage(it) }
                }
            }
        } catch (e: IOException) {
            return Result.Failure(
                "Koneksi terputus saat membaca balasan Gemini: ${e.message ?: e.javaClass.simpleName}",
                sb.toString(),
                images,
            )
        }
        if (blockReason != null) {
            return Result.Failure(
                "Gemini menolak permintaan ini (${blockReason}). Coba ubah kalimatnya.",
                sb.toString(),
                images,
            )
        }
        if (sb.isBlank() && images.isEmpty()) {
            return Result.Failure("Gemini membalas kosong.", "")
        }
        return Result.Success(images)
    }

    /** "Test connection" button. Returns ok -> message. */
    suspend fun testConnection(profile: ProviderProfile, apiKeyPlain: String?): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            if (apiKeyPlain.isNullOrBlank()) return@withContext false to "API key belum diisi"
            try {
                val result = chat(
                    profile = profile,
                    apiKeyPlain = apiKeyPlain,
                    systemInstruction = "Reply with the single word: pong",
                    messages = listOf("user" to "ping"),
                    temperature = 0f,
                    maxTokens = 8,
                    streaming = false,
                    onDelta = {},
                )
                when (result) {
                    is Result.Success -> {
                        val model = GeminiProtocol.normaliseModel(profile.modelId)
                        true to "Tersambung ke $model"
                    }
                    is Result.Failure -> false to result.message
                }
            } catch (e: Exception) {
                false to (e.message ?: e.javaClass.simpleName)
            }
        }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
