package com.hermes.client.data.repository

import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.data.network.MessageDto
import com.hermes.client.data.network.ProfileSessionsDto
import com.hermes.client.data.network.SessionDto
import com.hermes.client.domain.Role
import com.hermes.client.data.auth.AccountRoutingContext
import com.hermes.client.data.auth.AccountSessionManager
import com.hermes.client.data.auth.ConversationDeviceStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRepositoryTest {
    private val rest = mockk<HermesRestApi>()
    // Supervisor + Unconfined mirrors the injected app scope: a failed fetch must not take the
    // scope down with it, and the shared work runs eagerly so the coalescing is deterministic.
    private val repo = SessionRepository(rest, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

    private fun dto(id: String, source: String?, msgs: Int, archived: Boolean = false) =
        SessionDto(sessionId = id, source = source, messageCount = msgs, archived = archived, profile = "personal")

    // Parity with upstream's human pickers: the list hides scheduled/internal automation and every
    // messaging-platform source, plus empty (0-message) sessions. Local sources (tui/cli/…), the
    // app's own hermes-dispatch sessions, and unknown/null sources are kept.
    @Test fun listAllProfiles_hides_excluded_sources_and_empty_sessions() = runTest {
        assertFalse(repo.hasLoadedAllProfiles())
        coEvery { rest.profileSessions(any(), false) } returns ProfileSessionsDto(
            sessions = listOf(
                dto("keep-tui", "tui", 5),
                dto("hide-cron", "cron", 12),           // cron → hidden
                dto("hide-subagent", "subagent", 8),    // subagent → hidden
                dto("hide-tool", "tool", 4),            // tool → hidden
                dto("hide-kanban", "kanban", 5),        // worker → hidden
                dto("hide-oneshot", "oneshot", 7),      // finite automation → hidden (HG-84)
                dto("hide-telegram", "telegram", 6),    // messaging → hidden
                dto("hide-empty", "tui", 0),            // 0 messages → hidden
                dto("keep-dispatch", "hermes-dispatch", 2),
                dto("keep-null-source", null, 3),       // unknown/null source → kept
            ),
        )
        assertEquals(
            listOf("keep-tui", "keep-dispatch", "keep-null-source"),
            repo.listAllProfiles().map { it.id },
        )
        assertTrue(repo.hasLoadedAllProfiles())
    }

    /**
     * HG-103: the notification coordinator recounts the launcher badge, and persists the set for
     * push-woken processes, off this flow — so it must carry every listed session, bots included,
     * and only after a real list load.
     */
    @Test fun a_list_load_publishes_the_read_tokens_of_every_listed_session() = runTest {
        assertNull(repo.loadedSessionTokens.value)
        coEvery { rest.profileSessions(any(), false) } returns ProfileSessionsDto(
            sessions = listOf(dto("chat", "tui", 3), dto("bot", "telegram", 2), dto("empty", "tui", 0)),
        )
        repo.botSessions()
        assertNull(repo.loadedSessionTokens.value)

        repo.listAllProfiles()
        assertEquals(
            setOf(SessionReadStore.token("personal", "chat"), SessionReadStore.token("personal", "bot")),
            repo.loadedSessionTokens.value,
        )
        assertEquals(repo.cachedSessionTokens(), repo.loadedSessionTokens.value)
    }

    /**
     * The bug this fixes: [SessionRepository.cachedSession] read a cache that [listAllProfiles]
     * had already filtered messaging sources out of, so every bot conversation resolved to null.
     * The chat screen then opened with no title and — worse — wrote the profile's default model
     * into the session as though it were the model that had answered on the other app.
     */
    @Test fun a_bot_session_is_resolvable_by_id_after_the_bots_list_was_read() = runTest {
        coEvery { rest.profileSessions(any(), false) } returns ProfileSessionsDto(
            listOf(dto("local-1", "cli", 3), dto("bot-1", "dingtalk", 5)),
        )
        repo.botSessions()

        val row = repo.cachedSession("bot-1")
        assertEquals("dingtalk", row?.source)
    }

    /** …and the Chats list must NOT gain those rows as a side effect of that cache being shared. */
    @Test fun the_chats_list_still_excludes_messaging_sources_after_the_bots_list_was_read() = runTest {
        coEvery { rest.profileSessions(any(), false) } returns ProfileSessionsDto(
            listOf(dto("local-1", "cli", 3), dto("bot-1", "dingtalk", 5)),
        )
        repo.botSessions()

        assertEquals(listOf("local-1"), repo.cachedAllProfiles().map { it.id })
    }

    /**
     * The Chats screen distinguishes "loaded and empty" from "not fetched yet" with this flag.
     * Reading the Bots list fills the same cache but answers a different question, so it must not
     * satisfy that gate — otherwise a Bots-first launch shows an empty Chats list as final.
     */
    @Test fun reading_only_the_bots_list_does_not_count_as_having_loaded_the_chats_list() = runTest {
        coEvery { rest.profileSessions(any(), false) } returns ProfileSessionsDto(
            listOf(dto("bot-1", "dingtalk", 5)),
        )
        repo.botSessions()
        assertFalse(repo.hasLoadedAllProfiles())

        repo.listAllProfiles()
        assertTrue(repo.hasLoadedAllProfiles())
    }

    @Test fun sessionMeta_answers_from_cache_without_a_second_round_trip() = runTest {
        coEvery { rest.profileSessions(any(), false) } returns ProfileSessionsDto(
            listOf(dto("bot-1", "dingtalk", 5)),
        )
        repo.botSessions()

        assertEquals("dingtalk", repo.sessionMeta("bot-1")?.source)
        coVerify(exactly = 1) { rest.profileSessions(any(), false) }
    }

    @Test fun sessionMeta_fetches_when_the_cache_is_cold_and_returns_null_for_an_unknown_id() = runTest {
        coEvery { rest.profileSessions(any(), false) } returns ProfileSessionsDto(
            listOf(dto("bot-1", "dingtalk", 5)),
        )

        assertEquals("dingtalk", repo.sessionMeta("bot-1")?.source)
        assertEquals(null, repo.sessionMeta("nobody"))
    }

    @Test fun archivedAllProfiles_also_hides_internal_sources_and_empty() = runTest {
        coEvery { rest.profileSessions(any(), true) } returns ProfileSessionsDto(
            sessions = listOf(
                dto("a-keep", "cli", 3, archived = true),
                dto("a-cron", "cron", 9, archived = true),
                dto("a-kanban", "kanban", 9, archived = true),
                dto("a-oneshot", "oneshot", 9, archived = true),
                dto("a-empty", "tui", 0, archived = true),
            ),
        )
        assertEquals(listOf("a-keep"), repo.archivedAllProfiles().map { it.id })
    }

    @Test fun history_removes_every_internal_tool_payload_shape_at_data_boundary() = runTest {
        rest.stubTranscript("session-1", "default", listOf(
            MessageDto(1, "user", "请检查环境"),
            MessageDto(2, "tool", "<untrusted_tool_result source=\"web_search\">raw</untrusted_tool_result>"),
            MessageDto(3, "tool", "{\"output\":\"health=200\",\"exit_code\":0}"),
            MessageDto(4, "function", "table\\n| host | port |"),
            MessageDto(5, "tool_result", "{\"success\":true,\"content\":\"skill body\"}"),
            MessageDto(6, "tool_call", "internal call arguments"),
            MessageDto(7, "assistant", "环境检查完成。"),
        ))

        val history = repo.history("session-1", "default")

        assertEquals(listOf(Role.USER, Role.ASSISTANT), history.map { it.role })
        assertEquals(listOf("请检查环境", "环境检查完成。"), history.map { it.text })
        assertEquals(listOf("h-0-1", "h-1-7"), history.map { it.id })
    }

    @Test fun history_keeps_non_tool_system_notices() = runTest {
        rest.stubTranscript("session-2", null, listOf(
            MessageDto(1, "system", "会话已恢复"),
        ))

        val history = repo.history("session-2")

        assertEquals(1, history.size)
        assertEquals(Role.SYSTEM, history.single().role)
        assertEquals("会话已恢复", history.single().text)
    }

    // Chat open, history reconciliation, foreground recovery and the startup coordinator all ask
    // for the same transcript when a reconnect wakes them together. Measured on 2026-09-03, that
    // downloaded one 0.5 MB conversation seven times in five seconds. Concurrent callers must
    // share a single round trip.
    @Test fun concurrent_history_fetches_share_one_round_trip() = runTest {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val rows = listOf(MessageDto(1, "user", "开始"))
        val payload = payloadFor(rows)
        coEvery { rest.messagesRaw("session-3", "default", any(), any(), any(), any(), any()) } coAnswers {
            calls += 1
            if (calls == 1) release.await()
            payload
        }
        every { rest.parseMessages(any()) } returns rows

        val first = async(Dispatchers.Unconfined) { repo.history("session-3", "default") }
        val second = async(Dispatchers.Unconfined) { repo.history("session-3", "default") }
        assertEquals("the second caller must join the in-flight fetch", 1, calls)

        release.complete(Unit)
        assertEquals(listOf("开始"), first.await().map { it.text })
        assertEquals(listOf("开始"), second.await().map { it.text })

        // Sequential fetches still hit the network: the reconciliation ladder re-reads REST to
        // wait out a turn Hermes has not committed yet, so coalescing must not become a cache.
        repo.history("session-3", "default")
        assertEquals(2, calls)
    }

    @Test fun concurrent_session_list_fetches_share_one_round_trip() = runTest {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        coEvery { rest.profileSessions(any(), false) } coAnswers {
            calls += 1
            if (calls == 1) release.await()
            ProfileSessionsDto(sessions = listOf(dto("keep-tui", "tui", 5)))
        }

        val first = async(Dispatchers.Unconfined) { repo.listAllProfiles() }
        val second = async(Dispatchers.Unconfined) { repo.listAllProfiles() }
        assertEquals(1, calls)

        release.complete(Unit)
        assertEquals(listOf("keep-tui"), first.await().map { it.id })
        assertEquals(listOf("keep-tui"), second.await().map { it.id })
    }

    @Test fun account_session_rows_are_bound_to_the_mac_that_returned_them() = runTest {
        val manager = mockk<AccountSessionManager>()
        val affinity = mockk<ConversationDeviceStore>(relaxed = true)
        var route = AccountRoutingContext("account-1", "mac-1")
        every { manager.routingContext() } answers { route }
        val accountRepo = SessionRepository(
            rest = rest,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            accountSessions = manager,
            conversationDevices = affinity,
        )
        coEvery { rest.profileSessions(any(), false, "mac-1") } returns ProfileSessionsDto(
            sessions = listOf(dto("session-1", "tui", 2)),
        )
        coEvery { rest.profileSessions(any(), false, "mac-2") } returns ProfileSessionsDto(
            sessions = listOf(dto("session-1", "tui", 2)),
        )

        val first = accountRepo.listAllProfiles().single()
        assertEquals("mac-1", first.deviceId)
        verify { affinity.bind("account-1", "personal", "session-1", "mac-1") }

        route = AccountRoutingContext("account-1", "mac-2")
        assertTrue(accountRepo.cachedAllProfiles().isEmpty())
        val second = accountRepo.listAllProfiles().single()
        assertEquals("mac-2", second.deviceId)
    }

    @Test fun history_uses_its_conversation_device_instead_of_the_current_default() = runTest {
        val manager = mockk<AccountSessionManager>()
        every { manager.routingContext() } returns AccountRoutingContext("account-1", "mac-default")
        val accountRepo = SessionRepository(
            rest = rest,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            accountSessions = manager,
            conversationDevices = mockk(relaxed = true),
        )
        coEvery { rest.messagesRaw("session-2", "personal", "mac-history", any(), any(), any(), any()) } returns
            """{"messages":[{"id":1,"role":"assistant","content":"from historical Mac"}]}"""
        every { rest.parseMessages(any()) } returns listOf(
            MessageDto(1, "assistant", "from historical Mac"),
        )

        val history = accountRepo.history("session-2", "personal", "mac-history")

        assertEquals("from historical Mac", history.single().text)
    }

    @Test fun delete_routes_to_the_row_mac_and_removes_its_persisted_affinity() = runTest {
        val manager = mockk<AccountSessionManager>()
        val affinity = mockk<ConversationDeviceStore>(relaxed = true)
        every { manager.session } returns MutableStateFlow(
            com.hermes.client.data.auth.AccountSession(
                baseUrl = "https://gateway.example",
                accountId = "account-1",
                installationId = "install-1",
                installationDisplayName = "Pixel",
                accessToken = "access",
                accessExpiresAt = "2099-01-01T00:00:00Z",
                refreshToken = "refresh",
                refreshExpiresAt = "2099-02-01T00:00:00Z",
                selectedDeviceId = "mac-default",
            ),
        )
        val accountRepo = SessionRepository(
            rest = rest,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            accountSessions = manager,
            conversationDevices = affinity,
        )
        coEvery {
            rest.deleteSession("session-2", "personal", "mac-history", any(), any())
        } returns Unit

        accountRepo.delete("session-2", "personal", "mac-history")

        coVerify { rest.deleteSession("session-2", "personal", "mac-history", any(), any()) }
        verify { affinity.remove("account-1", "personal", "session-2") }
    }

    // ── HG-250: the mutation fence Hermes ≥ 0.22.5 (2026-10-16) requires on every write ─────

    private fun revisionDto(id: String, revision: Int?) = SessionDto(
        sessionId = id, source = "tui", messageCount = 2, profile = "personal",
        runtimeRevision = revision,
    )

    @Test fun archive_sends_the_row_revision_and_a_fresh_request_id() = runTest {
        coEvery { rest.profileSessions(any(), false) } returns
            ProfileSessionsDto(sessions = listOf(revisionDto("s1", 5)))
        repo.listAllProfiles() // warm the cache the fence is read from
        coEvery {
            rest.patchSession("s1", archived = true, profile = "personal", deviceId = null,
                requestId = any(), expectedRevision = any())
        } returns Unit

        repo.archive("s1", archived = true, "personal")

        coVerify {
            rest.patchSession("s1", archived = true, profile = "personal", deviceId = null,
                requestId = any(), expectedRevision = 5)
        }
    }

    @Test fun missing_revision_falls_back_to_zero_for_older_hermes() = runTest {
        coEvery { rest.profileSessions(any(), false) } returns
            ProfileSessionsDto(sessions = listOf(revisionDto("s1", null)))
        repo.listAllProfiles()
        coEvery {
            rest.patchSession("s1", title = "new title", profile = "personal", deviceId = null,
                requestId = any(), expectedRevision = any())
        } returns Unit

        repo.rename("s1", "new title", "personal")

        coVerify {
            rest.patchSession("s1", title = "new title", profile = "personal", deviceId = null,
                requestId = any(), expectedRevision = 0)
        }
    }

    @Test fun revision_conflict_retries_once_with_the_re_read_revision() = runTest {
        coEvery { rest.profileSessions(any(), false) } returns
            ProfileSessionsDto(sessions = listOf(revisionDto("s1", 3)))
        repo.listAllProfiles()

        // The other client's write lands between our read and our PATCH; the re-read sees it.
        var calls = 0
        coEvery {
            rest.patchSession("s1", archived = true, profile = "personal", deviceId = null,
                requestId = any(), expectedRevision = any())
        } answers {
            calls += 1
            if (calls == 1) throw com.hermes.client.data.network.HermesApiException(409, "revision_conflict")
        }
        coEvery { rest.profileSessions(any(), false) } returns
            ProfileSessionsDto(sessions = listOf(revisionDto("s1", 4)))

        repo.archive("s1", archived = true, "personal")

        coVerify(exactly = 2) {
            rest.patchSession("s1", archived = true, profile = "personal", deviceId = null,
                requestId = any(), expectedRevision = any())
        }
        coVerify(atLeast = 1) {
            rest.patchSession("s1", archived = true, profile = "personal", deviceId = null,
                requestId = any(), expectedRevision = 4)
        }
    }

    @Test fun non_conflict_failures_are_not_retried() = runTest {
        coEvery { rest.profileSessions(any(), false) } returns
            ProfileSessionsDto(sessions = listOf(revisionDto("s1", 1)))
        repo.listAllProfiles()

        coEvery {
            rest.patchSession("s1", archived = true, profile = "personal", deviceId = null,
                requestId = any(), expectedRevision = any())
        } throws com.hermes.client.data.network.HermesApiException(404, "not_found")

        var caught: Throwable? = null
        try {
            repo.archive("s1", archived = true, "personal")
        } catch (expected: com.hermes.client.data.network.HermesApiException) {
            caught = expected
        }
        assertNotNull("the 404 must surface to the caller", caught)
        coVerify(exactly = 1) {
            rest.patchSession(any(), any(), any(), any(), any(), any(), any())
        }
    }
}
