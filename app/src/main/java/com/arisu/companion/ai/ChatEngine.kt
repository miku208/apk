package com.arisu.companion.ai

import com.arisu.companion.animation.PetState
import com.arisu.companion.database.ChatDao
import com.arisu.companion.database.ChatMessageEntity
import com.arisu.companion.settings.AppStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

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
)

/**
 * Owns the partner-conversation state machine:
 * input -> context build -> provider call -> streaming text -> persistence.
 * Also publishes [PetState]s so the renderer can gesture along. The AI talks
 * to the animation system through a narrow, whitelisted channel (this flow) —
 * text can never execute anything beyond choosing a visual mood.
 */
class ChatEngine(
    private val scope: CoroutineScope,
    private val dao: ChatDao,
    private val memoryDao: com.arisu.companion.database.MemoryDao,
    private val store: AppStateStore,
    private val client: AiClient,
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_ERROR = "error"
        const val ACTIVE_SESSION = "default"
        private const val CONTEXT_TAIL = 14
    }

    private val _messages = MutableStateFlow<List<ChatUiMessage>>(emptyList())
    /** Visible conversation (history + current streamed draft). */
    val messages: StateFlow<List<ChatUiMessage>> get() = _messages

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> get() = _busy

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> get() = _lastError

    private val _petState = MutableSharedFlow<PetState>(replay = 1, extraBufferCapacity = 16)
    val petStateFlow: SharedFlow<PetState> get() = _petState

    private var activeJob: Job? = null
    private var nextLocalId = -1L

    init {
        scope.launch {
            dao.messages(ACTIVE_SESSION).collect { rows ->
                val draft = _messages.value.filter { it.streaming }
                _messages.value = rows.map(::fromEntity) + draft
            }
        }
    }

    private fun fromEntity(e: ChatMessageEntity) = ChatUiMessage(
        localId = e.id, role = e.role, content = e.content,
        timestamp = e.timestamp, failed = e.failed,
    )

    /** Newly proposed memory sentences (shown to the user before storing). */
    private val _memoryProposal = MutableStateFlow<String?>(null)
    val memoryProposal: StateFlow<String?> get() = _memoryProposal

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

    /**
     * Sends a user message. Returns false without sending when a request is
     * already in flight (prevents duplicate chat requests).
     */
    fun send(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _busy.value) return false
        val profile = store.activeProfile() ?: return false
        _busy.value = true

        activeJob = scope.launch {
            dao.insert(ChatMessageEntity(role = ROLE_USER, content = trimmed))
            if (store.memoryEnabled.value) {
                val candidates = MemoryExtractor.extract(trimmed)
                if (candidates.isNotEmpty()) _memoryProposal.value = candidates.first()
            }
            _petState.tryEmit(PetState.THINKING)
            val result = request(profile, trimmed)
            when (result) {
                is AiClient.Result.Success -> {
                    _lastError.value = null
                    _petState.tryEmit(PetState.HAPPY)
                }
                is AiClient.Result.Failure -> {
                    _lastError.value = result.message
                    dao.insert(
                        ChatMessageEntity(
                            role = ROLE_ASSISTANT,
                            content = result.partialText.ifBlank { "(failed) ${result.message}" },
                            failed = true,
                        ),
                    )
                    _petState.tryEmit(PetState.PANIC)
                }
                else -> Unit
            }
            _busy.value = false
        }
        return true
    }

    /** Streams a completion for the newest user message visible in DB. */
    private suspend fun request(profile: ProviderProfile, justSent: String): AiClient.Result {
        val systemPrompt = buildSystemPrompt()
        val history = dao.messagesOnce(ACTIVE_SESSION)
            .takeLast(CONTEXT_TAIL + 1)
            .map { it.role to it.content }
        val messages = mutableListOf<Pair<String, String>>().apply {
            add("system" to systemPrompt)
            addAll(history)
        }
        if (messages.none { it.first == ROLE_USER && it.second == justSent }) {
            messages.add(ROLE_USER to justSent)
        }

        val apiKey = store.decryptKey(profile.apiKeyCipher)
        if (apiKey.isNullOrBlank()) {
            return AiClient.Result.Failure(
                "No API key stored for profile \"${profile.name}\". Open AI Settings → add your key.",
                "",
            )
        }

        val filter = EmotionTagFilter()
        val draft = _messages.value.filter { it.streaming }
        val draftMessage = ChatUiMessage(
            localId = nextLocalId--, role = ROLE_ASSISTANT,
            content = "", timestamp = System.currentTimeMillis(), streaming = true,
        )
        _messages.value = _messages.value - draft.toSet() + draftMessage

        var lastEmitted = ""
        val onDelta: (String) -> Unit = { delta ->
            val (displayable, mood) = filter.feed(delta)
            if (displayable.isNotEmpty() || mood != null) {
                lastEmitted += displayable
                mood?.let { _petState.tryEmit(it) }
                updateDraft(lastEmitted)
            }
        }

        val result = try {
            client.chatCompletion(
                profile = profile,
                apiKeyPlain = apiKey,
                messages = messages,
                temperature = store.temperature.value,
                maxTokens = store.maxTokens.value,
                streaming = profile.streaming,
                onDelta = onDelta,
            )
        } catch (e: Exception) {
            AiClient.Result.Failure(e.message ?: "Network error", lastEmitted)
        } finally {
            val flush = filter.flush()
            if (flush.isNotEmpty()) {
                lastEmitted += flush
                updateDraft(lastEmitted)
            }
        }

        val finalText = lastEmitted.ifBlank {
            (result as? AiClient.Result.Failure)?.let { "(failed) ${it.message}" }
        } ?: ""

        if (result is AiClient.Result.Success && finalText.isNotBlank()) {
            dao.insert(ChatMessageEntity(role = ROLE_ASSISTANT, content = finalText))
        }
        _messages.value = _messages.value.filterNot { it.streaming }
        scope.launch { }
        return result
    }

    private fun updateDraft(content: String) {
        _messages.value = _messages.value.map {
            if (it.streaming) it.copy(content = content) else it
        }
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
            val rows = dao.messagesOnce(ACTIVE_SESSION)
            if (rows.isNotEmpty()) {
                val sessionId = "conv-" + System.currentTimeMillis()
                rows.forEach { dao.delete(it.id); dao.insert(it.copy(id = 0, sessionId = sessionId)) }
            }
            _messages.value = emptyList()
        }
    }

    private suspend fun buildSystemPrompt(): String {
        val storePrompt = store.systemPrompt.value.ifBlank { AppStateStore.DEFAULT_PROMPT }
        val sb = StringBuilder(storePrompt).append('\n')
        store.nickname.value.takeIf { it.isNotBlank() }?.let {
            sb.append("The user's nickname is \"").append(it).append("\". Address them warmly by it.\n")
        }
        store.preferredLanguage.value.takeIf { it.isNotBlank() }?.let {
            sb.append("Always reply in this language: ").append(it).append(".\n")
        }
        if (store.memoryEnabled.value) {
            val memories = memoryDao.allOnceSafe()
            if (memories.isNotEmpty()) {
                sb.append("\nFacts you (Arisu) were asked to remember about the user:\n")
                memories.take(MAX_MEMORY_LINES).forEachIndexed { i, m -> sb.append(i + 1).append(". ").append(m).append('\n') }
            }
        }
        return sb.toString()
    }

    private suspend fun com.arisu.companion.database.MemoryDao.allOnceSafe(): List<String> =
        runCatching { allOnce() }.getOrDefault(emptyList()).map { it.content }
}

private const val MAX_MEMORY_LINES = 12
