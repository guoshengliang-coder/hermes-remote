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
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.ChatMediaRepository
import com.hermes.client.data.repository.ChatFileRepository
import com.hermes.client.data.repository.ModelRepository
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chat: ChatRepository,
    private val sessions: SessionRepository,
    private val modelRepo: ModelRepository,
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

    private companion object {
        const val LIVE_HANDLE_TIMEOUT_MS = 25_000L
        const val STALE_SESSION_CODE = 4001
    }

    private val _state = MutableStateFlow(ChatUiState.empty())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val _sessionTitle = MutableStateFlow("新会话")
    val sessionTitle: StateFlow<String> = _sessionTitle.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = chat.connectionState

    // I1: expose 401 unauthorized so the nav layer can route back to Setup
    private val _unauthorized = MutableStateFlow(false)
    val unauthorized: StateFlow<Boolean> = _unauthorized.asStateFlow()

    // The model this session is confirmed to be using. Null until a switch succeeds (the gateway
    // doesn't report the session's current model up-front), so the picker shows "Model" until the
    // user changes it, then the chosen model as confirmation the switch took.
    private val _currentModel = MutableStateFlow<String?>(null)
    val currentModel: StateFlow<String?> = _currentModel.asStateFlow()

    // Provider list for the model sheet (grouped by real slug); loaded alongside options.
    private val _providers = MutableStateFlow<List<com.hermes.client.data.network.ModelProviderDto>>(emptyList())
    val providers: kotlinx.coroutines.flow.StateFlow<List<com.hermes.client.data.network.ModelProviderDto>> = _providers.asStateFlow()

    // Provider of the confirmed session model (set together with _currentModel on a successful switch).
    private val _currentProvider = MutableStateFlow<String?>(null)
    val currentProvider: kotlinx.coroutines.flow.StateFlow<String?> = _currentProvider.asStateFlow()

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
        val scope: com.hermes.client.ui.models.ModelScope = com.hermes.client.ui.models.ModelScope.SESSION,
        val pending: Boolean = false,
        val error: String? = null,
    )
    // Model-LIST loading state (the sheet's pending/error covers selection, not the list).
    private val _providersLoading = MutableStateFlow(false)
    val providersLoading: kotlinx.coroutines.flow.StateFlow<Boolean> = _providersLoading.asStateFlow()
    private val _providersError = MutableStateFlow(false)
    val providersError: kotlinx.coroutines.flow.StateFlow<Boolean> = _providersError.asStateFlow()

    /**
     * Fetch the provider/model catalog if it isn't loaded. The open()-time fetch is best-effort
     * and swallowed on failure, which used to leave the model sheet a silent empty shell for the
     * rest of the session; the sheet now calls this on open (and on explicit retry).
     */
    fun ensureProviders(force: Boolean = false) {
        if (_providersLoading.value) return
        if (_providers.value.isNotEmpty() && !force) return
        _providersLoading.value = true
        _providersError.value = false
        viewModelScope.launch {
            runCatching { modelRepo.providers(profileManager.active.value) }
                .onSuccess {
                    _providers.value = it
                    _providersError.value = it.isEmpty()
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _providersError.value = true
                }
            _providersLoading.value = false
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
    private var runtimeKey: SessionRuntimeKey? = null
    private var currentProfile: String? = null
    private var liveHandleGate = CompletableDeferred<String>()
    private val resumeMutex = Mutex()

    fun open(
        id: String,
        requestedProfile: String? = null,
        initialTitle: String? = null,
        isNewSession: Boolean = false,
    ) {
        sendJob?.cancel()
        resumeJob?.cancel()
        liveHandleGate.completeExceptionally(CancellationException("session changed"))
        liveHandleGate = CompletableDeferred()
        runtimeKey?.let { runtimeStore.setVisible(it, false) }
        sessionId = id
        storedSessionId = id
        val profile = requestedProfile?.ifBlank { null } ?: profileManager.active.value
        currentProfile = profile
        val key = runtimeStore.register(id, profile)
        runtimeKey = key
        runtimeStore.setVisible(key, true)
        val cachedMeta = sessions.cachedSession(id, profile)
        val fallbackTitle = if (isNewSession) "新会话" else "会话"
        _sessionTitle.value = when {
            !initialTitle.isNullOrBlank() -> displaySessionTitle(initialTitle, fallbackTitle)
            cachedMeta != null -> displaySessionTitle(cachedMeta.title, fallbackTitle)
            else -> fallbackTitle
        }
        _currentModel.value = cachedMeta?.model?.ifBlank { null }
        _currentProvider.value = cachedMeta?.provider?.ifBlank { null }
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
                runtimeStore.historyFailed(key, e.message ?: "无法加载历史消息")
            } catch (e: Exception) {
                // Keep a cached/live transcript visible if history refresh fails.
                com.hermes.client.data.diagnostics.DebugLog.log("error", "history($id) failed: ${e.message}")
                runtimeStore.historyFailed(key, e.message ?: "无法加载历史消息")
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
                            appendError("Attach failed: ${e.message}")
                        }
                }
            }
            // Load model options, profiles, and the slash-command catalog; failures are non-fatal
            launch {
                runCatching { _providers.value = modelRepo.providers(profileManager.active.value) }
                // A brand-new session has no model in its metadata yet, which used to render as
                // "自动" even though Hermes has a definite default. Fall back to the configured
                // default model (and the provider marked current) so the chip names the real model.
                if (_currentModel.value.isNullOrBlank()) {
                    runCatching {
                        val cfg = configRepo.get(profile)
                        val defaultModel = (cfg["model"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.ifBlank { null }
                        if (storedSessionId == id && _currentModel.value.isNullOrBlank() && defaultModel != null) {
                            _currentModel.value = defaultModel
                            if (_currentProvider.value.isNullOrBlank()) {
                                _currentProvider.value = _providers.value.firstOrNull { it.isCurrent }?.slug
                            }
                        }
                    }
                }
            }
            launch { runCatching { _profiles.value = profileRepo.list() } }
            launch { runCatching { _commands.value = chat.commandsCatalog() } }
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
                appendError(e.message ?: "Failed to send message")
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
                    appendError("Couldn't send your approval — check the connection and try again.")
                }
        }
    }

    fun clarify(answer: String) {
        val requestId = _state.value.pendingClarify?.requestId ?: ""
        mutateState { it.copy(pendingClarify = null) }
        viewModelScope.launch {
            runCatching { chat.respondClarify(sessionId, requestId, answer) }
                .onSuccess { runtimeKey?.let(runtimeStore::continueAfterInput) }
        }
    }

    /** Appends a non-fatal error as a system message and stops the generating spinner. */
    private fun appendError(text: String) {
        val failed = _state.value.copy(
            messages = _state.value.messages + ChatMessage(
                id = "e-${_state.value.messages.size}", role = Role.SYSTEM, text = text, isError = true,
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
    fun onSheetScope(s: com.hermes.client.ui.models.ModelScope) {
        _modelSheet.value = _modelSheet.value.copy(scope = s, error = null)
    }
    fun toggleFavorite(provider: String, model: String) =
        viewModelScope.launch { favoritesStore.toggle(provider, model) }

    /**
     * Apply a model chosen in the sheet. SESSION → the /model --session slash (overrides just this
     * chat); DEFAULT → the global default via REST. On failure the gateway message is surfaced in
     * the sheet (kept open); on success the sheet is dismissed by the caller via [onDone].
     */
    fun onSelectFromSheet(provider: String, model: String, onDone: () -> Unit) {
        _modelSheet.value = _modelSheet.value.copy(pending = true, error = null)
        viewModelScope.launch {
            when (_modelSheet.value.scope) {
                com.hermes.client.ui.models.ModelScope.SESSION ->
                    runCatching { chat.slashExec(sessionId, "/model $model --provider $provider --session") }
                        .onSuccess {
                            _currentModel.value = model
                            _currentProvider.value = provider
                            _modelSheet.value = ModelSheetUi()  // reset + clear pending/error
                            onDone()
                        }
                        .onFailure { e ->
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            _modelSheet.value = _modelSheet.value.copy(
                                pending = false,
                                error = e.message ?: "Couldn't switch model.",
                            )
                        }
                com.hermes.client.ui.models.ModelScope.DEFAULT ->
                    runCatching { modelRepo.set(provider, model, profileManager.active.value) }
                        .onSuccess {
                            _modelSheet.value = _modelSheet.value.copy(pending = false, error = null)
                            appendSystem("Default set to $model")
                            onDone()
                        }
                        .onFailure { e ->
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            _modelSheet.value = _modelSheet.value.copy(
                                pending = false,
                                error = e.message ?: "Couldn't set default model.",
                            )
                        }
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
        val error: String? = null,
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
                    _personaUi.value = _personaUi.value.copy(loading = false, error = "Couldn't load personas")
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
                        _personaUi.value = _personaUi.value.copy(loading = false, error = "Couldn't apply that persona")
                    } else {
                        _personaUi.value = _personaUi.value.copy(loading = false, active = if (wire == "none") null else wire)
                    }
                }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _personaUi.value = _personaUi.value.copy(loading = false, error = "Couldn't apply persona")
                }
        }
    }
}

internal fun displaySessionTitle(raw: String?, fallback: String = "新会话"): String {
    val title = raw?.trim().orEmpty()
    return title.takeIf {
        it.isNotEmpty() && !it.equals("Untitled", ignoreCase = true) &&
            !it.equals("New chat", ignoreCase = true)
    } ?: fallback
}
