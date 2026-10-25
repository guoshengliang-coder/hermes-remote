package com.hermes.client.ui.chat

import app.cash.turbine.test
import com.hermes.client.data.network.ConnectionState
import com.hermes.client.MainDispatcherRule
import com.hermes.client.data.network.ProfileDto
import com.hermes.client.data.network.ServerEvent
import com.hermes.client.data.progress.SessionRuntimeStore
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.ModelSwitchResult
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        coEvery { chatRepo.activeSessions(any()) } throws UnsupportedOperationException("old Hermes")
        // resume returns null here so the ViewModel keeps the opened id stable for these tests
        // (production switches to the live handle resume returns).
        coEvery { chatRepo.resume(any(), any()) } returns null
        coEvery { chatRepo.switchSessionModel(any(), any(), any(), any()) } answers {
            ModelSwitchResult.Applied(thirdArg(), null)
        }
        every { profileManager.active } returns MutableStateFlow<String?>(null)
        coEvery { sessionRepo.history(any(), any()) } returns emptyList()
        // Relaxed mockk hands back a stub Session for a nullable reference return, and a stub with
        // a blank title would overwrite the title the caller passed in. Say "no row" explicitly.
        coEvery { sessionRepo.sessionMeta(any(), any(), any()) } returns null
        coEvery { mediaRepo.hydrateMessages(any(), any()) } answers { firstArg() }
        coEvery { fileRepo.upload(any(), any(), any()) } returns
            com.hermes.client.data.network.UploadedArtifact("/tmp/uploaded", "attachment", 3)
        coEvery { fileRepo.uploadStagedFile(any(), any(), any()) } returns
            com.hermes.client.data.network.UploadedArtifact("/tmp/uploaded", "attachment", 3)
        coEvery { modelRepo.options() } returns emptyList()
        coEvery { modelRepo.providers() } returns emptyList()
        coEvery { profileRepo.list() } returns emptyList()
        every { favoritesStore.sync } returns null
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

    // Messages that were submitted and refused (HG-49). Shared across the ViewModels a test builds,
    // which is the whole point: in production this store outlives the chat destination.
    private var unsent = com.hermes.client.data.repository.FakeUnsentSnapshot()

    /**
     * A runtime store that outlives one ViewModel, so a test can model leaving the chat screen and
     * coming back. In production this store is an application singleton and the ViewModel is scoped
     * to the nav entry; a test that builds a fresh store per ViewModel cannot see the difference.
     */
    private fun newRuntimeStore(): SessionRuntimeStore {
        val runtimeJob = SupervisorJob()
        runtimeJobs += runtimeJob
        return SessionRuntimeStore(chatRepo, CoroutineScope(runtimeJob + Dispatchers.Main), profileManager)
    }

    private fun buildVm(
        accountSessions: com.hermes.client.data.auth.AccountSessionManager? = null,
        conversationDevices: com.hermes.client.data.auth.ConversationDeviceStore? = null,
        runtimeStore: SessionRuntimeStore = newRuntimeStore(),
        defaultDispatcher: kotlinx.coroutines.CoroutineDispatcher = mainDispatcherRule.dispatcher,
        artifactDownloads: com.hermes.client.data.downloads.ArtifactDownloadManager? = null,
    ): ChatViewModel {
        val runtimeJob = SupervisorJob()
        runtimeJobs += runtimeJob
        val store = com.hermes.client.data.repository.ModelCatalogStore(
            modelRepo, profileManager, credentialStore, connectivityChecker, chatRepo,
            CoroutineScope(runtimeJob + Dispatchers.Main),
        )
        catalogStore = store
        return ChatViewModel(
            chatRepo, sessionRepo, store, reasoningPresetStore, profileRepo, profileManager,
            favoritesStore, recentsStore, pendingShareStore, tts, promptStore, configRepo, runtimeStore,
            mediaRepo, fileRepo, defaultDispatcher, projectPrefs,
            com.hermes.client.data.repository.ProjectCatalog(
                mockk(relaxed = true), sessionRepo, profileManager, projectPrefs,
            ),
            botSendNotice, drafts, unsent, CoroutineScope(runtimeJob + Dispatchers.Main),
            accountSessions, conversationDevices, artifactDownloads = artifactDownloads,
        )
    }

    // ── HG-41: the unsent draft. Token shape mirrors SessionReadStore.token(profile, id, device);
    // profileManager.active is null in these tests, hence "default/…".
    private val draftToken = com.hermes.client.data.repository.SessionReadStore.token(null, "s1")

    @Test fun small_legacy_video_retains_full_download_fallback_and_owner_scoped_position() = runUnsentComposerTest {
        val downloads = mockk<com.hermes.client.data.downloads.ArtifactDownloadManager>()
        val ranges = mockk<com.hermes.client.data.video.ArtifactRangeRepository>()
        coEvery { downloads.owner(any()) } returns "owner-a"
        coEvery { downloads.awaitTask(any()) } returns null
        every { downloads.videoRanges } returns ranges
        coEvery { ranges.prepare(any(), any()) } throws com.hermes.client.data.network.HermesApiException(501, "old peer", errorCode = "HR-FILE-012")
        val file = com.hermes.client.domain.ChatFile("v", "video.mp4", "video/mp4", 10, remotePath = "/video.mp4")
        val local = java.io.File.createTempFile("legacy-video", ".mp4").apply { writeText("fixture") }
        try {
            coEvery { fileRepo.metadata(file, any()) } returns com.hermes.client.domain.ArtifactMetadata(10)
            coEvery { fileRepo.download(file, any()) } returns local
            val vm = buildVm(artifactDownloads = downloads)
            vm.prepareVideo(file).positionMs = 7_000
            assertEquals(7_000L, vm.prepareVideo(file).positionMs)
            coEvery { downloads.owner(any()) } returns "owner-b"
            assertEquals(0L, vm.prepareVideo(file).positionMs)
            coEvery { fileRepo.metadata(file, any()) } returns com.hermes.client.domain.ArtifactMetadata(126L * 1024 * 1024)
            try { vm.prepareVideo(file); org.junit.Assert.fail("large legacy video bypassed channel limit") }
            catch (error: com.hermes.client.data.network.HermesApiException) { assertEquals("HR-FILE-012", error.errorCode) }
            coVerify(exactly = 3) { fileRepo.download(file, any()) }
        } finally { local.delete() }
    }

    @Test fun cachedHistoryPreparationUsesDefaultDispatcherAndKeepsNavigationResponsive() = runUnsentComposerTest {
        val background = kotlinx.coroutines.test.TestCoroutineScheduler()
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(background)
        val pendingNetwork = kotlinx.coroutines.CompletableDeferred<List<ChatMessage>>()
        every { sessionRepo.cachedHistory("s1", any(), any()) } returns listOf(
            ChatMessage("a", Role.ASSISTANT, "{\"output\":\"cached tool result\"}\n\nCached answer"),
        )
        coEvery { sessionRepo.history(any(), any(), any()) } coAnswers { pendingNetwork.await() }
        val vm = buildVm(defaultDispatcher = dispatcher)
        vm.open("s1")
        runCurrent()
        assertTrue("cached organization must not execute on Main", vm.state.value.messages.isEmpty())

        // Navigation and other main-thread work remain available before preparation runs.
        vm.open("s2")
        runCurrent()
        background.runCurrent()
        runCurrent()
        assertTrue("the departed cache must not leak into the new chat", vm.state.value.messages.isEmpty())

        vm.open("s1")
        runCurrent()
        background.runCurrent()
        runCurrent()
        val cached = vm.state.value.messages.single()
        assertEquals("Cached answer", cached.text)
        assertEquals("cached tool result", cached.tools.single().output)
        pendingNetwork.cancel()
    }

    @Test fun lateMemoryPreparationYieldsToAuthoritativeHistory() = runUnsentComposerTest {
        val background = kotlinx.coroutines.test.TestCoroutineScheduler()
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(background)
        val pendingNetwork = kotlinx.coroutines.CompletableDeferred<List<ChatMessage>>()
        every { sessionRepo.cachedHistory("s1", any(), any()) } returns listOf(
            ChatMessage("a", Role.ASSISTANT, "Old cache"),
        )
        coEvery { sessionRepo.history(any(), any(), any()) } coAnswers { pendingNetwork.await() }
        val runtime = newRuntimeStore()
        val vm = buildVm(runtimeStore = runtime, defaultDispatcher = dispatcher)
        vm.open("s1")
        runCurrent()
        runtime.acceptHistory(
            com.hermes.client.data.progress.SessionRuntimeKey(null, "s1"),
            listOf(ChatMessage("new", Role.ASSISTANT, "Authoritative answer")),
            System.currentTimeMillis(),
        )
        background.runCurrent()
        runCurrent()
        assertEquals("Authoritative answer", vm.state.value.messages.single().text)
        pendingNetwork.cancel()
    }

    @Test fun opening_a_session_with_a_saved_draft_seeds_the_composer() = runUnsentComposerTest {
        drafts = com.hermes.client.data.repository.FakeDraftSnapshot(
            listOf(com.hermes.client.data.repository.DraftRecord(token = draftToken, text = "半句话", updatedAt = 1L)),
        )
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()
        assertEquals("半句话", vm.initialDraft.value)
    }

    @Test fun restoredUiDraftOutranksDiskButStillReceivesAnIncomingShare() = runUnsentComposerTest {
        drafts = com.hermes.client.data.repository.FakeDraftSnapshot(
            listOf(com.hermes.client.data.repository.DraftRecord(token = draftToken, text = "旧的磁盘草稿", updatedAt = 1L)),
        )
        val first = buildVm()
        first.open("s1")
        runCurrent()
        assertEquals("最新的草稿", first.initialDraftForComposer("最新的草稿"))
        pendingShareStore.put("s1", com.hermes.client.share.PendingShare(text = "分享进来的"))
        val shared = buildVm()
        shared.open("s1")
        runCurrent()
        assertEquals("最新的草稿\n\n分享进来的", shared.initialDraftForComposer("最新的草稿"))
    }

    // HG-40 changed this: a share used to be dropped when a draft existed. Delivering a transcript
    // into a conversation someone had already started typing in must not delete their half
    // sentence — so the two are joined, the user's own words first.
    @Test fun a_share_is_appended_to_the_draft_the_user_already_had() = runUnsentComposerTest {
        drafts = com.hermes.client.data.repository.FakeDraftSnapshot(
            listOf(com.hermes.client.data.repository.DraftRecord(token = draftToken, text = "我自己写的", updatedAt = 1L)),
        )
        pendingShareStore.put("s1", com.hermes.client.share.PendingShare(text = "分享进来的"))
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()
        assertEquals("我自己写的\n\n分享进来的", vm.initialDraft.value)
    }

    @Test fun delivered_attachments_are_staged_as_chips_not_sent() = runUnsentComposerTest {
        pendingShareStore.put(
            "s1",
            com.hermes.client.share.PendingShare(
                attachments = listOf(
                    com.hermes.client.share.PendingShareAttachment("# 记录".toByteArray(), "text/markdown", "对话.md"),
                ),
            ),
        )
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()
        val staged = vm.state.value.pendingAttachments
        assertEquals(1, staged.size)
        assertEquals("对话.md", staged[0].name)
        assertEquals("text/markdown", staged[0].mimeType)
        // Nothing may have gone out: the user says what it is for, then presses send.
        assertTrue(vm.state.value.messages.none { it.role == com.hermes.client.domain.Role.USER })
    }

    @Test fun delivering_parks_the_payload_for_the_target_conversation_only() = runUnsentComposerTest {
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()
        vm.deliverToSession("other", text = "转录正文")
        // Opening the wrong conversation must not consume it.
        assertNull(pendingShareStore.take("s1"))
        assertEquals("转录正文", pendingShareStore.take("other")?.text)
    }

    @Test fun a_share_still_seeds_the_composer_when_there_is_no_draft() = runUnsentComposerTest {
        pendingShareStore.put("s1", com.hermes.client.share.PendingShare(text = "分享进来的"))
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()
        assertEquals("分享进来的", vm.initialDraft.value)
    }

    @Test fun typing_is_debounced_into_a_single_write() = runUnsentComposerTest {
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

    @Test fun the_empty_composer_at_open_does_not_wipe_the_stored_draft() = runUnsentComposerTest {
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

    @Test fun clearing_the_composer_by_hand_drops_the_draft() = runUnsentComposerTest {
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()
        vm.rememberDraft("半句话")
        advanceUntilIdle()
        vm.rememberDraft("")
        advanceUntilIdle()
        assertNull(drafts.peek(draftToken))
    }

    @Test fun clearDraft_drops_it_without_waiting_for_the_debounce() = runUnsentComposerTest {
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

    @Test fun picked_conversations_become_one_markdown_attachment_each() = runUnsentComposerTest {
        coEvery { sessionRepo.fullHistory("a", any(), any(), any()) } returns sourceHistory("甲会话的内容")
        coEvery { sessionRepo.fullHistory("b", any(), any(), any()) } returns sourceHistory("乙会话的内容")
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

    @Test fun a_conversation_that_cannot_be_read_does_not_take_the_others_with_it() = runUnsentComposerTest {
        coEvery { sessionRepo.fullHistory("a", any(), any(), any()) } returns sourceHistory("甲会话的内容")
        coEvery { sessionRepo.fullHistory("b", any(), any(), any()) } throws IllegalStateException("boom")
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

    @Test fun an_empty_conversation_produces_no_attachment_and_counts_as_a_failure() = runUnsentComposerTest {
        coEvery { sessionRepo.fullHistory("a", any(), any(), any()) } returns emptyList()
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

    @Test fun attaching_nothing_is_a_no_op() = runUnsentComposerTest {
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()
        vm.attachSessions(emptyList())
        advanceUntilIdle()
        assertTrue(vm.state.value.pendingAttachments.isEmpty())
        assertEquals(0, vm.attachingSessions.value)
    }

    @Test fun identical_titles_do_not_produce_identical_file_names() = runUnsentComposerTest {
        coEvery { sessionRepo.fullHistory(any(), any(), any(), any()) } returns sourceHistory("内容")
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()

        vm.attachSessions(listOf(sourceSession("a", "周报"), sourceSession("b", "周报")))
        advanceUntilIdle()

        val names = vm.state.value.pendingAttachments.map { it.name }
        assertEquals(2, names.toSet().size)
        assertTrue(names.any { it.endsWith(" (2).md") })
    }

    @Test fun opening_account_conversation_routes_to_its_original_mac_without_changing_default() = runUnsentComposerTest {
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

    @Test fun revoked_conversation_device_surfaces_the_registered_binding_error() = runUnsentComposerTest {
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


    @Test fun a_new_unpersisted_conversation_does_not_prefetch_older_history() = runUnsentComposerTest {
        coEvery { sessionRepo.history("fresh", null, null) } throws
            com.hermes.client.data.network.HermesApiException(404, "Session not found")
        every { sessionRepo.hasOlderHistory("fresh", null, null) } returns null
        coEvery { sessionRepo.olderHistory("fresh", null, null) } throws
            com.hermes.client.data.network.HermesApiException(404, "Session not found")
        val vm = buildVm()
        vm.open("fresh", isNewSession = true)
        advanceUntilIdle()
        vm.loadOlderHistory()
        advanceUntilIdle()
        assertNull(vm.olderHistory.value.error)
        assertFalse(vm.olderHistory.value.loading)
        coVerify(exactly = 0) { sessionRepo.olderHistory("fresh", null, null) }
    }

    @Test fun a_new_conversation_must_not_hide_a_revoked_device_404() = runUnsentComposerTest {
        coEvery { sessionRepo.history("fresh", null, null) } throws
            com.hermes.client.data.network.HermesApiException(404, "revoked", errorCode = "HR-BIND-011")
        val vm = buildVm()
        vm.open("fresh", isNewSession = true)
        advanceUntilIdle()
        assertTrue(vm.state.value.historyError?.contains("HR-BIND-011") == true)
    }

    @Test fun an_existing_conversation_404_is_still_an_error() = runUnsentComposerTest {
        every { sessionRepo.hasOlderHistory("existing", null, null) } returns null
        coEvery { sessionRepo.olderHistory("existing", null, null) } throws
            com.hermes.client.data.network.HermesApiException(404, "Session not found")
        val vm = buildVm()
        vm.open("existing")
        advanceUntilIdle()
        vm.loadOlderHistory()
        advanceUntilIdle()
        assertNotNull(vm.olderHistory.value.error)
        coVerify(exactly = 1) { sessionRepo.olderHistory("existing", null, null) }
    }

    @Test fun known_persisted_history_still_pages_and_reports_real_failures() = runUnsentComposerTest {
        every { sessionRepo.hasOlderHistory("fresh", null, null) } returns true
        coEvery { sessionRepo.olderHistory("fresh", null, null) } throws
            com.hermes.client.data.network.HermesApiException(500, "upstream failed")
        val vm = buildVm()
        vm.open("fresh", isNewSession = true)
        advanceUntilIdle()
        vm.loadOlderHistory()
        advanceUntilIdle()
        assertEquals(com.hermes.client.data.error.AppErrorCode.HISTORY_UPSTREAM_FAILED, vm.olderHistory.value.error?.code)
        assertFalse(vm.olderHistory.value.error!!.retryable)
    }

    @Test fun full_artifact_history_does_not_report_success_when_paging_stalls() = runUnsentComposerTest {
        every { sessionRepo.hasOlderHistory("full", null, null) } returns true
        coEvery { sessionRepo.olderHistory("full", null, null) } returns
            com.hermes.client.data.repository.OlderHistoryPage(emptyList(), reachedStart = false, added = 0)
        val vm = buildVm()
        vm.open("full")
        advanceUntilIdle()
        assertFalse(vm.loadEntireHistory())
        assertEquals(com.hermes.client.data.error.AppErrorCode.HISTORY_INCOMPLETE, vm.olderHistory.value.error?.code)
    }

    @Test fun full_artifact_history_completes_after_reaching_the_first_page() = runUnsentComposerTest {
        every { sessionRepo.hasOlderHistory("full", null, null) } returnsMany listOf(true, false)
        coEvery { sessionRepo.olderHistory("full", null, null) } returns
            com.hermes.client.data.repository.OlderHistoryPage(listOf(ChatMessage("early", Role.ASSISTANT, "MEDIA:/tmp/early.md")), reachedStart = true, added = 1)
        val vm = buildVm()
        vm.open("full")
        advanceUntilIdle()
        assertTrue(vm.loadEntireHistory())
        assertNull(vm.olderHistory.value.error)
        coVerify(exactly = 1) { sessionRepo.olderHistory("full", null, null) }
    }

    // The chat subtitle follows the gateway's session.info (cwd/branch) — the workspace the
    // chat's tools actually run in, not a stale list row.
    @Test fun session_info_updates_the_workspace_subtitle() = runUnsentComposerTest {
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

    @Test fun streamed_delta_appears_in_state() = runUnsentComposerTest {
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
    @Test fun reconnect_triggers_second_resume() = runUnsentComposerTest {
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
    @Test fun open_resumes_with_active_profile() = runUnsentComposerTest {
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
    @Test fun opening_a_bot_conversation_does_not_resume_it() = runUnsentComposerTest {
        every { sessionRepo.cachedSession("bot-1", any(), any()) } returns botSession("bot-1")
        val vm = buildVm()
        vm.open("bot-1")
        advanceUntilIdle()

        coVerify(exactly = 0) { chatRepo.resume(any(), any()) }
        assertEquals("dingtalk", vm.botOrigin.value?.source)
    }

    @Test fun opening_an_ordinary_conversation_still_resumes_it() = runUnsentComposerTest {
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
    @Test fun sending_into_a_bot_conversation_resumes_it() = runUnsentComposerTest {
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
    @Test fun a_bot_conversation_never_adopts_the_profile_default_model() = runUnsentComposerTest {
        every { sessionRepo.cachedSession("bot-1", any(), any()) } returns botSession("bot-1")
        coEvery { configRepo.get(any()) } returns buildJsonObject { put("model", "anthropic/claude-sonnet-4") }
        val vm = buildVm()
        vm.open("bot-1")
        advanceUntilIdle()

        assertNull(vm.currentModel.value)
        assertEquals("anthropic/claude-sonnet-4", vm.defaultModel.value)
    }

    @Test fun an_ordinary_conversation_still_falls_back_to_the_profile_default_model() = runUnsentComposerTest {
        every { sessionRepo.cachedSession(any(), any(), any()) } returns null
        coEvery { configRepo.get(any()) } returns buildJsonObject { put("model", "anthropic/claude-sonnet-4") }
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()

        assertEquals("anthropic/claude-sonnet-4", vm.currentModel.value)
    }

    /** Said once per channel, not once per device: a DingTalk acknowledgement says nothing about Slack. */
    @Test fun the_send_notice_is_needed_per_channel_and_not_at_all_for_a_local_chat() = runUnsentComposerTest {
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

    @Test fun open_prefers_navigation_profile_and_title_for_existing_session() = runUnsentComposerTest {
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

    @Test fun reopeningSameConversationAfterConfigurationChangeDoesNotReload() = runUnsentComposerTest {
        val vm = buildVm()

        vm.open("s1")
        advanceUntilIdle()
        vm.open("s1")
        advanceUntilIdle()

        coVerify(exactly = 1) { sessionRepo.history("s1", null) }
        coVerify(exactly = 1) { chatRepo.resume("s1", null) }
        assertFalse(vm.state.value.historyLoading)
    }

    @Test fun manualRefreshSwapsAuthoritativeHistoryWithoutEnteringLoadingState() = runUnsentComposerTest {
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

    @Test fun manualRefreshRechecksOwnershipAndRestoresTheComposerAfterDesktopReleasesIt() = runUnsentComposerTest {
        coEvery { chatRepo.resume("s1", null) } returns "s1-live"
        coEvery { chatRepo.sessionAccess("s1", null, "s1-live") } returnsMany listOf(
            com.hermes.client.data.repository.SessionAccess(
                state = com.hermes.client.data.repository.SessionAccessState.OWNED_ELSEWHERE,
                // Ownership is the behaviour under test; keeping the runtime idle avoids the
                // store's intentional periodic probe loop from making advanceUntilIdle endless.
                running = false,
                writable = false,
                ownerSurface = "desktop",
            ),
            com.hermes.client.data.repository.SessionAccess(
                state = com.hermes.client.data.repository.SessionAccessState.AVAILABLE,
                running = false,
                writable = true,
                ownerSurface = null,
            ),
        )
        val vm = buildVm()

        vm.open("s1")
        advanceUntilIdle()
        assertEquals(
            com.hermes.client.data.repository.SessionAccessState.OWNED_ELSEWHERE,
            vm.sessionAccessState.value,
        )

        vm.refreshCurrentConversation()
        advanceUntilIdle()

        assertEquals(
            com.hermes.client.data.repository.SessionAccessState.AVAILABLE,
            vm.sessionAccessState.value,
        )
        coVerify(exactly = 2) { chatRepo.sessionAccess("s1", null, "s1-live") }
    }

    @Test fun manualRefreshDuringARunAsksHermesAndReportsItStillRunning() = runUnsentComposerTest {
        coEvery { sessionRepo.history("s1", null) } returns listOf(ChatMessage("u", Role.USER, "跑起来"))
        coEvery { chatRepo.sessionAccess(any(), any(), any()) } returns
            com.hermes.client.data.repository.SessionAccess(
                state = com.hermes.client.data.repository.SessionAccessState.OWNED_BY_REQUESTER,
                running = true,
                writable = true,
                ownerSurface = "android",
            )
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

    @Test fun manualRefreshDuringAStaleRunLearnsItEndedAndSaysSo() = runUnsentComposerTest {
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

    @Test fun identicalManualRefreshDoesNotRequestTranscriptRelayout() = runUnsentComposerTest {
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

    @Test fun manualRefreshRequestedDuringStreamingWaitsForCompletion() = runUnsentComposerTest {
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
    @Test fun open_stages_pending_share_image_as_attachment() = runUnsentComposerTest {
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
    @Test fun user_turn_is_sending_until_submit_is_acknowledged() = runUnsentComposerTest {
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

    @Test fun a_path_sent_immediately_after_stop_is_a_prompt_not_a_slash_command() = runUnsentComposerTest {
        coEvery { chatRepo.resume("s1", null) } returns "s1-live"
        coEvery { chatRepo.commandsCatalog() } returns listOf("/help" to "Show help")
        val vm = buildVm()
        vm.open("s1")
        runCurrent()

        vm.stop()
        runCurrent()
        val message = "/Users/me/Documents/notes.md\n请参考这个文件"
        vm.send(message)
        runCurrent()

        coVerify(exactly = 1) { chatRepo.interrupt("s1-live") }
        coVerify(exactly = 1) { chatRepo.submit("s1-live", message) }
        coVerify(exactly = 0) { chatRepo.slashExec(any(), any()) }
        assertEquals(
            com.hermes.client.domain.DeliveryState.SENT,
            vm.state.value.messages.last { it.role == Role.USER }.delivery,
        )

        events.emit(event("message.complete", "s1-live", "done"))
        advanceUntilIdle()
    }

    @Test fun a_catalogued_slash_command_still_uses_slash_exec() = runUnsentComposerTest {
        coEvery { chatRepo.resume("s1", null) } returns "s1-live"
        coEvery { chatRepo.commandsCatalog() } returns listOf("/help" to "Show help")
        val vm = buildVm()
        vm.open("s1")
        runCurrent()

        vm.send("/help")
        runCurrent()

        coVerify(exactly = 1) { chatRepo.slashExec("s1-live", "/help") }
        coVerify(exactly = 0) { chatRepo.submit(any(), any()) }
        events.emit(event("message.complete", "s1-live", "done"))
        advanceUntilIdle()
    }

    @Test fun a_slash_command_waits_for_the_catalog_when_it_was_not_loaded_at_open() = runUnsentComposerTest {
        val catalog = kotlinx.coroutines.CompletableDeferred<List<Pair<String, String>>>()
        coEvery { chatRepo.resume("s1", null) } returns "s1-live"
        coEvery { chatRepo.commandsCatalog() } coAnswers { catalog.await() }
        val vm = buildVm()
        vm.open("s1")
        runCurrent()

        vm.send("/help")
        runCurrent()
        coVerify(exactly = 0) { chatRepo.submit(any(), any()) }
        coVerify(exactly = 0) { chatRepo.slashExec(any(), any()) }

        catalog.complete(listOf("/help" to "Show help"))
        runCurrent()
        coVerify(exactly = 1) { chatRepo.slashExec("s1-live", "/help") }
    }

    @Test fun an_unknown_slash_token_is_regular_message_text() {
        val commands = listOf("/help" to "Show help")
        assertFalse(isKnownSlashCommand("/unknown hello", commands))
        assertFalse(isKnownSlashCommand("/Users/me/file.md", commands))
        assertTrue(isKnownSlashCommand("/help topic", commands))
    }

    @Test fun an_unknown_slash_token_is_submitted_as_a_prompt() = runUnsentComposerTest {
        coEvery { chatRepo.resume("s1", null) } returns "s1-live"
        coEvery { chatRepo.commandsCatalog() } returns listOf("/help" to "Show help")
        val vm = buildVm()
        vm.open("s1")
        runCurrent()

        vm.send("/unknown hello")
        runCurrent()

        coVerify(exactly = 1) { chatRepo.submit("s1-live", "/unknown hello") }
        coVerify(exactly = 0) { chatRepo.slashExec(any(), any()) }
        events.emit(event("message.complete", "s1-live", "done"))
        advanceUntilIdle()
    }

    @Test fun failed_submit_marks_the_bubble_not_sent_without_an_error_row_and_retry_resends() = runUnsentComposerTest {
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

    // HG-49: the same 4090, but the user walks away before dealing with it. In the report the
    // failure happened at 22:05, the user opened two other conversations, and came back at 22:14 —
    // by then the nav-scoped ViewModel that recorded the failure was gone. The bubble survived in
    // the runtime store, so it still said 未发送, but its code had collapsed to the generic
    // SESS-007 fallback and its tap did nothing at all. Both halves of that are this test.
    @Test fun a_failed_send_keeps_its_code_and_its_retry_after_leaving_the_chat_and_coming_back() = runUnsentComposerTest {
        coEvery { chatRepo.resume("s1", null) } returns "s1-live"
        coEvery { chatRepo.submit("s1-live", "巨浪事业群呢") } throws
            com.hermes.client.data.network.GatewayRpcException(
                4090,
                "Session s1 already has a live owner (desktop, pid 5313, running 1h23m).",
            ) andThen Unit
        val shared = newRuntimeStore()
        val vm = buildVm(runtimeStore = shared)
        vm.open("s1")
        runCurrent()
        vm.send("巨浪事业群呢")
        runCurrent()
        val failed = vm.state.value.messages.last { it.role == com.hermes.client.domain.Role.USER }
        assertEquals(
            com.hermes.client.data.error.AppErrorCode.SESSION_OWNED_ELSEWHERE,
            vm.sendErrorCode(failed.id),
        )

        // Back to the list, into other conversations, then back here: the destination was popped,
        // so this is a different ViewModel over the same runtime store.
        val reopened = buildVm(runtimeStore = shared)
        reopened.open("s1")
        advanceUntilIdle()

        val still = reopened.state.value.messages.last { it.role == com.hermes.client.domain.Role.USER }
        assertEquals("the failed bubble is still there", failed.id, still.id)
        assertEquals(com.hermes.client.domain.DeliveryState.FAILED, still.delivery)
        assertEquals(
            "it must still name the real cause instead of collapsing to the generic SESS-007",
            com.hermes.client.data.error.AppErrorCode.SESSION_OWNED_ELSEWHERE,
            reopened.sendErrorCode(still.id),
        )

        // And the tap has to actually do something. Offering it and swallowing it is worse than
        // not offering it (docs/ERROR_HANDLING.md).
        reopened.retrySend(still.id)
        runCurrent()
        val resent = reopened.state.value.messages.last { it.role == com.hermes.client.domain.Role.USER }
        assertEquals(com.hermes.client.domain.DeliveryState.SENT, resent.delivery)
        coVerify(exactly = 2) { chatRepo.submit("s1-live", "巨浪事业群呢") }

        events.emit(event("message.complete", "s1-live", "done"))
        advanceUntilIdle()
    }

    // HG-49, the cold-start half. The process died: the runtime store is empty and REST history
    // can never contain this turn, because upstream never accepted it. The record on disk is the
    // only thing left, so the bubble is re-inserted from it — under its stored id, which is what
    // lets the retry remove the same turn it replaces.
    @Test fun a_refused_send_comes_back_after_a_cold_start_and_can_still_be_retried() = runUnsentComposerTest {
        unsent = com.hermes.client.data.repository.FakeUnsentSnapshot(
            listOf(
                com.hermes.client.data.repository.UnsentRecord(
                    token = draftToken,
                    messageId = "u-restored",
                    text = "巨浪事业群呢",
                    code = "HR-SESS-013",
                    retryable = true,
                    updatedAt = 1L,
                ),
            ),
        )
        coEvery { chatRepo.resume("s1", null) } returns "s1-live"
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()

        val restored = vm.state.value.messages.single { it.id == "u-restored" }
        assertEquals("巨浪事业群呢", restored.text)
        assertEquals(com.hermes.client.domain.DeliveryState.FAILED, restored.delivery)
        assertEquals(
            com.hermes.client.data.error.AppErrorCode.SESSION_OWNED_ELSEWHERE,
            vm.sendErrorCode("u-restored"),
        )
        assertFalse("a restored failure is not a running turn", vm.state.value.isGenerating)

        vm.retrySend("u-restored")
        runCurrent()
        val resent = vm.state.value.messages.last { it.role == com.hermes.client.domain.Role.USER }
        assertEquals(com.hermes.client.domain.DeliveryState.SENT, resent.delivery)
        assertNull("the record is gone once it actually went out", unsent.peek(draftToken))

        events.emit(event("message.complete", "s1-live", "done"))
        advanceUntilIdle()
    }

    // A record whose attachments left no readable disk copy — a v1 record (HG-49 era, paths were
    // not recorded) or one whose cache entries were evicted. Sending the text alone would deliver
    // less than the user meant, so the restored turn takes HR-SESS-015 and the tap is withheld —
    // docs/ERROR_HANDLING.md: an offer that cannot work is worse than none. Since HG-208 this is
    // the degradation, not the default; the default is the disk-copy replay above.
    @Test fun a_restored_send_that_had_attachments_says_so_and_refuses_the_retry() = runUnsentComposerTest {
        unsent = com.hermes.client.data.repository.FakeUnsentSnapshot(
            listOf(
                com.hermes.client.data.repository.UnsentRecord(
                    token = draftToken, messageId = "u-withfiles", text = "看这几张图",
                    code = "HR-SESS-007", retryable = true, attachments = 3, updatedAt = 1L,
                ),
            ),
        )
        coEvery { chatRepo.resume("s1", null) } returns "s1-live"
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()

        assertEquals(
            com.hermes.client.data.error.AppErrorCode.UNSENT_ATTACHMENTS_LOST,
            vm.sendErrorCode("u-withfiles"),
        )

        vm.retrySend("u-withfiles")
        advanceUntilIdle()
        assertTrue("the bubble stays put", vm.state.value.messages.any { it.id == "u-withfiles" })
        coVerify(exactly = 0) { chatRepo.submit(any(), any()) }
    }

    // HG-162: retrying was the only thing a failed bubble offered, so a message the user had given
    // up on stayed on screen indefinitely — and the record on disk put it back after a restart.
    // The failure row now carries 撤回, and withdrawing has to be real: the turn goes AND the
    // record goes, or the next cold start quietly undoes it.
    @Test fun a_withdrawn_failed_send_leaves_the_transcript_and_the_disk_record() = runUnsentComposerTest {
        coEvery { chatRepo.resume("s1", null) } returns "s1-live"
        coEvery { chatRepo.submit("s1-live", "hello") } throws
            com.hermes.client.data.network.GatewayRpcException(5000, "mock: submit refused")
        val vm = buildVm()
        vm.open("s1")
        runCurrent()
        vm.send("hello")
        runCurrent()

        val failed = vm.state.value.messages.last { it.role == com.hermes.client.domain.Role.USER }
        assertEquals(com.hermes.client.domain.DeliveryState.FAILED, failed.delivery)
        assertNotNull(
            "a failed send is persisted, which is what a cold start restores",
            unsent.peek(draftToken),
        )

        vm.discardFailedSend(failed.id)
        runCurrent()

        assertTrue("the bubble goes", vm.state.value.messages.none { it.id == failed.id })
        assertNull("and so does its diagnostic", vm.sendDiagnostic(failed.id))
        assertNull(
            "withdrawing must clear the record, or the next cold start puts the bubble back",
            unsent.peek(draftToken),
        )
        coVerify(exactly = 1) { chatRepo.submit("s1-live", "hello") }
    }

    // The HG-162 report itself: the send was refused before the app restarted, so the record came
    // back with its attachments gone and the bubble offered a tap that could only ever fail. The
    // retry stays withheld (docs/ERROR_HANDLING.md) — but the user must be able to get rid of it.
    @Test fun a_restored_send_without_its_attachments_can_be_withdrawn() = runUnsentComposerTest {
        unsent = com.hermes.client.data.repository.FakeUnsentSnapshot(
            listOf(
                com.hermes.client.data.repository.UnsentRecord(
                    token = draftToken, messageId = "u-withfiles", text = "看这几张图",
                    code = "HR-SESS-007", retryable = true, attachments = 2, updatedAt = 1L,
                ),
            ),
        )
        coEvery { chatRepo.resume("s1", null) } returns "s1-live"
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()
        assertTrue("the bubble is back on screen", vm.state.value.messages.any { it.id == "u-withfiles" })

        vm.discardFailedSend("u-withfiles")
        advanceUntilIdle()

        assertTrue("withdrawing removes it", vm.state.value.messages.none { it.id == "u-withfiles" })
        assertNull("and it does not come back on the next cold start", unsent.peek(draftToken))
        coVerify(exactly = 0) { chatRepo.submit(any(), any()) }
    }

    // HG-208, the report itself: an image send died of an upload timeout, the user switched
    // conversations (the nav-scoped ViewModel went with it), and the retry they found on coming
    // back said 附件已丢失 — because the record kept a count, the bytes were ViewModel memory,
    // and every attachment-carrying failure degraded to HR-SESS-015 the moment the screen was
    // left. The fix has two halves, tested separately: this one writes the disk copies and the
    // record that points at them at the moment the send fails; the two restores below read them
    // back after the ViewModel — or the whole process — is gone. One test spanning both halves
    // would have to advanceUntilIdle past a failed send, which hands the runtime store's process
    // polling an endless hasActiveWork budget under virtual time (five minutes of GC storm per
    // run, in practice); the halves are equivalent combined and each stays fast.
    @Test fun a_failed_image_send_records_where_its_bytes_are_cached() = runUnsentComposerTest {
        val cached = java.io.File.createTempFile("hg208-image", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            coEvery { chatRepo.resume("s1", null) } returns "s1-live"
            coEvery { mediaRepo.cacheOutgoing(any(), any(), any()) } answers {
                com.hermes.client.domain.ChatImage(firstArg(), mimeType = thirdArg(), localPath = cached.absolutePath)
            }
            coEvery { fileRepo.upload(any(), any(), any()) } throws java.net.SocketTimeoutException("timeout")

            val vm = buildVm()
            vm.open("s1")
            runCurrent()
            vm.stageAttachment(cached.readBytes(), "image/jpeg", "shot.jpg")
            vm.send("看图")
            runCurrent()

            val record = unsent.peek(draftToken)
            assertNotNull("a failed send is persisted", record)
            assertEquals(1, record!!.attachments)
            assertEquals(1, record.attachmentMeta.size)
            assertEquals("the record points at the media-cache copy", cached.absolutePath, record.attachmentMeta[0].path)
            assertEquals("shot.jpg", record.attachmentMeta[0].name)
            assertEquals(
                "the failure stays retryable — it is a network refusal, not a lost attachment",
                com.hermes.client.data.error.AppErrorCode.MESSAGE_SEND_FAILED,
                vm.sendErrorCode(record.messageId),
            )
        } finally {
            cached.delete()
        }
    }

    // The other half: the record above is what a fresh ViewModel — or a cold-started process —
    // restores from. With every listed file readable the turn replays whole: the original error
    // code survives, the tap is real, and the retry uploads the image and submits the text.
    @Test fun a_restored_send_whose_attachment_files_survived_replays_with_them() = runUnsentComposerTest {
        val cached = java.io.File.createTempFile("hg208-image", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            unsent = com.hermes.client.data.repository.FakeUnsentSnapshot(
                listOf(
                    com.hermes.client.data.repository.UnsentRecord(
                        token = draftToken, messageId = "u-att", text = "看图",
                        code = "HR-SESS-007", retryable = true, attachments = 1,
                        attachmentMeta = listOf(
                            com.hermes.client.data.repository.UnsentAttachment(
                                id = "att-1", name = "shot.jpg", mimeType = "image/jpeg",
                                path = cached.absolutePath, sizeBytes = cached.length(),
                            ),
                        ),
                        updatedAt = 1L,
                    ),
                ),
            )
            coEvery { chatRepo.resume("s1", null) } returns "s1-live"
            coEvery { mediaRepo.cacheOutgoing(any(), any(), any()) } answers {
                com.hermes.client.domain.ChatImage(firstArg(), mimeType = thirdArg())
            }
            coEvery { chatRepo.attachImagePath("s1-live", "/tmp/uploaded") } returns
                com.hermes.client.data.repository.AttachedImage("/tmp/uploaded")
            val vm = buildVm()
            vm.open("s1")
            advanceUntilIdle()

            assertEquals(
                "the upload timeout is what the restored bubble reports — not 附件丢失",
                com.hermes.client.data.error.AppErrorCode.MESSAGE_SEND_FAILED,
                vm.sendErrorCode("u-att"),
            )

            vm.retrySend("u-att")
            runCurrent()
            coVerify(exactly = 1) { fileRepo.upload(any(), "shot.jpg", "image/jpeg") }
            coVerify(exactly = 1) { chatRepo.attachImagePath("s1-live", "/tmp/uploaded") }
            coVerify(exactly = 1) { chatRepo.submit("s1-live", "看图") }

            events.emit(event("message.complete", "s1-live", "done"))
            runCurrent()
        } finally {
            cached.delete()
        }
    }

    // The disk copy is a cache entry, not a promise: the media cache evicts by size and age, and
    // the staged file can be gone for the same reasons. A record whose files did not survive
    // degrades exactly as v1 records do — HR-SESS-015, tap withheld, nothing half-sent.
    @Test fun a_restored_send_whose_disk_copies_were_evicted_still_refuses_the_retry() = runUnsentComposerTest {
        unsent = com.hermes.client.data.repository.FakeUnsentSnapshot(
            listOf(
                com.hermes.client.data.repository.UnsentRecord(
                    token = draftToken, messageId = "u-att", text = "看图",
                    code = "HR-SESS-007", retryable = true, attachments = 1,
                    attachmentMeta = listOf(
                        com.hermes.client.data.repository.UnsentAttachment(
                            id = "att-1", name = "shot.jpg", mimeType = "image/jpeg",
                            path = "/nonexistent/cache/att-1.jpg", sizeBytes = 3L,
                        ),
                    ),
                    updatedAt = 1L,
                ),
            ),
        )
        coEvery { chatRepo.resume("s1", null) } returns "s1-live"
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()

        assertEquals(
            com.hermes.client.data.error.AppErrorCode.UNSENT_ATTACHMENTS_LOST,
            vm.sendErrorCode("u-att"),
        )
        vm.retrySend("u-att")
        advanceUntilIdle()
        assertTrue("the bubble stays put", vm.state.value.messages.any { it.id == "u-att" })
        coVerify(exactly = 0) { chatRepo.submit(any(), any()) }
    }

    @Test fun a_session_owned_by_another_client_says_so_and_keeps_its_retry() = runUnsentComposerTest {
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

    @Test fun send_waits_for_live_handle_instead_of_using_stored_session_id() = runUnsentComposerTest {
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

    @Test fun file_attachment_uploads_raw_then_attaches_visible_mac_path() = runUnsentComposerTest {
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

    @Test fun voice_send_consumes_and_uploads_staged_attachment() = kotlinx.coroutines.runBlocking {
        connectionStateFlow.value = ConnectionState.Connected
        coEvery { chatRepo.resume("s1", null) } returns "s1-live"
        coEvery { chatRepo.attachFilePath("s1-live", "/tmp/uploaded", "draft.txt") } returns
            com.hermes.client.data.repository.AttachedFile("draft.txt", "/tmp/uploaded", "@file:/tmp/uploaded")
        val vm = buildVm()
        vm.open("s1")
        mainDispatcherRule.dispatcher.scheduler.runCurrent()
        vm.stageAttachment("untouched".toByteArray(), "text/plain", "draft.txt")
        assertEquals(1, vm.state.value.pendingAttachments.size)

        assertTrue(vm.sendVoiceText("  语音问题  ", "已有文字"))
        mainDispatcherRule.dispatcher.scheduler.runCurrent()

        assertTrue(vm.state.value.pendingAttachments.isEmpty())
        coVerify(exactly = 1) { fileRepo.upload(any(), "draft.txt", "text/plain") }
        coVerify(exactly = 1) { chatRepo.submit("s1-live", "已有文字\n语音问题\n@file:/tmp/uploaded") }
    }

    @Test fun voice_send_retries_the_combined_text_image_and_file() = runUnsentComposerTest {
        connectionStateFlow.value = ConnectionState.Connected
        coEvery { chatRepo.resume("s1", null) } returns "s1-live"
        coEvery { mediaRepo.cacheOutgoing(any(), any(), any()) } answers {
            com.hermes.client.domain.ChatImage(firstArg(), mimeType = thirdArg())
        }
        coEvery { chatRepo.attachImagePath("s1-live", "/tmp/uploaded") } returns
            com.hermes.client.data.repository.AttachedImage("/tmp/uploaded")
        coEvery { chatRepo.attachFilePath("s1-live", "/tmp/uploaded", "report.pdf") } returns
            com.hermes.client.data.repository.AttachedFile("report.pdf", "/tmp/uploaded", "@file:/tmp/uploaded")
        var attempts = 0
        coEvery { chatRepo.submit("s1-live", any()) } coAnswers {
            if (attempts++ == 0) throw com.hermes.client.data.network.GatewayRpcException(5000, "temporary submit refusal")
        }
        val vm = buildVm()
        vm.open("s1")
        runCurrent()
        vm.stageAttachment(byteArrayOf(1, 2), "image/png", "photo.png")
        vm.stageAttachment("%PDF".toByteArray(), "application/pdf", "report.pdf")

        assertTrue(vm.sendVoiceText("语音问题", "已有草稿"))
        assertTrue(vm.state.value.pendingAttachments.isEmpty())
        runCurrent()
        val failed = vm.state.value.messages.last { it.role == Role.USER }
        assertEquals("已有草稿\n语音问题", failed.text)
        assertEquals(1, failed.images.size)
        assertEquals(listOf("report.pdf"), failed.files.map { it.name })
        assertEquals(com.hermes.client.domain.DeliveryState.FAILED, failed.delivery)
        assertTrue(vm.sendDiagnostic(failed.id)!!.contains("HR-SESS-007"))

        vm.retrySend(failed.id)
        runCurrent()
        coVerify(exactly = 2) { fileRepo.upload(any(), "photo.png", "image/png") }
        coVerify(exactly = 2) { fileRepo.upload(any(), "report.pdf", "application/pdf") }
        coVerify(exactly = 2) { chatRepo.attachImagePath("s1-live", "/tmp/uploaded") }
        coVerify(exactly = 2) { chatRepo.submit("s1-live", "已有草稿\n语音问题\n@file:/tmp/uploaded") }
        assertEquals(com.hermes.client.domain.DeliveryState.SENT, vm.state.value.messages.last { it.role == Role.USER }.delivery)
        events.emit(event("message.complete", "s1-live", "done"))
        runCurrent()
    }

    @Test fun empty_or_offline_voice_final_does_not_consume_the_draft_attachments() = runUnsentComposerTest {
        val vm = buildVm()
        vm.open("s1")
        runCurrent()
        vm.stageAttachment("keep".toByteArray(), "text/plain", "draft.txt")
        connectionStateFlow.value = ConnectionState.Connected
        assertFalse(vm.sendVoiceText("   ", "已有草稿"))
        connectionStateFlow.value = ConnectionState.Disconnected
        assertFalse(vm.sendVoiceText("语音", "已有草稿"))
        runCurrent()
        assertEquals(listOf("draft.txt"), vm.state.value.pendingAttachments.map { it.name })
        coVerify(exactly = 0) { fileRepo.upload(any(), any(), any()) }
        coVerify(exactly = 0) { chatRepo.submit(any(), any()) }
    }

    @Test fun voice_final_cannot_consume_attachments_when_another_client_owns_the_session() = runUnsentComposerTest {
        connectionStateFlow.value = ConnectionState.Connected
        coEvery { chatRepo.sessionAccess(any(), any(), any()) } returns
            com.hermes.client.data.repository.SessionAccess(
                state = com.hermes.client.data.repository.SessionAccessState.OWNED_ELSEWHERE,
                running = false, writable = false, ownerSurface = "desktop",
            )
        val vm = buildVm()
        vm.open("s1")
        runCurrent()
        vm.stageAttachment("keep".toByteArray(), "text/plain", "draft.txt")
        assertFalse(vm.sendVoiceText("语音", "已有草稿"))
        assertEquals(1, vm.state.value.pendingAttachments.size)
        coVerify(exactly = 0) { chatRepo.submit(any(), any()) }
    }

    // HG-65: a PDF used to go to `pdf.attach`, which rasterises every page and leaves the
    // conversation carrying megabytes of base64 that Hermes re-inlines on every read — 12.59 MiB
    // from one 37-page document, past the relay's frame ceiling on its own. It goes as a file now,
    // like every other document, and the model reads it with its tools.
    @Test fun pdf_attachment_goes_to_hermes_as_a_file_reference_not_as_page_images() = runUnsentComposerTest {
        coEvery { chatRepo.resume("s1", null) } returns "s1-live"
        coEvery { chatRepo.attachFilePath("s1-live", "/tmp/uploaded", "report.pdf") } returns
            com.hermes.client.data.repository.AttachedFile(
                name = "report.pdf",
                path = "/tmp/uploaded",
                refText = "@file:/tmp/uploaded",
            )
        val vm = buildVm()
        vm.open("s1")
        runCurrent()

        vm.stageAttachment("%PDF-1.4".toByteArray(), "application/pdf", "report.pdf")
        vm.send("这份报告里有哪些检查项目？")
        runCurrent()

        coVerify(exactly = 0) { chatRepo.attachPdfPath(any(), any()) }
        coVerify { chatRepo.attachFilePath("s1-live", "/tmp/uploaded", "report.pdf") }
        // The reference has to reach the prompt, or Hermes never expands it and the model is told
        // about a file it was never given.
        coVerify { chatRepo.submit("s1-live", "这份报告里有哪些检查项目？\n@file:/tmp/uploaded") }

        events.emit(event("message.complete", "s1-live", "done"))
        runCurrent()
    }

    @Test fun shared_pdf_is_staged_on_disk_uploaded_as_a_file_and_removed_after_send() = runUnsentComposerTest {
        val file = java.io.File.createTempFile("shared-pdf", ".pending")
        file.writeText("%PDF-1.4")
        try {
            pendingShareStore.put("s1", com.hermes.client.share.PendingShare(
                stagedFilePath = file.absolutePath,
                imageMime = "application/pdf",
                attachmentName = "shared.pdf",
            ))
            coEvery { chatRepo.resume("s1", null) } returns "s1-live"
            coEvery { chatRepo.attachFilePath("s1-live", "/tmp/uploaded", "shared.pdf") } returns
                com.hermes.client.data.repository.AttachedFile(
                    name = "shared.pdf", path = "/tmp/uploaded", refText = "@file:/tmp/uploaded",
                )
            val vm = buildVm()
            vm.open("s1")
            runCurrent()
            assertEquals(1, vm.state.value.pendingAttachments.size)
            assertTrue(vm.state.value.pendingAttachments.single().bytes.isEmpty())
            assertEquals(file.length(), vm.state.value.pendingAttachments.single().sizeBytes)

            vm.send("总结")
            runCurrent()

            coVerify { fileRepo.uploadStagedFile(file, "shared.pdf", "application/pdf") }
            coVerify { chatRepo.submit("s1-live", "总结\n@file:/tmp/uploaded") }
            assertFalse(file.exists())
            events.emit(event("message.complete", "s1-live", "done"))
            runCurrent()
        } finally {
            // runTest drains Main's virtual-time scheduler before JUnit @After can cancel these jobs.
            // Stop the app-scoped process poller here so the test can finish.
            runtimeJobs.forEach(Job::cancel)
            file.delete()
        }
    }

    @Test fun leavingAndReturningToChatKeepsItsPendingFileAndDoesNotSubmit() = runUnsentComposerTest {
        val file = java.io.File.createTempFile("hg197", ".pending").apply { writeText("unsent") }
        try {
            val runtime = newRuntimeStore()
            pendingShareStore.put("s1", com.hermes.client.share.PendingShare(stagedFilePath = file.absolutePath, imageMime = "text/plain", attachmentName = "draft.txt"))
            val first = buildVm(runtimeStore = runtime)
            first.open("s1")
            runCurrent()
            val owner = androidx.lifecycle.ViewModelStore()
            owner.put("chat", first)
            owner.clear()
            assertTrue("navigation must not delete a still-pending file", file.exists())
            val second = buildVm(runtimeStore = runtime)
            second.open("s1")
            runCurrent()
            assertEquals(listOf("draft.txt"), second.state.value.pendingAttachments.map { it.name })
            assertEquals(file, second.state.value.pendingAttachments.single().stagedFile)
            coVerify(exactly = 0) { chatRepo.submit(any(), any()) }
            second.removeAttachment(second.state.value.pendingAttachments.single().id)
            assertFalse(file.exists())
        } finally { file.delete() }
    }

    @Test fun upload_413_is_the_server_limit_error_and_preserves_the_staged_file() = runUnsentComposerTest {
        val file = java.io.File.createTempFile("upload-rejected", ".pending")
        file.writeText("document")
        try {
            coEvery { chatRepo.resume("s1", null) } returns "s1-live"
            coEvery { fileRepo.uploadStagedFile(file, "document.pdf", "application/pdf") } throws
                com.hermes.client.data.network.HermesApiException(413, "server rejected upload")
            val vm = buildVm()
            vm.open("s1")
            runCurrent()
            vm.stageAttachment(file, "application/pdf", "document.pdf")
            vm.send("阅读")
            runCurrent()

            val failed = vm.state.value.messages.last { it.role == com.hermes.client.domain.Role.USER }
            assertEquals(com.hermes.client.data.error.AppErrorCode.UPLOAD_SERVER_LIMIT, vm.sendErrorCode(failed.id))
            assertTrue(file.exists())
            coVerify(exactly = 0) { chatRepo.submit(any(), any()) }
        } finally {
            file.delete()
        }
    }

    @Test fun stale_submit_resumes_and_retries_once_with_new_handle() = runUnsentComposerTest {
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

    @Test fun send_retries_resume_when_initial_open_resume_failed() = runUnsentComposerTest {
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
    @Test fun open_with_text_only_pending_share_does_not_attach_image() = runUnsentComposerTest {
        pendingShareStore.put("s1", com.hermes.client.share.PendingShare(text = "hello"))
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()

        coVerify(exactly = 0) { chatRepo.attachImageBytes(any(), any(), any()) }
        assertEquals("hello", vm.initialDraft.value)
    }

    @Test fun save_image_to_gallery_delegates_and_reports_completion() = runUnsentComposerTest {
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
    @Test fun reconnecting_while_generating_keeps_live_snapshot() = runUnsentComposerTest {
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
    @Test fun initial_connected_does_not_double_resume() = runUnsentComposerTest {
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

    @Test fun sendAfterTransientResumeFailureUsesTheRecoveredSocketAndKeepsHistory() = runUnsentComposerTest {
        coEvery { sessionRepo.history("s1", null) } returns listOf(
            ChatMessage("h-1", Role.USER, "earlier user"),
            ChatMessage("h-2", Role.ASSISTANT, "earlier reply"),
        )
        coEvery { chatRepo.resume("s1", null) } throws
            com.hermes.client.data.network.GatewayRpcException(0, "account authorization changed") andThen "live-1"
        coEvery { chatRepo.submit("live-1", "new prompt") } returns Unit
        val vm = buildVm()

        vm.open("s1")
        advanceUntilIdle()
        assertEquals(listOf("earlier user", "earlier reply"), vm.state.value.messages.map { it.text })

        connectionStateFlow.value = ConnectionState.Connected
        runCurrent()
        vm.send("new prompt")
        runCurrent()

        coVerify(exactly = 2) { chatRepo.resume("s1", null) }
        coVerify(exactly = 1) { chatRepo.submit("live-1", "new prompt") }
        assertEquals(
            com.hermes.client.domain.DeliveryState.SENT,
            vm.state.value.messages.last { it.role == Role.USER }.delivery,
        )
        assertEquals("earlier reply", vm.state.value.messages[1].text)
        events.emit(event("message.complete", "live-1", "done"))
        advanceUntilIdle()
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
    @Test fun reaped_empty_session_is_recreated_and_the_message_is_delivered() = runUnsentComposerTest {
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
    @Test fun reaped_session_with_history_is_terminal_and_never_recreated() = runUnsentComposerTest {
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
    @Test fun retrySend_ignores_an_undeliverable_bubble() = runUnsentComposerTest {
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

    // Reclaim removes the live runtime, not the durable transcript. Resume before submitting.
    @Test fun session_reclaimed_event_recovers_without_a_doomed_submit() = runUnsentComposerTest {
        coEvery { sessionRepo.history(any(), any()) } returns listOf(
            ChatMessage(id = "h1", role = Role.USER, text = "earlier"),
        )
        coEvery { chatRepo.resume("s1", null) } returns "live-1" andThen "live-2"
        coEvery { chatRepo.submit("live-2", "hello") } returns Unit

        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()

        events.emit(event("session.reclaimed", "s1"))
        runCurrent()

        vm.send("hello")
        runCurrent()

        coVerify(exactly = 0) { chatRepo.submit("live-1", any()) }
        coVerify(exactly = 0) { chatRepo.createSession(any(), any()) }
        coVerify(exactly = 2) { chatRepo.resume("s1", null) }
        coVerify(exactly = 1) { chatRepo.submit("live-2", "hello") }
        assertEquals(com.hermes.client.domain.DeliveryState.SENT, vm.state.value.messages.last().delivery)

        events.emit(event("message.complete", "live-2", "done"))
        advanceUntilIdle()
    }

    @Test fun leaving_before_submit_settles_the_original_turn_as_unsent() = runUnsentComposerTest {
        val resume = kotlinx.coroutines.CompletableDeferred<String>()
        coEvery { chatRepo.resume("s1", null) } coAnswers { resume.await() }
        val runtime = newRuntimeStore()
        val vm = buildVm(runtimeStore = runtime)
        vm.open("s1")
        runCurrent()
        vm.send("first")
        runCurrent()
        vm.open("s2")
        runCurrent()

        val original = runtime.runtimes.value.values.single { it.key.sessionId == "s1" }
        assertEquals(com.hermes.client.domain.DeliveryState.FAILED, original.chat.messages.last().delivery)
        assertFalse(original.chat.isGenerating)
        assertEquals("first", unsent.read(draftToken)?.text)
        assertNull(unsent.read(com.hermes.client.data.repository.SessionReadStore.token(null, "s2")))
        resume.complete("live-1")
        runCurrent()
        coVerify(exactly = 0) { chatRepo.submit(any(), "first") }

        // HG-214: the unsent first turn must not hide the later run's persisted answer.
        vm.open("s1")
        runCurrent()
        vm.send("second")
        runCurrent()
        events.emit(event("message.complete", "live-1", "second answer"))
        runCurrent()
        runtime.acceptHistory(original.key, listOf(
            ChatMessage(id = "h1", role = Role.USER, text = "second", serverId = 1L),
            ChatMessage(id = "h2", role = Role.ASSISTANT, text = "second answer", serverId = 2L),
        ), Long.MAX_VALUE)
        runCurrent()
        val reconciled = runtime.runtimes.value.getValue(original.key)
        assertTrue(reconciled.chat.messages.any { it.text == "second answer" && it.serverId == 2L })
        assertEquals(com.hermes.client.domain.DeliveryState.FAILED,
            reconciled.chat.messages.single { it.text == "first" }.delivery)
        assertEquals("first", unsent.read(draftToken)?.text)
    }

    @Test fun clearing_the_chat_before_submit_preserves_an_unsent_turn() = runUnsentComposerTest {
        coEvery { chatRepo.resume("s1", null) } coAnswers { kotlinx.coroutines.awaitCancellation() }
        val runtime = newRuntimeStore()
        val vm = buildVm(runtimeStore = runtime)
        val owner = androidx.lifecycle.ViewModelStore()
        owner.put("chat", vm)
        vm.open("s1")
        runCurrent()
        vm.send("first")
        owner.clear()
        runCurrent()

        val original = runtime.runtimes.value.values.single { it.key.sessionId == "s1" }
        assertEquals(com.hermes.client.domain.DeliveryState.FAILED, original.chat.messages.last().delivery)
        assertFalse(original.chat.isGenerating)
        assertEquals("HR-SESS-007", unsent.read(draftToken)?.code)
        assertTrue(unsent.read(draftToken)!!.retryable)
        coVerify(exactly = 0) { chatRepo.submit(any(), any()) }
    }

    @Test fun waiting_for_a_live_handle_has_a_total_deadline_even_when_resume_hangs() = runUnsentComposerTest {
        coEvery { chatRepo.resume("s1", null) } coAnswers { kotlinx.coroutines.awaitCancellation() }
        val vm = buildVm()
        vm.open("s1")
        runCurrent()
        vm.send("hello")
        runCurrent()
        advanceTimeBy(25_001)
        runCurrent()
        assertEquals(com.hermes.client.domain.DeliveryState.FAILED, vm.state.value.messages.last().delivery)
        assertFalse(vm.state.value.isGenerating)
        assertEquals(com.hermes.client.data.error.AppErrorCode.MESSAGE_SEND_FAILED,
            vm.sendErrorCode(vm.state.value.messages.last().id))
        coVerify(exactly = 0) { chatRepo.submit(any(), any()) }
    }

    @Test fun clearing_during_submit_keeps_delivery_pending_until_history_confirms_it() = runUnsentComposerTest {
        coEvery { chatRepo.resume("s1", null) } returns "live-1"
        coEvery { chatRepo.submit("live-1", "hello") } coAnswers { kotlinx.coroutines.awaitCancellation() }
        val runtime = newRuntimeStore()
        val vm = buildVm(runtimeStore = runtime)
        val owner = androidx.lifecycle.ViewModelStore()
        owner.put("chat", vm)
        vm.open("s1")
        runCurrent()
        vm.send("hello")
        runCurrent()
        owner.clear()
        runCurrent()
        val key = runtime.runtimes.value.keys.single()
        assertEquals(com.hermes.client.domain.DeliveryState.UNCONFIRMED,
            runtime.runtimes.value.getValue(key).chat.messages.single().delivery)
        assertTrue(unsent.read(draftToken)?.deliveryUnconfirmed == true)
        events.emit(event("message.complete", "live-1", "answer"))
        runCurrent()
        runtime.acceptHistory(key, listOf(
            ChatMessage(id = "h1", role = Role.USER, text = "hello", serverId = 1L),
            ChatMessage(id = "h2", role = Role.ASSISTANT, text = "answer", serverId = 2L),
        ), Long.MAX_VALUE)
        assertEquals(com.hermes.client.domain.DeliveryState.SENT,
            runtime.runtimes.value.getValue(key).chat.messages.first().delivery)
        assertEquals(com.hermes.client.domain.DeliveryState.UNCONFIRMED,
            runtime.runtimes.value.getValue(key).chat.messages.last().delivery)
        assertEquals(com.hermes.client.data.progress.SessionRunPhase.COMPLETED_UNREAD,
            runtime.runtimes.value.getValue(key).phase)
        coVerify(exactly = 1) { chatRepo.submit(any(), "hello") }
    }

    @Test fun an_obsolete_reclaim_does_not_invalidate_a_newly_bound_handle() = runUnsentComposerTest {
        coEvery { chatRepo.resume("s1", null) } returns "live-2"
        val vm = buildVm()
        vm.open("s1")
        runCurrent()
        events.emit(ServerEvent("session.reclaimed", "s1", buildJsonObject {
            put("session_id", "live-1")
            put("stored_session_id", "s1")
        }))
        runCurrent()
        vm.send("hello")
        runCurrent()
        coVerify(exactly = 1) { chatRepo.resume("s1", null) }
        coVerify(exactly = 1) { chatRepo.submit("live-2", "hello") }
        events.emit(event("message.complete", "live-2", "answer"))
        runCurrent()
    }

    @Test fun cancelling_preparation_preserves_staged_attachments_across_a_cold_start() = runUnsentComposerTest {
        val file = java.io.File.createTempFile("cancelled-send", ".pending")
        file.writeText("keep this document")
        try {
            coEvery { chatRepo.resume("s1", null) } coAnswers { kotlinx.coroutines.awaitCancellation() }
            val vm = buildVm()
            val owner = androidx.lifecycle.ViewModelStore()
            owner.put("chat", vm)
            vm.open("s1")
            runCurrent()
            vm.stageAttachment(file, "text/plain", "notes.txt")
            vm.send("read")
            runCurrent()
            owner.clear()
            runCurrent()
            assertTrue(file.exists())
            assertEquals(file.absolutePath, unsent.read(draftToken)!!.attachmentMeta.single().path)

            coEvery { chatRepo.resume("s1", null) } returns "live-2"
            val restored = buildVm()
            restored.open("s1")
            runCurrent()
            val bubble = restored.state.value.messages.single { it.role == Role.USER }
            assertEquals(com.hermes.client.domain.DeliveryState.FAILED, bubble.delivery)
            restored.retrySend(bubble.id)
            runCurrent()
            coVerify(exactly = 1) { fileRepo.uploadStagedFile(file, "notes.txt", "text/plain") }
            coVerify(exactly = 1) { chatRepo.submit("live-2", any()) }
            events.emit(event("message.complete", "live-2", "done"))
            runCurrent()
        } finally {
            file.delete()
        }
    }

    @Test fun optional_access_timeout_does_not_hold_the_live_handle_gate() = runUnsentComposerTest {
        coEvery { chatRepo.sessionAccess(any(), any(), any()) } coAnswers { kotlinx.coroutines.awaitCancellation() }
        coEvery { chatRepo.resume("s1", null) } returns "live-1"
        val vm = buildVm()
        val owner = androidx.lifecycle.ViewModelStore()
        owner.put("chat", vm)
        vm.open("s1")
        runCurrent()
        vm.send("hello")
        advanceTimeBy(3_001)
        runCurrent()
        coVerify(exactly = 1) { chatRepo.submit("live-1", "hello") }
        assertEquals(com.hermes.client.domain.DeliveryState.SENT, vm.state.value.messages.last().delivery)
        events.emit(event("message.complete", "live-1", "done"))
        runCurrent()
        owner.clear()
    }

    @Test fun cancelling_an_unsent_turn_does_not_erase_an_authoritative_completion() = runUnsentComposerTest {
        coEvery { chatRepo.resume("s1", null) } coAnswers { kotlinx.coroutines.awaitCancellation() }
        val runtime = newRuntimeStore()
        val vm = buildVm(runtimeStore = runtime)
        val owner = androidx.lifecycle.ViewModelStore()
        owner.put("chat", vm)
        vm.open("s1")
        runCurrent()
        vm.send("first")
        events.emit(event("message.complete", "s1", "previous run finished"))
        runCurrent()
        owner.clear()
        runCurrent()
        val original = runtime.runtimes.value.values.single()
        assertEquals(com.hermes.client.domain.DeliveryState.FAILED,
            original.chat.messages.single { it.role == Role.USER }.delivery)
        assertEquals(com.hermes.client.data.progress.SessionRunPhase.COMPLETED_UNREAD, original.phase)
    }

    @Test fun a_submit_response_timeout_waits_for_history_without_offering_duplicate_retry() = runUnsentComposerTest {
        coEvery { chatRepo.resume("s1", null) } returns "live-1"
        coEvery { chatRepo.submit("live-1", "hello") } throws
            com.hermes.client.data.network.GatewayResponseTimeoutException("gateway response timeout")
        val runtime = newRuntimeStore()
        val vm = buildVm(runtimeStore = runtime)
        vm.open("s1")
        runCurrent()
        vm.send("hello")
        runCurrent()
        val bubble = vm.state.value.messages.single { it.role == Role.USER }
        assertEquals(com.hermes.client.domain.DeliveryState.UNCONFIRMED, bubble.delivery)
        assertEquals("HR-SESS-022", vm.sendErrorCode(bubble.id)?.value)
        assertTrue(unsent.read(draftToken)?.deliveryUnconfirmed == true)
        vm.retrySend(bubble.id)
        runCurrent()
        coVerify(exactly = 1) { chatRepo.submit(any(), "hello") }
        events.emit(event("message.complete", "live-1", "answer"))
        runCurrent()
        val key = runtime.runtimes.value.keys.single()
        runtime.acceptHistory(key, listOf(
            ChatMessage(id = "h1", role = Role.USER, text = "hello", serverId = 1L),
            ChatMessage(id = "h2", role = Role.ASSISTANT, text = "answer", serverId = 2L),
        ), Long.MAX_VALUE)
        val messages = runtime.runtimes.value.getValue(key).chat.messages
        assertEquals(2, messages.count { it.role == Role.USER })
        assertEquals(com.hermes.client.domain.DeliveryState.UNCONFIRMED, messages.single { it.id == bubble.id }.delivery)
        assertEquals(1L, messages.single { it.id == "h1" }.serverId)
    }

    // The sheet switches this session through config.set; it never changes the profile default.
    @Test fun onSelectFromSheet_switches_this_session_directly() = runUnsentComposerTest {
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()

        var onDoneCalled = false
        vm.onSelectFromSheet("anthropic", "opus") { onDoneCalled = true }
        advanceUntilIdle()

        coVerify { chatRepo.switchSessionModel("s1", "anthropic", "opus", false) }
        coVerify(exactly = 0) { chatRepo.slashExec(any(), any()) }
        coVerify(exactly = 0) { modelRepo.set(any(), any(), any()) }
        assertEquals("opus", vm.currentModel.value)
        assertTrue("success must clear any sheet error", vm.modelSheet.value.error == null)
        assertTrue("onDone must be invoked so the caller dismisses the sheet", onDoneCalled)
    }

    // 快捷切换 (docs/DESIGN.md §5.17) is fed by this: every switch that actually took effect goes
    // to the front of the device-local recents list.
    @Test fun a_successful_switch_is_recorded_for_the_quick_switch_row() = runUnsentComposerTest {
        val vm = buildVm()
        vm.open("s1"); advanceUntilIdle()

        vm.onSelectFromSheet("anthropic", "opus") {}
        advanceUntilIdle()

        coVerify(exactly = 1) { recentsStore.record("anthropic", "opus") }
    }

    // The other half: a switch that did NOT take effect must not offer itself as a shortcut.
    @Test fun a_failed_switch_is_not_recorded() = runUnsentComposerTest {
        coEvery { chatRepo.switchSessionModel("s1", any(), any(), any()) } throws
            com.hermes.client.data.network.GatewayRpcException(5000, "could not resolve credentials")
        val vm = buildVm()
        vm.open("s1"); advanceUntilIdle()

        vm.onSelectFromSheet("anthropic", "opus") {}
        advanceUntilIdle()

        coVerify(exactly = 0) { recentsStore.record(any(), any()) }
    }

    // 恢复默认 changes the model too, so the chip row has to offer the way back as well.
    @Test fun restoring_the_default_is_recorded_too() = runUnsentComposerTest {
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
    @Test fun onSelectFromSheet_failure_surfaces_sheet_error() = runUnsentComposerTest {
        coEvery { chatRepo.switchSessionModel("s1", any(), any(), any()) } throws
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

    @Test fun model_switch_timeout_is_unconfirmed_not_a_refusal() = runUnsentComposerTest {
        coEvery { chatRepo.switchSessionModel("s1", any(), any(), any()) } throws
            com.hermes.client.data.network.GatewayResponseTimeoutException("gateway response timeout")
        val vm = buildVm()
        vm.open("s1"); advanceUntilIdle()

        var onDoneCalled = false
        vm.onSelectFromSheet("anthropic", "opus") { onDoneCalled = true }
        advanceUntilIdle()

        assertEquals("HR-RPC-008", vm.modelSheet.value.error?.code?.value)
        assertFalse(vm.modelSheet.value.error?.retryable == true)
        assertFalse(onDoneCalled)
        coVerify(exactly = 0) { recentsStore.record(any(), any()) }
    }

    @Test fun malformed_switch_reply_is_unconfirmed_not_a_refusal() = runUnsentComposerTest {
        coEvery { chatRepo.switchSessionModel("s1", any(), any(), any()) } throws
            com.hermes.client.data.repository.ModelSwitchUnconfirmedException("no applied value")
        val vm = buildVm()
        vm.open("s1"); advanceUntilIdle()
        vm.onSelectFromSheet("anthropic", "opus") {}
        advanceUntilIdle()
        assertEquals("HR-RPC-008", vm.modelSheet.value.error?.code?.value)
        assertFalse(vm.modelSheet.value.error?.retryable == true)
    }

    @Test fun confirmation_does_not_claim_success_until_confirmed() = runUnsentComposerTest {
        coEvery { chatRepo.switchSessionModel("s1", "anthropic", "opus", false) } returns
            ModelSwitchResult.ConfirmationRequired
        val vm = buildVm()
        vm.open("s1"); advanceUntilIdle()
        var done = false
        vm.onSelectFromSheet("anthropic", "opus") { done = true }
        advanceUntilIdle()
        assertFalse(done)
        assertEquals("opus", vm.modelSheet.value.confirmation?.model)
        coVerify(exactly = 0) { recentsStore.record(any(), any()) }

        vm.confirmModelSelection { done = true }
        advanceUntilIdle()
        coVerify { chatRepo.switchSessionModel("s1", "anthropic", "opus", true) }
        assertTrue(done)
    }

    @Test fun deferred_switch_does_not_claim_or_record_success() = runUnsentComposerTest {
        coEvery { chatRepo.switchSessionModel("s1", any(), any(), any()) } returns ModelSwitchResult.Deferred
        val vm = buildVm()
        vm.open("s1"); advanceUntilIdle()
        var done = false
        vm.onSelectFromSheet("anthropic", "opus") { done = true }
        advanceUntilIdle()
        assertFalse(done)
        assertEquals(ChatViewModel.ModelSheetNotice.DEFERRED, vm.modelSheet.value.notice)
        assertFalse(vm.currentModel.value == "opus")
        coVerify(exactly = 0) { recentsStore.record(any(), any()) }
    }

    @Test fun successful_switch_dismisses_before_recent_write_finishes() = runUnsentComposerTest {
        coEvery { recentsStore.record("anthropic", "opus") } coAnswers {
            kotlinx.coroutines.delay(5_000)
        }
        val vm = buildVm()
        vm.open("s1"); advanceUntilIdle()
        var done = false
        vm.onSelectFromSheet("anthropic", "opus") { done = true }
        runCurrent()
        assertTrue(done)
        assertEquals("opus", vm.currentModel.value)
        advanceUntilIdle()
    }

    @Test fun applied_switch_with_warning_stays_visible() = runUnsentComposerTest {
        coEvery { chatRepo.switchSessionModel("s1", any(), any(), any()) } returns
            ModelSwitchResult.Applied("opus", "provider warning")
        val vm = buildVm()
        vm.open("s1"); advanceUntilIdle()
        var done = false
        vm.onSelectFromSheet("anthropic", "opus") { done = true }
        advanceUntilIdle()
        assertFalse(done)
        assertEquals("opus", vm.currentModel.value)
        assertEquals(ChatViewModel.ModelSheetNotice.WARNING, vm.modelSheet.value.notice)
    }

    // A second tap while a switch is in flight must be ignored — otherwise two writes race and
    // the session lands on whichever finishes last.
    @Test fun onSelectFromSheet_ignores_taps_while_pending() = runUnsentComposerTest {
        coEvery { chatRepo.switchSessionModel("s1", any(), any(), any()) } coAnswers {
            kotlinx.coroutines.delay(5_000); ModelSwitchResult.Applied(thirdArg(), null)
        }
        val vm = buildVm()
        vm.open("s1"); advanceUntilIdle()

        vm.onSelectFromSheet("anthropic", "opus") {}
        runCurrent()
        vm.onSelectFromSheet("anthropic", "sonnet") {}
        advanceUntilIdle()

        coVerify(exactly = 1) { chatRepo.switchSessionModel("s1", any(), any(), any()) }
    }

    // Chip contract: a successful SESSION switch marks the chat as overridden; "恢复默认" pins the
    // session back to the configured default and clears the override.
    @Test fun session_override_flag_sets_on_switch_and_clears_on_restore() = runUnsentComposerTest {
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
        coVerify { chatRepo.switchSessionModel("s1", "prov", "def-model", false) }
        assertFalse(vm.sessionModelOverridden.value)
        assertEquals("def-model", vm.currentModel.value)
        watcher.cancel()
    }

    // ---- Session reasoning effort ----

    // Changing effort writes a session-scoped override and remembers it as this model's preset
    // (the desktop-client behavior that makes effort FEEL global).
    @Test fun setReasoning_success_saves_per_model_preset() = runUnsentComposerTest {
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

    @Test fun setReasoning_failure_rolls_back_and_reports_code() = runUnsentComposerTest {
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
    @Test fun model_switch_applies_remembered_preset() = runUnsentComposerTest {
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
    @Test fun ensureProviders_uses_warm_cache_without_refetch() = runUnsentComposerTest {
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
    @Test fun providers_error_only_without_cache_and_retry_refetches() = runUnsentComposerTest {
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

    @Test fun selectProfile_calls_profileRepo_setActive() = runUnsentComposerTest {
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
    private fun runUnsentComposerTest(block: suspend kotlinx.coroutines.test.TestScope.() -> Unit) = runTest {
        try { block() } finally { runtimeJobs.forEach(Job::cancel) }
    }

    // HG-220: cancel preparation, keep source identity during editing, and never replay ambiguity.
    @Test fun withdrawing_pre_submit_returns_text_and_does_not_resurrect_the_bubble() = runUnsentComposerTest {
        val resume = kotlinx.coroutines.CompletableDeferred<String>()
        coEvery { chatRepo.resume("s1", null) } coAnswers { resume.await() }
        val vm = buildVm()
        vm.open("s1")
        runCurrent()
        vm.send("pending")
        runCurrent()
        val id = vm.state.value.messages.single { it.role == Role.USER }.id
        val result = vm.prepareUnsentComposer(id, "", withdraw = true)
        runCurrent()
        assertNull(result.error)
        assertEquals("pending", result.text)
        assertTrue(vm.state.value.messages.none { it.id == id })
        assertFalse(vm.state.value.isGenerating)
        assertEquals(true, unsent.peek(draftToken)?.composer?.withdrawn)
        assertNull(vm.sendDiagnostic(id))
        resume.complete("live-1")
        runCurrent()
        assertTrue(vm.state.value.messages.none { it.id == id })
        coVerify(exactly = 0) { chatRepo.submit(any(), any()) }
    }

    @Test fun editing_a_failed_send_replaces_it_only_when_the_composer_is_sent() = runUnsentComposerTest {
        coEvery { chatRepo.resume("s1", null) } returns "s1"
        coEvery { chatRepo.submit("s1", "original") } throws com.hermes.client.data.network.GatewayRpcException(5000, "refused")
        val vm = buildVm()
        vm.open("s1")
        runCurrent()
        vm.send("original")
        runCurrent()
        val id = vm.state.value.messages.single { it.role == Role.USER }.id
        assertEquals("original", vm.prepareUnsentComposer(id, "").text)
        runCurrent()
        assertEquals(id, vm.editingUnsentId.value)
        assertTrue(vm.state.value.messages.any { it.id == id })
        vm.retrySend(id)
        runCurrent()
        coVerify(exactly = 1) { chatRepo.submit("s1", "original") }
        vm.send("edited")
        runCurrent()
        assertNull(vm.editingUnsentId.value)
        assertEquals(listOf("edited"), vm.state.value.messages.filter { it.role == Role.USER }.map { it.text })
        assertNull(unsent.peek(draftToken))
        coVerify(exactly = 1) { chatRepo.submit("s1", "edited") }
    }

    @Test fun cancellation_of_edit_keeps_original_file_and_failure_record() = runUnsentComposerTest {
        coEvery { chatRepo.resume("s1", null) } returns "s1"
        val file = java.io.File.createTempFile("hg220-edit", ".txt").apply { writeText("attachment") }
        try {
            coEvery { chatRepo.submit(any(), any()) } throws com.hermes.client.data.network.GatewayRpcException(5000, "refused")
            val vm = buildVm()
            vm.open("s1")
            runCurrent()
            vm.stageAttachment(file, "text/plain", "source.txt")
            vm.send("original")
            runCurrent()
            val id = vm.state.value.messages.single { it.role == Role.USER }.id
            assertEquals("original", vm.prepareUnsentComposer(id, "").text)
            runCurrent()
            val attachment = vm.state.value.pendingAttachments.single()
            vm.removeAttachment(attachment.id)
            assertTrue("editing must not delete the original's attachment", file.isFile)
            vm.rememberDraft("changed")
            vm.cancelUnsentEdit()
            runCurrent()
            assertNull(vm.editingUnsentId.value)
            assertTrue(vm.state.value.messages.any { it.id == id && it.text == "original" })
            assertEquals("original", unsent.peek(draftToken)?.text)
            assertNull(unsent.peek(draftToken)?.composer)
            assertTrue(vm.state.value.pendingAttachments.isEmpty())
        } finally { file.delete() }
    }

    @Test fun a_submitted_prompt_cannot_be_withdrawn_or_replaced_while_awaiting_ack() = runUnsentComposerTest {
        coEvery { chatRepo.resume("s1", null) } returns "s1"
        val acknowledgement = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { chatRepo.submit("s1", "original") } coAnswers { acknowledgement.await() }
        val vm = buildVm()
        vm.open("s1")
        runCurrent()
        vm.send("original")
        runCurrent()
        val id = vm.state.value.messages.single { it.role == Role.USER }.id
        for (withdraw in listOf(false, true)) {
            val result = vm.prepareUnsentComposer(id, "", withdraw)
            assertEquals("HR-SESS-020", result.error?.code?.value)
            assertFalse(result.error!!.retryable)
            assertNull(result.text)
        }
        assertTrue(vm.state.value.messages.any { it.id == id })
        assertTrue(vm.state.value.pendingAttachments.isEmpty())
        assertNull(vm.editingUnsentId.value)
        acknowledgement.complete(Unit)
        runCurrent()
        assertEquals(com.hermes.client.domain.DeliveryState.SENT, vm.state.value.messages.single { it.id == id }.delivery)
        coVerify(exactly = 1) { chatRepo.submit("s1", "original") }
        vm.stop()
        runCurrent()
    }

    @Test fun timed_out_submission_stays_pending_when_withdraw_is_requested() = runUnsentComposerTest {
        coEvery { chatRepo.resume("s1", null) } returns "s1"
        coEvery { chatRepo.submit("s1", "original") } throws com.hermes.client.data.network.GatewayResponseTimeoutException("unconfirmed")
        val vm = buildVm()
        vm.open("s1")
        runCurrent()
        vm.send("original")
        runCurrent()
        val id = vm.state.value.messages.single { it.role == Role.USER }.id
        assertEquals("HR-SESS-020", vm.prepareUnsentComposer(id, "", withdraw = true).error?.code?.value)
        assertTrue(vm.state.value.messages.any { it.id == id })
        assertTrue(unsent.peek(draftToken)?.deliveryUnconfirmed == true)
        coVerify(exactly = 1) { chatRepo.submit("s1", "original") }
        vm.stop()
        runCurrent()
    }

    @Test fun an_existing_draft_is_not_overwritten_and_does_not_cancel_preparation() = runUnsentComposerTest {
        coEvery { chatRepo.resume("s1", null) } coAnswers { kotlinx.coroutines.awaitCancellation() }
        val vm = buildVm()
        vm.open("s1")
        runCurrent()
        vm.send("original")
        runCurrent()
        val id = vm.state.value.messages.single { it.role == Role.USER }.id
        val result = vm.prepareUnsentComposer(id, "existing draft", withdraw = true)
        assertEquals("HR-SESS-021", result.error?.code?.value)
        assertNull(result.text)
        assertEquals(com.hermes.client.domain.DeliveryState.SENDING, vm.state.value.messages.single { it.id == id }.delivery)
        assertNull(unsent.peek(draftToken))
    }

    @Test fun restored_edit_keeps_the_target_identity_and_replaces_the_old_bubble() = runUnsentComposerTest {
        coEvery { chatRepo.resume("s1", null) } returns "s1"
        unsent = com.hermes.client.data.repository.FakeUnsentSnapshot(listOf(
            com.hermes.client.data.repository.UnsentRecord(token = draftToken, messageId = "original-id", text = "original", code = "HR-SESS-007",
                composer = com.hermes.client.data.repository.UnsentComposer(text = "edited before restart")),
        ))
        val vm = buildVm()
        vm.open("s1")
        runCurrent()
        assertEquals("original-id", vm.editingUnsentId.value)
        assertEquals("edited before restart", vm.initialDraft.value)
        assertTrue(vm.state.value.messages.any { it.id == "original-id" })
        vm.send("edited before restart")
        runCurrent()
        assertEquals(listOf("edited before restart"), vm.state.value.messages.filter { it.role == Role.USER }.map { it.text })
        assertNull(unsent.peek(draftToken))
    }

    @Test fun a_withdrawn_file_restores_to_the_composer_without_restoring_its_bubble() = runUnsentComposerTest {
        coEvery { chatRepo.resume("s1", null) } returns "s1"
        val file = java.io.File.createTempFile("hg220-withdraw", ".txt").apply { writeText("attachment") }
        try {
            val attachment = com.hermes.client.data.repository.UnsentAttachment("file-id", "source.txt", "text/plain", file.absolutePath, file.length())
            unsent = com.hermes.client.data.repository.FakeUnsentSnapshot(listOf(
                com.hermes.client.data.repository.UnsentRecord(token = draftToken, messageId = "withdrawn-id", text = "original", code = "HR-SESS-007",
                    attachments = 1, attachmentMeta = listOf(attachment),
                    composer = com.hermes.client.data.repository.UnsentComposer(withdrawn = true, text = "editable", attachments = 1, attachmentMeta = listOf(attachment))),
            ))
            val vm = buildVm()
            vm.open("s1")
            runCurrent()
            assertTrue(vm.state.value.messages.none { it.id == "withdrawn-id" })
            assertEquals("editable", vm.initialDraft.value)
            assertEquals("source.txt", vm.state.value.pendingAttachments.single().name)
            vm.send("editable")
            runCurrent()
            assertNull(unsent.peek(draftToken))
            coVerify(exactly = 1) { fileRepo.uploadStagedFile(any(), "source.txt", "text/plain") }
            coVerify(exactly = 1) { chatRepo.submit(any(), match { it.startsWith("editable") }) }
        } finally { file.delete() }
    }

    @Test fun a_restored_editor_with_missing_attachments_cannot_silently_send_text_only() = runUnsentComposerTest {
        val missing = com.hermes.client.data.repository.UnsentAttachment("lost", "source.txt", "text/plain", "/missing/hg220.txt", 5)
        unsent = com.hermes.client.data.repository.FakeUnsentSnapshot(listOf(
            com.hermes.client.data.repository.UnsentRecord(token = draftToken, messageId = "old", text = "original", code = "HR-SESS-007",
                attachments = 1, attachmentMeta = listOf(missing),
                composer = com.hermes.client.data.repository.UnsentComposer(text = "edited", attachments = 1, attachmentMeta = listOf(missing))),
        ))
        val vm = buildVm()
        vm.open("s1")
        runCurrent()
        assertEquals("HR-SESS-015", vm.composerRecoveryError.value?.code?.value)
        assertFalse(vm.send("edited"))
        assertNotNull(unsent.peek(draftToken))
        coVerify(exactly = 0) { chatRepo.submit(any(), any()) }
    }

    @Test fun withdrawing_an_upload_waits_for_cancellation_and_never_attaches_or_sends_its_late_result() = runUnsentComposerTest {
        coEvery { chatRepo.resume("s1", null) } returns "s1"
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { fileRepo.uploadStagedFile(any(), any(), any()) } coAnswers {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }
            com.hermes.client.data.network.UploadedArtifact("/tmp/late", "source.txt", 10)
        }
        val file = java.io.File.createTempFile("hg220-late", ".txt").apply { writeText("attachment") }
        try {
            val vm = buildVm()
            vm.open("s1")
            runCurrent()
            vm.stageAttachment(file, "text/plain", "source.txt")
            vm.send("original")
            runCurrent()
            val id = vm.state.value.messages.single { it.role == Role.USER }.id
            var result: ChatViewModel.ComposerMessageResult? = null
            val withdrawal = launch { result = vm.prepareUnsentComposer(id, "", withdraw = true) }
            runCurrent()
            assertNull(result)
            gate.complete(Unit)
            withdrawal.join()
            runCurrent()
            assertEquals("original", result?.text)
            assertEquals("source.txt", vm.state.value.pendingAttachments.single().name)
            assertTrue(file.isFile)
            assertTrue(vm.state.value.messages.none { it.id == id })
            coVerify(exactly = 0) { chatRepo.attachFilePath(any(), any(), any()) }
            coVerify(exactly = 0) { chatRepo.submit(any(), any()) }
        } finally { file.delete() }
    }

    @Test fun an_attachment_already_staged_on_the_mac_cannot_be_claimed_as_withdrawn() = runUnsentComposerTest {
        coEvery { chatRepo.resume("s1", null) } returns "s1"
        coEvery { chatRepo.attachFilePath(any(), any(), any()) } coAnswers { kotlinx.coroutines.awaitCancellation() }
        val file = java.io.File.createTempFile("hg220-remote", ".txt").apply { writeText("attachment") }
        try {
            val vm = buildVm()
            vm.open("s1")
            runCurrent()
            vm.stageAttachment(file, "text/plain", "source.txt")
            vm.send("original")
            runCurrent()
            val id = vm.state.value.messages.single { it.role == Role.USER }.id
            assertEquals("HR-SESS-020", vm.prepareUnsentComposer(id, "", withdraw = true).error?.code?.value)
            assertTrue(vm.state.value.messages.any { it.id == id })
            coVerify(exactly = 0) { chatRepo.submit(any(), any()) }
        } finally { file.delete() }
    }

    @Test fun editing_one_failed_message_preserves_other_messages_and_their_restart_records() = runUnsentComposerTest {
        coEvery { chatRepo.resume("s1", null) } returns "s1"
        unsent = com.hermes.client.data.repository.FakeUnsentSnapshot(listOf(
            com.hermes.client.data.repository.UnsentRecord(token = draftToken, messageId = "old", text = "old source", code = "HR-SESS-007", updatedAt = 1),
            com.hermes.client.data.repository.UnsentRecord(token = draftToken, messageId = "other", text = "other source", code = "HR-SESS-007", updatedAt = 2),
        ))
        val vm = buildVm()
        vm.open("s1")
        runCurrent()
        assertEquals("old source", vm.prepareUnsentComposer("old", "").text)
        vm.send("replacement")
        runCurrent()
        assertEquals(setOf("other source", "replacement"), vm.state.value.messages.filter { it.role == Role.USER }.map { it.text }.toSet())
        assertEquals(listOf("other"), unsent.readAll(draftToken).map { it.messageId })
    }

    @Test fun editing_a_sent_message_copies_text_without_replacing_history_or_stealing_an_unsent_edit() = runUnsentComposerTest {
        coEvery { chatRepo.resume("s1", null) } returns "s1"
        coEvery { sessionRepo.history("s1", null) } returns listOf(ChatMessage("sent", Role.USER, "sent source", serverId = 1L))
        unsent = com.hermes.client.data.repository.FakeUnsentSnapshot(listOf(
            com.hermes.client.data.repository.UnsentRecord(token = draftToken, messageId = "failed", text = "failed source", code = "HR-SESS-007"),
        ))
        val vm = buildVm()
        vm.open("s1")
        runCurrent()
        val sentId = vm.state.value.messages.first { it.text == "sent source" }.id
        assertEquals("sent source", vm.prepareUnsentComposer(sentId, "").text)
        assertNull(vm.editingUnsentId.value)
        assertEquals("failed source", vm.prepareUnsentComposer("failed", "").text)
        assertEquals("HR-SESS-021", vm.prepareUnsentComposer(sentId, "typed edit").error?.code?.value)
        assertEquals("failed", vm.editingUnsentId.value)
        assertTrue(vm.state.value.messages.any { it.id == sentId })
    }

    @Test fun post_submit_disconnect_is_unknown_and_cannot_be_replayed_or_withdrawn() = runUnsentComposerTest {
        coEvery { chatRepo.resume("s1", null) } returns "s1"
        coEvery { chatRepo.submit("s1", "original") } throws com.hermes.client.data.network.GatewayRpcException(0, "socket disconnected")
        val vm = buildVm()
        vm.open("s1"); runCurrent()
        vm.send("original"); runCurrent()
        val message = vm.state.value.messages.single { it.role == Role.USER }
        assertEquals(com.hermes.client.domain.DeliveryState.UNCONFIRMED, message.delivery)
        assertTrue(unsent.peek(draftToken)?.deliveryUnconfirmed == true)
        assertEquals("HR-SESS-020", vm.prepareUnsentComposer(message.id, "", withdraw = true).error?.code?.value)
        vm.retrySend(message.id); vm.checkMessageDelivery(message.id); runCurrent()
        coVerify(exactly = 1) { chatRepo.submit("s1", "original") }
        vm.stop(); runCurrent()
    }

    @Test fun a_restored_unknown_outcome_never_becomes_an_unsent_retry() = runUnsentComposerTest {
        unsent = com.hermes.client.data.repository.FakeUnsentSnapshot(listOf(
            com.hermes.client.data.repository.UnsentRecord(token = draftToken, messageId = "unknown-id",
                text = "original", code = "HR-SESS-022", retryable = false, deliveryUnconfirmed = true),
        ))
        val vm = buildVm()
        vm.open("s1"); runCurrent()
        assertEquals(com.hermes.client.domain.DeliveryState.UNCONFIRMED,
            vm.state.value.messages.single { it.id == "unknown-id" }.delivery)
        vm.retrySend("unknown-id"); runCurrent()
        assertEquals("HR-SESS-020", vm.prepareUnsentComposer("unknown-id", "", withdraw = true).error?.code?.value)
        coVerify(exactly = 0) { chatRepo.submit(any(), any()) }
    }

    @Test fun a_request_that_never_entered_the_socket_queue_remains_editable() = runUnsentComposerTest {
        coEvery { chatRepo.resume("s1", null) } returns "s1"
        coEvery { chatRepo.submit("s1", "original") } throws com.hermes.client.data.network.GatewayRequestNotSentException("not connected")
        val vm = buildVm()
        vm.open("s1"); runCurrent()
        vm.send("original"); runCurrent()
        val message = vm.state.value.messages.single { it.role == Role.USER }
        assertEquals(com.hermes.client.domain.DeliveryState.FAILED, message.delivery)
        assertFalse(unsent.peek(draftToken)?.deliveryUnconfirmed == true)
        assertEquals("original", vm.prepareUnsentComposer(message.id, "").text)
    }

    @Test fun an_explicit_refusal_after_safe_handle_recovery_is_still_retryable() = runUnsentComposerTest {
        coEvery { chatRepo.resume("s1", null) } returnsMany listOf("live-1", "live-2")
        coEvery { chatRepo.submit("live-1", "hello") } throws com.hermes.client.data.network.GatewayRpcException(4001, "session not found")
        coEvery { chatRepo.submit("live-2", "hello") } throws com.hermes.client.data.network.GatewayRpcException(4090, "busy")
        val vm = buildVm()
        vm.open("s1"); runCurrent()
        vm.send("hello"); runCurrent()
        val message = vm.state.value.messages.single { it.role == Role.USER }
        assertEquals(com.hermes.client.domain.DeliveryState.FAILED, message.delivery)
        assertFalse(unsent.peek(draftToken)?.deliveryUnconfirmed == true)
        assertEquals("hello", vm.prepareUnsentComposer(message.id, "").text)
        coVerify(exactly = 1) { chatRepo.submit("live-2", "hello") }
    }

}
