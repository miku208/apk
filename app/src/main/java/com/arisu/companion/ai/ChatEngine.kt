package com.arisu.companion.ai

import com.arisu.companion.animation.PetState
import com.arisu.companion.database.ChatDao
import com.arisu.companion.database.ChatMessageEntity
import com.arisu.companion.database.MemoryDao
import com.arisu.companion.pet.PetLibrary
import com.arisu.companion.pet.PetProfile
import com.arisu.companion.settings.AppStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File
import java.util.Base64
import java.util.UUID

/** UI-facing message model used by both the settings app and the floating panel. */
@Serializable
data class ChatUiMessage(
    val localId: Long,
    val role: String,
    val content: String,
    val timestamp: Long,
    val failed: Boolean = false,
    /** True only for the assistant message currently streaming in. */
    val streaming: Boolean = false,
    /** Absolute paths of images returned by the provider (Gemini image models). */
    val images: List<String> = emptyList(),
)

/**
 * Owns the partner-conversation state machine:
 * input -> context build -> provider call -> streaming text -> persistence.
 * Also publishes [PetState]s so the renderer can gesture along.
 *
 * Provider routing: OpenAI-compatible endpoints go through [AiClient], Google
 * AI Studio profiles through [GeminiClient]. Every character has its own
 * conversation bucket (`pet-<id>`), so switching characters never mixes or
 * overwrites another character's history.
 */
class ChatEngine(
    private val scope: CoroutineScope,
    private val dao: ChatDao,
    private val memoryDao: MemoryDao,
    private val store: AppStateStore,
    private val client: AiClient,
    private val gemini: GeminiClient,
    private val petLibrary: PetLibrary,
    private val imageDir: File,
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_ERROR = "error"
        /** Conversation bucket used before any character is chosen. */
        const val ACTIVE_SESSION = "default"
        private const val CONTEXT_TAIL = 14
        /** `/img <prompt>` routes to the configured Gemini image model. */
        const val IMAGE_PREFIX = "/img "
    }

    private val _messages = MutableStateFlow<List<ChatUiMessage>>(emptyList())
    val messages: StateFlow<List<ChatUiMessage>> get() = _messages

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> get() = _busy

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> get() = _lastError

    private val _petState = MutableSharedFlow<PetState>(replay = 1, extraBufferCapacity = 16)
    val petStateFlow: SharedFlow<PetState> get() = _petState

    private val _sessionId = MutableStateFlow(ACTIVE_SESSION)
    val sessionId: StateFlow<String> get() = _sessionId

    private var activeJob: Job? = null
    private var nextLocalId = -1L

    private val _memoryProposal = MutableStateFlow<String?>(null)
    val memoryProposal: StateFlow<String?> get() = _memoryProposal

    init {
        scope.launch {
            petLibrary.activeId.collect { id ->
                _sessionId.value = if (id == PetProfile.BUNDLED_ID) ACTIVE_SESSION else "pet-$id"
            }
        }
        scope.launch {
            collectMessages()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun collectMessages() {
        _sessionId.flatMapLatest { session -> dao.messages(session) }.collect { rows ->
            val draft = _messages.value.filter { it.streaming }
            _messages.value = rows.map(::fromEntity) + draft
        }
    }

    private fun fromEntity(e: ChatMessageEntity) = ChatUiMessage(
        localId = e.id,
        role = e.role,
        content = e.content,
        timestamp = e.timestamp,
        failed = e.failed,
        images = if (e.images.isBlank()) emptyList() else e.images.split('\n').filter { it.isNotBlank() },
    )

    private fun currentPet(): PetProfile = petLibrary.active.value

    // ---------------------------------------------------------------- memory

    fun approveMemory() {
        val text = _memoryProposal.value ?: return
        _memoryProposal.value = null
        if (store.memoryEnabled.value) {
            scope.launch { memoryDao.insert(com.arisu.companion.database.MemoryEntity(content = text)) }
        }
    }

    fun dismissMemory() {
        _memoryProposal.value = null
    }

    // ------------------------------------------------------------- conversation

    /**
     * Sends a user message. Returns false without sending when a request is
     * already in flight (prevents duplicate chat requests).
     */
    fun send(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _busy.value) return false
        val profile = store.activeProfile()
        if (profile == null) {
            _lastError.value = "Belum ada profil AI. Buka AI Settings dan tambahkan API key."
            return false
        }
        _busy.value = true
        val pet = currentPet()

        activeJob = scope.launch {
            dao.insert(ChatMessageEntity(sessionId = _sessionId.value, role = ROLE_USER, content = trimmed))
            if (store.memoryEnabled.value) {
                val candidates = MemoryExtractor.extract(trimmed)
                if (candidates.isNotEmpty()) _memoryProposal.value = candidates.first()
            }
            _petState.tryEmit(PetState.THINKING)
            val outcome = runTurn(profile, pet, trimmed)
            if (outcome.ok) {
                _lastError.value = null
                _petState.tryEmit(PetState.HAPPY)
            } else {
                _lastError.value = outcome.message
                dao.insert(
                    ChatMessageEntity(
                        sessionId = _sessionId.value,
                        role = ROLE_ASSISTANT,
                        content = outcome.partialText.ifBlank { "(gagal) ${outcome.message}" },
                        failed = true,
                    ),
                )
                _petState.tryEmit(PetState.PANIC)
            }
            _busy.value = false
        }
        return true
    }

    private data class Outcome(
        val ok: Boolean,
        val message: String = "",
        val partialText: String = "",
    )

    private suspend fun runTurn(profile: ProviderProfile, pet: PetProfile, justSent: String): Outcome {
        val systemPrompt = buildSystemPrompt(pet)
        val history = dao.messagesOnce(_sessionId.value)
            .takeLast(CONTEXT_TAIL + 1)
            .map { it.role to it.content }
        val messages = mutableListOf<Pair<String, String>>().apply {
            addAll(history)
        }
        if (messages.none { it.first == ROLE_USER && it.second == justSent }) {
            messages.add(ROLE_USER to justSent)
        }

        val apiKey = store.decryptKey(profile.apiKeyCipher)
        if (apiKey.isNullOrBlank()) {
            return Outcome(
                ok = false,
                message = "Profil \"${profile.name}\" belum punya API key. Buka AI Settings → tempel key.",
            )
        }

        val draftId = nextLocalId--
        val draft = ChatUiMessage(
            localId = draftId,
            role = ROLE_ASSISTANT,
            content = "",
            timestamp = System.currentTimeMillis(),
            streaming = true,
        )
        _messages.value = _messages.value.filterNot { it.streaming } + draft
        var lastEmitted = ""
        var images = emptyList<String>()
        val filter = EmotionTagFilter()

        val failMessage: String? = try {
            when {
                profile.isGemini -> {
                    val imageRequest = justSent.startsWith(IMAGE_PREFIX) && profile.imageModelId.isNotBlank()
                    val prompt = if (imageRequest) justSent.removePrefix(IMAGE_PREFIX) else justSent
                    val turns = messages.dropLast(if (imageRequest) 1 else 0) + (ROLE_USER to prompt)
                    val result = gemini.chat(
                        profile = profile,
                        apiKeyPlain = apiKey,
                        systemInstruction = systemPrompt,
                        messages = turns,
                        temperature = store.temperature.value,
                        maxTokens = store.maxTokens.value,
                        streaming = profile.streaming,
                        modelOverride = if (imageRequest) profile.imageModelId else null,
                        onDelta = { delta ->
                            val (displayable, mood) = filter.feed(delta)
                            if (displayable.isNotEmpty()) {
                                lastEmitted += displayable
                                updateDraft(lastEmitted, images)
                            }
                            mood?.let { _petState.tryEmit(it) }
                        },
                        onImage = { inline ->
                            saveImage(inline)?.let { path ->
                                images = images + path
                                updateDraft(lastEmitted, images)
                            }
                        },
                    )
                    when (result) {
                        is GeminiClient.Result.Success -> {
                            if (result.images.isNotEmpty() && images.isEmpty()) {
                                images = result.images.mapNotNull { saveImage(it) }
                            }
                            null
                        }
                        is GeminiClient.Result.Failure -> result.message
                    }
                }
                else -> {
                    val result = client.chatCompletion(
                        profile = profile,
                        apiKeyPlain = apiKey,
                        messages = listOf("system" to systemPrompt) + messages,
                        temperature = store.temperature.value,
                        maxTokens = store.maxTokens.value,
                        streaming = profile.streaming,
                        onDelta = { delta ->
                            val (displayable, mood) = filter.feed(delta)
                            if (displayable.isNotEmpty()) {
                                lastEmitted += displayable
                                updateDraft(lastEmitted, images)
                            }
                            mood?.let { _petState.tryEmit(it) }
                        },
                    )
                    when (result) {
                        is AiClient.Result.Success -> null
                        is AiClient.Result.Failure -> result.message
                        else -> "Provider membalas kosong."
                    }
                }
            }
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        } finally {
            val flush = filter.flush()
            if (flush.isNotEmpty()) {
                lastEmitted += flush
                updateDraft(lastEmitted, images)
            }
        }

        _messages.value = _messages.value.filterNot { it.streaming }
        if (failMessage != null) {
            return Outcome(ok = false, message = failMessage, partialText = lastEmitted)
        }
        if (lastEmitted.isBlank() && images.isEmpty()) {
            return Outcome(ok = false, message = "Provider membalas kosong.", partialText = "")
        }
        dao.insert(
            ChatMessageEntity(
                sessionId = _sessionId.value,
                role = ROLE_ASSISTANT,
                content = lastEmitted,
                images = images.joinToString("\n"),
            ),
        )
        return Outcome(ok = true, partialText = lastEmitted)
    }

    /** Rewrites the streaming bubble; images appear as soon as they arrive. */
    private fun updateDraft(content: String, images: List<String>) {
        _messages.value = _messages.value.map {
            if (it.streaming) it.copy(content = content, images = images) else it
        }
    }

    /** Stores a base64 image part in app-private storage; returns its path. */
    private fun saveImage(inline: GeminiProtocol.InlineImage): String? = try {
        val ext = when {
            inline.mimeType.contains("png") -> "png"
            inline.mimeType.contains("jpeg") || inline.mimeType.contains("jpg") -> "jpg"
            inline.mimeType.contains("webp") -> "webp"
            else -> "img"
        }
        if (!imageDir.isDirectory && !imageDir.mkdirs()) {
            null
        } else {
            val file = File(imageDir, "img-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(6)}.$ext")
            file.writeBytes(Base64.getDecoder().decode(inline.dataBase64))
            file.absolutePath
        }
    } catch (e: Exception) {
        _lastError.value = "Gambar dari Gemini tidak bisa disimpan: ${e.message ?: "data rusak"}"
        null
    }

    /** Retry the newest failed assistant message. */
    fun retry(): Boolean {
        if (_busy.value) return false
        val lastUser = _messages.value.lastOrNull { it.role == ROLE_USER }?.content ?: return false
        val failed = _messages.value.lastOrNull { it.failed } ?: return false
        _busy.value = true
        scope.launch {
            dao.delete(failed.localId)
            _busy.value = false
            send(lastUser)
        }
        return true
    }

    /** Stop the in-flight generation; partial text is kept as a failed message. */
    fun stop() {
        activeJob?.cancel()
        _busy.value = false
        _petState.tryEmit(PetState.IDLE)
    }

    fun clearConversation() {
        stop()
        scope.launch {
            val session = _sessionId.value
            val rows = dao.messagesOnce(session)
            if (rows.isNotEmpty()) {
                val archiveId = "conv-" + System.currentTimeMillis()
                rows.forEach {
                    dao.delete(it.id)
                    dao.insert(it.copy(id = 0, sessionId = archiveId))
                }
            }
            _messages.value = emptyList()
        }
    }

    private suspend fun buildSystemPrompt(pet: PetProfile): String {
        val storePrompt = store.systemPrompt.value.ifBlank { AppStateStore.DEFAULT_PROMPT }
        val petPrompt = pet.personality.systemInstruction.trim()
        val base = when {
            petPrompt.isNotBlank() -> petPrompt
            pet.bundled -> storePrompt
            else -> AppStateStore.DEFAULT_PROMPT.replace("Arisu", pet.displayName)
        }
        val sb = StringBuilder(base).append('\n')
        if (petPrompt.isNotBlank()) {
            sb.append("Your character name is \"").append(pet.displayName).append("\".\n")
        }
        store.nickname.value.takeIf { it.isNotBlank() }?.let {
            sb.append("The user's nickname is \"").append(it).append("\". Address them warmly by it.\n")
        }
        val language = pet.personality.preferredLanguage.ifBlank { store.preferredLanguage.value }
        language.takeIf { it.isNotBlank() }?.let {
            sb.append("Always reply in this language: ").append(it).append(".\n")
        }
        if (store.memoryEnabled.value) {
            val memories = memoryDao.allOnceSafe()
            if (memories.isNotEmpty()) {
                sb.append("\nFacts you were asked to remember about the user:\n")
                memories.take(MAX_MEMORY_LINES).forEachIndexed { i, m ->
                    sb.append(i + 1).append(". ").append(m).append('\n')
                }
            }
        }
        return sb.toString()
    }

    private suspend fun MemoryDao.allOnceSafe(): List<String> =
        runCatching { allOnce() }.getOrDefault(emptyList()).map { it.content }
}

private const val MAX_MEMORY_LINES = 12
