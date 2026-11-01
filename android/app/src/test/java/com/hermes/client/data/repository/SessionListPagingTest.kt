package com.hermes.client.data.repository

import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.data.network.testAccountConnection
import com.hermes.client.data.network.testHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.junit4.MockWebServerRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercises the real HTTP query and repository together, including an old phone-local pin. */
class SessionListPagingTest {
    @get:Rule val serverRule = MockWebServerRule()
    private fun repo() = SessionRepository(
        HermesRestApi(testHttpClient(), Json { ignoreUnknownKeys = true }) {
            testAccountConnection(serverRule.server.url("/").toString().trimEnd('/'))
        }, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )
    private fun row(id: String, profile: String = "default", source: String = "desktop", archived: Boolean = false) =
        """{"id":"$id","profile":"$profile","source":"$source","message_count":3,"archived":$archived}"""
    private fun page(rows: List<String>, total: Int, offset: Int = 0) {
        serverRule.server.enqueue(MockResponse.Builder().code(200).body(
            """{"sessions":[${rows.joinToString(",")}],"total":$total,"offset":$offset,"limit":500,"errors":[]}""",
        ).build())
    }

    @Test fun excludes_internal_and_empty_rows_before_the_server_limits_the_page() = runTest {
        page(listOf(row("ordinary")), 1)
        assertEquals("ordinary", repo().listAllProfiles().single().id)
        val url = serverRule.server.takeRequest().url
        assertEquals("1", url.queryParameter("min_messages"))
        assertTrue(url.queryParameter("exclude_sources").orEmpty().split(',').containsAll(
            listOf("cron", "subagent", "tool", "kanban", "oneshot"),
        ))
    }

    @Test fun reads_beyond_500_and_restores_a_local_pin_on_the_later_page_after_cold_start() = runTest {
        val oldPin = setOf(PinStore.token("default", "old-pinned"))
        repeat(2) {
            page((0 until 500).map { row("s$it") }, 501)
            page(listOf(row("old-pinned")), 501, 500)
            val rows = repo().listAllProfiles()
            assertEquals(501, rows.size)
            assertEquals(listOf("old-pinned"), rows.filter {
                PinStore.token(it.profile, it.id, it.deviceId) in oldPin
            }.map { it.id })
            assertEquals("0", serverRule.server.takeRequest().url.queryParameter("offset"))
            assertEquals("500", serverRule.server.takeRequest().url.queryParameter("offset"))
        }
    }

    @Test fun a_failed_later_page_does_not_replace_a_previously_complete_cache() = runTest {
        val repo = repo()
        page(listOf(row("previous")), 1)
        repo.listAllProfiles()
        page((0 until 500).map { row("new$it") }, 501)
        serverRule.server.enqueue(MockResponse.Builder().code(503).body("unavailable").build())
        assertTrue(runCatching { repo.listAllProfiles() }.isFailure)
        assertEquals(listOf("previous"), repo.cachedAllProfiles().map { it.id })
    }

    @Test fun server_pin_backfill_does_not_skip_the_next_window_or_duplicate_rows() = runTest {
        page((0 until 500).map { row("s$it") } + row("backfilled-pin"), 502)
        page(listOf(row("last-ordinary"), row("backfilled-pin")), 502, 500)
        val result = repo().listAllProfiles()
        assertEquals(502, result.size)
        assertTrue(result.any { it.id == "last-ordinary" })
        serverRule.server.takeRequest()
        assertEquals("500", serverRule.server.takeRequest().url.queryParameter("offset"))
    }

    @Test fun profile_identity_and_bot_metadata_survive_the_complete_shared_live_cache() = runTest {
        page(listOf(row("same", "default"), row("same", "work"), row("bot", source = "dingtalk")), 3)
        val repo = repo()
        assertEquals(setOf("default", "work"), repo.listAllProfiles().map { it.profile }.toSet())
        assertEquals("dingtalk", repo.sessionMeta("bot", "default")?.source)
        assertEquals(3, repo.loadedSessionTokens.value!!.size)
    }

    @Test fun bots_keep_channels_while_archive_excludes_them_before_paging() = runTest {
        val repo = repo()
        page(listOf(row("bot", source = "dingtalk")), 1)
        assertEquals("bot", repo.botSessions().single().id)
        assertFalse(serverRule.server.takeRequest().url.queryParameter("exclude_sources")!!.contains("dingtalk"))
        page((0 until 500).map { row("a$it", archived = true) }, 501)
        page(listOf(row("old-archived", archived = true), row("live-server-pin")), 501, 500)
        assertEquals(501, repo.archivedAllProfiles().size)
        repeat(2) {
            val url = serverRule.server.takeRequest().url
            assertEquals("only", url.queryParameter("archived"))
            assertTrue(url.queryParameter("exclude_sources")!!.contains("dingtalk"))
        }
    }

    @Test fun profile_errors_reject_partial_success_instead_of_erasing_cached_chats() = runTest {
        val repo = repo()
        page(listOf(row("previous")), 1)
        repo.listAllProfiles()
        serverRule.server.enqueue(MockResponse.Builder().code(200).body(
            """{"sessions":[],"errors":[{"profile":"default","error":"unavailable"}]}""",
        ).build())
        assertTrue(runCatching { repo.listAllProfiles() }.isFailure)
        assertEquals("previous", repo.cachedAllProfiles().single().id)
    }

    @Test fun an_ignored_offset_is_a_failure_not_a_successful_truncated_list() = runTest {
        val rows = (0 until 500).map { row("s$it") }
        page(rows, 501)
        page(rows, 501, 0)
        val repo = repo()
        assertTrue(runCatching { repo.listAllProfiles() }.isFailure)
        assertFalse(repo.hasLoadedAllProfiles())
    }

    @Test fun a_short_page_finishes_even_when_total_includes_hidden_rows() = runTest {
        page(listOf(row("visible")), 1000)
        assertEquals("visible", repo().listAllProfiles().single().id)
        assertEquals(1, serverRule.server.requestCount)
    }
}
