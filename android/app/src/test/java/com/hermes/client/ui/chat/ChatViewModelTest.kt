package com.hermes.client.ui.chat

import app.cash.turbine.test
import com.hermes.client.data.network.ConnectionState
import com.hermes.client.MainDispatcherRule
import com.hermes.client.data.network.ProfileDto
import com.hermes.client.data.network.ServerEvent
import com.hermes.client.data.progress.SessionRuntimeStore
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.ChatMediaRepository
import com.hermes.client.data.repository.ModelFavoritesStore
import com.hermes.client.data.repository.ModelRepository
import com.hermes.client.data.repository.ProfileRepository
import com.hermes.client.data.repository.SessionRepository
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private val events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
    private val connectionStateFlow = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val chatRepo = mockk<ChatRepository>(relaxed = true)
    private val mediaRepo = mockk<ChatMediaRepository>(relaxed = true)
    private val fileRepo = mockk<com.hermes.client.data.repository.ChatFileRepository>(relaxed = true)
    private val sessionRepo = mockk<SessionRepository>(relaxed = true)
    private val modelRepo = mockk<ModelRepository>(relaxed = true)
    private val profileRepo = mockk<ProfileRepository>(relaxed = true)
    private val profileManager = mockk<com.hermes.client.data.repository.ProfileManager>(relaxed = true)
    private val favoritesStore = mockk<ModelFavoritesStore>(relaxed = true)
    private val pendingShareStore = com.hermes.client.share.PendingShareStore()
    private val tts = mockk<com.hermes.client.data.tts.TextToSpeechController>(relaxed = true)
    private val promptStore = mockk<com.hermes.client.data.repository.PromptStore>(relaxed = true)
    private val configRepo = mockk<com.hermes.client.data.repository.ConfigRepository>(relaxed = true)
    private val credentialStore = mockk<com.hermes.client.data.auth.CredentialStore> {
        every { load() } returns mockk()
    }
    private val connectivityChecker = mockk<com.hermes.client.data.network.ConnectivityChecker> {
        every { isOnline() } returns true
    }
    private val presetsFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    private val reasoningPresetStore = mockk<com.hermes.client.data.repository.ReasoningPresetStore>(relaxed = true) {
        every { presets } returns presetsFlow
    }
    private val runtimeJobs = mutableListOf<Job>()

    private fun event(type: String, sessionId: String, text: String? = null) = ServerEvent(
        type = type,
        sessionId = sessionId,
        payload = buildJsonObject {
            put("session_id", sessionId)
            text?.let { put("text", it) }
        },
    )

    @Before fun setUp() {
        every { chatRepo.events } returns events
        every { chatRepo.connectionState } returns connectionStateFlow
        // resume returns null here so the ViewModel keeps the opened id stable for these tests
        // (production switches to the live handle resume returns).
        coEvery { chatRepo.resume(any(), any()) } returns null
        every { profileManager.active } returns MutableStateFlow<String?>(null)
        coEvery { sessionRepo.history(any(), any()) } returns emptyList()
        coEvery { mediaRepo.hydrateMessages(any(), any()) } answers { firstArg() }
        coEvery { fileRepo.upload(any(), any(), any()) } returns
            com.hermes.client.data.network.UploadedArtifact("/tmp/uploaded", "attachment", 3)
        coEvery { modelRepo.options() } returns emptyList()
        coEvery { modelRepo.providers() } returns emptyList()
        coEvery { profileRepo.list() } returns emptyList()
        every { favoritesStore.favorites } returns MutableStateFlow(emptySet())
        every { tts.speaking } returns MutableStateFlow(false)
        every { promptStore.prompts } returns MutableStateFlow(emptyList())
    }

    @After fun tearDown() {
        runtimeJobs.forEach(Job::cancel)
        runtimeJobs.clear()
    }

    // Real store over the mocked ModelRepository so cache semantics are exercised for real.
    private var catalogStore: com.hermes.client.data.repository.ModelCatalogStore? = null

    private fun buildVm(): ChatViewModel {
        val runtimeJob = SupervisorJob()
        runtimeJobs += runtimeJob
        val runtimeStore = SessionRuntimeStore(
            chatRepo,
            CoroutineScope(runtimeJob + Dispatchers.Main),
            profileManager,
        )
        val store = com.hermes.client.data.repository.ModelCatalogStore(
            modelRepo, profileManager, credentialStore, connectivityChecker, chatRepo,
            CoroutineScope(runtimeJob + Dispatchers.Main),
        )
        catalogStore = store
        return ChatViewModel(
            chatRepo, sessionRepo, store, reasoningPresetStore, profileRepo, profileManager,
            favoritesStore, pendingShareStore, tts, promptStore, configRepo, runtimeStore,
            mediaRepo, fileRepo, mainDispatcherRule.dispatcher,
        )
    }

    @Test fun streamed_delta_appears_in_state() = runTest {
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()
        vm.state.test {
            awaitItem() // initial empty (or current) state
            events.emit(ServerEvent("message.start", "s1", buildJsonObject { put("session_id", "s1") }))
            events.emit(ServerEvent("message.delta", "s1", buildJsonObject { put("session_id", "s1"); put("text", "Hi") }))
            advanceUntilIdle()
            val latest = expectMostRecentItem()
            assertEquals("Hi", latest.messages.last().text)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * C2: when connectionState transitions Reconnecting → Connected (not the first Connected),
     * chat.resume() must be called a second time to re-attach the agent stream.
     */
    @Test fun reconnect_triggers_second_resume() = runTest {
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()
        events.emit(ServerEvent("message.start", "s1", buildJsonObject { put("session_id", "s1") }))
        advanceUntilIdle()
        // open() already called resume once; now simulate a reconnect cycle
        connectionStateFlow.value = ConnectionState.Reconnecting
        advanceUntilIdle()
        connectionStateFlow.value = ConnectionState.Connected
        advanceUntilIdle()

        // resume must have been called exactly twice: once in open(), once on reconnect
        coVerify(exactly = 2) { chatRepo.resume("s1", null) }
    }

    /**
     * Profile bug: session-scoped WebSocket RPCs must carry the active profile, or the gateway
     * resolves session.resume against the wrong profile's DB and returns "session not found"
     * (4007) — which then makes the next prompt.submit fail too. open() must pass the active
     * profile to resume so a session that lives in a non-default profile can be reattached.
     */
    @Test fun open_resumes_with_active_profile() = runTest {
        every { profileManager.active } returns MutableStateFlow<String?>("personal")
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()
        coVerify { chatRepo.resume("s1", "personal") }
    }

    @Test fun open_prefers_navigation_profile_and_title_for_existing_session() = runTest {
        every { profileManager.active } returns MutableStateFlow<String?>("personal")
        val vm = buildVm()

        vm.open(
            id = "s2",
            requestedProfile = "odos",
            initialTitle = "已有会话标题",
            isNewSession = false,
        )
        advanceUntilIdle()

        assertEquals("已有会话标题", vm.sessionTitle.value)
        coVerify { chatRepo.resume("s2", "odos") }
    }

    @Test fun reopeningSameConversationAfterConfigurationChangeDoesNotReload() = runTest {
        val vm = buildVm()

        vm.open("s1")
        advanceUntilIdle()
        vm.open("s1")
        advanceUntilIdle()

        coVerify(exactly = 1) { sessionRepo.history("s1", null) }
        coVerify(exactly = 1) { chatRepo.resume("s1", null) }
        assertFalse(vm.state.value.historyLoading)
    }

    @Test fun manualRefreshSwapsAuthoritativeHistoryWithoutEnteringLoadingState() = runTest {
        val old = ChatMessage("old", Role.USER, "before")
        val fresh = ChatMessage("fresh", Role.ASSISTANT, "after")
        coEvery { sessionRepo.history("s1", null) } returnsMany listOf(listOf(old), listOf(fresh))
        val vm = buildVm()

        vm.open("s1")
        advanceUntilIdle()
        assertEquals("before", vm.state.value.messages.single().text)

        vm.refreshCurrentConversation()
        runCurrent()
        assertFalse("manual refresh must keep the transcript out of skeleton loading", vm.state.value.historyLoading)
        advanceUntilIdle()

        assertEquals("after", vm.state.value.messages.single().text)
        assertFalse(vm.state.value.historyLoading)
        assertFalse(vm.refreshing.value)
        coVerify(exactly = 2) { sessionRepo.history("s1", null) }
    }

    @Test fun manualRefreshRequestedDuringStreamingWaitsForCompletion() = runTest {
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()

        events.emit(event("message.start", "s1"))
        runCurrent()
        assertTrue(vm.state.value.isGenerating)

        vm.refreshCurrentConversation()
        runCurrent()
        coVerify(exactly = 1) { sessionRepo.history("s1", null) }

        events.emit(event("message.complete", "s1", "done"))
        runCurrent()

        coVerify(exactly = 2) { sessionRepo.history("s1", null) }
        assertFalse(vm.refreshing.value)
    }

    /**
     * A pending image share must be staged as a local attachment chip (not attached to the
     * gateway immediately) — it's flushed on the next send() using whatever the live
     * post-resume sessionId is at that time.
     */
    @Test fun open_stages_pending_share_image_as_attachment() = runTest {
        coEvery { chatRepo.resume("s1", null) } returns "s1-live"
        pendingShareStore.put(
            "s1",
            // Valid base64 (decodes to "abc") — real decode logic runs in unit tests.
            com.hermes.client.share.PendingShare(imageBase64 = "YWJj", imageMime = "image/png"),
        )
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()

        coVerify(exactly = 0) { chatRepo.attachImageBytes(any(), any(), any()) }
        assertEquals(1, vm.state.value.pendingAttachments.size)
        assertEquals("image/png", vm.state.value.pendingAttachments.first().mimeType)
    }

    @Test fun send_waits_for_live_handle_instead_of_using_stored_session_id() = runTest {
        val resumed = kotlinx.coroutines.CompletableDeferred<String?>()
        coEvery { chatRepo.resume("s1", null) } coAnswers { resumed.await() }
        val vm = buildVm()

        vm.open("s1")
        runCurrent()
        vm.send("hello")
        runCurrent()
        coVerify(exactly = 0) { chatRepo.submit(any(), any()) }

        resumed.complete("s1-live")
        runCurrent()
        coVerify(exactly = 1) { chatRepo.submit("s1-live", "hello") }

        events.emit(event("message.complete", "s1-live", "done"))
        advanceUntilIdle()
    }

    @Test fun file_attachment_uploads_raw_then_attaches_visible_mac_path() = runTest {
        coEvery { chatRepo.resume("s1", null) } returns "s1-live"
        coEvery { chatRepo.attachFilePath("s1-live", "/tmp/uploaded", "notes.txt") } returns
            com.hermes.client.data.repository.AttachedFile(
                name = "notes.txt",
                path = "/tmp/uploaded",
                refText = "@file:/tmp/uploaded",
            )
        val vm = buildVm()
        vm.open("s1")
        runCurrent()

        vm.stageAttachment("abc".toByteArray(), "text/plain", "notes.txt")
        vm.send("请总结")
        runCurrent()

        coVerify { fileRepo.upload(any(), "notes.txt", "text/plain") }
        coVerify { chatRepo.attachFilePath("s1-live", "/tmp/uploaded", "notes.txt") }
        coVerify { chatRepo.submit("s1-live", "请总结\n@file:/tmp/uploaded") }

        events.emit(event("message.complete", "s1-live", "done"))
        runCurrent()
    }

    @Test fun stale_submit_resumes_and_retries_once_with_new_handle() = runTest {
        coEvery { chatRepo.resume("s1", null) } returnsMany listOf("live-1", "live-2")
        coEvery { chatRepo.submit("live-1", "hello") } throws
            com.hermes.client.data.network.GatewayRpcException(4001, "session not found")
        coEvery { chatRepo.submit("live-2", "hello") } returns Unit
        val vm = buildVm()

        vm.open("s1")
        advanceUntilIdle()
        vm.send("hello")
        runCurrent()

        coVerify(exactly = 2) { chatRepo.resume("s1", null) }
        coVerify(exactly = 1) { chatRepo.submit("live-1", "hello") }
        coVerify(exactly = 1) { chatRepo.submit("live-2", "hello") }

        events.emit(event("message.complete", "live-2", "done"))
        advanceUntilIdle()
    }

    @Test fun send_retries_resume_when_initial_open_resume_failed() = runTest {
        coEvery { chatRepo.resume("s1", null) } returnsMany listOf(null, "live-2")
        val vm = buildVm()

        vm.open("s1")
        advanceUntilIdle()
        vm.send("hello")
        runCurrent()

        coVerify(exactly = 2) { chatRepo.resume("s1", null) }
        coVerify(exactly = 1) { chatRepo.submit("live-2", "hello") }

        events.emit(event("message.complete", "live-2", "done"))
        advanceUntilIdle()
    }

    /** A text-only pending share (no image) must not trigger an attach call at all. */
    @Test fun open_with_text_only_pending_share_does_not_attach_image() = runTest {
        pendingShareStore.put("s1", com.hermes.client.share.PendingShare(text = "hello"))
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()

        coVerify(exactly = 0) { chatRepo.attachImageBytes(any(), any(), any()) }
        assertEquals("hello", vm.initialDraft.value)
    }

    @Test fun save_image_to_gallery_delegates_and_reports_completion() = runTest {
        val image = com.hermes.client.domain.ChatImage(
            id = "generated",
            mimeType = "image/png",
            localPath = "/cache/generated.png",
        )
        val saved = com.hermes.client.data.repository.SavedChatImage(mockk(), "generated.png")
        coEvery { mediaRepo.saveToGallery(image) } returns saved
        val vm = buildVm()
        var result: Result<com.hermes.client.data.repository.SavedChatImage>? = null

        vm.saveImageToGallery(image) { result = it }
        advanceUntilIdle()

        coVerify(exactly = 1) { mediaRepo.saveToGallery(image) }
        assertEquals("generated.png", result?.getOrThrow()?.displayName)
    }

    /**
     * I3: when connectionState enters Reconnecting while generation is in progress,
     * the in-flight assistant message must be marked interrupted and isGenerating cleared.
     */
    @Test fun reconnecting_while_generating_keeps_live_snapshot() = runTest {
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()

        // Start a generation via message.start event
        events.emit(ServerEvent("message.start", "s1", buildJsonObject { put("message_id", "m1") }))
        advanceUntilIdle()
        assertTrue("should be generating after message.start", vm.state.value.isGenerating)

        // Simulate connection drop while generating
        connectionStateFlow.value = ConnectionState.Reconnecting
        advanceUntilIdle()

        val s = vm.state.value
        assertTrue("generation stays resumable while reconnecting", s.isGenerating)
        val lastMsg = s.messages.lastOrNull()
        assertFalse("a recoverable reconnect must not discard the live turn", lastMsg?.interrupted == true)
    }

    /**
     * C2 edge case: the very first Connected (startup) must NOT trigger a second resume.
     */
    @Test fun initial_connected_does_not_double_resume() = runTest {
        val vm = buildVm()
        // Start with Connecting, then transition to Connected (first connect)
        connectionStateFlow.value = ConnectionState.Connecting
        vm.open("s1")
        advanceUntilIdle()
        connectionStateFlow.value = ConnectionState.Connected
        advanceUntilIdle()

        // Only one resume from open(); the Connected transition had prev==Connecting (not Reconnecting)
        coVerify(exactly = 1) { chatRepo.resume("s1", null) }
    }

    // Selecting in the chat sheet ALWAYS switches THIS session's model (the `/model … --session`
    // slash) — the sheet no longer carries a scope choice; the profile default is edited on the
    // settings Models screen only.
    @Test fun onSelectFromSheet_switches_this_session_via_slash() = runTest {
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()

        var onDoneCalled = false
        vm.onSelectFromSheet("anthropic", "opus") { onDoneCalled = true }
        advanceUntilIdle()

        coVerify { chatRepo.slashExec("s1", "/model opus --provider anthropic --session") }
        coVerify(exactly = 0) { modelRepo.set(any(), any(), any()) }
        assertEquals("opus", vm.currentModel.value)
        assertTrue("success must clear any sheet error", vm.modelSheet.value.error == null)
        assertTrue("onDone must be invoked so the caller dismisses the sheet", onDoneCalled)
    }

    // A worker failure ("slash worker closed pipe") throws — it must surface in the sheet's error
    // (not the chat transcript), and the sheet must stay open (onDone not invoked) so the user can
    // retry or pick a different model.
    @Test fun onSelectFromSheet_failure_surfaces_sheet_error() = runTest {
        coEvery { chatRepo.slashExec("s1", any()) } throws RuntimeException("slash worker closed pipe")
        val vm = buildVm()
        vm.open("s1"); advanceUntilIdle()

        var onDoneCalled = false
        vm.onSelectFromSheet("anthropic", "opus") { onDoneCalled = true }
        advanceUntilIdle()

        assertTrue("a failed switch must surface a sheet error", vm.modelSheet.value.error != null)
        assertFalse("the sheet must stay open on failure", onDoneCalled)
    }

    // Model names and provider slugs may contain spaces upstream — the slash command must quote
    // them, or the argument splits and the wrong model (or an error) results.
    @Test fun sessionModelCommand_quotes_arguments_with_spaces() {
        assertEquals("/model opus --provider anthropic --session", sessionModelCommand("anthropic", "opus"))
        assertEquals(
            "/model \"step 3.7 (flash)\" --provider openrouter --session",
            sessionModelCommand("openrouter", "step 3.7 (flash)"),
        )
        assertEquals("\"say \\\"hi\\\"\"", slashArg("say \"hi\""))
    }

    // A second tap while a switch is in flight must be ignored — otherwise two slashes race and
    // the session lands on whichever finishes last.
    @Test fun onSelectFromSheet_ignores_taps_while_pending() = runTest {
        coEvery { chatRepo.slashExec("s1", any()) } coAnswers {
            kotlinx.coroutines.delay(5_000); null
        }
        val vm = buildVm()
        vm.open("s1"); advanceUntilIdle()

        vm.onSelectFromSheet("anthropic", "opus") {}
        runCurrent()
        vm.onSelectFromSheet("anthropic", "sonnet") {}
        advanceUntilIdle()

        coVerify(exactly = 1) { chatRepo.slashExec("s1", any()) }
    }

    // Chip contract: a successful SESSION switch marks the chat as overridden; "恢复默认" pins the
    // session back to the configured default and clears the override.
    @Test fun session_override_flag_sets_on_switch_and_clears_on_restore() = runTest {
        coEvery { configRepo.get(any()) } returns buildJsonObject { put("model", "def-model") }
        coEvery { modelRepo.providers(any()) } returns listOf(
            com.hermes.client.data.network.ModelProviderDto(
                slug = "prov", isCurrent = true, models = listOf("def-model", "opus"),
            ),
        )
        val vm = buildVm()
        // WhileSubscribed: the derived flow only computes under collection.
        val watcher = launch { vm.sessionModelOverridden.collect {} }
        vm.open("s1"); advanceUntilIdle()

        assertFalse("a session following the default is not overridden", vm.sessionModelOverridden.value)
        assertEquals("def-model", vm.currentModel.value)

        vm.onSelectFromSheet("prov", "opus") {}
        advanceUntilIdle()
        assertTrue(vm.sessionModelOverridden.value)

        vm.restoreDefaultModel {}
        advanceUntilIdle()
        coVerify { chatRepo.slashExec("s1", "/model def-model --provider prov --session") }
        assertFalse(vm.sessionModelOverridden.value)
        assertEquals("def-model", vm.currentModel.value)
        watcher.cancel()
    }

    // ---- Session reasoning effort ----

    // Changing effort writes a session-scoped override and remembers it as this model's preset
    // (the desktop-client behavior that makes effort FEEL global).
    @Test fun setReasoning_success_saves_per_model_preset() = runTest {
        coEvery { configRepo.get(any()) } returns buildJsonObject { put("model", "def-model") }
        coEvery { modelRepo.providers(any()) } returns listOf(
            com.hermes.client.data.network.ModelProviderDto(
                slug = "prov", isCurrent = true, models = listOf("def-model"),
            ),
        )
        val vm = buildVm()
        vm.open("s1"); advanceUntilIdle()

        vm.setReasoning("high")
        advanceUntilIdle()

        coVerify { chatRepo.reasoningSet("s1", "high") }
        coVerify { reasoningPresetStore.set("prov", "def-model", "high") }
        assertEquals("high", vm.reasoningEffort.value)
        assertTrue(vm.modelSheet.value.error == null)
    }

    @Test fun setReasoning_failure_rolls_back_and_reports_code() = runTest {
        coEvery { chatRepo.reasoningGet(any()) } returns "medium"
        coEvery { chatRepo.reasoningSet(any(), any()) } throws RuntimeException("rpc down")
        val vm = buildVm()
        vm.open("s1"); advanceUntilIdle()
        assertEquals("medium", vm.reasoningEffort.value)

        vm.setReasoning("ultra")
        advanceUntilIdle()

        assertEquals("rollback to the pre-change level", "medium", vm.reasoningEffort.value)
        assertEquals(
            com.hermes.client.data.error.AppErrorCode.MODEL_REASONING_FAILED,
            vm.modelSheet.value.error?.code,
        )
    }

    // Selecting a model re-applies that model's remembered effort to the session.
    @Test fun model_switch_applies_remembered_preset() = runTest {
        presetsFlow.value = mapOf(
            com.hermes.client.data.repository.favKey("anthropic", "opus") to "xhigh",
        )
        val vm = buildVm()
        vm.open("s1"); advanceUntilIdle()

        vm.onSelectFromSheet("anthropic", "opus") {}
        advanceUntilIdle()

        coVerify { chatRepo.reasoningSet("s1", "xhigh") }
        assertEquals("xhigh", vm.reasoningEffort.value)
    }

    // The process-wide catalog store keeps providers warm: with a cached catalog, opening the
    // sheet must trigger no new fetch and never show the loading state.
    @Test fun ensureProviders_uses_warm_cache_without_refetch() = runTest {
        coEvery { modelRepo.providers(any()) } returns listOf(
            com.hermes.client.data.network.ModelProviderDto(slug = "prov", isCurrent = true, models = listOf("m")),
        )
        val vm = buildVm()
        val watchers = listOf(
            launch { vm.providers.collect {} },
            launch { vm.providersLoading.collect {} },
        )
        vm.open("s1"); advanceUntilIdle()   // open()'s safety-net refresh warms the cache

        vm.ensureProviders()                 // sheet open
        advanceUntilIdle()

        coVerify(exactly = 1) { modelRepo.providers(any()) }
        assertEquals(1, vm.providers.value.size)
        assertFalse(vm.providersLoading.value)
        watchers.forEach { it.cancel() }
    }

    // With NO cache the error state still surfaces, and an explicit retry refetches and clears it.
    @Test fun providers_error_only_without_cache_and_retry_refetches() = runTest {
        coEvery { modelRepo.providers(any()) } throws RuntimeException("catalog down")
        val vm = buildVm()
        val watchers = listOf(
            launch { vm.providers.collect {} },
            launch { vm.providersLoading.collect {} },
            launch { vm.providersError.collect {} },
        )
        vm.open("s1"); advanceUntilIdle()
        vm.ensureProviders()
        advanceUntilIdle()

        assertTrue("empty cache + failed fetch must surface the list error", vm.providersError.value)
        assertFalse(vm.providersLoading.value)

        coEvery { modelRepo.providers(any()) } returns listOf(
            com.hermes.client.data.network.ModelProviderDto(slug = "prov", isCurrent = true, models = listOf("m")),
        )
        vm.ensureProviders(force = true)
        advanceUntilIdle()

        assertFalse("retry success must clear the list error", vm.providersError.value)
        assertEquals(1, vm.providers.value.size)
        watchers.forEach { it.cancel() }
    }

    @Test fun selectProfile_calls_profileRepo_setActive() = runTest {
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()

        vm.selectProfile("personal")
        advanceUntilIdle()

        coVerify { profileRepo.setActive("personal") }
    }

    @Test fun readAloud_speaks_markdown_stripped_text() {
        val vm = buildVm()
        vm.readAloud("**hi** `there`")
        io.mockk.verify { tts.speak("hi there") }
    }

    @Test fun stopReading_stops_tts() {
        val vm = buildVm()
        vm.stopReading()
        io.mockk.verify { tts.stop() }
    }

    @Test fun setPersona_sends_personality_slash() = runTest {
        val vm = buildVm()
        vm.setPersona("witty"); advanceUntilIdle()
        io.mockk.coVerify { chatRepo.slashExec(any(), "/personality witty") }
    }

    @Test fun setPersona_null_clears_with_none() = runTest {
        val vm = buildVm()
        vm.setPersona(null); advanceUntilIdle()
        io.mockk.coVerify { chatRepo.slashExec(any(), "/personality none") }
    }

    // chat.slashExec returns command-level errors in its output string (only transport failures
    // throw), so a gateway rejection of an unknown persona must surface as an error, not silently
    // set active — otherwise the UI would show a persona as applied when the gateway refused it.
    @Test fun setPersona_rejection_surfaces_error_and_does_not_set_active() = runTest {
        coEvery { chatRepo.slashExec(any(), any()) } returns "unknown personality: x"
        val vm = buildVm()
        vm.setPersona("bad"); advanceUntilIdle()

        assertTrue("a gateway rejection must surface a persona error", vm.personaUi.value.error != null)
        assertEquals(null, vm.personaUi.value.active)
    }
}
