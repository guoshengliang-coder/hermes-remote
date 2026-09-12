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
import com.hermes.client.data.repository.ModelRecentsStore
import com.hermes.client.data.repository.ModelRepository
import com.hermes.client.data.repository.ProfileRepository
import com.hermes.client.data.repository.SessionRepository
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import org.junit.Assert.assertNull
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
    private val projectPrefs = io.mockk.mockk<com.hermes.client.data.repository.ProjectPrefsStore>(relaxed = true).also {
        io.mockk.every { it.defaultProjectPath } returns kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    }
    private val mediaRepo = mockk<ChatMediaRepository>(relaxed = true)
    private val fileRepo = mockk<com.hermes.client.data.repository.ChatFileRepository>(relaxed = true)
    private val sessionRepo = mockk<SessionRepository>(relaxed = true)
    private val modelRepo = mockk<ModelRepository>(relaxed = true)
    private val profileRepo = mockk<ProfileRepository>(relaxed = true)
    private val profileManager = mockk<com.hermes.client.data.repository.ProfileManager>(relaxed = true)
    private val favoritesStore = mockk<ModelFavoritesStore>(relaxed = true)
    private val recentsStore = mockk<ModelRecentsStore>(relaxed = true)
    private val pendingShareStore = com.hermes.client.share.PendingShareStore()
    private val tts = mockk<com.hermes.client.data.tts.TextToSpeechController>(relaxed = true)
    private val promptStore = mockk<com.hermes.client.data.repository.PromptStore>(relaxed = true)
    private val configRepo = mockk<com.hermes.client.data.repository.ConfigRepository>(relaxed = true)
    private val acknowledgedChannels = MutableStateFlow<Set<String>>(emptySet())
    private val botSendNotice = mockk<com.hermes.client.data.repository.BotSendNoticeStore>(relaxed = true).also {
        every { it.acknowledged } returns acknowledgedChannels
    }
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
        // Relaxed mockk hands back a stub Session for a nullable reference return, and a stub with
        // a blank title would overwrite the title the caller passed in. Say "no row" explicitly.
        coEvery { sessionRepo.sessionMeta(any(), any(), any()) } returns null
        coEvery { mediaRepo.hydrateMessages(any(), any()) } answers { firstArg() }
        coEvery { fileRepo.upload(any(), any(), any()) } returns
            com.hermes.client.data.network.UploadedArtifact("/tmp/uploaded", "attachment", 3)
        coEvery { modelRepo.options() } returns emptyList()
        coEvery { modelRepo.providers() } returns emptyList()
        coEvery { profileRepo.list() } returns emptyList()
        every { favoritesStore.favorites } returns MutableStateFlow(emptySet())
        every { recentsStore.recents } returns MutableStateFlow(emptyList())
        every { tts.speaking } returns MutableStateFlow(false)
        every { promptStore.prompts } returns MutableStateFlow(emptyList())
    }

    @After fun tearDown() {
        runtimeJobs.forEach(Job::cancel)
        runtimeJobs.clear()
    }

    // Real store over the mocked ModelRepository so cache semantics are exercised for real.
    private var catalogStore: com.hermes.client.data.repository.ModelCatalogStore? = null

    // The unsent-draft cache (HG-41); a fake rather than a mock so "blank clears it" is real.
    private var drafts = com.hermes.client.data.repository.FakeDraftSnapshot()

    private fun buildVm(
        accountSessions: com.hermes.client.data.auth.AccountSessionManager? = null,
        conversationDevices: com.hermes.client.data.auth.ConversationDeviceStore? = null,
    ): ChatViewModel {
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
            favoritesStore, recentsStore, pendingShareStore, tts, promptStore, configRepo, runtimeStore,
            mediaRepo, fileRepo, mainDispatcherRule.dispatcher, projectPrefs,
            com.hermes.client.data.repository.ProjectCatalog(
                mockk(relaxed = true), sessionRepo, profileManager, projectPrefs,
            ),
            botSendNotice, drafts, CoroutineScope(runtimeJob + Dispatchers.Main),
            accountSessions, conversationDevices,
        )
    }

    // ── HG-41: the unsent draft. Token shape mirrors SessionReadStore.token(profile, id, device);
    // profileManager.active is null in these tests, hence "default/…".
    private val draftToken = com.hermes.client.data.repository.SessionReadStore.token(null, "s1")

    @Test fun opening_a_session_with_a_saved_draft_seeds_the_composer() = runTest {
        drafts = com.hermes.client.data.repository.FakeDraftSnapshot(
            listOf(com.hermes.client.data.repository.DraftRecord(token = draftToken, text = "半句话", updatedAt = 1L)),
        )
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()
        assertEquals("半句话", vm.initialDraft.value)
    }

    @Test fun a_saved_draft_outranks_a_share_handoff() = runTest {
        drafts = com.hermes.client.data.repository.FakeDraftSnapshot(
            listOf(com.hermes.client.data.repository.DraftRecord(token = draftToken, text = "我自己写的", updatedAt = 1L)),
        )
        pendingShareStore.put("s1", com.hermes.client.share.PendingShare(text = "分享进来的"))
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()
        assertEquals("我自己写的", vm.initialDraft.value)
    }

    @Test fun a_share_still_seeds_the_composer_when_there_is_no_draft() = runTest {
        pendingShareStore.put("s1", com.hermes.client.share.PendingShare(text = "分享进来的"))
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()
        assertEquals("分享进来的", vm.initialDraft.value)
    }

    @Test fun typing_is_debounced_into_a_single_write() = runTest {
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()
        vm.rememberDraft("半")
        vm.rememberDraft("半句")
        vm.rememberDraft("半句话")
        advanceUntilIdle()
        assertEquals("半句话", drafts.peek(draftToken))
        assertEquals(1, drafts.saves)
    }

    @Test fun the_empty_composer_at_open_does_not_wipe_the_stored_draft() = runTest {
        // The screen's LaunchedEffect fires once with "" before the stored text has been read;
        // writing that through would delete the draft this whole feature exists to restore.
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        drafts = com.hermes.client.data.repository.FakeDraftSnapshot(
            listOf(com.hermes.client.data.repository.DraftRecord(token = draftToken, text = "半句话", updatedAt = 1L)),
            readGate = gate,
        )
        val vm = buildVm()
        vm.open("s1")
        vm.rememberDraft("")
        advanceUntilIdle()
        assertEquals("半句话", drafts.peek(draftToken))
        assertEquals(0, drafts.saves)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("半句话", vm.initialDraft.value)
    }

    @Test fun clearing_the_composer_by_hand_drops_the_draft() = runTest {
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()
        vm.rememberDraft("半句话")
        advanceUntilIdle()
        vm.rememberDraft("")
        advanceUntilIdle()
        assertNull(drafts.peek(draftToken))
    }

    @Test fun clearDraft_drops_it_without_waiting_for_the_debounce() = runTest {
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()
        vm.rememberDraft("半句话")
        advanceUntilIdle()
        vm.clearDraft()
        advanceUntilIdle()
        assertNull(drafts.peek(draftToken))
    }

    // ── HG-38: 添加会话 — each picked conversation becomes its own Markdown attachment.
    private fun sourceSession(id: String, title: String) = com.hermes.client.domain.Session(
        id = id, title = title, model = "claude-opus-5", provider = null,
        messageCount = 2, profile = null,
    )

    private fun sourceHistory(text: String) = listOf(
        com.hermes.client.domain.ChatMessage(id = "h1", role = com.hermes.client.domain.Role.USER, text = text),
        com.hermes.client.domain.ChatMessage(id = "h2", role = com.hermes.client.domain.Role.ASSISTANT, text = "好的"),
    )

    @Test fun picked_conversations_become_one_markdown_attachment_each() = runTest {
        coEvery { sessionRepo.history("a", any(), any()) } returns sourceHistory("甲会话的内容")
        coEvery { sessionRepo.history("b", any(), any()) } returns sourceHistory("乙会话的内容")
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()

        vm.attachSessions(listOf(sourceSession("a", "重构网关"), sourceSession("b", "翻译文案")))
        advanceUntilIdle()

        val staged = vm.state.value.pendingAttachments
        assertEquals("three picked, three files — never merged into one", 2, staged.size)
        assertTrue(staged.all { it.mimeType == "text/markdown" })
        assertTrue(staged.all { it.name.endsWith(".md") })
        assertTrue(staged.all { it.kind == AttachmentKind.FILE })
        val first = String(staged[0].bytes, Charsets.UTF_8)
        assertTrue("the document must be that conversation's transcript", first.contains("甲会话的内容"))
        assertTrue(first.startsWith("# 重构网关"))
        assertEquals(0, vm.attachingSessions.value)
    }

    @Test fun a_conversation_that_cannot_be_read_does_not_take_the_others_with_it() = runTest {
        coEvery { sessionRepo.history("a", any(), any()) } returns sourceHistory("甲会话的内容")
        coEvery { sessionRepo.history("b", any(), any()) } throws IllegalStateException("boom")
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()
        val failures = mutableListOf<Int>()
        // Subscribe BEFORE the work starts and let the collector actually attach: this is a
        // one-shot event with no replay, exactly so a recomposition cannot re-show the toast.
        val collect = launch { vm.sessionAttachFailures.collect { failures += it } }
        advanceUntilIdle()

        vm.attachSessions(listOf(sourceSession("a", "重构网关"), sourceSession("b", "坏掉的")))
        advanceUntilIdle()

        assertEquals(1, vm.state.value.pendingAttachments.size)
        assertEquals(listOf(1), failures)
        assertEquals(0, vm.attachingSessions.value)
        collect.cancel()
    }

    @Test fun an_empty_conversation_produces_no_attachment_and_counts_as_a_failure() = runTest {
        coEvery { sessionRepo.history("a", any(), any()) } returns emptyList()
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()
        val failures = mutableListOf<Int>()
        // Subscribe BEFORE the work starts and let the collector actually attach: this is a
        // one-shot event with no replay, exactly so a recomposition cannot re-show the toast.
        val collect = launch { vm.sessionAttachFailures.collect { failures += it } }
        advanceUntilIdle()

        vm.attachSessions(listOf(sourceSession("a", "空的")))
        advanceUntilIdle()

        assertTrue("an empty transcript must not become a zero-byte file", vm.state.value.pendingAttachments.isEmpty())
        assertEquals(listOf(1), failures)
        collect.cancel()
    }

    @Test fun attaching_nothing_is_a_no_op() = runTest {
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()
        vm.attachSessions(emptyList())
        advanceUntilIdle()
        assertTrue(vm.state.value.pendingAttachments.isEmpty())
        assertEquals(0, vm.attachingSessions.value)
    }

    @Test fun identical_titles_do_not_produce_identical_file_names() = runTest {
        coEvery { sessionRepo.history(any(), any(), any()) } returns sourceHistory("内容")
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()

        vm.attachSessions(listOf(sourceSession("a", "周报"), sourceSession("b", "周报")))
        advanceUntilIdle()

        val names = vm.state.value.pendingAttachments.map { it.name }
        assertEquals(2, names.toSet().size)
        assertTrue(names.any { it.endsWith(" (2).md") })
    }

    @Test fun opening_account_conversation_routes_to_its_original_mac_without_changing_default() = runTest {
        val manager = mockk<com.hermes.client.data.auth.AccountSessionManager>()
        val affinity = mockk<com.hermes.client.data.auth.ConversationDeviceStore>(relaxed = true)
        every { manager.session } returns MutableStateFlow(
            com.hermes.client.data.auth.AccountSession(
                baseUrl = "https://relay.example",
                accountId = "account-1",
                installationId = "phone-1",
                installationDisplayName = "Pixel",
                accessToken = "hga",
                accessExpiresAt = "2099-01-01T00:00:00Z",
                refreshToken = "hgr",
                refreshExpiresAt = "2099-02-01T00:00:00Z",
                selectedDeviceId = "mac-default",
            ),
        )
        every { manager.routeToDevice("mac-history") } returns true
        val vm = buildVm(manager, affinity)

        vm.open("session-1", requestedDeviceId = "mac-history")
        advanceUntilIdle()

        verify { affinity.bind("account-1", null, "session-1", "mac-history") }
        verify(exactly = 1) { manager.routeToDevice("mac-history") }
        verify(exactly = 1) { chatRepo.reconnect() }
        coVerify { sessionRepo.history("session-1", null, "mac-history") }
        coVerify { sessionRepo.sessionMeta("session-1", null, "mac-history") }
    }

    @Test fun revoked_conversation_device_surfaces_the_registered_binding_error() = runTest {
        val manager = mockk<com.hermes.client.data.auth.AccountSessionManager>()
        every { manager.session } returns MutableStateFlow(
            com.hermes.client.data.auth.AccountSession(
                baseUrl = "https://relay.example",
                accountId = "account-1",
                installationId = "phone-1",
                installationDisplayName = "Pixel",
                accessToken = "hga",
                accessExpiresAt = "2099-01-01T00:00:00Z",
                refreshToken = "hgr",
                refreshExpiresAt = "2099-02-01T00:00:00Z",
                selectedDeviceId = "mac-default",
            ),
        )
        every { manager.routeToDevice("mac-revoked") } returns true
        coEvery { sessionRepo.history("session-revoked", null, "mac-revoked") } throws
            com.hermes.client.data.network.HermesApiException(
                code = 404,
                message = "HR-BIND-011",
                errorCode = "HR-BIND-011",
            )
        val vm = buildVm(manager, mockk(relaxed = true))

        vm.open("session-revoked", requestedDeviceId = "mac-revoked")
        advanceUntilIdle()

        assertTrue(vm.state.value.historyError?.contains("HR-BIND-011") == true)
    }

    // The chat subtitle follows the gateway's session.info (cwd/branch) — the workspace the
    // chat's tools actually run in, not a stale list row.
    @Test fun session_info_updates_the_workspace_subtitle() = runTest {
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()

        events.emit(
            ServerEvent(
                "session.info", "s1",
                buildJsonObject { put("session_id", "s1"); put("cwd", "/Users/me/proj"); put("branch", "main"); put("running", false) },
            ),
        )
        advanceUntilIdle()

        assertEquals("proj", vm.workspace.value?.projectLabel)
        assertEquals("main", vm.workspace.value?.branch)
        assertEquals("/Users/me/proj", vm.workspace.value?.cwd)
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

    private fun botSession(id: String, source: String = "dingtalk") = com.hermes.client.domain.Session(
        id = id, title = "钉钉会话", model = null, provider = null, messageCount = 4,
        profile = null, archived = false, source = source, lastActive = 0L, chatType = "dm",
    )

    /**
     * Opening a channel conversation must NOT resume it. Resume materialises an agent runtime in
     * the dashboard process and binds a live handle, which starts process polling — for a
     * conversation another process owns. Reading a log should cost nothing on the Mac.
     */
    @Test fun opening_a_bot_conversation_does_not_resume_it() = runTest {
        every { sessionRepo.cachedSession("bot-1", any(), any()) } returns botSession("bot-1")
        val vm = buildVm()
        vm.open("bot-1")
        advanceUntilIdle()

        coVerify(exactly = 0) { chatRepo.resume(any(), any()) }
        assertEquals("dingtalk", vm.botOrigin.value?.source)
    }

    @Test fun opening_an_ordinary_conversation_still_resumes_it() = runTest {
        every { sessionRepo.cachedSession(any(), any(), any()) } returns null
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()

        coVerify(exactly = 1) { chatRepo.resume("s1", null) }
        assertNull(vm.botOrigin.value)
    }

    /**
     * Sending is what pays for the runtime. The exact call count is not pinned: the send path has
     * its own retry ladder for a gate that fails or times out, and asserting a number here would
     * pin that ladder rather than this behaviour. What matters is that browsing cost nothing and
     * sending resumes.
     */
    @Test fun sending_into_a_bot_conversation_resumes_it() = runTest {
        every { sessionRepo.cachedSession("bot-1", any(), any()) } returns botSession("bot-1")
        val vm = buildVm()
        vm.open("bot-1")
        advanceUntilIdle()
        coVerify(exactly = 0) { chatRepo.resume(any(), any()) }

        vm.send("在吗")
        advanceUntilIdle()

        coVerify(atLeast = 1) { chatRepo.resume("bot-1", any()) }
    }

    /**
     * The chip used to name the profile's default model as though it had answered on DingTalk.
     * A channel conversation with no model of its own must stay unknown.
     */
    @Test fun a_bot_conversation_never_adopts_the_profile_default_model() = runTest {
        every { sessionRepo.cachedSession("bot-1", any(), any()) } returns botSession("bot-1")
        coEvery { configRepo.get(any()) } returns buildJsonObject { put("model", "anthropic/claude-sonnet-4") }
        val vm = buildVm()
        vm.open("bot-1")
        advanceUntilIdle()

        assertNull(vm.currentModel.value)
        assertEquals("anthropic/claude-sonnet-4", vm.defaultModel.value)
    }

    @Test fun an_ordinary_conversation_still_falls_back_to_the_profile_default_model() = runTest {
        every { sessionRepo.cachedSession(any(), any(), any()) } returns null
        coEvery { configRepo.get(any()) } returns buildJsonObject { put("model", "anthropic/claude-sonnet-4") }
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()

        assertEquals("anthropic/claude-sonnet-4", vm.currentModel.value)
    }

    /** Said once per channel, not once per device: a DingTalk acknowledgement says nothing about Slack. */
    @Test fun the_send_notice_is_needed_per_channel_and_not_at_all_for_a_local_chat() = runTest {
        every { sessionRepo.cachedSession("bot-1", any(), any()) } returns botSession("bot-1")
        val vm = buildVm()
        vm.open("bot-1")
        advanceUntilIdle()
        assertTrue(vm.botNoticeNeeded.value)

        acknowledgedChannels.value = setOf("dingtalk")
        advanceUntilIdle()
        assertFalse(vm.botNoticeNeeded.value)

        every { sessionRepo.cachedSession("bot-2", any(), any()) } returns botSession("bot-2", "slack")
        vm.open("bot-2")
        advanceUntilIdle()
        assertTrue(vm.botNoticeNeeded.value)
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

        vm.refreshEvents.test {
            vm.refreshCurrentConversation()
            runCurrent()
            assertFalse("manual refresh must keep the transcript out of skeleton loading", vm.state.value.historyLoading)
            advanceUntilIdle()
            assertEquals(ChatViewModel.ConversationRefreshEvent.SUCCEEDED_CHANGED, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals("after", vm.state.value.messages.single().text)
        assertFalse(vm.state.value.historyLoading)
        assertFalse(vm.refreshing.value)
        coVerify(exactly = 2) { sessionRepo.history("s1", null) }
    }

    @Test fun manualRefreshDuringARunAsksHermesAndReportsItStillRunning() = runTest {
        coEvery { sessionRepo.history("s1", null) } returns listOf(ChatMessage("u", Role.USER, "跑起来"))
        connectionStateFlow.value = ConnectionState.Connected
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()
        events.emit(ServerEvent("message.start", "s1", buildJsonObject { put("session_id", "s1"); put("message_id", "agent") }))
        events.emit(ServerEvent("message.delta", "s1", buildJsonObject { put("session_id", "s1"); put("text", "部分") }))
        advanceUntilIdle()
        assertTrue(vm.state.value.isGenerating)

        vm.refreshEvents.test {
            vm.refreshCurrentConversation()
            advanceUntilIdle()
            assertEquals(ChatViewModel.ConversationRefreshEvent.STILL_RUNNING, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        // open() resumed once; the refresh probed once more instead of queueing behind the run.
        coVerify(exactly = 2) { chatRepo.resume("s1", null) }
        assertTrue(vm.state.value.isGenerating)
    }

    @Test fun manualRefreshDuringAStaleRunLearnsItEndedAndSaysSo() = runTest {
        coEvery { sessionRepo.history("s1", null) } returns listOf(
            ChatMessage("u", Role.USER, "跑起来"), ChatMessage("a", Role.ASSISTANT, "完成内容"),
        )
        connectionStateFlow.value = ConnectionState.Connected
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()
        events.emit(ServerEvent("message.start", "s1", buildJsonObject { put("session_id", "s1"); put("message_id", "agent") }))
        advanceUntilIdle()

        vm.refreshEvents.test {
            vm.refreshCurrentConversation()
            runCurrent()
            // What Hermes answers the probe with when the run finished while the phone slept.
            events.emit(ServerEvent("session.info", "s1", buildJsonObject { put("session_id", "s1"); put("running", false) }))
            advanceUntilIdle()
            assertEquals(ChatViewModel.ConversationRefreshEvent.RUN_ENDED, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertFalse(vm.state.value.isGenerating)
        assertEquals("完成内容", vm.state.value.messages.last().text)
    }

    @Test fun identicalManualRefreshDoesNotRequestTranscriptRelayout() = runTest {
        val history = listOf(ChatMessage("server", Role.ASSISTANT, "same answer"))
        coEvery { sessionRepo.history("s1", null) } returnsMany listOf(history, history)
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()

        vm.refreshEvents.test {
            vm.refreshCurrentConversation()
            advanceUntilIdle()
            assertEquals(ChatViewModel.ConversationRefreshEvent.SUCCEEDED_UNCHANGED, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals("same answer", vm.state.value.messages.single().text)
        assertFalse(vm.state.value.historyLoading)
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

    // Delivery three-state (docs/DESIGN.md §5.4): SENDING from the optimistic insert, SENT once
    // prompt.submit is acknowledged, FAILED on the bubble (never a detached error row) when it raises.
    @Test fun user_turn_is_sending_until_submit_is_acknowledged() = runTest {
        val submitted = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { chatRepo.resume("s1", null) } returns "s1-live"
        coEvery { chatRepo.submit("s1-live", "hello") } coAnswers { submitted.await() }
        val vm = buildVm()
        vm.open("s1")
        runCurrent()

        vm.send("hello")
        runCurrent()
        val pending = vm.state.value.messages.last { it.role == com.hermes.client.domain.Role.USER }
        assertEquals(com.hermes.client.domain.DeliveryState.SENDING, pending.delivery)

        submitted.complete(Unit)
        runCurrent()
        val acked = vm.state.value.messages.first { it.id == pending.id }
        assertEquals(com.hermes.client.domain.DeliveryState.SENT, acked.delivery)

        events.emit(event("message.complete", "s1-live", "done"))
        advanceUntilIdle()
    }

    @Test fun failed_submit_marks_the_bubble_not_sent_without_an_error_row_and_retry_resends() = runTest {
        coEvery { chatRepo.resume("s1", null) } returns "s1-live"
        coEvery { chatRepo.submit("s1-live", "hello") } throws
            com.hermes.client.data.network.GatewayRpcException(5000, "mock: submit refused") andThen Unit
        val vm = buildVm()
        vm.open("s1")
        runCurrent()

        vm.send("hello")
        runCurrent()

        val failed = vm.state.value.messages.last { it.role == com.hermes.client.domain.Role.USER }
        assertEquals(com.hermes.client.domain.DeliveryState.FAILED, failed.delivery)
        assertTrue("no detached error row", vm.state.value.messages.none { it.isError })
        assertFalse("a send that never left the phone is not a running turn", vm.state.value.isGenerating)
        assertTrue(vm.sendDiagnostic(failed.id)!!.contains("HR-SESS-007"))

        vm.retrySend(failed.id)
        runCurrent()

        assertTrue("failed bubble is replaced", vm.state.value.messages.none { it.id == failed.id })
        val resent = vm.state.value.messages.last { it.role == com.hermes.client.domain.Role.USER }
        assertEquals("hello", resent.text)
        assertEquals(com.hermes.client.domain.DeliveryState.SENT, resent.delivery)
        assertEquals(null, vm.sendDiagnostic(failed.id))
        coVerify(exactly = 2) { chatRepo.submit("s1-live", "hello") }

        // Close the resent turn; while it awaits the model the runtime keeps polling processes and
        // the virtual clock would never go idle.
        events.emit(event("message.complete", "s1-live", "done"))
        advanceUntilIdle()
    }

    @Test fun a_session_owned_by_another_client_says_so_and_keeps_its_retry() = runTest {
        // HG-30: the desktop held the conversation, upstream refused with 4090, and the phone
        // collapsed it into the generic HR-SESS-007 "点按重试" — a retry that repeats the same
        // refusal for as long as the other side is running. The tap is right (the conflict clears
        // by itself); the sentence was not.
        coEvery { chatRepo.resume("s1", null) } returns "s1-live"
        coEvery { chatRepo.submit("s1-live", "hello") } throws
            com.hermes.client.data.network.GatewayRpcException(
                4090,
                "Session s1 already has a live owner (desktop, pid 32991, running 2h22m).",
            ) andThen Unit
        val vm = buildVm()
        vm.open("s1")
        runCurrent()

        vm.send("hello")
        runCurrent()

        val failed = vm.state.value.messages.last { it.role == com.hermes.client.domain.Role.USER }
        assertEquals(
            "retryable, so FAILED — not the terminal UNDELIVERABLE of a conversation that is gone",
            com.hermes.client.domain.DeliveryState.FAILED,
            failed.delivery,
        )
        assertEquals(
            com.hermes.client.data.error.AppErrorCode.SESSION_OWNED_ELSEWHERE,
            vm.sendErrorCode(failed.id),
        )
        assertTrue(vm.sendDiagnostic(failed.id)!!.contains("HR-SESS-013"))

        // The retry must still be offered: the moment the desktop lets go, the same send works.
        vm.retrySend(failed.id)
        runCurrent()
        val resent = vm.state.value.messages.last { it.role == com.hermes.client.domain.Role.USER }
        assertEquals(com.hermes.client.domain.DeliveryState.SENT, resent.delivery)
        assertEquals(null, vm.sendErrorCode(resent.id))
        coVerify(exactly = 2) { chatRepo.submit("s1-live", "hello") }

        events.emit(event("message.complete", "s1-live", "done"))
        advanceUntilIdle()
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

    // HG-29. A conversation upstream reaped answers 4001 to prompt.submit and then 4007 to the
    // resume the client retries with. The old behaviour flattened both into a retryable
    // HR-SESS-007 "点按重试" that could never succeed. A conversation opened as new, with nothing
    // persisted, must instead be replaced silently and the typed message delivered to the
    // replacement — the user loses neither the message nor anything else, because there was
    // nothing else.
    //
    // Timing note (house pattern, see stale_submit_resumes_and_retries_once_with_new_handle): a
    // just-submitted turn leaves the runtime in SUBMITTING, and the store's process poller loops
    // on a 5s delay while a runtime has active work. advanceUntilIdle() would advance virtual time
    // into that loop forever, so drive the send with runCurrent() and only advance once
    // message.complete has taken the run out of the active phases.
    @Test fun reaped_empty_session_is_recreated_and_the_message_is_delivered() = runTest {
        coEvery { chatRepo.resume("s1", null) } returns "live-1" andThenThrows
            com.hermes.client.data.network.GatewayRpcException(4007, "session not found")
        coEvery { chatRepo.submit("live-1", "hello") } throws
            com.hermes.client.data.network.GatewayRpcException(4001, "session not found")
        coEvery { chatRepo.createSession(any(), any()) } returns
            com.hermes.client.data.repository.CreatedSession("s2", null)
        coEvery { chatRepo.resume("s2", null) } returns "live-2"
        coEvery { chatRepo.submit("live-2", "hello") } returns Unit

        val vm = buildVm()
        vm.open("s1", isNewSession = true)
        advanceUntilIdle()

        vm.send("hello")
        runCurrent()

        coVerify(exactly = 1) { chatRepo.createSession(any(), any()) }
        coVerify(exactly = 1) { chatRepo.submit("live-2", "hello") }
        assertEquals("the screen must re-navigate to the live id", "s2", vm.recreatedSessionId.value)
        assertEquals(
            "a delivered message must not be left looking failed",
            com.hermes.client.domain.DeliveryState.SENT,
            vm.state.value.messages.last { it.role == Role.USER }.delivery,
        )

        events.emit(event("message.complete", "live-2", "done"))
        advanceUntilIdle()
    }

    // The other half of the same rule: a conversation that may hold history is NEVER silently
    // replaced — that would cut the transcript loose from everything said before. It is terminal,
    // and terminal means HR-SESS-001 with no retry offered, not HR-SESS-007 with one that lies.
    @Test fun reaped_session_with_history_is_terminal_and_never_recreated() = runTest {
        coEvery { sessionRepo.history(any(), any()) } returns listOf(
            ChatMessage(id = "h1", role = Role.USER, text = "earlier"),
            ChatMessage(id = "h2", role = Role.ASSISTANT, text = "earlier reply"),
        )
        coEvery { chatRepo.resume("s1", null) } returns "live-1" andThenThrows
            com.hermes.client.data.network.GatewayRpcException(4007, "session not found")
        coEvery { chatRepo.submit("live-1", "hello") } throws
            com.hermes.client.data.network.GatewayRpcException(4001, "session not found")

        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()

        vm.send("hello")
        runCurrent()

        coVerify(exactly = 0) { chatRepo.createSession(any(), any()) }
        assertNull("a session with history must not be replaced", vm.recreatedSessionId.value)
        assertEquals(
            "the bubble must say the conversation is gone, not offer a retry",
            com.hermes.client.domain.DeliveryState.UNDELIVERABLE,
            vm.state.value.messages.last { it.role == Role.USER }.delivery,
        )
        advanceUntilIdle()
    }

    // Tapping the bubble of a terminal failure must do nothing. The UI already withholds the tap;
    // this is the ViewModel refusing to re-dispatch even if something else asks it to.
    @Test fun retrySend_ignores_an_undeliverable_bubble() = runTest {
        coEvery { sessionRepo.history(any(), any()) } returns listOf(
            ChatMessage(id = "h1", role = Role.USER, text = "earlier"),
        )
        coEvery { chatRepo.resume("s1", null) } returns "live-1" andThenThrows
            com.hermes.client.data.network.GatewayRpcException(4007, "session not found")
        coEvery { chatRepo.submit("live-1", "hello") } throws
            com.hermes.client.data.network.GatewayRpcException(4001, "session not found")

        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()
        vm.send("hello")
        runCurrent()

        val bubble = vm.state.value.messages.last { it.role == Role.USER }
        vm.retrySend(bubble.id)
        runCurrent()

        coVerify(exactly = 1) { chatRepo.submit("live-1", "hello") }
        assertEquals(
            "the bubble stays exactly as it was",
            com.hermes.client.domain.DeliveryState.UNDELIVERABLE,
            vm.state.value.messages.last { it.role == Role.USER }.delivery,
        )
        advanceUntilIdle()
    }

    // Upstream broadcasts session.reclaimed precisely so the next prompt is not sent into a session
    // that no longer exists. Acting on it saves a doomed round trip AND, more importantly, is the
    // only warning that arrives before the user has typed anything.
    @Test fun session_reclaimed_event_recovers_without_a_doomed_submit() = runTest {
        coEvery { chatRepo.resume("s1", null) } returns "live-1"
        coEvery { chatRepo.createSession(any(), any()) } returns
            com.hermes.client.data.repository.CreatedSession("s2", null)
        coEvery { chatRepo.resume("s2", null) } returns "live-2"
        coEvery { chatRepo.submit("live-2", "hello") } returns Unit

        val vm = buildVm()
        vm.open("s1", isNewSession = true)
        advanceUntilIdle()

        events.emit(event("session.reclaimed", "s1"))
        runCurrent()

        vm.send("hello")
        runCurrent()

        coVerify(exactly = 0) { chatRepo.submit("live-1", any()) }
        coVerify(exactly = 1) { chatRepo.createSession(any(), any()) }
        coVerify(exactly = 1) { chatRepo.submit("live-2", "hello") }

        events.emit(event("message.complete", "live-2", "done"))
        advanceUntilIdle()
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

    // 快捷切换 (docs/DESIGN.md §5.17) is fed by this: every switch that actually took effect goes
    // to the front of the device-local recents list.
    @Test fun a_successful_switch_is_recorded_for_the_quick_switch_row() = runTest {
        val vm = buildVm()
        vm.open("s1"); advanceUntilIdle()

        vm.onSelectFromSheet("anthropic", "opus") {}
        advanceUntilIdle()

        coVerify(exactly = 1) { recentsStore.record("anthropic", "opus") }
    }

    // The other half: a switch that did NOT take effect must not offer itself as a shortcut.
    @Test fun a_failed_switch_is_not_recorded() = runTest {
        coEvery { chatRepo.slashExec("s1", any()) } throws
            com.hermes.client.data.network.GatewayRpcException(5000, "could not resolve credentials")
        val vm = buildVm()
        vm.open("s1"); advanceUntilIdle()

        vm.onSelectFromSheet("anthropic", "opus") {}
        advanceUntilIdle()

        coVerify(exactly = 0) { recentsStore.record(any(), any()) }
    }

    // 恢复默认 changes the model too, so the chip row has to offer the way back as well.
    @Test fun restoring_the_default_is_recorded_too() = runTest {
        coEvery { configRepo.get(any()) } returns buildJsonObject { put("model", "def-model") }
        coEvery { modelRepo.providers(any()) } returns listOf(
            com.hermes.client.data.network.ModelProviderDto(
                slug = "prov", isCurrent = true, models = listOf("def-model"),
            ),
        )
        val vm = buildVm()
        vm.open("s1"); advanceUntilIdle()

        vm.restoreDefaultModel {}
        advanceUntilIdle()

        coVerify(exactly = 1) { recentsStore.record("prov", "def-model") }
    }

    // A refused switch surfaces in the sheet's error (not the chat transcript), and the sheet stays
    // open (onDone not invoked) so the user can retry or pick a different model.
    @Test fun onSelectFromSheet_failure_surfaces_sheet_error() = runTest {
        coEvery { chatRepo.slashExec("s1", any()) } throws
            com.hermes.client.data.network.GatewayRpcException(5000, "could not resolve credentials")
        val vm = buildVm()
        vm.open("s1"); advanceUntilIdle()

        var onDoneCalled = false
        vm.onSelectFromSheet("anthropic", "opus") { onDoneCalled = true }
        advanceUntilIdle()

        val error = vm.modelSheet.value.error
        assertEquals("HR-RPC-004", error?.code?.value)
        assertTrue("a refused switch is worth retrying", error?.retryable == true)
        assertFalse("the sheet must stay open on failure", onDoneCalled)
    }

    // HG-28. `slash.exec` 5030 means the Mac's Hermes could not start its slash worker at all — the
    // managed 0.3.0 bundle shipped sources that its own child processes could not import, so every
    // slash command was dead. Collapsing that into HR-RPC-004 told the user "请重试" for something
    // no number of retries could fix; it needs its own non-retryable code.
    @Test fun onSelectFromSheet_maps_a_dead_slash_worker_to_its_own_terminal_code() = runTest {
        coEvery { chatRepo.slashExec("s1", any()) } throws
            com.hermes.client.data.network.GatewayRpcException(
                5030,
                "slash worker closed pipe: ... (ModuleNotFoundError: No module named 'tui_gateway')",
            )
        val vm = buildVm()
        vm.open("s1"); advanceUntilIdle()

        var onDoneCalled = false
        vm.onSelectFromSheet("anthropic", "opus") { onDoneCalled = true }
        advanceUntilIdle()

        val error = vm.modelSheet.value.error
        assertEquals("HR-RPC-007", error?.code?.value)
        assertFalse("retrying a worker that cannot start is a lie", error?.retryable == true)
        assertFalse("the sheet must stay open on failure", onDoneCalled)
        assertTrue(
            "the cause must survive for diagnostics",
            error?.technicalCause?.contains("tui_gateway") == true,
        )
    }

    // "恢复默认" runs the same slash, so it must classify failures the same way.
    @Test fun restoreDefaultModel_maps_a_dead_slash_worker_to_its_own_terminal_code() = runTest {
        coEvery { configRepo.get(any()) } returns buildJsonObject { put("model", "def-model") }
        coEvery { modelRepo.providers(any()) } returns listOf(
            com.hermes.client.data.network.ModelProviderDto(
                slug = "prov", isCurrent = true, models = listOf("def-model"),
            ),
        )
        coEvery { chatRepo.slashExec("s1", any()) } throws
            com.hermes.client.data.network.GatewayRpcException(5030, "slash worker closed pipe")
        val vm = buildVm()
        vm.open("s1"); advanceUntilIdle()

        var onDoneCalled = false
        vm.restoreDefaultModel { onDoneCalled = true }
        advanceUntilIdle()

        assertEquals("HR-RPC-007", vm.modelSheet.value.error?.code?.value)
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
}
