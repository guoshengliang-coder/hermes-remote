package com.hermes.client.data.progress

import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.LifecycleEventDto
import com.hermes.client.data.network.ServerEvent
import com.hermes.client.data.network.bool
import com.hermes.client.data.network.str
import com.hermes.client.data.network.todoCounts
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

/**
 * Outcomes a row keeps showing until the user opens the chat (docs/DESIGN.md §5.2). A stop
 * issued from this app is deliberately NOT one of these: markInterrupted goes straight to IDLE.
 */
val SessionRunPhase.isTerminalVerdict: Boolean
    get() = this == SessionRunPhase.COMPLETED_UNREAD ||
        this == SessionRunPhase.INTERRUPTED ||
        this == SessionRunPhase.FAILED

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
    /** Session title as last reported by Hermes (lifecycle event or an opened chat); null if unknown. */
    val title: String? = null,
    /** Todo counts from the latest `todo` tool result; 0/0 until the run reports a list. */
    val todoDone: Int = 0,
    val todoTotal: Int = 0,
    /** Wall-clock start of the current or most recent run; drives the elapsed chronometer. */
    val runStartedAt: Long? = null,
    /** When the run last reached a terminal state (complete/error/idle); 0 = never. */
    val lastTerminalAt: Long = 0L,
    /** When the state now described by [phase] happened — event time, not sync time. */
    val occurredAt: Long = 0L,
    /**
     * The active phase this run was in when the socket dropped; restored once it is back. Only
     * meaningful while [phase] is RECONNECTING and cleared by [normalized] otherwise.
     */
    val phaseBeforeReconnect: SessionRunPhase? = null,
) {
    val hasRunningProcesses: Boolean get() = chat.backgroundProcesses.any { it.running }
    val hasActiveWork: Boolean get() = phase.isActive || hasRunningProcesses
}

/**
 * The store's invariant, applied to every committed runtime: [SessionRuntime.phase] is the single
 * source of truth for "is this turn running". `isGenerating` is derived from it, and a phase that
 * is not active leaves no assistant bubble streaming.
 *
 * Every session-level terminal writer (an observed `run.completed`, `session.info{running:false}`,
 * finishLocal, markFailed) used to clear phase and isGenerating and forget the bubble, so a turn
 * that had finished on the Mac kept rendering "生成中" with a live chronometer for as long as the
 * process lived, and the unlocked composer let a second live bubble stack under it (HG-6, HG-7).
 * Normalizing here instead of fixing each writer means a writer that forgets cannot desync the
 * committed state.
 */
internal fun SessionRuntime.normalized(): SessionRuntime {
    val active = phase.isActive
    val messages = if (active) chat.messages else chat.messages.map { message ->
        if (message.role == Role.ASSISTANT && message.isStreaming) {
            message.copy(isStreaming = false).organizedForDisplay()
        } else message
    }
    return copy(
        chat = chat.copy(isGenerating = active, messages = messages),
        phaseBeforeReconnect = if (phase == SessionRunPhase.RECONNECTING) phaseBeforeReconnect else null,
    )
}

/**
 * A reconnect used to collapse every active phase into THINKING, so a run that was waiting for the
 * user came back as "思考中" and nobody was told they were being waited on (HG-8). Pending cards are
 * authoritative for approval and clarification; WAITING_ATTENTION is known only to the observer,
 * so it is remembered across the outage.
 */
internal fun SessionRuntime.restoredPhaseAfterReconnect(): SessionRunPhase = when {
    chat.pendingApproval != null -> SessionRunPhase.WAITING_APPROVAL
    chat.pendingClarify != null -> SessionRunPhase.WAITING_CLARIFICATION
    else -> phaseBeforeReconnect
        ?.takeIf { it.isActive && it != SessionRunPhase.RECONNECTING }
        ?: SessionRunPhase.THINKING
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
    /** Wall clock, injectable so staleness and expiry can be driven by a test. */
    private val clock: () -> Long = { System.currentTimeMillis() },
    /**
     * Whether the foreground watchdog runs. Off by default so a test's advanceUntilIdle never
     * chases a rescheduling timer; production turns it on (AppModule).
     */
    private val watchdogEnabled: Boolean = false,
) {
    private val _runtimes = MutableStateFlow<Map<SessionRuntimeKey, SessionRuntime>>(emptyMap())
    val runtimes: StateFlow<Map<SessionRuntimeKey, SessionRuntime>> = _runtimes.asStateFlow()
    private val _unreadTokens = MutableStateFlow<Set<String>>(emptySet())
    val unreadTokens: StateFlow<Set<String>> = _unreadTokens.asStateFlow()

    private val aliases = ConcurrentHashMap<String, SessionRuntimeKey>()
    /**
     * Events that arrived for a session id nothing is aliased to yet — a run started on the Mac,
     * a scheduled run, a handle the phone has not resumed. They used to be dropped on the floor
     * (observed as `unmatched reasoning.delta … awaiting history reconciliation`); a dropped
     * message.complete then left the turn open until the lifecycle inbox caught up minutes
     * later. Held briefly and replayed in order the moment an alias for that id appears.
     */
    private val pendingEvents = ConcurrentHashMap<String, ArrayDeque<Pair<Long, ServerEvent>>>()
    private val replaying = ThreadLocal<Boolean>()
    private val processPollJobs = ConcurrentHashMap<SessionRuntimeKey, Job>()
    private val processPollGraceRemaining = ConcurrentHashMap<SessionRuntimeKey, Int>()
    private val historyReconcileJobs = ConcurrentHashMap<SessionRuntimeKey, Job>()
    private val visible = ConcurrentHashMap.newKeySet<SessionRuntimeKey>()
    private val _visibleSessions = MutableStateFlow<Set<SessionRuntimeKey>>(emptySet())
    /** Sessions whose chat screen is currently composed (regardless of app foreground state). */
    val visibleSessions: StateFlow<Set<SessionRuntimeKey>> = _visibleSessions.asStateFlow()
    private val readPersistenceQueue = Channel<Pair<String, Boolean>>(Channel.UNLIMITED)
    @Volatile private var lastActiveKey: SessionRuntimeKey? = null
    // A composed chat screen stays "visible" while the phone is locked; only a visible chat in a
    // foreground app is actually being read. Completion folds use this to decide read vs unread.
    @Volatile private var appInForeground = false
    @Volatile private var connected = false
    private val lastProbeAt = ConcurrentHashMap<SessionRuntimeKey, Long>()
    private val probeFailures = ConcurrentHashMap<SessionRuntimeKey, Int>()
    @Volatile private var watchdogJob: Job? = null

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
                connected = current is ConnectionState.Connected
                if (current is ConnectionState.Reconnecting || current is ConnectionState.Error ||
                    current is ConnectionState.Disconnected
                ) {
                    _runtimes.update { map ->
                        map.mapValues { (_, runtime) ->
                            if (runtime.phase.isActive && runtime.phase != SessionRunPhase.RECONNECTING) {
                                runtime.copy(
                                    phase = SessionRunPhase.RECONNECTING,
                                    phaseBeforeReconnect = runtime.phase,
                                ).normalized()
                            } else runtime
                        }
                    }
                    DebugLog.log("phase") {
                        val held = _runtimes.value.values.filter { it.phase == SessionRunPhase.RECONNECTING }
                        "${held.size} active run(s) → RECONNECTING cause=connection:${current::class.simpleName}" +
                            held.joinToString(prefix = " [", postfix = "]") { "${it.key.sessionId}:${it.phaseBeforeReconnect}" }
                    }
                }
                if (current is ConnectionState.Connected && previous != null && previous !is ConnectionState.Connected) {
                    resumeRunningSessions()
                    // WebSocket notifications are not replayed across a disconnect, so a run
                    // that was streaming has to be re-read to recover the gap. An idle chat that
                    // merely happens to be on screen has no gap to recover: nothing was streaming,
                    // and the foreground startup gate already refreshes the visible destination
                    // (ForegroundRecoveryCoordinator) when the app comes back. Pulling its whole
                    // transcript here too was the common case of the reconnect fetch storm.
                    _runtimes.value.values
                        .filter { it.phase.isActive || it.chat.isGenerating }
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
        replayPending(sessionId)
        return key
    }

    private fun pruneIdleRuntimes(map: Map<SessionRuntimeKey, SessionRuntime>): Map<SessionRuntimeKey, SessionRuntime> {
        val protected = map.values.filter { runtime ->
            runtime.hasActiveWork ||
                runtime.phase.isTerminalVerdict ||
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
        replayPending(key.sessionId)
        if (!handle.isNullOrBlank()) replayPending(handle)
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
        _visibleSessions.value = visible.toSet()
    }

    /**
     * Process foreground state from ProcessLifecycleOwner. Returning to the foreground with a chat
     * still open means the user is now looking at whatever finished while the phone was locked.
     */
    fun setAppInForeground(foreground: Boolean) {
        appInForeground = foreground
        if (foreground) visible.toList().forEach { key ->
            if (_runtimes.value[key]?.phase?.isTerminalVerdict == true) markRead(key)
        }
        // Waking up is the one moment Doze cannot have hidden: whatever finished while the phone
        // slept is asked about now instead of whenever the inbox next gets polled.
        if (foreground) {
            probeActiveRuntimes(reason = "foreground", staleOnly = false)
            scheduleWatchdog()
        }
    }

    enum class ProbeResult { PROBED, RATE_LIMITED, OFFLINE, FAILED, GAVE_UP, IDLE }

    /**
     * Ask Hermes whether a run the store still believes is active really is. A successful
     * `session.resume` makes Hermes emit `session.info{running}`, which the normal event fold
     * settles: `running:false` retires the phase and (via normalization) closes the bubble. The
     * store never invents a terminal state from a transport error — only a run that has been
     * silent past [ACTIVE_RUN_HARD_CAP_MS] and failed to answer twice is marked interrupted, so
     * a row cannot spin forever after the Mac disappears.
     */
    suspend fun probe(key: SessionRuntimeKey, force: Boolean = false): ProbeResult {
        val runtime = _runtimes.value[key] ?: return ProbeResult.IDLE
        if (!runtime.phase.isActive || runtime.phase == SessionRunPhase.RECONNECTING) return ProbeResult.IDLE
        if (!connected) return ProbeResult.OFFLINE
        val now = clock()
        if (!force && now - (lastProbeAt[key] ?: 0L) < PROBE_MIN_INTERVAL_MS) return ProbeResult.RATE_LIMITED
        lastProbeAt[key] = now
        return runCatching { chatRepository.resume(key.sessionId, key.profile) }
            .fold(
                onSuccess = { handle ->
                    probeFailures.remove(key)
                    bindLiveHandle(key, handle)
                    ProbeResult.PROBED
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    val failures = probeFailures.merge(key, 1, Int::plus) ?: 1
                    DebugLog.log("session", "probe ${key.sessionId} failed ($failures): ${error.message}")
                    val silentFor = now - runtime.lastEventAt
                    if (failures >= PROBE_FAILURES_BEFORE_GIVING_UP && silentFor > ACTIVE_RUN_HARD_CAP_MS) {
                        DebugLog.log("session", "probe ${key.sessionId}: silent ${silentFor / 60_000} min and unreachable, marking interrupted")
                        markUnconfirmed(key)
                        probeFailures.remove(key)
                        ProbeResult.GAVE_UP
                    } else ProbeResult.FAILED
                },
            )
    }

    /** Probe every active runtime; [staleOnly] restricts it to runs silent past [STALE_RUN_MS]. */
    fun probeActiveRuntimes(reason: String, staleOnly: Boolean) {
        val now = clock()
        val candidates = _runtimes.value.values.filter { runtime ->
            runtime.phase.isActive && runtime.phase != SessionRunPhase.RECONNECTING &&
                (!staleOnly || now - runtime.lastEventAt > STALE_RUN_MS)
        }
        if (candidates.isEmpty()) return
        DebugLog.log("session", "probing ${candidates.size} active run(s): $reason")
        candidates.forEach { runtime -> appScope.launch { probe(runtime.key) } }
    }

    private fun scheduleWatchdog() {
        if (!watchdogEnabled || !appInForeground) return
        if (watchdogJob?.isActive == true) return
        watchdogJob = appScope.launch {
            delay(WATCHDOG_TICK_MS)
            watchdogJob = null
            if (!appInForeground) return@launch
            probeActiveRuntimes(reason = "watchdog", staleOnly = true)
            if (_runtimes.value.values.any { it.phase.isActive }) scheduleWatchdog()
        }
    }

    /** A run silent past the hard cap whose Mac no longer answers: the outcome is unconfirmed. */
    private fun markUnconfirmed(key: SessionRuntimeKey) {
        val now = clock()
        updateRuntime(key, cause = "probe:gave-up") { runtime ->
            runtime.copy(
                chat = runtime.chat.markInterrupted(),
                phase = if (isWatched(key)) SessionRunPhase.IDLE else SessionRunPhase.INTERRUPTED,
                toolName = null,
                lastEventAt = now,
                startedLocally = false,
                lastTerminalAt = now,
                occurredAt = now,
            )
        }
    }

    private fun isWatched(key: SessionRuntimeKey): Boolean = appInForeground && key in visible

    /** Record the session title so a notification can name the task; blank titles are ignored. */
    fun setTitle(key: SessionRuntimeKey, title: String?) {
        val clean = title?.trim()?.takeIf { it.isNotBlank() } ?: return
        if (_runtimes.value[key]?.title == clean) return
        updateRuntime(key) { it.copy(title = clean) }
    }

    /** An approval was answered from the notification shade: clear the pending card locally. */
    fun clearPendingApproval(key: SessionRuntimeKey) {
        updateRuntime(key) { it.copy(chat = it.chat.copy(pendingApproval = null)) }
    }

    /**
     * A clarify answer was sent from the notification shade. Mirrors ChatViewModel.clarify: a
     * batch request locks one answer (by qid) and advances; a single question clears the request.
     */
    fun lockClarifyAnswer(key: SessionRuntimeKey, questionId: String?, answer: String) {
        updateRuntime(key) { runtime ->
            val request = runtime.chat.pendingClarify ?: return@updateRuntime runtime
            val next = if (!questionId.isNullOrBlank()) {
                request.copy(lockedAnswers = request.lockedAnswers + (questionId to answer))
                    .takeIf { it.currentQuestion != null }
            } else null
            runtime.copy(chat = runtime.chat.copy(pendingClarify = next))
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

    /**
     * Clear unread only after the chat has successfully loaded, not merely when its row is tapped.
     * Opening the chat also retires every terminal verdict — completed, interrupted, failed — since
     * the transcript now shows the outcome (docs/DESIGN.md §5.2, decision 2026-09-02).
     */
    fun markRead(key: SessionRuntimeKey) {
        val token = SessionReadStore.token(key.profile, key.sessionId)
        _unreadTokens.update { it - token }
        _runtimes.update { map ->
            val current = map[key] ?: return@update map
            if (current.phase.isTerminalVerdict) {
                map + (key to current.copy(phase = SessionRunPhase.IDLE).normalized())
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
                        com.hermes.client.ui.chat.inheritStreamFields(
                            com.hermes.client.ui.chat.alignMessageIds(messages, runtime.chat.messages),
                            runtime.chat.messages,
                            runActive = runtime.phase.isActive,
                        )
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
        val before = _runtimes.value[key] ?: return ManualHistoryResult.BUSY
        val previous = before.chat.messages
        // A run in progress no longer defers the refresh: "something looks wrong" is exactly the
        // moment the user must not be told to wait for a completion the phone may never hear
        // (HG-8). The transcript is refreshed the way a reconnect reconcile is — accepted only
        // when REST covers every locally observed turn — and the phase is left to the events.
        val active = before.phase.isActive || before.chat.isGenerating
        if (active && !messages.covers(expectationFor(before).copy(lastAssistantText = ""))) {
            return ManualHistoryResult.BUSY
        }
        updateRuntime(key, cause = "manual-refresh") { runtime ->
            runtime.copy(
                chat = runtime.chat.copy(
                    messages = com.hermes.client.ui.chat.inheritStreamFields(
                        com.hermes.client.ui.chat.inheritTimestamps(
                            com.hermes.client.ui.chat.alignMessageIds(messages, runtime.chat.messages),
                            runtime.chat.messages,
                        ),
                        runtime.chat.messages,
                        runActive = runtime.phase.isActive,
                    ),
                    historyLoading = false,
                    historyLoaded = true,
                    historyError = null,
                ),
            )
        }
        val committed = _runtimes.value[key]?.chat?.messages ?: return ManualHistoryResult.BUSY
        // Inherited stream fields (reasoning, tool results, the live tail) legitimately differ
        // from the raw REST rows; compare with them normalized out, as the reconcile does.
        fun ChatMessage.comparable() = copy(timestamp = null, id = "", thinking = "", tools = emptyList(), isStreaming = false)
        val accepted = committed.size == messages.size &&
            committed.zip(messages).all { (a, b) -> a.comparable() == b.comparable() }
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
        updateRuntime(key, cause = "prompt") { runtime ->
            runtime.copy(
                chat = runtime.chat.withUserMessage(shownText, images, files, messageId)
                    .copy(pendingAttachments = emptyList()),
                phase = SessionRunPhase.SUBMITTING,
                toolName = null,
                lastEventAt = System.currentTimeMillis(),
                startedLocally = true,
                todoDone = 0,
                todoTotal = 0,
                runStartedAt = System.currentTimeMillis(),
                occurredAt = System.currentTimeMillis(),
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

    fun updateUserDelivery(
        key: SessionRuntimeKey,
        messageId: String,
        delivery: com.hermes.client.domain.DeliveryState,
    ) {
        updateRuntime(key) { runtime ->
            runtime.copy(chat = runtime.chat.copy(messages = runtime.chat.messages.map { message ->
                if (message.id == messageId) message.copy(delivery = delivery) else message
            }))
        }
    }

    fun removeMessage(key: SessionRuntimeKey, messageId: String) {
        updateRuntime(key) { runtime ->
            runtime.copy(chat = runtime.chat.copy(messages = runtime.chat.messages.filterNot { it.id == messageId }))
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

    /**
     * The user pressed stop in this app. They already know, so the row carries no verdict: the
     * runtime goes straight to IDLE (the transcript keeps its 已中断 note). lastTerminalAt still
     * advances so the observer's own run.interrupted a moment later is folded as a replay.
     */
    fun markInterrupted(key: SessionRuntimeKey) {
        updateRuntime(key, cause = "stop") { runtime ->
            runtime.copy(
                chat = runtime.chat.markInterrupted(),
                phase = SessionRunPhase.IDLE,
                toolName = null,
                lastEventAt = System.currentTimeMillis(),
                startedLocally = false,
                lastTerminalAt = System.currentTimeMillis(),
                occurredAt = System.currentTimeMillis(),
            )
        }
    }

    fun markFailed(key: SessionRuntimeKey, state: ChatUiState) {
        updateRuntime(key, cause = "send-failed") { runtime ->
            runtime.copy(
                chat = state.copy(isGenerating = false),
                phase = if (isWatched(key)) SessionRunPhase.IDLE else SessionRunPhase.FAILED,
                toolName = null,
                lastEventAt = System.currentTimeMillis(),
                startedLocally = false,
                lastTerminalAt = System.currentTimeMillis(),
                occurredAt = System.currentTimeMillis(),
            )
        }
    }

    fun finishLocal(key: SessionRuntimeKey) {
        updateRuntime(key, cause = "finish-local") { runtime ->
            runtime.copy(
                chat = runtime.chat.copy(isGenerating = false),
                phase = SessionRunPhase.IDLE,
                toolName = null,
                lastEventAt = System.currentTimeMillis(),
                startedLocally = false,
                lastTerminalAt = System.currentTimeMillis(),
                occurredAt = System.currentTimeMillis(),
            )
        }
    }

    fun continueAfterInput(key: SessionRuntimeKey) {
        updateRuntime(key, cause = "input-answered") { runtime ->
            runtime.copy(
                phase = SessionRunPhase.THINKING,
                lastEventAt = System.currentTimeMillis(),
                startedLocally = true,
                occurredAt = System.currentTimeMillis(),
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
        aliases[event.storedSessionId] = key
        event.runtimeSessionId.takeIf { it.isNotBlank() }?.let(::replayPending)
        replayPending(event.storedSessionId)
        val now = System.currentTimeMillis()
        val occurred = parseOccurredAt(event.occurredAt) ?: now
        // Delivery latency as the phone sees it (phone clock minus the Mac's stamp). 26% of
        // completions were more than 30s late on 2026-09-05; this line makes that visible per run.
        DebugLog.log("lifecycle") { "${event.event} s=${key.sessionId} late=${(now - occurred) / 1000}s" }
        val title = event.title?.trim()?.takeIf { it.isNotBlank() }
        // The inbox replays a terminal transition the live socket already delivered (or that the
        // user already read) a few seconds later. Folding it again would resurrect an unread badge,
        // re-alert the card, or flip a finished run to "unconfirmed", so a terminal observation
        // arriving shortly after the runtime's own terminal transition is a no-op. Phone clock only:
        // the Connector stamp is the Mac's clock. A failed/interrupted runtime is NOT deduped for
        // run.completed — the observer confirming the run actually finished must win there.
        val current = _runtimes.value[key]
        val recentTerminal = current != null && !current.phase.isActive && current.lastTerminalAt > 0L &&
            now - current.lastTerminalAt <= TERMINAL_REPLAY_WINDOW_MS
        val replay = when (event.event) {
            "run.completed" -> recentTerminal &&
                (current!!.phase == SessionRunPhase.IDLE || current.phase == SessionRunPhase.COMPLETED_UNREAD)
            "run.interrupted", "run.unknown" -> recentTerminal
            else -> false
        }
        if (replay) {
            title?.let { setTitle(key, it) }
            return
        }
        updateRuntime(key, cause = "lifecycle:${event.event}") { runtime ->
            val titled = runtime.copy(title = title ?: runtime.title)
            when (event.event) {
                "run.started", "run.resumed" -> titled.copy(
                    chat = runtime.chat.copy(isGenerating = true),
                    phase = SessionRunPhase.THINKING,
                    toolName = null,
                    lastEventAt = now,
                    runStartedAt = if (runtime.phase.isActive) runtime.runStartedAt else occurred,
                    todoDone = if (runtime.phase.isActive) runtime.todoDone else 0,
                    todoTotal = if (runtime.phase.isActive) runtime.todoTotal else 0,
                    occurredAt = occurred,
                )
                "run.waiting" -> titled.copy(
                    chat = runtime.chat.copy(isGenerating = true),
                    phase = when (runtime.phase) {
                        SessionRunPhase.WAITING_APPROVAL,
                        SessionRunPhase.WAITING_CLARIFICATION -> runtime.phase
                        else -> SessionRunPhase.WAITING_ATTENTION
                    },
                    lastEventAt = now,
                    occurredAt = if (runtime.phase == SessionRunPhase.WAITING_APPROVAL ||
                        runtime.phase == SessionRunPhase.WAITING_CLARIFICATION
                    ) runtime.occurredAt else occurred,
                )
                "run.completed" -> titled.copy(
                    chat = runtime.chat.copy(isGenerating = false),
                    phase = if (isWatched(key)) SessionRunPhase.IDLE else SessionRunPhase.COMPLETED_UNREAD,
                    toolName = null,
                    lastEventAt = now,
                    startedLocally = false,
                    lastTerminalAt = now,
                    occurredAt = occurred,
                )
                "run.interrupted", "run.unknown" -> titled.copy(
                    chat = runtime.chat.markInterrupted(),
                    phase = if (isWatched(key)) SessionRunPhase.IDLE else SessionRunPhase.INTERRUPTED,
                    toolName = null,
                    lastEventAt = now,
                    startedLocally = false,
                    lastTerminalAt = now,
                    occurredAt = occurred,
                )
                else -> titled
            }
        }
        when (event.event) {
            "run.completed" -> {
                if (isWatched(key)) markRead(key) else markUnread(key)
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
            "run.interrupted", "run.unknown" -> if (!isWatched(key)) markUnread(key)
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
        cause: String = "update",
        transform: (SessionRuntime) -> SessionRuntime,
    ) {
        aliases[key.sessionId] = key
        val before = if (DebugLog.isEnabled()) _runtimes.value[key] else null
        _runtimes.update { map ->
            map + (key to transform(map[key] ?: SessionRuntime(key)).normalized())
        }
        val after = _runtimes.value[key]
        if (after?.phase?.isActive == true) scheduleWatchdog()
        if (DebugLog.isEnabled() && after != null) logTransition(key, before, after, cause)
    }

    /**
     * One diagnostic line per change of the three things a user can see — phase, isGenerating,
     * how many bubbles are streaming — with what caused it. This is the line that was missing on
     * 2026-09-05: the whole HG-6/7/8 reconstruction would have been a grep. Costs nothing unless
     * diagnostics are on; never fires for a delta that changes only text.
     */
    private fun logTransition(key: SessionRuntimeKey, before: SessionRuntime?, after: SessionRuntime, cause: String) {
        val streamingAfter = after.chat.messages.count { it.role == Role.ASSISTANT && it.isStreaming }
        val streamingBefore = before?.chat?.messages?.count { it.role == Role.ASSISTANT && it.isStreaming } ?: 0
        if (before?.phase == after.phase && before.chat.isGenerating == after.chat.isGenerating &&
            streamingBefore == streamingAfter
        ) return
        DebugLog.log("phase") {
            "s=${key.sessionId} ${before?.phase ?: "-"}→${after.phase} gen=${after.chat.isGenerating} " +
                "streaming=$streamingAfter cause=$cause"
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
            if (replaying.get() == true) {
                DebugLog.log("event", "dropped ${event.type} session=$id: still unmatched after replay")
            } else {
                bufferUnmatched(id, event)
                DebugLog.log("event", "buffered ${event.type} session=$id until its session is known")
            }
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

    private fun bufferUnmatched(id: String, event: ServerEvent) {
        val now = clock()
        val queue = pendingEvents.getOrPut(id) { ArrayDeque() }
        synchronized(queue) {
            while (queue.isNotEmpty() && now - queue.first().first > PENDING_EVENT_TTL_MS) queue.removeFirst()
            if (queue.size >= PENDING_EVENT_CAP) queue.removeFirst()
            queue.addLast(now to event)
        }
    }

    /** Apply, in arrival order, whatever was held for [id]; anything older than the TTL is gone. */
    private fun replayPending(id: String) {
        val queue = pendingEvents.remove(id) ?: return
        val now = clock()
        val due = synchronized(queue) { queue.filter { now - it.first <= PENDING_EVENT_TTL_MS }.map { it.second } }
        if (due.isEmpty()) return
        DebugLog.log("event", "replaying ${due.size} buffered event(s) for session=$id")
        replaying.set(true)
        try {
            due.forEach { event ->
                try {
                    applyEvent(event)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    DebugLog.log("event", "replay failed ${event.type} session=$id: ${error.message}")
                }
            }
        } finally {
            replaying.set(false)
        }
    }

    private fun applyEvent(event: ServerEvent) {
        val key = resolve(event) ?: return
        if (event.type == "message.start") lastActiveKey = key
        updateRuntime(key, cause = "event:${event.type}") { runtime ->
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
                "message.complete" -> if (isWatched(key)) SessionRunPhase.IDLE else SessionRunPhase.COMPLETED_UNREAD
                "error" -> SessionRunPhase.FAILED
                "session.info" -> when (event.bool("running")) {
                    true -> if (runtime.phase.isActive) runtime.phase else SessionRunPhase.THINKING
                    false -> if (isWatched(key)) SessionRunPhase.IDLE else SessionRunPhase.COMPLETED_UNREAD
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
            val now = System.currentTimeMillis()
            val terminal = event.type == "message.complete" || event.type == "error" ||
                (event.type == "session.info" && event.bool("running") == false)
            val starting = !runtime.phase.isActive && (
                event.type == "message.start" ||
                    (event.type == "session.info" && event.bool("running") == true)
                )
            val phaseChanged = nextPhase != runtime.phase
            val todo = if (event.type == "tool.complete" && event.str("name") == "todo") {
                event.todoCounts()
            } else null
            runtime.copy(
                chat = finalChat,
                phase = nextPhase,
                toolName = when (event.type) {
                    "tool.start" -> event.str("name")?.ifBlank { null }
                    "tool.complete", "message.complete", "error" -> null
                    else -> runtime.toolName
                },
                lastEventAt = now,
                // Sticky: only an authoritative "this session is no longer running" clears the
                // flag. A run can emit message.complete (or a recoverable error) and keep working
                // — background processes still running, another message to follow — and dropping
                // the flag there used to hand the session back to the idle-background policy,
                // which closed the socket mid-run. Terminal transitions the app performs itself
                // (finishLocal / markFailed / markInterrupted) and observed run.completed /
                // run.interrupted still clear it.
                startedLocally = when (event.type) {
                    "session.info" -> if (event.bool("running") == false) false else runtime.startedLocally
                    else -> runtime.startedLocally
                },
                runStartedAt = if (starting) now else runtime.runStartedAt,
                todoDone = when {
                    starting -> 0
                    todo != null -> todo.first
                    else -> runtime.todoDone
                },
                todoTotal = when {
                    starting -> 0
                    todo != null -> todo.second
                    else -> runtime.todoTotal
                },
                lastTerminalAt = if (terminal) now else runtime.lastTerminalAt,
                occurredAt = if (phaseChanged || terminal || starting) now else runtime.occurredAt,
            )
        }
        if (event.type in setOf("tool.complete", "message.complete", "agent.terminal.output")) {
            scheduleProcessPolling(key, PROCESS_DISCOVERY_GRACE_POLLS)
        }
        if (event.type == "message.complete" || (event.type == "session.info" && event.bool("running") == false)) {
            if (isWatched(key)) markRead(key) else markUnread(key)
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
                    if (accepted) {
                        if (mediaRepository != null) {
                            val committed = _runtimes.value[key]?.chat?.messages.orEmpty()
                            val hydrated = mediaRepository.hydrateMessages(committed, key.profile)
                            acceptHydratedImages(key, hydrated)
                        }
                        // The ladder exists to wait out a turn Hermes has not committed yet. Once
                        // an authoritative snapshot is accepted there is nothing left to wait for,
                        // and every further rung re-downloads the WHOLE transcript: four passes
                        // over a 0.5 MB conversation, on every reconnect, is what turned a routine
                        // socket blip into minutes of stalled traffic (measured 2026-09-03).
                        break
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
        DebugLog.log("history") {
            val current = _runtimes.value[key]?.let(::expectationFor)
            val reason = when {
                current == null -> null
                current.userTurns > expectation.userTurns ||
                    (expectation.lastUserText.isNotBlank() && current.lastUserText != expectation.lastUserText) ->
                    "a newer prompt started"
                else -> messages.coverageGap(expectation)
            }
            if (reason == null) "reconcile s=${key.sessionId}: ${messages.size} rows cover the local turns"
            else "reconcile s=${key.sessionId} rejected: $reason"
        }
        updateRuntime(key, cause = "reconcile") { runtime ->
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
                    messages = com.hermes.client.ui.chat.inheritStreamFields(
                        com.hermes.client.ui.chat.inheritTimestamps(
                            com.hermes.client.ui.chat.alignMessageIds(messages, runtime.chat.messages),
                            runtime.chat.messages,
                        ),
                        runtime.chat.messages,
                        runActive = runtime.phase.isActive,
                    ),
                    historyLoading = false,
                    historyLoaded = true,
                    historyError = null,
                ),
            )
        }
        // StateFlow.update may retry its transform under contention, so keep the transform free of
        // side effects and derive acceptance from the committed snapshot afterward.
        // Timestamp inheritance, id alignment and stream-field inheritance all mutate the committed
        // list relative to the raw REST result, so acceptance compares content with stamps, ids,
        // reasoning, tools and streaming state normalized out. Comparing the inherited fields would
        // never match, and a reconcile that never "accepts" re-downloads the whole transcript on
        // every rung of the ladder (the 2026-09-03 fetch storm).
        val committed = _runtimes.value[key]?.chat?.messages ?: return false
        fun ChatMessage.comparable() = copy(
            timestamp = null, id = "", thinking = "", tools = emptyList(), isStreaming = false,
        )
        return committed.size == messages.size &&
            committed.zip(messages).all { (a, b) -> a.comparable() == b.comparable() }
    }

    private fun List<ChatMessage>.covers(expectation: HistoryExpectation): Boolean = coverageGap(expectation) == null

    /** Null when the snapshot covers every locally observed turn; otherwise why it does not. */
    private fun List<ChatMessage>.coverageGap(expectation: HistoryExpectation): String? {
        val users = filter { it.role == Role.USER }
        val assistants = filter { it.role == Role.ASSISTANT }
        if (users.size < expectation.userTurns) return "userTurns ${users.size}<${expectation.userTurns}"
        if (assistants.size < expectation.assistantTurns) return "assistantTurns ${assistants.size}<${expectation.assistantTurns}"
        if (expectation.lastUserText.isNotBlank() && users.lastOrNull()?.text.orEmpty().matchText() != expectation.lastUserText) {
            return "last user turn differs"
        }
        if (expectation.lastAssistantText.isBlank()) return null
        val persisted = assistants.lastOrNull()?.text.orEmpty().matchText()
        if (persisted == expectation.lastAssistantText || persisted.contains(expectation.lastAssistantText)) return null
        return "last assistant text not yet persisted"
    }

    private fun String.matchText(): String = trim().replace(Regex("\\s+"), " ")

    /** Relay lifecycle events stamp ISO-8601 `occurredAt`; a malformed stamp falls back to now. */
    private fun parseOccurredAt(value: String?): Long? {
        val text = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { java.time.Instant.parse(text).toEpochMilli() }.getOrNull()
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
                    updateRuntime(runtime.key, cause = "reconnect") { it.copy(phase = it.restoredPhaseAfterReconnect()) }
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
        /**
         * How long after a local terminal transition an inbox terminal event counts as the same
         * one. The inbox lags the live socket by the 2–3 s poll (plus the 45 s socket grace when
         * going idle), so two minutes covers a replay without swallowing a later run.
         */
        const val TERMINAL_REPLAY_WINDOW_MS = 2 * 60_000L
        const val PROCESS_DISCOVERY_GRACE_POLLS = 4
        const val PROCESS_OUTPUT_TAIL_CHARS = 4_000
        const val DEFAULT_PROFILE = "default"
        val HISTORY_RECONCILE_DELAYS_MS = longArrayOf(250L, 1_000L, 3_000L, 10_000L)
        val FOREGROUND_RECOVERY_DELAYS_MS = longArrayOf(0L, 250L, 750L, 1_500L)
        /** Keep recent idle histories warm without retaining every session opened in this process. */
        const val MAX_CACHED_IDLE_RUNTIMES = 20
        /** How long an event for a not-yet-aliased session waits for its alias before it is dropped. */
        const val PENDING_EVENT_TTL_MS = 60_000L
        const val PENDING_EVENT_CAP = 200
        /** A foreground run this long without any event is asked about by the watchdog. */
        const val STALE_RUN_MS = 3 * 60_000L
        const val WATCHDOG_TICK_MS = 60_000L
        /** One probe per run per minute, however many triggers fire. */
        const val PROBE_MIN_INTERVAL_MS = 60_000L
        /** Silent this long AND unreachable twice: the outcome is unconfirmed, the row stops spinning. */
        const val ACTIVE_RUN_HARD_CAP_MS = 30 * 60_000L
        const val PROBE_FAILURES_BEFORE_GIVING_UP = 2
    }
}
