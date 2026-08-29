package com.hermes.client.data.progress

import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.ServerEvent
import com.hermes.client.data.network.bool
import com.hermes.client.data.network.str
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.domain.ChatMessage
import com.hermes.client.ui.chat.ChatUiState
import com.hermes.client.ui.chat.markInterrupted
import com.hermes.client.ui.chat.reduce
import com.hermes.client.ui.chat.withUserMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class SessionRuntimeKey(val profile: String?, val sessionId: String)

enum class SessionRunPhase {
    IDLE,
    SUBMITTING,
    THINKING,
    STREAMING,
    USING_TOOL,
    WAITING_APPROVAL,
    WAITING_CLARIFICATION,
    RECONNECTING,
    COMPLETED_UNREAD,
    FAILED,
    INTERRUPTED,
}

val SessionRunPhase.isActive: Boolean
    get() = this in setOf(
        SessionRunPhase.SUBMITTING,
        SessionRunPhase.THINKING,
        SessionRunPhase.STREAMING,
        SessionRunPhase.USING_TOOL,
        SessionRunPhase.WAITING_APPROVAL,
        SessionRunPhase.WAITING_CLARIFICATION,
        SessionRunPhase.RECONNECTING,
    )

data class SessionRuntime(
    val key: SessionRuntimeKey,
    val liveHandle: String? = null,
    val chat: ChatUiState = ChatUiState.empty(),
    val phase: SessionRunPhase = SessionRunPhase.IDLE,
    val toolName: String? = null,
    val lastEventAt: Long = 0L,
)

/**
 * Process-lifetime source of truth for every live conversation.
 *
 * A ChatViewModel belongs to one navigation entry and is destroyed when the user returns to the
 * sessions list. Gateway events do not stop then, so collecting them in that ViewModel loses all
 * reasoning/deltas produced off-screen. This singleton folds the hot WebSocket stream once and
 * keeps per-session snapshots that both the list and any chat screen can observe.
 */
class SessionRuntimeStore(
    private val chatRepository: ChatRepository,
    private val appScope: CoroutineScope,
    private val profiles: ProfileManager,
) {
    private val _runtimes = MutableStateFlow<Map<SessionRuntimeKey, SessionRuntime>>(emptyMap())
    val runtimes: StateFlow<Map<SessionRuntimeKey, SessionRuntime>> = _runtimes.asStateFlow()

    private val aliases = ConcurrentHashMap<String, SessionRuntimeKey>()
    private val visible = ConcurrentHashMap.newKeySet<SessionRuntimeKey>()
    @Volatile private var lastActiveKey: SessionRuntimeKey? = null

    init {
        appScope.launch {
            chatRepository.events.collect { event -> runCatching { applyEvent(event) } }
        }
        appScope.launch {
            var previous: ConnectionState? = null
            chatRepository.connectionState.collect { current ->
                if (current is ConnectionState.Reconnecting || current is ConnectionState.Error) {
                    _runtimes.update { map ->
                        map.mapValues { (_, runtime) ->
                            if (runtime.phase.isActive) runtime.copy(phase = SessionRunPhase.RECONNECTING)
                            else runtime
                        }
                    }
                }
                if (current is ConnectionState.Connected && previous is ConnectionState.Reconnecting) {
                    resumeRunningSessions()
                }
                previous = current
            }
        }
    }

    fun key(sessionId: String, profile: String?): SessionRuntimeKey =
        SessionRuntimeKey(profile?.ifBlank { null }, sessionId)

    fun register(sessionId: String, profile: String?): SessionRuntimeKey {
        val key = key(sessionId, profile)
        aliases[sessionId] = key
        _runtimes.update { map ->
            if (key in map) map else map + (key to SessionRuntime(key = key))
        }
        return key
    }

    fun bindLiveHandle(key: SessionRuntimeKey, handle: String?) {
        aliases[key.sessionId] = key
        if (!handle.isNullOrBlank()) aliases[handle] = key
        _runtimes.update { map ->
            val current = map[key] ?: SessionRuntime(key)
            map + (key to current.copy(liveHandle = handle ?: current.liveHandle))
        }
    }

    fun setVisible(key: SessionRuntimeKey, value: Boolean) {
        if (value) {
            visible += key
            _runtimes.update { map ->
                val current = map[key] ?: return@update map
                if (current.phase == SessionRunPhase.COMPLETED_UNREAD) {
                    map + (key to current.copy(phase = SessionRunPhase.IDLE))
                } else map
            }
        } else {
            visible -= key
        }
    }

    fun markHistoryLoading(key: SessionRuntimeKey, cached: List<ChatMessage>?) {
        updateRuntime(key) { runtime ->
            val base = runtime.chat
            runtime.copy(
                chat = base.copy(
                    messages = if (base.messages.isEmpty() && !cached.isNullOrEmpty()) cached else base.messages,
                    historyLoading = true,
                    historyLoaded = !cached.isNullOrEmpty() || base.historyLoaded,
                    historyError = null,
                ),
            )
        }
    }

    /** Do not let a slower REST response overwrite deltas received after that request started. */
    fun acceptHistory(
        key: SessionRuntimeKey,
        messages: List<ChatMessage>,
        requestStartedAt: Long,
    ) {
        updateRuntime(key) { runtime ->
            val liveChars = runtime.chat.messages.sumOf { it.text.length + it.thinking.length }
            val historyChars = messages.sumOf { it.text.length + it.thinking.length }
            val keepLive = runtime.chat.messages.isNotEmpty() && (
                runtime.phase.isActive ||
                    runtime.phase == SessionRunPhase.COMPLETED_UNREAD ||
                    runtime.lastEventAt > requestStartedAt ||
                    runtime.chat.messages.size > messages.size ||
                    liveChars > historyChars
                )
            runtime.copy(
                chat = runtime.chat.copy(
                    messages = if (keepLive) runtime.chat.messages else messages,
                    historyLoading = false,
                    historyLoaded = true,
                    historyError = null,
                ),
            )
        }
    }

    fun historyFailed(key: SessionRuntimeKey, message: String) {
        updateRuntime(key) { runtime ->
            runtime.copy(
                chat = runtime.chat.copy(
                    historyLoading = false,
                    historyLoaded = runtime.chat.messages.isNotEmpty(),
                    historyError = message,
                ),
            )
        }
    }

    fun updateChat(key: SessionRuntimeKey, transform: (ChatUiState) -> ChatUiState) {
        updateRuntime(key) { it.copy(chat = transform(it.chat)) }
    }

    fun beginPrompt(key: SessionRuntimeKey, shownText: String) {
        lastActiveKey = key
        updateRuntime(key) { runtime ->
            runtime.copy(
                chat = runtime.chat.withUserMessage(shownText).copy(pendingAttachments = emptyList()),
                phase = SessionRunPhase.SUBMITTING,
                toolName = null,
                lastEventAt = System.currentTimeMillis(),
            )
        }
    }

    fun markInterrupted(key: SessionRuntimeKey) {
        updateRuntime(key) { runtime ->
            runtime.copy(
                chat = runtime.chat.markInterrupted(),
                phase = SessionRunPhase.INTERRUPTED,
                toolName = null,
                lastEventAt = System.currentTimeMillis(),
            )
        }
    }

    fun markFailed(key: SessionRuntimeKey, state: ChatUiState) {
        updateRuntime(key) { runtime ->
            runtime.copy(
                chat = state.copy(isGenerating = false),
                phase = SessionRunPhase.FAILED,
                toolName = null,
                lastEventAt = System.currentTimeMillis(),
            )
        }
    }

    fun finishLocal(key: SessionRuntimeKey) {
        updateRuntime(key) { runtime ->
            runtime.copy(
                chat = runtime.chat.copy(isGenerating = false),
                phase = SessionRunPhase.IDLE,
                toolName = null,
                lastEventAt = System.currentTimeMillis(),
            )
        }
    }

    fun continueAfterInput(key: SessionRuntimeKey) {
        updateRuntime(key) { runtime ->
            runtime.copy(
                phase = SessionRunPhase.THINKING,
                lastEventAt = System.currentTimeMillis(),
            )
        }
    }

    private fun updateRuntime(
        key: SessionRuntimeKey,
        transform: (SessionRuntime) -> SessionRuntime,
    ) {
        aliases[key.sessionId] = key
        _runtimes.update { map ->
            map + (key to transform(map[key] ?: SessionRuntime(key)))
        }
    }

    private fun resolve(event: ServerEvent): SessionRuntimeKey? {
        val id = event.sessionId
        if (id != null) {
            aliases[id]?.let { return it }
            _runtimes.value.keys.firstOrNull { it.sessionId == id }?.let { return it }
            if (event.type == "message.start" || event.type == "session.info") {
                return register(id, profiles.active.value)
            }
        }
        val active = _runtimes.value.values.filter { it.phase.isActive }
        return if (active.size == 1) active.single().key else lastActiveKey
    }

    private fun applyEvent(event: ServerEvent) {
        val key = resolve(event) ?: return
        if (event.type == "message.start") lastActiveKey = key
        updateRuntime(key) { runtime ->
            val reduced = runCatching { runtime.chat.reduce(event) }.getOrDefault(runtime.chat)
            val nextPhase = when (event.type) {
                "message.start", "reasoning.delta", "reasoning.available" -> SessionRunPhase.THINKING
                "message.delta" -> SessionRunPhase.STREAMING
                "tool.start" -> SessionRunPhase.USING_TOOL
                "tool.complete" -> if (reduced.isGenerating) SessionRunPhase.THINKING else runtime.phase
                "approval.request" -> SessionRunPhase.WAITING_APPROVAL
                "clarify.request" -> SessionRunPhase.WAITING_CLARIFICATION
                "message.complete" -> if (key in visible) SessionRunPhase.IDLE else SessionRunPhase.COMPLETED_UNREAD
                "error" -> SessionRunPhase.FAILED
                "session.info" -> when (event.bool("running")) {
                    true -> if (runtime.phase.isActive) runtime.phase else SessionRunPhase.THINKING
                    false -> if (key in visible) SessionRunPhase.IDLE else SessionRunPhase.COMPLETED_UNREAD
                    null -> runtime.phase
                }
                else -> runtime.phase
            }
            val finalChat = if (event.type == "session.info" && event.bool("running") == false) {
                reduced.copy(isGenerating = false)
            } else reduced
            runtime.copy(
                chat = finalChat,
                phase = nextPhase,
                toolName = when (event.type) {
                    "tool.start" -> event.str("name")?.ifBlank { null }
                    "tool.complete", "message.complete", "error" -> null
                    else -> runtime.toolName
                },
                lastEventAt = System.currentTimeMillis(),
            )
        }
    }

    private suspend fun resumeRunningSessions() {
        val candidates = _runtimes.value.values.filter { it.phase == SessionRunPhase.RECONNECTING }
        candidates.forEach { runtime ->
            runCatching { chatRepository.resume(runtime.key.sessionId, runtime.key.profile) }
                .onSuccess { handle ->
                    bindLiveHandle(runtime.key, handle)
                    updateRuntime(runtime.key) {
                        it.copy(phase = if (it.chat.isGenerating) SessionRunPhase.THINKING else SessionRunPhase.IDLE)
                    }
                }
                .onFailure { markInterrupted(runtime.key) }
        }
    }
}
