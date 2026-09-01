package com.hermes.client.data.progress

import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.LifecycleEventDto
import com.hermes.client.data.network.ServerEvent
import com.hermes.client.data.network.bool
import com.hermes.client.data.network.str
import com.hermes.client.data.diagnostics.DebugLog
import com.hermes.client.data.repository.ChatMediaRepository
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.SessionReadStore
import com.hermes.client.data.repository.SessionRepository
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.ui.chat.ChatUiState
import com.hermes.client.ui.chat.markInterrupted
import com.hermes.client.ui.chat.organizedForDisplay
import com.hermes.client.ui.chat.reduce
import com.hermes.client.ui.chat.withUserMessage
import kotlinx.coroutines.CancellationException
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

enum class ManualHistoryResult { CHANGED, UNCHANGED, BUSY }

enum class SessionRunPhase {
    IDLE,
    SUBMITTING,
    THINKING,
    STREAMING,
    USING_TOOL,
    WAITING_APPROVAL,
    WAITING_CLARIFICATION,
    WAITING_ATTENTION,
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
        SessionRunPhase.WAITING_ATTENTION,
        SessionRunPhase.RECONNECTING,
    )

data class SessionRuntime(
    val key: SessionRuntimeKey,
    val liveHandle: String? = null,
    val chat: ChatUiState = ChatUiState.empty(),
    val phase: SessionRunPhase = SessionRunPhase.IDLE,
    val toolName: String? = null,
    val lastEventAt: Long = 0L,
    val startedLocally: Boolean = false,
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
    private val sessionRepository: SessionRepository? = null,
    private val mediaRepository: ChatMediaRepository? = null,
) {
    private val _runtimes = MutableStateFlow<Map<SessionRuntimeKey, SessionRuntime>>(emptyMap())
    val runtimes: StateFlow<Map<SessionRuntimeKey, SessionRuntime>> = _runtimes.asStateFlow()
    private val _unreadTokens = MutableStateFlow<Set<String>>(emptySet())
    val unreadTokens: StateFlow<Set<String>> = _unreadTokens.asStateFlow()

    private val aliases = ConcurrentHashMap<String, SessionRuntimeKey>()
    private val processPollJobs = ConcurrentHashMap<SessionRuntimeKey, Job>()
    private val processPollGraceRemaining = ConcurrentHashMap<SessionRuntimeKey, Int>()
    private val historyReconcileJobs = ConcurrentHashMap<SessionRuntimeKey, Job>()
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
            chatRepository.events.collect { event ->
                try {
                    applyEvent(event)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    DebugLog.log("event", "failed ${event.type} session=${event.sessionId ?: "-"}: ${error.message}")
                }
            }
        }
        appScope.launch {
            var previous: ConnectionState? = null
            chatRepository.connectionState.collect { current ->
                if (current is ConnectionState.Reconnecting || current is ConnectionState.Error ||
                    current is ConnectionState.Disconnected
                ) {
                    _runtimes.update { map ->
                        map.mapValues { (_, runtime) ->
                            if (runtime.phase.isActive) runtime.copy(phase = SessionRunPhase.RECONNECTING)
                            else runtime
                        }
                    }
                }
                if (current is ConnectionState.Connected && previous != null && previous !is ConnectionState.Connected) {
                    resumeRunningSessions()
                    // WebSocket notifications are not replayed across a disconnect. Re-read every
                    // active/visible transcript so any events produced in the gap are recovered.
                    _runtimes.value.values
                        .filter { it.phase.isActive || it.key in visible || it.chat.isGenerating }
                        .forEach { runtime ->
                            val expectation = expectationFor(runtime).let { expected ->
                                // A stream interrupted mid-answer may not be a literal prefix of
                                // Hermes' persisted final formatting. Preserve the user-turn guard,
                                // but let authoritative REST replace the partial assistant body.
                                if (runtime.phase == SessionRunPhase.RECONNECTING || runtime.chat.isGenerating) {
                                    expected.copy(lastAssistantText = "")
                                } else expected
                            }
                            scheduleHistoryReconciliation(runtime.key, expectation)
                        }
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

    /**
     * Resolve a transient gateway runtime handle to the durable session/profile used by REST
     * history and navigation. Notifications must use this rather than treating every event id as
     * a stored conversation id; otherwise tapping a completion alert can open an empty chat.
     */
    fun notificationTarget(eventSessionId: String?): SessionRuntimeKey? {
        val id = eventSessionId?.takeIf { it.isNotBlank() } ?: return null
        return aliases[id] ?: _runtimes.value.keys.firstOrNull { it.sessionId == id }
    }

    fun setVisible(key: SessionRuntimeKey, value: Boolean) {
        if (value) {
            visible += key
        } else {
            visible -= key
        }
    }

    /**
     * Foreground recovery gate for the conversation currently under the startup overlay. It
     * refreshes the authoritative transcript and replaces the stale socket handle before the UI
     * is revealed, so returning to a chat never exposes a second full-screen loading state.
     */
    suspend fun recoverVisibleSession(key: SessionRuntimeKey): Boolean {
        val repository = sessionRepository ?: return false
        val runtime = _runtimes.value[key] ?: SessionRuntime(key)
        val expectation = expectationFor(runtime).let { expected ->
            if (runtime.phase == SessionRunPhase.RECONNECTING || runtime.chat.isGenerating) {
                expected.copy(lastAssistantText = "")
            } else expected
        }
        var accepted = false
        for (delayMs in FOREGROUND_RECOVERY_DELAYS_MS) {
            if (delayMs > 0L) delay(delayMs)
            try {
                val history = repository.history(key.sessionId, key.profile)
                    .map { it.organizedForDisplay() }
                accepted = acceptReconciledHistory(key, history, expectation)
                if (accepted) break
                DebugLog.log("history", "foreground recovery ${key.sessionId} waiting for complete history")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DebugLog.log("history", "foreground recovery ${key.sessionId} failed: ${error.message}")
            }
        }
        if (!accepted) return false

        val handle = try {
            chatRepository.resume(key.sessionId, key.profile)?.takeIf { it.isNotBlank() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            DebugLog.log("session", "foreground resume ${key.sessionId} failed: ${error.message}")
            null
        } ?: return false
        bindLiveHandle(key, handle)
        markRead(key)
        val media = mediaRepository
        if (media != null) {
            runCatching {
                val committed = _runtimes.value[key]?.chat?.messages.orEmpty()
                acceptHydratedImages(key, media.hydrateMessages(committed, key.profile))
            }.onFailure { error ->
                if (error is CancellationException) throw error
                DebugLog.log("media", "foreground hydration ${key.sessionId} failed: ${error.message}")
            }
        }
        return true
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
                    // Same identity alignment as acceptReconciledHistory: reuse the retained
                    // runtime's ids so id-derived list keys survive the swap. Without it,
                    // reopening a session chatted in this app run replaced every u-*/a-* id
                    // with h-* in one frame — a full LazyColumn remount (visible ghost flash)
                    // and a lost viewport anchor.
                    messages = if (keepLive) {
                        runtime.chat.messages
                    } else {
                        com.hermes.client.ui.chat.alignMessageIds(messages, runtime.chat.messages)
                    },
                    historyLoading = false,
                    historyLoaded = true,
                    historyError = null,
                ),
            )
        }
    }

    /**
     * User-requested authoritative refresh for an already visible conversation. Unlike initial
     * history loading this never exposes a loading state or clears the current transcript first.
     * If a run starts while the request is in flight, retain the live state and let the caller
     * queue one refresh after completion instead of overwriting unpersisted deltas.
     */
    fun acceptManualHistory(key: SessionRuntimeKey, messages: List<ChatMessage>): ManualHistoryResult {
        val previous = _runtimes.value[key]?.chat?.messages
        updateRuntime(key) { runtime ->
            if (runtime.chat.isGenerating || runtime.phase.isActive) return@updateRuntime runtime
            runtime.copy(
                chat = runtime.chat.copy(
                    messages = com.hermes.client.ui.chat.inheritTimestamps(
                        com.hermes.client.ui.chat.alignMessageIds(messages, runtime.chat.messages),
                        runtime.chat.messages,
                    ),
                    historyLoading = false,
                    historyLoaded = true,
                    historyError = null,
                ),
            )
        }
        val committedRuntime = _runtimes.value[key] ?: return ManualHistoryResult.BUSY
        if (committedRuntime.chat.isGenerating || committedRuntime.phase.isActive) {
            return ManualHistoryResult.BUSY
        }
        val committed = committedRuntime.chat.messages
        val accepted = committed.size == messages.size && committed.zip(messages).all { (a, b) ->
            a.copy(timestamp = null, id = "") == b.copy(timestamp = null, id = "")
        }
        if (!accepted) return ManualHistoryResult.BUSY
        return if (previous == committed) ManualHistoryResult.UNCHANGED else ManualHistoryResult.CHANGED
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
        _runtimes.value[key]?.let { scheduleHistoryReconciliation(key, expectationFor(it)) }
    }

    fun updateChat(key: SessionRuntimeKey, transform: (ChatUiState) -> ChatUiState) {
        updateRuntime(key) { it.copy(chat = transform(it.chat)) }
    }

    fun beginPrompt(
        key: SessionRuntimeKey,
        shownText: String,
        images: List<com.hermes.client.domain.ChatImage> = emptyList(),
        files: List<com.hermes.client.domain.ChatFile> = emptyList(),
        messageId: String = "u-${System.nanoTime()}",
    ) {
        historyReconcileJobs.remove(key)?.cancel()
        lastActiveKey = key
        updateRuntime(key) { runtime ->
            runtime.copy(
                chat = runtime.chat.withUserMessage(shownText, images, files, messageId)
                    .copy(pendingAttachments = emptyList()),
                phase = SessionRunPhase.SUBMITTING,
                toolName = null,
                lastEventAt = System.currentTimeMillis(),
                startedLocally = true,
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

    fun updateUserFiles(
        key: SessionRuntimeKey,
        messageId: String,
        transform: (List<com.hermes.client.domain.ChatFile>) -> List<com.hermes.client.domain.ChatFile>,
    ) {
        updateRuntime(key) { runtime ->
            runtime.copy(chat = runtime.chat.copy(messages = runtime.chat.messages.map { message ->
                if (message.id == messageId) message.copy(files = transform(message.files)) else message
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
                startedLocally = false,
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
                startedLocally = false,
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
                startedLocally = false,
            )
        }
    }

    fun continueAfterInput(key: SessionRuntimeKey) {
        updateRuntime(key) { runtime ->
            runtime.copy(
                phase = SessionRunPhase.THINKING,
                lastEventAt = System.currentTimeMillis(),
                startedLocally = true,
            )
        }
    }

    /** Fold sanitized Relay observations into the same state read by session/chat/activity UIs. */
    fun applyObservedLifecycle(event: LifecycleEventDto) {
        val key = observedLifecycleKey(event)
        // Connector observations are the authoritative bridge between Hermes' short-lived runtime
        // handle and its durable database key. Preserve both aliases so a later WebSocket
        // completion/progress notification always opens the stored conversation.
        event.runtimeSessionId.takeIf { it.isNotBlank() }?.let { aliases[it] = key }
        val now = System.currentTimeMillis()
        updateRuntime(key) { runtime ->
            when (event.event) {
                "run.started", "run.resumed" -> runtime.copy(
                    chat = runtime.chat.copy(isGenerating = true),
                    phase = SessionRunPhase.THINKING,
                    toolName = null,
                    lastEventAt = now,
                )
                "run.waiting" -> runtime.copy(
                    chat = runtime.chat.copy(isGenerating = true),
                    phase = when (runtime.phase) {
                        SessionRunPhase.WAITING_APPROVAL,
                        SessionRunPhase.WAITING_CLARIFICATION -> runtime.phase
                        else -> SessionRunPhase.WAITING_ATTENTION
                    },
                    lastEventAt = now,
                )
                "run.completed" -> runtime.copy(
                    chat = runtime.chat.copy(isGenerating = false),
                    phase = if (key in visible) SessionRunPhase.IDLE else SessionRunPhase.COMPLETED_UNREAD,
                    toolName = null,
                    lastEventAt = now,
                    startedLocally = false,
                )
                "run.interrupted", "run.unknown" -> runtime.copy(
                    chat = runtime.chat.copy(isGenerating = false),
                    phase = SessionRunPhase.INTERRUPTED,
                    toolName = null,
                    lastEventAt = now,
                    startedLocally = false,
                )
                else -> runtime
            }
        }
        when (event.event) {
            "run.completed" -> {
                if (key in visible) markRead(key) else markUnread(key)
                _runtimes.value[key]?.let { runtime ->
                    // The observer confirms that Hermes has finished, but it does not carry the
                    // final assistant body. A phone that was backgrounded may only hold an early
                    // streaming prefix (or a differently formatted partial snapshot), so requiring
                    // REST to contain that exact assistant text can reject the authoritative final
                    // answer forever. Preserve the user-turn identity/count guard, but let the
                    // completed REST assistant turn replace the partial body.
                    scheduleHistoryReconciliation(
                        key,
                        expectationFor(runtime).copy(lastAssistantText = ""),
                    )
                }
            }
            "run.interrupted", "run.unknown" -> if (key !in visible) markUnread(key)
        }
    }

    /**
     * Connector events omit `profile` for Hermes' default identity while session-list rows
     * normalize the same identity to "default". Prefer an already registered runtime for the
     * session (and specifically its default-profile variant) so completion does not update a
     * shadow `(null, session)` entry while the visible `(default, session)` chat stays generating.
     * If the process has no runtime yet, retaining null is safe: the notification route explicitly
     * opens the default profile and its fresh history request is authoritative.
     */
    private fun observedLifecycleKey(event: LifecycleEventDto): SessionRuntimeKey {
        val profile = event.profile?.trim()?.ifBlank { null }
        val candidates = _runtimes.value.keys.filter { it.sessionId == event.storedSessionId }
        if (profile != null) {
            candidates.firstOrNull { it.profile == profile }?.let { return it }
        } else {
            candidates.singleOrNull()?.let { return it }
            candidates.firstOrNull { it.profile == null || it.profile == DEFAULT_PROFILE }?.let { return it }
        }
        return key(event.storedSessionId, profile)
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
            DebugLog.log("event", "unmatched ${event.type} session=$id; awaiting history reconciliation")
            return null
        }
        val active = _runtimes.value.values.filter { it.phase.isActive }
        if (active.size == 1) return active.single().key
        if (active.size > 1) {
            DebugLog.log("event", "ambiguous ${event.type} without session id across ${active.size} runs")
        } else {
            DebugLog.log("event", "unmatched ${event.type} without session id")
        }
        return null
    }

    private fun applyEvent(event: ServerEvent) {
        val key = resolve(event) ?: return
        if (event.type == "message.start") lastActiveKey = key
        updateRuntime(key) { runtime ->
            val reduced = try {
                runtime.chat.reduce(event)
            } catch (error: Exception) {
                DebugLog.log("event", "reducer rejected ${event.type} session=${event.sessionId ?: "-"}: ${error.message}")
                runtime.chat
            }
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
            val finalChat = if (event.type == "session.info") {
                when (event.bool("running")) {
                    true -> withTerminalOutput.copy(isGenerating = true)
                    false -> withTerminalOutput.copy(isGenerating = false)
                    null -> withTerminalOutput
                }
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
                startedLocally = when (event.type) {
                    "message.complete", "error" -> false
                    "session.info" -> if (event.bool("running") == false) false else runtime.startedLocally
                    else -> runtime.startedLocally
                },
            )
        }
        if (event.type in setOf("tool.complete", "message.complete", "agent.terminal.output")) {
            scheduleProcessPolling(key, PROCESS_DISCOVERY_GRACE_POLLS)
        }
        if (event.type == "message.complete" || (event.type == "session.info" && event.bool("running") == false)) {
            if (key in visible) markRead(key) else markUnread(key)
            if (event.type == "message.complete" && mediaRepository != null) {
                // The final WebSocket event may already contain @image or Markdown image output.
                // Hydrate that snapshot immediately instead of waiting for the eventually
                // consistent REST history reconciliation passes below.
                val snapshot = _runtimes.value[key]?.chat?.messages.orEmpty()
                appScope.launch {
                    val hydrated = mediaRepository.hydrateMessages(snapshot, key.profile)
                    acceptHydratedImages(key, hydrated)
                }
            }
            _runtimes.value[key]?.let { scheduleHistoryReconciliation(key, expectationFor(it)) }
        }
    }

    private data class HistoryExpectation(
        val userTurns: Int,
        val assistantTurns: Int,
        val lastUserText: String,
        val lastAssistantText: String,
    )

    private fun expectationFor(runtime: SessionRuntime): HistoryExpectation = HistoryExpectation(
        userTurns = runtime.chat.messages.count { it.role == Role.USER },
        assistantTurns = runtime.chat.messages.count { it.role == Role.ASSISTANT },
        lastUserText = runtime.chat.messages.lastOrNull { it.role == Role.USER }?.text.orEmpty().matchText(),
        lastAssistantText = runtime.chat.messages.lastOrNull { it.role == Role.ASSISTANT }?.text.orEmpty().matchText(),
    )

    /**
     * Reconcile several times because Hermes can emit its terminal event slightly before the final
     * database transaction becomes visible through REST. Every pass is safe: a snapshot that does
     * not yet cover the locally observed turn is rejected, while a later authoritative snapshot
     * repairs missing WebSocket start/delta/complete events.
     */
    private fun scheduleHistoryReconciliation(
        key: SessionRuntimeKey,
        expectation: HistoryExpectation,
    ) {
        val repository = sessionRepository ?: return
        historyReconcileJobs.remove(key)?.cancel()
        lateinit var job: Job
        job = appScope.launch(start = CoroutineStart.LAZY) {
            try {
                for (delayMs in HISTORY_RECONCILE_DELAYS_MS) {
                    delay(delayMs)
                    val history = try {
                        repository.history(key.sessionId, key.profile).map { it.organizedForDisplay() }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        DebugLog.log("history", "reconcile ${key.sessionId} failed: ${error.message}")
                        continue
                    }
                    val accepted = acceptReconciledHistory(key, history, expectation)
                    DebugLog.log(
                        "history",
                        "reconcile ${key.sessionId}: ${history.size} messages, accepted=$accepted",
                    )
                    if (accepted && mediaRepository != null) {
                        val committed = _runtimes.value[key]?.chat?.messages.orEmpty()
                        val hydrated = mediaRepository.hydrateMessages(committed, key.profile)
                        acceptHydratedImages(key, hydrated)
                    }
                }
            } finally {
                historyReconcileJobs.remove(key, job)
            }
        }
        historyReconcileJobs[key] = job
        job.start()
    }

    private fun acceptReconciledHistory(
        key: SessionRuntimeKey,
        messages: List<ChatMessage>,
        expectation: HistoryExpectation,
    ): Boolean {
        updateRuntime(key) { runtime ->
            val current = expectationFor(runtime)
            val newerPromptStarted = current.userTurns > expectation.userTurns ||
                (expectation.lastUserText.isNotBlank() && current.lastUserText != expectation.lastUserText)
            // It is safe to refresh text while a run is still active as long as REST covers every
            // locally observed turn. Keep the phase unchanged; a terminal event/session.info still
            // owns the transition to idle. This also recovers deltas lost during reconnect.
            if (newerPromptStarted || !messages.covers(expectation)) {
                return@updateRuntime runtime
            }
            runtime.copy(
                chat = runtime.chat.copy(
                    // Order matters: identity first (list keys/anchors survive the swap), then
                    // timestamps inherited onto the aligned list.
                    messages = com.hermes.client.ui.chat.inheritTimestamps(
                        com.hermes.client.ui.chat.alignMessageIds(messages, runtime.chat.messages),
                        runtime.chat.messages,
                    ),
                    historyLoading = false,
                    historyLoaded = true,
                    historyError = null,
                ),
            )
        }
        // StateFlow.update may retry its transform under contention, so keep the transform free of
        // side effects and derive acceptance from the committed snapshot afterward.
        // Timestamp inheritance and id alignment both mutate the committed list relative to the
        // raw REST result, so acceptance compares content with stamps AND ids normalized out.
        val committed = _runtimes.value[key]?.chat?.messages ?: return false
        return committed.size == messages.size &&
            committed.zip(messages).all { (a, b) ->
                a.copy(timestamp = null, id = "") == b.copy(timestamp = null, id = "")
            }
    }

    private fun List<ChatMessage>.covers(expectation: HistoryExpectation): Boolean {
        val users = filter { it.role == Role.USER }
        val assistants = filter { it.role == Role.ASSISTANT }
        if (users.size < expectation.userTurns || assistants.size < expectation.assistantTurns) return false
        if (expectation.lastUserText.isNotBlank() && users.lastOrNull()?.text.orEmpty().matchText() != expectation.lastUserText) {
            return false
        }
        if (expectation.lastAssistantText.isBlank()) return true
        val persisted = assistants.lastOrNull()?.text.orEmpty().matchText()
        return persisted == expectation.lastAssistantText || persisted.contains(expectation.lastAssistantText)
    }

    private fun String.matchText(): String = trim().replace(Regex("\\s+"), " ")

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
                .onFailure { error ->
                    // Resume can race a task completing while the socket was down. Do not invent
                    // an interruption: lifecycle sync and authoritative history decide whether it
                    // finished, is still running, or genuinely stopped.
                    DebugLog.log("session", "resume after reconnect ${runtime.key.sessionId} failed: ${error.message}")
                    scheduleHistoryReconciliation(
                        runtime.key,
                        expectationFor(runtime).copy(lastAssistantText = ""),
                    )
                }
        }
    }

    private companion object {
        const val PROCESS_POLL_MS = 5_000L
        const val PROCESS_DISCOVERY_GRACE_POLLS = 4
        const val PROCESS_OUTPUT_TAIL_CHARS = 4_000
        const val DEFAULT_PROFILE = "default"
        val HISTORY_RECONCILE_DELAYS_MS = longArrayOf(250L, 1_000L, 3_000L, 10_000L)
        val FOREGROUND_RECOVERY_DELAYS_MS = longArrayOf(0L, 250L, 750L, 1_500L)
        /** Keep recent idle histories warm without retaining every session opened in this process. */
        const val MAX_CACHED_IDLE_RUNTIMES = 20
    }
}
