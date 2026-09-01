package com.hermes.client.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.HermesApiException
import com.hermes.client.data.network.GatewayRpcException
import com.hermes.client.data.network.ProfileDto
import com.hermes.client.data.network.str
import com.hermes.client.data.progress.SessionRuntimeKey
import com.hermes.client.data.progress.SessionRuntimeStore
import com.hermes.client.data.progress.ManualHistoryResult
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.ChatMediaRepository
import com.hermes.client.data.repository.ChatFileRepository
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.ProfileRepository
import com.hermes.client.data.repository.SessionRepository
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.di.DefaultDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.hermes.client.ui.localization.LocalizedText
import com.hermes.client.ui.localization.localizedText
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.localized

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chat: ChatRepository,
    private val sessions: SessionRepository,
    private val catalogStore: com.hermes.client.data.repository.ModelCatalogStore,
    private val reasoningPresets: com.hermes.client.data.repository.ReasoningPresetStore,
    private val profileRepo: ProfileRepository,
    private val profileManager: ProfileManager,
    private val favoritesStore: com.hermes.client.data.repository.ModelFavoritesStore,
    private val pendingShareStore: com.hermes.client.share.PendingShareStore,
    private val tts: com.hermes.client.data.tts.TextToSpeechController,
    private val promptStore: com.hermes.client.data.repository.PromptStore,
    private val configRepo: com.hermes.client.data.repository.ConfigRepository,
    private val runtimeStore: SessionRuntimeStore,
    private val mediaRepository: ChatMediaRepository,
    private val fileRepository: ChatFileRepository,
    @param:DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {

    enum class ConversationRefreshEvent { QUEUED, SUCCEEDED_CHANGED, SUCCEEDED_UNCHANGED, FAILED }

    private companion object {
        const val LIVE_HANDLE_TIMEOUT_MS = 25_000L
        const val STALE_SESSION_CODE = 4001
    }

    private val _state = MutableStateFlow(ChatUiState.empty())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private val _refreshEvents = MutableSharedFlow<ConversationRefreshEvent>(extraBufferCapacity = 4)
    val refreshEvents: SharedFlow<ConversationRefreshEvent> = _refreshEvents

    private val _sessionTitle = MutableStateFlow("新会话")
    val sessionTitle: StateFlow<String> = _sessionTitle.asStateFlow()
    private var appLanguage: AppLanguage = AppLanguage.ZH

    fun setAppLanguage(language: AppLanguage) {
        val oldNew = localized(appLanguage, "新会话", "New session")
        val oldChat = localized(appLanguage, "会话", "Chat")
        appLanguage = language
        _sessionTitle.value = when (_sessionTitle.value) {
            oldNew -> localized(language, "新会话", "New session")
            oldChat -> localized(language, "会话", "Chat")
            else -> _sessionTitle.value
        }
    }

    val connectionState: StateFlow<ConnectionState> = chat.connectionState

    // I1: expose 401 unauthorized so the nav layer can route back to Setup
    private val _unauthorized = MutableStateFlow(false)
    val unauthorized: StateFlow<Boolean> = _unauthorized.asStateFlow()

    // The model this session is confirmed to be using. Null until a switch succeeds (the gateway
    // doesn't report the session's current model up-front), so the picker shows "Model" until the
    // user changes it, then the chosen model as confirmation the switch took.
    private val _currentModel = MutableStateFlow<String?>(null)
    val currentModel: StateFlow<String?> = _currentModel.asStateFlow()

    // Provider list for the model sheet — served from the process-wide catalog store, which is
    // refreshed in the background on app start/foreground so the sheet opens instantly.
    val providers: kotlinx.coroutines.flow.StateFlow<List<com.hermes.client.data.network.ModelProviderDto>> =
        catalogStore.state.map { it.providers }
            .stateIn(
                viewModelScope,
                kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
                catalogStore.state.value.providers,
            )

    // Provider of the confirmed session model (set together with _currentModel on a successful switch).
    private val _currentProvider = MutableStateFlow<String?>(null)
    val currentProvider: kotlinx.coroutines.flow.StateFlow<String?> = _currentProvider.asStateFlow()

    // The profile's configured default model/provider (from config + the provider marked current).
    // Read on open so the UI can distinguish "following the default" from a session override.
    private val _defaultModel = MutableStateFlow<String?>(null)
    val defaultModel: StateFlow<String?> = _defaultModel.asStateFlow()
    private val _defaultProvider = MutableStateFlow<String?>(null)
    val defaultProvider: StateFlow<String?> = _defaultProvider.asStateFlow()

    // Set the moment a SESSION-scope switch succeeds; comparison alone can't tell an override
    // to the same model as the default apart from following it.
    private val _explicitSessionOverride = MutableStateFlow(false)

    /** True when this chat runs a session override instead of the profile default. */
    val sessionModelOverridden: StateFlow<Boolean> =
        kotlinx.coroutines.flow.combine(_currentModel, _defaultModel, _explicitSessionOverride) { cur, def, explicit ->
            explicit || (cur != null && def != null && cur != def)
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), false)

    // Text handed off from a share (Share-to-Hermes). ChatScreen pre-fills the composer with it once.
    private val _initialDraft = MutableStateFlow<String?>(null)
    val initialDraft: StateFlow<String?> = _initialDraft.asStateFlow()
    fun clearInitialDraft() { _initialDraft.value = null }

    val favorites: kotlinx.coroutines.flow.StateFlow<Set<String>> =
        favoritesStore.favorites.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptySet())

    /** True while a response is being read aloud. */
    val speaking: kotlinx.coroutines.flow.StateFlow<Boolean> = tts.speaking

    /** Read [text] aloud (markdown stripped for cleaner speech). */
    fun readAloud(text: String) = tts.speak(speechText(text))

    /** Stop any current read-aloud. */
    fun stopReading() = tts.stop()

    fun fetchFile(file: com.hermes.client.domain.ChatFile, onResult: (Result<java.io.File>) -> Unit) {
        viewModelScope.launch {
            val result = runCatching { fileRepository.download(file) }
            onResult(result)
        }
    }

    fun saveImageToGallery(
        image: com.hermes.client.domain.ChatImage,
        onResult: (Result<com.hermes.client.data.repository.SavedChatImage>) -> Unit,
    ) {
        viewModelScope.launch { onResult(runCatching { mediaRepository.saveToGallery(image) }) }
    }

    fun saveImageToUri(
        image: com.hermes.client.domain.ChatImage,
        destination: android.net.Uri,
        onResult: (Result<Unit>) -> Unit,
    ) {
        viewModelScope.launch { onResult(runCatching { mediaRepository.copyToUri(image, destination) }) }
    }

    fun prepareImageForShare(
        image: com.hermes.client.domain.ChatImage,
        onResult: (Result<java.io.File>) -> Unit,
    ) {
        viewModelScope.launch { onResult(runCatching { mediaRepository.requireLocalImage(image) }) }
    }

    fun imageExportName(image: com.hermes.client.domain.ChatImage): String =
        mediaRepository.exportDisplayName(image)

    fun imageExportMimeType(image: com.hermes.client.domain.ChatImage): String =
        mediaRepository.exportMimeType(image)

    /** Device-local saved prompts, for the composer's prompt picker. */
    val savedPrompts: kotlinx.coroutines.flow.StateFlow<List<com.hermes.client.data.repository.SavedPrompt>> =
        promptStore.prompts.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptyList())

    data class ModelSheetUi(
        val query: String = "",
        // favKey of the row whose selection is in flight; non-null disables the list (no double
        // submits) and shows the spinner on that row.
        val pendingKey: String? = null,
        val error: com.hermes.client.data.error.AppError? = null,
    )
    // Model-LIST loading/error states (the sheet's pending/error covers selection, not the
    // list). Loading shows only while the cache is genuinely empty; a background refresh that
    // fails with a cached list stays silent. "Loaded but empty" still counts as an error so a
    // gateway that returns no providers doesn't render a silent empty shell.
    val providersLoading: kotlinx.coroutines.flow.StateFlow<Boolean> =
        catalogStore.state.map { it.refreshing && it.providers.isEmpty() }
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), false)
    val providersError: kotlinx.coroutines.flow.StateFlow<Boolean> =
        catalogStore.state.map { it.providers.isEmpty() && !it.refreshing && (it.failed || it.loaded) }
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Ask the process-wide catalog store for the provider list. Normally the store is already
     * warm (app-start background refresh); this remains as the sheet-open safety net and the
     * explicit-retry path.
     */
    fun ensureProviders(force: Boolean = false) {
        catalogStore.refresh(force = force)
    }

    /**
     * Old session metadata can carry a model without its provider, and the default provider is
     * only knowable from the catalog — recompute both whenever the catalog lands so the sheet
     * can mark the current row (P0: unreliable "current" highlight).
     */
    private fun backfillProvidersFromCatalog() {
        val catalog = catalogStore.state.value.providers
        if (_defaultProvider.value.isNullOrBlank()) {
            _defaultProvider.value = com.hermes.client.ui.models.resolveModelProvider(
                catalog, null, _defaultModel.value,
            ) ?: catalog.firstOrNull { it.isCurrent }?.slug
        }
        if (_currentProvider.value.isNullOrBlank()) {
            _currentProvider.value = com.hermes.client.ui.models.resolveModelProvider(
                catalog, null, _currentModel.value,
            )
        }
    }

    init {
        // The catalog can land after open() (background refresh) — re-run the provider backfill
        // whenever it updates so the sheet can mark the current row/group.
        viewModelScope.launch {
            catalogStore.state.map { it.providers }.distinctUntilChanged()
                .collect { if (it.isNotEmpty()) backfillProvidersFromCatalog() }
        }
        // A manual refresh requested mid-stream waits for the authoritative reply to finish. This
        // collector owns that one deferred request so repeated taps cannot start competing REST
        // swaps or overwrite deltas that have not reached history yet.
        viewModelScope.launch {
            _state.map { it.isGenerating }.distinctUntilChanged().collect { generating ->
                if (!generating && manualRefreshQueued) {
                    manualRefreshQueued = false
                    startManualRefresh()
                }
            }
        }
    }

    private val _modelSheet = MutableStateFlow(ModelSheetUi())
    val modelSheet: kotlinx.coroutines.flow.StateFlow<ModelSheetUi> = _modelSheet.asStateFlow()

    private val _profiles = MutableStateFlow<List<ProfileDto>>(emptyList())
    val profiles: StateFlow<List<ProfileDto>> = _profiles.asStateFlow()

    /** Active profile name — shown in the chat top bar so the user knows which tenant they're in. */
    val activeProfile: StateFlow<String?> = profileManager.active

    /** Slash-command catalog (name to description) for the composer palette. */
    private val _commands = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val commands: StateFlow<List<Pair<String, String>>> = _commands.asStateFlow()

    /** "@" mention completions for the current @-word in the composer. */
    private val _pathItems = MutableStateFlow<List<com.hermes.client.data.repository.PathItem>>(emptyList())
    val pathItems: StateFlow<List<com.hermes.client.data.repository.PathItem>> = _pathItems.asStateFlow()

    private var sessionId: String = ""
    private var storedSessionId: String = ""
    private var collectJob: Job? = null
    private var titleJob: Job? = null
    private var resumeJob: Job? = null
    private var sendJob: Job? = null
    private var refreshJob: Job? = null
    private var manualRefreshQueued = false
    private var runtimeKey: SessionRuntimeKey? = null
    private var currentProfile: String? = null
    private var liveHandleGate = CompletableDeferred<String>()
    private val resumeMutex = Mutex()

    /** Reconciles this visible transcript and live handle before the warm-start overlay exits. */
    suspend fun recoverForForeground(): Boolean {
        val id = storedSessionId.takeIf { it.isNotBlank() } ?: return false
        val key = runtimeKey ?: SessionRuntimeKey(currentProfile, id)
        return runtimeStore.recoverVisibleSession(key)
    }

    fun open(
        id: String,
        requestedProfile: String? = null,
        initialTitle: String? = null,
        isNewSession: Boolean = false,
        language: AppLanguage = AppLanguage.ZH,
    ) {
        setAppLanguage(language)
        // Configuration changes recreate the composition and re-run its LaunchedEffect, while the
        // navigation-scoped ViewModel and runtime remain alive. Reopening the same key used to mark
        // history loading, restart REST/resume, and show the skeleton for a layout-only change.
        // Treat open as idempotent for the already active stored session; an explicit profile or
        // session change still follows the complete path below.
        val requested = requestedProfile?.ifBlank { null }
        val profile = requested ?: if (storedSessionId == id) currentProfile else profileManager.active.value
        val existingKey = runtimeKey
        if (storedSessionId == id && existingKey == SessionRuntimeKey(profile, id) && collectJob?.isActive == true) {
            runtimeStore.setVisible(existingKey, true)
            if (!initialTitle.isNullOrBlank()) {
                val fallback = if (isNewSession) localized(language, "新会话", "New session")
                else localized(language, "会话", "Chat")
                _sessionTitle.value = displaySessionTitle(initialTitle, fallback)
            }
            com.hermes.client.data.diagnostics.DebugLog.log("session", "reuse($id)")
            return
        }
        refreshJob?.cancel()
        _refreshing.value = false
        manualRefreshQueued = false
        sendJob?.cancel()
        resumeJob?.cancel()
        liveHandleGate.completeExceptionally(CancellationException("session changed"))
        liveHandleGate = CompletableDeferred()
        runtimeKey?.let { runtimeStore.setVisible(it, false) }
        sessionId = id
        storedSessionId = id
        currentProfile = profile
        val key = runtimeStore.register(id, profile)
        runtimeKey = key
        runtimeStore.setVisible(key, true)
        val cachedMeta = sessions.cachedSession(id, profile)
        val fallbackTitle = if (isNewSession) localized(language, "新会话", "New session") else localized(language, "会话", "Chat")
        _sessionTitle.value = when {
            !initialTitle.isNullOrBlank() -> displaySessionTitle(initialTitle, fallbackTitle)
            cachedMeta != null -> displaySessionTitle(cachedMeta.title, fallbackTitle)
            else -> fallbackTitle
        }
        _currentModel.value = cachedMeta?.model?.ifBlank { null }
        _currentProvider.value = cachedMeta?.provider?.ifBlank { null }
        _explicitSessionOverride.value = false
        _reasoningEffort.value = null
        val cachedHistory = sessions.cachedHistory(id, profile)?.map { it.organizedForDisplay() }
        runtimeStore.markHistoryLoading(key, cachedHistory)
        collectJob?.cancel()
        collectJob = viewModelScope.launch {
            runtimeStore.runtimes
                .map { it[key] }
                .filterNotNull()
                .collect { runtime ->
                    _state.value = runtime.chat
                    runtime.liveHandle?.takeIf { it.isNotBlank() }?.let { sessionId = it }
                }
        }
        // A share created this session and stashed its text; surface it as the initial composer draft.
        val ps = pendingShareStore.take(id)
        ps?.text?.let { _initialDraft.value = it }
        com.hermes.client.data.diagnostics.DebugLog.log("session", "open($id)")
        // Load the server-authoritative title/model separately from history so neither request
        // blocks the other. A new zero-message session may not have metadata yet; it stays
        // "新会话" until Hermes emits session.title after the first prompt.
        viewModelScope.launch {
            val meta = runCatching {
                sessions.list(profile).firstOrNull { it.id == id }
            }.getOrNull()
            if (storedSessionId == id && meta != null) {
                _sessionTitle.value = displaySessionTitle(meta.title, fallbackTitle)
                _currentModel.value = meta.model?.ifBlank { null }
                _currentProvider.value = meta.provider?.ifBlank { null }
            }
        }
        viewModelScope.launch {
            val requestStartedAt = System.currentTimeMillis()
            try {
                val rawHistory = sessions.history(id, profile)
                val organizedHistory = kotlinx.coroutines.withContext(defaultDispatcher) {
                    rawHistory.map { it.organizedForDisplay() }
                }
                com.hermes.client.data.diagnostics.DebugLog.log("session", "history($id) → ${organizedHistory.size} messages")
                runtimeStore.acceptHistory(key, organizedHistory, requestStartedAt)
                runtimeStore.markRead(key)
                // Do not hold the transcript behind image downloads. Show text and placeholders
                // immediately, then merge cached/downloaded thumbnails by stable history id.
                launch {
                    val hydrated = mediaRepository.hydrateMessages(organizedHistory, profile)
                    runtimeStore.acceptHydratedImages(key, hydrated)
                }
            } catch (e: HermesApiException) {
                com.hermes.client.data.diagnostics.DebugLog.log("error", "history($id) failed: ${e.code} ${e.message}")
                if (e.code == 401) { _unauthorized.value = true; return@launch }
                runtimeStore.historyFailed(
                    key,
                    localized(appLanguage, "无法加载历史消息（HR-RPC-001）", "Couldn't load message history (HR-RPC-001)"),
                )
            } catch (e: Exception) {
                // Keep a cached/live transcript visible if history refresh fails.
                com.hermes.client.data.diagnostics.DebugLog.log("error", "history($id) failed: ${e.message}")
                runtimeStore.historyFailed(
                    key,
                    localized(appLanguage, "无法加载历史消息（HR-RPC-001）", "Couldn't load message history (HR-RPC-001)"),
                )
            }
            // A share may have handed off an image; stage it so it shows as a chip and is
            // flushed to the gateway on the next send (rather than attaching immediately).
            ps?.let { share ->
                val imgB64 = share.imageBase64
                val imgMime = share.imageMime
                if (imgB64 != null && imgMime != null) {
                    // java.util.Base64 (not android.util.Base64): the latter is stubbed to a
                    // no-op returning null in the JVM unit-test environment (no Robolectric),
                    // which would silently drop every shared image under test. java.util.Base64
                    // is a real JDK class (available since API 26, our minSdk) so it decodes
                    // correctly both on-device and under test.
                    runCatching { java.util.Base64.getDecoder().decode(imgB64) }
                        .onSuccess { bytes -> stageAttachment(bytes, imgMime, share.attachmentName ?: "attachment") }
                        .onFailure { e ->
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            appendError(localizedText("附件处理失败（HR-FILE-001）", "Attachment failed (HR-FILE-001)"))
                        }
                }
            }
            // Load model options, profiles, and the slash-command catalog; failures are non-fatal
            launch {
                // Safety net only: the catalog store is normally warm from the app-start refresh.
                catalogStore.refresh(force = false)
                // The configured default is read on every open: the chip and sheet need it to
                // tell "following the default" from a session override, and a brand-new session
                // (no model in its metadata yet) falls back to it so the chip names the real
                // model instead of a placeholder.
                runCatching {
                    val cfg = configRepo.get(profile)
                    val defaultModel = (cfg["model"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.ifBlank { null }
                    if (storedSessionId == id) {
                        _defaultModel.value = defaultModel
                        _defaultProvider.value = null
                        if (_currentModel.value.isNullOrBlank() && defaultModel != null) {
                            _currentModel.value = defaultModel
                        }
                        backfillProvidersFromCatalog()
                    }
                }
            }
            launch { runCatching { _profiles.value = profileRepo.list() } }
            launch { runCatching { _commands.value = chat.commandsCatalog() } }
            launch { refreshReasoning() }
        }
        // Resume is independent from REST history. The composer can be used immediately, but any
        // send awaits this gate so a stored database id is never submitted as a live runtime id.
        val gateForOpen = liveHandleGate
        resumeJob = viewModelScope.launch {
            try {
                val handle = recoverLiveHandle(id, profile, key)
                if (storedSessionId == id && liveHandleGate === gateForOpen) {
                    gateForOpen.complete(handle)
                }
            } catch (cancelled: CancellationException) {
                gateForOpen.cancel(cancelled)
                throw cancelled
            } catch (error: Exception) {
                com.hermes.client.data.diagnostics.DebugLog.log(
                    "session", "resume($id) failed: ${error.message}",
                )
                gateForOpen.completeExceptionally(error)
            }
        }
        titleJob?.cancel()
        titleJob = viewModelScope.launch {
            chat.events.filter {
                it.sessionId == null || it.sessionId == sessionId || it.sessionId == storedSessionId
            }
                .onEach { event ->
                    if (event.type == "session.title") {
                        val eventBelongsHere = event.sessionId == sessionId || event.sessionId == storedSessionId
                        if (eventBelongsHere) {
                            event.str("title")?.let { _sessionTitle.value = displaySessionTitle(it, fallbackTitle) }
                        } else if (event.sessionId == null) {
                            // Some gateway versions omit session_id on title events. Never apply that
                            // unscoped title directly: re-read this session's own metadata instead.
                            val meta = runCatching {
                                sessions.list(profile).firstOrNull { it.id == storedSessionId }
                            }.getOrNull()
                            if (this@ChatViewModel.storedSessionId == id && meta != null) {
                                _sessionTitle.value = displaySessionTitle(meta.title, fallbackTitle)
                            }
                        }
                    }
                }
                .collect {}
        }
    }

    /** Force-sync only the current conversation without reopening its runtime or clearing UI. */
    fun refreshCurrentConversation() {
        if (_refreshing.value) return
        if (_state.value.isGenerating) {
            if (!manualRefreshQueued) {
                manualRefreshQueued = true
                _refreshEvents.tryEmit(ConversationRefreshEvent.QUEUED)
            }
            return
        }
        startManualRefresh()
    }

    private fun startManualRefresh() {
        if (_refreshing.value || refreshJob?.isActive == true) return
        val key = runtimeKey
        val id = storedSessionId
        val profile = currentProfile
        if (key == null || id.isBlank()) {
            _refreshEvents.tryEmit(ConversationRefreshEvent.FAILED)
            return
        }
        refreshJob = viewModelScope.launch {
            _refreshing.value = true
            try {
                val rawHistory = sessions.history(id, profile)
                val organizedHistory = withContext(defaultDispatcher) {
                    rawHistory.map { it.organizedForDisplay() }
                }
                // Navigation may have moved to another session while the request was in flight.
                if (runtimeKey != key || storedSessionId != id) return@launch
                val result = if (_state.value.isGenerating) {
                    ManualHistoryResult.BUSY
                } else {
                    runtimeStore.acceptManualHistory(key, organizedHistory)
                }
                if (result == ManualHistoryResult.BUSY) {
                    manualRefreshQueued = true
                    _refreshEvents.emit(ConversationRefreshEvent.QUEUED)
                    return@launch
                }
                runtimeStore.markRead(key)
                // Publish the committed runtime before the success event. The normal runtime
                // collector will observe the same value, but relying on collector scheduling here
                // could start viewport restoration against the pre-refresh composition.
                runtimeStore.runtimes.value[key]?.chat?.let { _state.value = it }
                // Stable-id rows update in place. Only changed geometry needs a viewport restore;
                // byte-for-byte identical history must not remount/reparse the transcript.
                _refreshEvents.emit(
                    if (result == ManualHistoryResult.CHANGED) {
                        ConversationRefreshEvent.SUCCEEDED_CHANGED
                    } else {
                        ConversationRefreshEvent.SUCCEEDED_UNCHANGED
                    },
                )
                launch {
                    val metadata = runCatching {
                        sessions.list(profile).firstOrNull { it.id == id }
                    }.getOrNull()
                    if (runtimeKey == key && metadata != null) {
                        val fallback = localized(appLanguage, "会话", "Chat")
                        _sessionTitle.value = displaySessionTitle(metadata.title, fallback)
                        _currentModel.value = metadata.model?.ifBlank { null }
                        _currentProvider.value = metadata.provider?.ifBlank { null }
                    }
                }
                // Text is authoritative immediately; media hydration may finish just after the
                // success affordance and merges by stable message identity without blanking rows.
                launch {
                    val hydrated = mediaRepository.hydrateMessages(organizedHistory, profile)
                    if (runtimeKey == key) runtimeStore.acceptHydratedImages(key, hydrated)
                }
                com.hermes.client.data.diagnostics.DebugLog.log(
                    "session", "manual-refresh($id) → ${organizedHistory.size} messages",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: HermesApiException) {
                if (error.code == 401) _unauthorized.value = true
                com.hermes.client.data.diagnostics.DebugLog.log(
                    "error", "manual-refresh($id) failed: ${error.code} ${error.message}",
                )
                _refreshEvents.emit(ConversationRefreshEvent.FAILED)
            } catch (error: Exception) {
                com.hermes.client.data.diagnostics.DebugLog.log(
                    "error", "manual-refresh($id) failed: ${error.message}",
                )
                _refreshEvents.emit(ConversationRefreshEvent.FAILED)
            } finally {
                _refreshing.value = false
            }
        }
    }

    private fun mutateState(block: (ChatUiState) -> ChatUiState) {
        val next = block(_state.value)
        _state.value = next
        runtimeKey?.let { key -> runtimeStore.updateChat(key) { next } }
    }

    fun stageAttachment(bytes: ByteArray, mimeType: String, name: String = "attachment") {
        // Generate the id outside update{}: staging is called from background (IO) threads, and the
        // update lambda can re-run under CAS contention — a shared counter would race/collide. UUID
        // is collision-free across threads.
        val id = "att-${java.util.UUID.randomUUID()}"
        require(bytes.size <= MAX_DIRECT_ATTACHMENT_BYTES) { "Attachment exceeds 6 MB" }
        mutateState { it.withAttachment(PendingAttachment(id, bytes, mimeType, name)) }
    }
    fun removeAttachment(id: String) { mutateState { it.withoutAttachment(id) } }

    /** Create a fresh chat in the currently active profile for the top-bar + action. */
    suspend fun createNewSession(): String? =
        runCatching { chat.createSession(profileManager.active.value) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .getOrNull()

    fun send(text: String) {
        val atts = _state.value.pendingAttachments
        if (text.isBlank() && atts.isEmpty()) return
        val isSlash = text.trimStart().startsWith("/")
        val messageId = "u-${java.util.UUID.randomUUID()}"
        val expectedStoredId = storedSessionId
        val expectedProfile = currentProfile
        val gateForSend = liveHandleGate
        // Clear the staging strip immediately. The cached thumbnails are attached to the sent turn
        // on an IO dispatcher below, so large images never block the Compose main thread.
        mutateState { it.copy(pendingAttachments = emptyList()) }
        sendJob = viewModelScope.launch {
            try {
                val outgoingImages = atts.filter { it.kind == AttachmentKind.IMAGE }.map { a ->
                    mediaRepository.cacheOutgoing(a.id, a.bytes, a.mimeType)
                }
                val outgoingFiles = atts.filter { it.kind != AttachmentKind.IMAGE }.map { a ->
                    com.hermes.client.domain.ChatFile(
                        id = a.id,
                        name = a.name,
                        mimeType = a.mimeType,
                        sizeBytes = a.sizeBytes,
                        state = com.hermes.client.domain.FileTransferState.UPLOADING,
                    )
                }
                runtimeKey?.let { runtimeStore.beginPrompt(it, text, outgoingImages, outgoingFiles, messageId) }
                    ?: mutateState { it.withUserMessage(text, outgoingImages, outgoingFiles, messageId) }
                com.hermes.client.data.diagnostics.DebugLog.log(
                    "session", "send($expectedStoredId) waiting for live handle",
                )
                val initialHandle = try {
                    withTimeout(LIVE_HANDLE_TIMEOUT_MS) { gateForSend.await() }
                } catch (timeout: TimeoutCancellationException) {
                    com.hermes.client.data.diagnostics.DebugLog.log(
                        "session", "resume gate timed out for $expectedStoredId; retrying resume",
                    )
                    val key = runtimeKey ?: throw timeout
                    recoverLiveHandle(expectedStoredId, expectedProfile, key)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (resumeError: Exception) {
                    com.hermes.client.data.diagnostics.DebugLog.log(
                        "session", "resume gate failed for $expectedStoredId; retrying resume once",
                    )
                    val key = runtimeKey ?: throw resumeError
                    recoverLiveHandle(expectedStoredId, expectedProfile, key)
                }
                check(storedSessionId == expectedStoredId) { "conversation changed before send" }
                val currentHandle = runtimeKey
                    ?.let { runtimeStore.runtimes.value[it]?.liveHandle }
                    ?.takeIf { it.isNotBlank() }
                    ?: initialHandle
                submitTurnWithRecovery(
                    initialHandle = currentHandle,
                    storedId = expectedStoredId,
                    profile = expectedProfile,
                    text = text,
                    isSlash = isSlash,
                    attachments = atts,
                    messageId = messageId,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                // A gateway error (e.g. "session not found") must surface, not crash the app.
                updateSentImages(messageId) { images ->
                    images.map { image ->
                        if (image.state == com.hermes.client.domain.ImageTransferState.UPLOADING) {
                            image.copy(state = com.hermes.client.domain.ImageTransferState.FAILED)
                        } else image
                    }
                }
                updateSentFiles(messageId) { files ->
                    files.map { file ->
                        if (file.state == com.hermes.client.domain.FileTransferState.UPLOADING) {
                            file.copy(state = com.hermes.client.domain.FileTransferState.FAILED)
                        } else file
                    }
                }
                appendError(localizedText("消息发送失败（HR-RPC-001）", "Failed to send the message (HR-RPC-001)"))
            }
        }
    }

    private suspend fun submitTurnWithRecovery(
        initialHandle: String,
        storedId: String,
        profile: String?,
        text: String,
        isSlash: Boolean,
        attachments: List<PendingAttachment>,
        messageId: String,
    ) {
        suspend fun submitOn(handle: String) {
            com.hermes.client.data.diagnostics.DebugLog.log(
                "session",
                "submit stored=$storedId handle=$handle chars=${text.length} attachments=${attachments.size}",
            )
            val fileRefs = mutableListOf<String>()
            attachments.forEach { attachment ->
                val uploaded = fileRepository.upload(
                    attachment.bytes,
                    attachment.name,
                    attachment.mimeType,
                )
                when (attachment.kind) {
                    AttachmentKind.IMAGE -> {
                        val attached = chat.attachImagePath(handle, uploaded.path)
                        updateSentImage(messageId, attachment.id) { image ->
                            image.copy(
                                remotePath = attached.path,
                                width = attached.width,
                                height = attached.height,
                                state = com.hermes.client.domain.ImageTransferState.READY,
                            )
                        }
                    }
                    AttachmentKind.PDF -> {
                        chat.attachPdfPath(handle, uploaded.path)
                        updateSentFile(messageId, attachment.id) {
                            it.copy(state = com.hermes.client.domain.FileTransferState.READY)
                        }
                    }
                    AttachmentKind.FILE -> {
                        val attached = chat.attachFilePath(handle, uploaded.path, attachment.name)
                        fileRefs += attached.refText
                        updateSentFile(messageId, attachment.id) {
                            it.copy(
                                name = attached.name,
                                remotePath = attached.path,
                                state = com.hermes.client.domain.FileTransferState.READY,
                            )
                        }
                    }
                }
            }
            if (isSlash) {
                val output = chat.slashExec(handle, text.trim())
                output?.takeIf { it.isNotBlank() }?.let(::appendSystem)
                runtimeKey?.let(runtimeStore::finishLocal)
            } else {
                val submittedText = buildString {
                    append(text.trimEnd())
                    fileRefs.forEach { ref ->
                        if (isNotEmpty()) append('\n')
                        append(ref)
                    }
                }
                chat.submit(handle, submittedText)
            }
        }

        try {
            submitOn(initialHandle)
        } catch (error: GatewayRpcException) {
            if (error.code != STALE_SESSION_CODE) throw error
            com.hermes.client.data.diagnostics.DebugLog.log(
                "session", "submit rejected as stale; resuming $storedId and retrying once",
            )
            val key = runtimeKey ?: throw error
            val recovered = recoverLiveHandle(storedId, profile, key)
            submitOn(recovered)
        }
    }

    private suspend fun recoverLiveHandle(
        storedId: String,
        profile: String?,
        key: SessionRuntimeKey,
    ): String = resumeMutex.withLock {
        check(this.storedSessionId == storedId) { "conversation changed during resume" }
        val handle = chat.resume(storedId, profile)
            ?.takeIf { it.isNotBlank() }
            ?: throw GatewayRpcException(STALE_SESSION_CODE, "session resume returned no live handle")
        sessionId = handle
        runtimeStore.bindLiveHandle(key, handle)
        com.hermes.client.data.diagnostics.DebugLog.log(
            "session", "resume($storedId) → handle=$handle",
        )
        handle
    }

    private fun updateSentImage(
        messageId: String,
        imageId: String,
        transform: (com.hermes.client.domain.ChatImage) -> com.hermes.client.domain.ChatImage,
    ) = updateSentImages(messageId) { images ->
        images.map { if (it.id == imageId) transform(it) else it }
    }

    private fun updateSentImages(
        messageId: String,
        transform: (List<com.hermes.client.domain.ChatImage>) -> List<com.hermes.client.domain.ChatImage>,
    ) {
        runtimeKey?.let { runtimeStore.updateUserImages(it, messageId, transform) }
            ?: mutateState { current ->
                current.copy(messages = current.messages.map { message ->
                    if (message.id == messageId) message.copy(images = transform(message.images)) else message
                })
            }
    }

    private fun updateSentFile(
        messageId: String,
        fileId: String,
        transform: (com.hermes.client.domain.ChatFile) -> com.hermes.client.domain.ChatFile,
    ) = updateSentFiles(messageId) { files ->
        files.map { if (it.id == fileId) transform(it) else it }
    }

    private fun updateSentFiles(
        messageId: String,
        transform: (List<com.hermes.client.domain.ChatFile>) -> List<com.hermes.client.domain.ChatFile>,
    ) {
        runtimeKey?.let { runtimeStore.updateUserFiles(it, messageId, transform) }
            ?: mutateState { current ->
                current.copy(messages = current.messages.map { message ->
                    if (message.id == messageId) message.copy(files = transform(message.files)) else message
                })
            }
    }

    /** Re-ask: re-submit the last user prompt (appends a new answer; the gateway can't replace). */
    fun regenerate() {
        if (_state.value.isGenerating) return
        val prompt = lastUserMessageText(_state.value.messages) ?: return
        send(prompt)
    }

    fun stop() {
        viewModelScope.launch {
            runCatching { chat.interrupt(sessionId) }
                .onSuccess { runtimeKey?.let(runtimeStore::markInterrupted) }
        }
    }

    private fun appendSystem(text: String) {
        mutateState { current ->
            current.copy(
                messages = current.messages + ChatMessage(
                    id = "s-${current.messages.size}", role = Role.SYSTEM, text = text,
                ),
            )
        }
    }

    /** User tapped "Retry" on the offline banner — force an immediate reconnect. */
    fun reconnect() { runCatching { chat.reconnect() } }

    /** Fetch "@" completions for [word] (empty word clears them). */
    fun completePath(word: String) = viewModelScope.launch {
        if (!word.startsWith("@")) { _pathItems.value = emptyList(); return@launch }
        runCatching { chat.completePath(sessionId, word) }
            .onSuccess { _pathItems.value = it }
            .onFailure { _pathItems.value = emptyList() }
    }

    fun clearPathItems() { _pathItems.value = emptyList() }

    fun respondApproval(choice: ApprovalChoice) {
        mutateState { it.copy(pendingApproval = null) }
        viewModelScope.launch {
            runCatching { chat.respondApproval(sessionId, choice) }
                .onSuccess { runtimeKey?.let(runtimeStore::continueAfterInput) }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    // The sheet is already gone; surface the failure so a lost approve/deny is visible.
                    appendError(localizedText("审批结果发送失败，请检查连接后重试（HR-RPC-001）。", "Couldn't send your approval. Check the connection and retry (HR-RPC-001)."))
                }
        }
    }

    /**
     * Answer the CURRENT clarify question. Batch requests lock one answer at a time (keyed by
     * qid) and advance locally; the request clears when the last question is answered. A failed
     * respond (expired request) also clears the card — the turn has moved on without us.
     */
    fun clarify(answer: String) {
        val request = _state.value.pendingClarify ?: return
        val current = request.currentQuestion
        com.hermes.client.data.diagnostics.DebugLog.log(
            "clarify",
            "respond req=${request.requestId.ifBlank { "<EMPTY>" }} qid=${current?.qid ?: "-"} answerLen=${answer.length}",
        )
        if (request.isBatch && current != null) {
            val advanced = request.copy(lockedAnswers = request.lockedAnswers + (current.qid to answer))
            val finished = advanced.currentQuestion == null
            mutateState { it.copy(pendingClarify = if (finished) null else advanced) }
            viewModelScope.launch {
                runCatching { chat.respondClarify(sessionId, request.requestId, answer, current.qid) }
                    .onSuccess { status ->
                        com.hermes.client.data.diagnostics.DebugLog.log("clarify", "respond status=$status")
                        if (status == "expired") {
                            onClarifyExpired()
                        } else if (finished) {
                            runtimeKey?.let(runtimeStore::continueAfterInput)
                        }
                    }
                    .onFailure { error ->
                        // Transport failure: put the question back so the user can retry —
                        // a swallowed error looked exactly like a successful submit.
                        com.hermes.client.data.diagnostics.DebugLog.log("clarify", "respond FAILED: ${error.message}")
                        mutateState { it.copy(pendingClarify = request) }
                    }
            }
        } else {
            mutateState { it.copy(pendingClarify = null) }
            viewModelScope.launch {
                runCatching { chat.respondClarify(sessionId, request.requestId, answer) }
                    .onSuccess { status ->
                        com.hermes.client.data.diagnostics.DebugLog.log("clarify", "respond status=$status")
                        if (status == "expired") onClarifyExpired()
                        else runtimeKey?.let(runtimeStore::continueAfterInput)
                    }
                    .onFailure { error ->
                        com.hermes.client.data.diagnostics.DebugLog.log("clarify", "respond FAILED: ${error.message}")
                        mutateState { it.copy(pendingClarify = request) }
                    }
            }
        }
    }

    /**
     * The server reported the clarify request was already gone ("expired"): the agent was
     * released earlier — by its timeout, an interrupt, or another client — and never sees an
     * answer delivered now. Silence here made a lost answer look identical to a delivered one.
     */
    private fun onClarifyExpired() {
        mutateState { it.copy(pendingClarify = null) }
        appendSystem(clarifyExpiredNotice(appLanguage))
    }

    /** Explicit skip of the WHOLE request (empty answer = upstream Skip semantics). */
    fun skipClarify() {
        val request = _state.value.pendingClarify ?: return
        mutateState { it.copy(pendingClarify = null) }
        viewModelScope.launch {
            runCatching { chat.respondClarify(sessionId, request.requestId, "") }
                .onSuccess { runtimeKey?.let(runtimeStore::continueAfterInput) }
        }
    }

    /** Appends a non-fatal error as a system message and stops the generating spinner. */
    private fun appendError(text: LocalizedText) {
        val failed = _state.value.copy(
            messages = _state.value.messages + ChatMessage(
                id = "e-${_state.value.messages.size}", role = Role.SYSTEM, text = text.resolve(appLanguage), isError = true,
            ),
            isGenerating = false,
        )
        _state.value = failed
        runtimeKey?.let { runtimeStore.markFailed(it, failed) }
    }

    override fun onCleared() {
        runtimeKey?.let { runtimeStore.setVisible(it, false) }
    }

    fun onSheetQuery(q: String) { _modelSheet.value = _modelSheet.value.copy(query = q) }
    fun toggleFavorite(provider: String, model: String) =
        viewModelScope.launch { favoritesStore.toggle(provider, model) }

    /**
     * Apply a model chosen in the sheet — always a SESSION switch (the /model --session slash):
     * the sheet only ever changes THIS chat; the profile default is edited on the settings
     * Models screen. On failure the code is surfaced in the sheet (kept open); on success the
     * sheet is dismissed by the caller via [onDone] and the model's remembered reasoning preset
     * is applied. A second tap while one selection is in flight is ignored.
     */
    fun onSelectFromSheet(provider: String, model: String, onDone: () -> Unit) {
        if (_modelSheet.value.pendingKey != null) return
        val key = com.hermes.client.data.repository.favKey(provider, model)
        _modelSheet.value = _modelSheet.value.copy(pendingKey = key, error = null)
        viewModelScope.launch {
            runCatching { chat.slashExec(sessionId, sessionModelCommand(provider, model)) }
                .onSuccess {
                    _currentModel.value = model
                    _currentProvider.value = provider
                    _explicitSessionOverride.value = true
                    _modelSheet.value = ModelSheetUi()  // reset + clear pending/error
                    applyReasoningPresetFor(provider, model)
                    onDone()
                }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _modelSheet.value = _modelSheet.value.copy(
                        pendingKey = null,
                        error = com.hermes.client.data.error.AppError(
                            com.hermes.client.data.error.AppErrorCode.MODEL_SWITCH_FAILED,
                            retryable = true, technicalCause = e.message, stage = "model_session_switch",
                        ),
                    )
                }
        }
    }

    /**
     * "恢复默认" from the sheet's current-model strip: pins this session back to the configured
     * default model. (The upstream slash has no "clear override" verb, so this re-points the
     * session at the default explicitly — same effective model.)
     */
    fun restoreDefaultModel(onDone: () -> Unit) {
        val model = _defaultModel.value ?: return
        val provider = _defaultProvider.value ?: com.hermes.client.ui.models.resolveModelProvider(
            catalogStore.state.value.providers, null, model,
        ) ?: return
        if (_modelSheet.value.pendingKey != null) return
        val key = com.hermes.client.data.repository.favKey(provider, model)
        _modelSheet.value = _modelSheet.value.copy(pendingKey = key, error = null)
        viewModelScope.launch {
            runCatching { chat.slashExec(sessionId, sessionModelCommand(provider, model)) }
                .onSuccess {
                    _currentModel.value = model
                    _currentProvider.value = provider
                    _explicitSessionOverride.value = false
                    _modelSheet.value = ModelSheetUi()
                    applyReasoningPresetFor(provider, model)
                    onDone()
                }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _modelSheet.value = _modelSheet.value.copy(
                        pendingKey = null,
                        error = com.hermes.client.data.error.AppError(
                            com.hermes.client.data.error.AppErrorCode.MODEL_SWITCH_FAILED,
                            retryable = true, technicalCause = e.message, stage = "model_restore_default",
                        ),
                    )
                }
        }
    }

    // ---- Session reasoning effort (config.get/set {key:"reasoning"} — same RPC as desktop) ----

    private val _reasoningEffort = MutableStateFlow<String?>(null)
    /** Wire value ("medium", "none", …) or null while unknown / provider default. */
    val reasoningEffort: StateFlow<String?> = _reasoningEffort.asStateFlow()
    private val _reasoningPending = MutableStateFlow(false)
    val reasoningPending: StateFlow<Boolean> = _reasoningPending.asStateFlow()

    /** Per-model remembered efforts (device-local), for the sheet's row suffixes. */
    val reasoningPresetMap: StateFlow<Map<String, String>> =
        reasoningPresets.presets.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** True while any catalog fetch for the active profile is in flight (refresh-button spinner). */
    val catalogRefreshing: StateFlow<Boolean> =
        catalogStore.state.map { it.refreshing }
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), false)

    /** Re-read this session's effective reasoning effort (sheet open / after session changes). */
    fun refreshReasoning() {
        val id = sessionId
        if (id.isBlank()) return
        viewModelScope.launch {
            runCatching { chat.reasoningGet(id) }
                .onSuccess { _reasoningEffort.value = it?.ifBlank { null } }
            // Failure is silent: the sheet just shows "默认" until a read succeeds.
        }
    }

    /**
     * Session-scoped effort change from the sheet, with optimistic update + rollback. On success
     * the choice is remembered as this model's preset (what makes effort FEEL global while every
     * write stays on this session — desktop-client behavior).
     */
    fun setReasoning(level: String) {
        if (_reasoningPending.value) return
        val previous = _reasoningEffort.value
        _reasoningPending.value = true
        _reasoningEffort.value = level
        _modelSheet.value = _modelSheet.value.copy(error = null)
        viewModelScope.launch {
            runCatching { chat.reasoningSet(sessionId, level) }
                .onSuccess {
                    val provider = _currentProvider.value
                    val model = _currentModel.value
                    if (!provider.isNullOrBlank() && !model.isNullOrBlank()) {
                        runCatching { reasoningPresets.set(provider, model, level) }
                    }
                }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _reasoningEffort.value = previous
                    _modelSheet.value = _modelSheet.value.copy(
                        error = com.hermes.client.data.error.AppError(
                            com.hermes.client.data.error.AppErrorCode.MODEL_REASONING_FAILED,
                            retryable = true, technicalCause = e.message, stage = "reasoning_set",
                        ),
                    )
                }
            _reasoningPending.value = false
        }
    }

    /**
     * After a successful model switch, re-apply that model's remembered effort to the session
     * (mirrors the desktop client's applyModelPreset); without a memory, re-read the effective
     * value so the sheet and chip stay truthful.
     */
    private fun applyReasoningPresetFor(provider: String, model: String) {
        viewModelScope.launch {
            val preset = runCatching { reasoningPresets.presets.first() }.getOrNull()
                ?.get(com.hermes.client.data.repository.favKey(provider, model))
            if (preset != null && preset != _reasoningEffort.value) {
                runCatching { chat.reasoningSet(sessionId, preset) }
                    .onSuccess { _reasoningEffort.value = preset }
                // Failure is silent — the model switch itself succeeded.
            } else if (preset == null) {
                refreshReasoning()
            }
        }
    }

    fun selectProfile(name: String) {
        viewModelScope.launch { runCatching { profileRepo.setActive(name) } }
    }

    data class PersonaUi(
        val personas: List<Persona> = emptyList(),
        val active: String? = null,
        val loading: Boolean = false,
        val error: LocalizedText? = null,
    )
    private val _personaUi = MutableStateFlow(PersonaUi())
    val personaUi: StateFlow<PersonaUi> = _personaUi.asStateFlow()

    /** Fetch the profile's configured personalities (called when the persona sheet opens). */
    fun loadPersonas() {
        _personaUi.value = _personaUi.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { configRepo.get(profileManager.active.value) }
                .onSuccess { cfg -> _personaUi.value = PersonaUi(parsePersonas(cfg), activePersonaOf(cfg)) }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _personaUi.value = _personaUi.value.copy(loading = false, error = localizedText("加载角色失败（HR-CONFIG-001）", "Couldn't load personas (HR-CONFIG-001)"))
                }
        }
    }

    /** Apply a persona to this session (null / "none" / "default" clears it). */
    fun setPersona(name: String?) {
        val wire = name?.takeIf { it.isNotBlank() && !it.equals("none", true) && !it.equals("default", true) } ?: "none"
        _personaUi.value = _personaUi.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { chat.slashExec(sessionId, "/personality $wire") }
                .onSuccess { out ->
                    if (out != null && out.contains("unknown", ignoreCase = true)) {
                        _personaUi.value = _personaUi.value.copy(loading = false, error = localizedText("应用角色失败（HR-RPC-001）", "Couldn't apply that persona (HR-RPC-001)"))
                    } else {
                        _personaUi.value = _personaUi.value.copy(loading = false, active = if (wire == "none") null else wire)
                    }
                }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _personaUi.value = _personaUi.value.copy(loading = false, error = localizedText("应用角色失败（HR-RPC-001）", "Couldn't apply persona (HR-RPC-001)"))
                }
        }
    }
}

/**
 * Quote a `/model` slash argument when it contains whitespace (provider slugs and model names may
 * carry spaces upstream); space-free values pass through unchanged so the wire format the gateway
 * already accepts is untouched.
 */
internal fun slashArg(value: String): String =
    if (value.any { it.isWhitespace() }) "\"" + value.replace("\"", "\\\"") + "\"" else value

/** The session-scope model switch slash. */
internal fun sessionModelCommand(provider: String, model: String): String =
    "/model ${slashArg(model)} --provider ${slashArg(provider)} --session"

internal fun displaySessionTitle(raw: String?, fallback: String = "新会话"): String {
    val title = raw?.trim().orEmpty()
    return title.takeIf {
        it.isNotEmpty() && !it.equals("Untitled", ignoreCase = true) &&
            !it.equals("New chat", ignoreCase = true)
    } ?: fallback
}
