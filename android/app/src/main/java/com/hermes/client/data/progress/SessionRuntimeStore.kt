package com.hermes.client.data.progress

import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.ServerEvent
import com.hermes.client.data.network.bool
import com.hermes.client.data.network.str
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.SessionReadStore
import com.hermes.client.domain.ChatMessage
import com.hermes.client.ui.chat.ChatUiState
import com.hermes.client.ui.chat.markInterrupted
import com.hermes.client.ui.chat.reduce
import com.hermes.client.ui.chat.withUserMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
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
) {
    val hasRunningProcesses: Boolean get() = chat.backgroundProcesses.any { it.running }
    val hasActiveWork: Boolean get() = phase.isActive || hasRunningProcesses
}

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
    private val readStore: SessionReadStore? = null,
) {
    private val _runtimes = MutableStateFlow<Map<SessionRuntimeKey, SessionRuntime>>(emptyMap())
    val runtimes: StateFlow<Map<SessionRuntimeKey, SessionRuntime>> = _runtimes.asStateFlow()
    private val _unreadTokens = MutableStateFlow<Set<String>>(emptySet())
    val unreadTokens: StateFlow<Set<String>> = _unreadTokens.asStateFlow()

    private val aliases = ConcurrentHashMap<String, SessionRuntimeKey>()
    private val processPollJobs = ConcurrentHashMap<SessionRuntimeKey, Job>()
    private val processPollGraceRemaining = ConcurrentHashMap<SessionRuntimeKey, Int>()
    private val visible = ConcurrentHashMap.newKeySet<SessionRuntimeKey>()
    private val readPersistenceQueue = Channel<Pair<String, Boolean>>(Channel.UNLIMITED)
    @Volatile private var lastActiveKey: SessionRuntimeKey? = null

    init {
        readStore?.let { store ->
            appScope.launch { store.unread.collect { _unreadTokens.value = it } }
            // Serialize disk mutations. Launching one coroutine per mark can let a slower older
            // markUnread finish after markRead and resurrect a badge the user already cleared.
            appScope.launch {
                for ((token, unread) in readPersistenceQueue) {
                    if (unread) store.markUnread(token) else store.markRead(token)
                }
            }
        }
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
            if (key in map) map else pruneIdleRuntimes(
                map + (key to SessionRuntime(key = key, lastEventAt = System.currentTimeMillis())),
            )
        }
        val retained = _runtimes.value.keys
        aliases.entries.filter { it.value !in retained }.forEach { aliases.remove(it.key, it.value) }
        if (lastActiveKey !in retained) lastActiveKey = null
        return key
    }

    private fun pruneIdleRuntimes(map: Map<SessionRuntimeKey, SessionRuntime>): Map<SessionRuntimeKey, SessionRuntime> {
        val protected = map.values.filter { runtime ->
            runtime.hasActiveWork ||
                runtime.phase == SessionRunPhase.COMPLETED_UNREAD ||
                runtime.key in visible ||
                SessionReadStore.token(runtime.key.profile, runtime.key.sessionId) in _unreadTokens.value
        }.mapTo(mutableSetOf()) { it.key }
        val recentIdle = map.values.asSequence()
            .filter { it.key !in protected }
            .sortedByDescending { it.lastEventAt }
            .take(MAX_CACHED_IDLE_RUNTIMES)
            .mapTo(protected) { it.key }
        return map.filterKeys { it in recentIdle }
    }

    fun bindLiveHandle(key: SessionRuntimeKey, handle: String?) {
        aliases[key.sessionId] = key
        if (!handle.isNullOrBlank()) aliases[handle] = key
        _runtimes.update { map ->
            val current = map[key] ?: SessionRuntime(key)
            map + (key to current.copy(liveHandle = handle ?: current.liveHandle))
        }
        if (!handle.isNullOrBlank()) scheduleProcessPolling(key, PROCESS_DISCOVERY_GRACE_POLLS)
    }

    fun setVisible(key: SessionRuntimeKey, value: Boolean) {
        if (value) {
            visible += key
        } else {
            visible -= key
        }
    }

    /** Clear unread only after the chat has successfully loaded, not merely when its row is tapped. */
    fun markRead(key: SessionRuntimeKey) {
        val token = SessionReadStore.token(key.profile, key.sessionId)
        _unreadTokens.update { it - token }
        _runtimes.update { map ->
            val current = map[key] ?: return@update map
            if (current.phase == SessionRunPhase.COMPLETED_UNREAD) {
                map + (key to current.copy(phase = SessionRunPhase.IDLE))
            } else map
        }
        if (readStore != null) readPersistenceQueue.trySend(token to false)
    }

    private fun markUnread(key: SessionRuntimeKey) {
        val token = SessionReadStore.token(key.profile, key.sessionId)
        _unreadTokens.update { it + token }
        if (readStore != null) readPersistenceQueue.trySend(token to true)
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
        scheduleProcessPolling(key, PROCESS_DISCOVERY_GRACE_POLLS)
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

    /** Apply asynchronously downloaded thumbnails without replacing newer live text/deltas. */
    fun acceptHydratedImages(key: SessionRuntimeKey, hydrated: List<ChatMessage>) {
        val byId = hydrated.associate { it.id to it.images }
        updateRuntime(key) { runtime ->
            runtime.copy(chat = runtime.chat.copy(messages = runtime.chat.messages.map { message ->
                val images = byId[message.id]
                if (!images.isNullOrEmpty()) message.copy(images = images) else message
            }))
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

    fun beginPrompt(
        key: SessionRuntimeKey,
        shownText: String,
        images: List<com.hermes.client.domain.ChatImage> = emptyList(),
        messageId: String = "u-${System.nanoTime()}",
    ) {
        lastActiveKey = key
        updateRuntime(key) { runtime ->
            runtime.copy(
                chat = runtime.chat.withUserMessage(shownText, images, messageId)
                    .copy(pendingAttachments = emptyList()),
                phase = SessionRunPhase.SUBMITTING,
                toolName = null,
                lastEventAt = System.currentTimeMillis(),
            )
        }
        scheduleProcessPolling(key, PROCESS_DISCOVERY_GRACE_POLLS)
    }

    fun updateUserImages(
        key: SessionRuntimeKey,
        messageId: String,
        transform: (List<com.hermes.client.domain.ChatImage>) -> List<com.hermes.client.domain.ChatImage>,
    ) {
        updateRuntime(key) { runtime ->
            runtime.copy(chat = runtime.chat.copy(messages = runtime.chat.messages.map { message ->
                if (message.id == messageId) message.copy(images = transform(message.images)) else message
            }))
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
            val withTerminalOutput = if (event.type == "agent.terminal.output") {
                val processId = event.str("process_id")
                val chunk = event.str("chunk").orEmpty()
                reduced.copy(backgroundProcesses = reduced.backgroundProcesses.map { process ->
                    if (process.id == processId) process.copy(
                        outputTail = (process.outputTail + chunk).takeLast(PROCESS_OUTPUT_TAIL_CHARS),
                    ) else process
                })
            } else reduced
            val nextPhase = when (event.type) {
                "message.start", "reasoning.delta", "reasoning.available" -> SessionRunPhase.THINKING
                "message.delta" -> SessionRunPhase.STREAMING
                "tool.start" -> SessionRunPhase.USING_TOOL
                "tool.complete" -> if (withTerminalOutput.isGenerating) SessionRunPhase.THINKING else runtime.phase
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
                withTerminalOutput.copy(isGenerating = false)
            } else withTerminalOutput
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
        if (event.type in setOf("tool.complete", "message.complete", "agent.terminal.output")) {
            scheduleProcessPolling(key, PROCESS_DISCOVERY_GRACE_POLLS)
        }
        if (event.type == "message.complete" || (event.type == "session.info" && event.bool("running") == false)) {
            if (key in visible) markRead(key) else markUnread(key)
        }
    }

    private suspend fun refreshProcesses(key: SessionRuntimeKey) {
        val runtime = _runtimes.value[key] ?: return
        val handle = runtime.liveHandle ?: return
        runCatching { chatRepository.listProcesses(handle) }
            .onSuccess { processes ->
                updateRuntime(key) { current ->
                    current.copy(
                        chat = current.chat.copy(backgroundProcesses = processes),
                        lastEventAt = if (processes.any { it.running }) {
                            System.currentTimeMillis()
                        } else current.lastEventAt,
                    )
                }
            }
    }

    private fun scheduleProcessPolling(key: SessionRuntimeKey, gracePolls: Int = 0) {
        if (_runtimes.value[key]?.liveHandle.isNullOrBlank()) return
        if (gracePolls > 0) {
            processPollGraceRemaining.merge(key, gracePolls, ::maxOf)
        }
        val existing = processPollJobs[key]
        if (existing?.isActive == true) return
        val job = appScope.launch(start = CoroutineStart.LAZY) {
            try {
                do {
                    refreshProcesses(key)
                    val current = _runtimes.value[key] ?: break
                    val graceRemaining = processPollGraceRemaining.compute(key) { _, remaining ->
                        ((remaining ?: 0) - 1).coerceAtLeast(0)
                    } ?: 0
                    if (!current.hasActiveWork && graceRemaining <= 0) break
                    delay(PROCESS_POLL_MS)
                } while (isActive)
            } finally {
                processPollJobs.remove(key)
                processPollGraceRemaining.remove(key)
            }
        }
        processPollJobs[key] = job
        job.start()
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

    private companion object {
        const val PROCESS_POLL_MS = 5_000L
        const val PROCESS_DISCOVERY_GRACE_POLLS = 4
        const val PROCESS_OUTPUT_TAIL_CHARS = 4_000
        /** Keep recent idle histories warm without retaining every session opened in this process. */
        const val MAX_CACHED_IDLE_RUNTIMES = 20
    }
}
