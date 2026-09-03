package com.hermes.client.ui.sessions

import androidx.lifecycle.SavedStateHandle
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.data.network.SearchResultDto
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.RecentSearchesStore
import com.hermes.client.data.repository.SessionRepository
import com.hermes.client.domain.Session
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val sessionRepo = mockk<SessionRepository>(relaxed = true)
    private val profileManager = mockk<ProfileManager>(relaxed = true)
    private val projectPrefs = mockk<com.hermes.client.data.repository.ProjectPrefsStore>(relaxed = true).also {
        every { it.defaultProjectPath } returns MutableStateFlow<String?>(null)
    }
    private val recentFlow = MutableStateFlow<List<String>>(emptyList())
    private val recentStore = mockk<RecentSearchesStore>(relaxed = true).also {
        every { it.recent(any()) } returns recentFlow
    }

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        every { profileManager.active } returns MutableStateFlow<String?>("personal")
        every { sessionRepo.cachedAllProfiles() } returns emptyList()
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun session(id: String, title: String, profile: String = "personal", cwd: String? = null, lastActive: Long? = null) = Session(
        id = id, title = title, model = null, provider = null,
        messageCount = 1, profile = profile, workspace = "No workspace", source = "tui",
        cwd = cwd, lastActive = lastActive,
    )

    private fun vm(saved: SavedStateHandle = SavedStateHandle()) =
        SearchViewModel(saved, sessionRepo, profileManager, projectPrefs, recentStore)

    private val debounce = SearchViewModel.DEBOUNCE_MS + 50

    // Scope rule: title matches cover the ACTIVE profile only — live AND archived — and the same
    // rule (title OR project label) applies to both lists.
    @Test fun title_matches_scope_to_active_profile_include_archived_and_match_project_label() = runTest {
        coEvery { sessionRepo.listAllProfiles() } returns listOf(
            session("a", "Fix APK signing"),
            session("b", "Fix roof", profile = "work"),
            session("d", "Untitled", cwd = "/Users/me/signing-tools"),
        )
        coEvery { sessionRepo.archivedAllProfiles() } returns listOf(
            session("c", "Old signing debug"),
            session("e", "Untitled", cwd = "/Users/me/signing-tools"),
        )
        val vm = vm()
        advanceUntilIdle()
        vm.onQueryChange("signing")
        val m = vm.state.value.titleMatches
        assertEquals(listOf("a", "d", "c", "e"), m.map { it.session.id })
        assertTrue(m.first { it.session.id == "c" }.archived)
    }

    // First frame: titles come from the repository cache before the network refresh lands.
    @Test fun first_frame_uses_cached_sessions() = runTest {
        every { sessionRepo.cachedAllProfiles() } returns listOf(session("cached", "Cached hit"))
        coEvery { sessionRepo.listAllProfiles() } returns listOf(session("fresh", "Fresh hit"))
        coEvery { sessionRepo.archivedAllProfiles() } returns emptyList()
        val vm = vm()
        vm.onQueryChange("hit")
        assertEquals(listOf("cached"), vm.state.value.titleMatches.map { it.session.id })
        advanceUntilIdle()
        assertEquals(listOf("fresh"), vm.state.value.titleMatches.map { it.session.id })
    }

    // D1: message search runs by itself after the debounce, once, for the settled query.
    @Test fun message_search_runs_after_debounce_once() = runTest {
        coEvery { sessionRepo.listAllProfiles() } returns emptyList()
        coEvery { sessionRepo.archivedAllProfiles() } returns emptyList()
        coEvery { sessionRepo.search("hello", "personal") } returns listOf(
            SearchResultDto(sessionId = "s9", snippet = "well hello there", title = "Greeting", lastActive = 1700000000.0),
        )
        val vm = vm()
        advanceUntilIdle()
        vm.onQueryChange("h")
        assertEquals(MessageSearch.Idle, vm.state.value.messages)
        vm.onQueryChange("he")
        vm.onQueryChange("hel")
        vm.onQueryChange("hello")
        assertEquals(MessageSearch.Pending, vm.state.value.messages)
        advanceTimeBy(debounce)
        advanceUntilIdle()
        val results = vm.state.value.messages as MessageSearch.Results
        assertEquals("hello", results.query)
        assertEquals(listOf("s9"), results.hits.map { it.sessionId })
        assertEquals("Greeting", results.hits[0].title)
        assertEquals(1700000000000L, results.hits[0].lastActiveMs)
        coVerify(exactly = 1) { sessionRepo.search(any(), any()) }
        coVerify { recentStore.push("personal", "hello") }
    }

    // F2: editing the query drops the previous query's results immediately.
    @Test fun editing_query_invalidates_old_results() = runTest {
        coEvery { sessionRepo.listAllProfiles() } returns emptyList()
        coEvery { sessionRepo.archivedAllProfiles() } returns emptyList()
        coEvery { sessionRepo.search("deploy", "personal") } returns listOf(SearchResultDto(sessionId = "s1", snippet = "deploy now"))
        coEvery { sessionRepo.search("deploy script", "personal") } returns emptyList()
        val vm = vm()
        advanceUntilIdle()
        vm.onQueryChange("deploy")
        vm.searchMessages()
        advanceUntilIdle()
        assertTrue(vm.state.value.messages is MessageSearch.Results)

        vm.onQueryChange("deploy script")
        assertEquals(MessageSearch.Pending, vm.state.value.messages)
        advanceTimeBy(debounce)
        advanceUntilIdle()
        assertEquals(MessageSearch.Empty("deploy script"), vm.state.value.messages)

        vm.onQueryChange("")
        assertEquals(MessageSearch.Idle, vm.state.value.messages)
    }

    // The keyboard's Search action bypasses the debounce, and the debounce does not re-run it.
    @Test fun explicit_search_is_immediate_and_not_duplicated_by_debounce() = runTest {
        coEvery { sessionRepo.listAllProfiles() } returns emptyList()
        coEvery { sessionRepo.archivedAllProfiles() } returns emptyList()
        coEvery { sessionRepo.search("now", "personal") } returns listOf(SearchResultDto(sessionId = "s1", snippet = "now"))
        val vm = vm()
        advanceUntilIdle()
        vm.onQueryChange("now")
        vm.searchMessages()
        advanceUntilIdle()
        assertTrue(vm.state.value.messages is MessageSearch.Results)
        advanceTimeBy(debounce)
        advanceUntilIdle()
        coVerify(exactly = 1) { sessionRepo.search("now", "personal") }
    }

    // F3: a gateway failure becomes HR-SEARCH-001 in the message section; titles stay; retry works.
    @Test fun failure_yields_search_error_and_retry_recovers() = runTest {
        coEvery { sessionRepo.listAllProfiles() } returns listOf(session("t", "offline title"))
        coEvery { sessionRepo.archivedAllProfiles() } returns emptyList()
        coEvery { sessionRepo.search("offline", "personal") } throws RuntimeException("HTTP 502") andThen
            listOf(SearchResultDto(sessionId = "s1", snippet = "offline again"))
        val vm = vm()
        advanceUntilIdle()
        vm.onQueryChange("offline")
        vm.searchMessages()
        advanceUntilIdle()
        val failed = vm.state.value.messages as MessageSearch.Failed
        assertEquals(AppErrorCode.SEARCH_FAILED, failed.error.code)
        assertTrue(failed.error.retryable)
        assertEquals(listOf("t"), vm.state.value.titleMatches.map { it.session.id })

        vm.retry()
        advanceUntilIdle()
        assertTrue(vm.state.value.messages is MessageSearch.Results)
    }

    // F6: hits from excluded sources are dropped even if the gateway returns them; unknown
    // sessions keep the gateway's title; local sessions win for title/time/archived.
    @Test fun hits_are_filtered_by_source_and_resolved_against_local_sessions() = runTest {
        coEvery { sessionRepo.listAllProfiles() } returns listOf(session("known", "Local title", lastActive = 42L))
        coEvery { sessionRepo.archivedAllProfiles() } returns emptyList()
        coEvery { sessionRepo.search("x", "personal") } returns listOf(
            SearchResultDto(sessionId = "known", snippet = "x", title = "Gateway title", lastActive = 1.0),
            SearchResultDto(sessionId = "cronny", snippet = "x", source = "cron", title = "Cron"),
            SearchResultDto(sessionId = "unknown", snippet = "x", title = "Remote only", archived = true),
        )
        val vm = vm()
        advanceUntilIdle()
        vm.onQueryChange("x1")
        vm.onQueryChange("x")
        // Below the minimum: nothing runs.
        advanceTimeBy(debounce); advanceUntilIdle()
        assertEquals(MessageSearch.Idle, vm.state.value.messages)
        coVerify(exactly = 0) { sessionRepo.search(any(), any()) }
    }

    @Test fun hits_resolve_title_time_and_archived_from_local_rows_when_present() = runTest {
        coEvery { sessionRepo.listAllProfiles() } returns listOf(session("known", "Local title", lastActive = 42L))
        coEvery { sessionRepo.archivedAllProfiles() } returns emptyList()
        coEvery { sessionRepo.search("xx", "personal") } returns listOf(
            SearchResultDto(sessionId = "known", snippet = "xx", title = "Gateway title", lastActive = 1.0),
            SearchResultDto(sessionId = "cronny", snippet = "xx", source = "cron", title = "Cron"),
            SearchResultDto(sessionId = "unknown", snippet = "xx", title = "Remote only", archived = true),
        )
        val vm = vm()
        advanceUntilIdle()
        vm.onQueryChange("xx")
        vm.searchMessages()
        advanceUntilIdle()
        val hits = (vm.state.value.messages as MessageSearch.Results).hits
        assertEquals(listOf("known", "unknown"), hits.map { it.sessionId })
        assertEquals("Local title", hits[0].title)
        assertEquals(42L, hits[0].lastActiveMs)
        assertFalse(hits[0].archived)
        assertEquals("Remote only", hits[1].title)
        assertTrue(hits[1].archived)
        assertNull(hits[1].lastActiveMs)
    }

    // Recent searches follow the active profile's store; using one fills and searches.
    @Test fun recent_searches_are_exposed_and_reusable() = runTest {
        coEvery { sessionRepo.listAllProfiles() } returns emptyList()
        coEvery { sessionRepo.archivedAllProfiles() } returns emptyList()
        coEvery { sessionRepo.search("old", "personal") } returns emptyList()
        recentFlow.value = listOf("old", "older")
        val vm = vm()
        advanceUntilIdle()
        assertEquals(listOf("old", "older"), vm.state.value.recent)
        vm.useRecent("old")
        advanceUntilIdle()
        assertEquals("old", vm.state.value.query)
        assertEquals(MessageSearch.Empty("old"), vm.state.value.messages)
        vm.removeRecent("older"); vm.clearRecent()
        advanceUntilIdle()
        coVerify { recentStore.remove("personal", "older") }
        coVerify { recentStore.clear("personal") }
    }

    // Query survives process death through SavedStateHandle.
    @Test fun query_is_restored_from_saved_state() = runTest {
        coEvery { sessionRepo.listAllProfiles() } returns listOf(session("a", "restored"))
        coEvery { sessionRepo.archivedAllProfiles() } returns emptyList()
        val saved = SavedStateHandle(mapOf("q" to "restored"))
        val vm = vm(saved)
        advanceUntilIdle()
        assertEquals("restored", vm.state.value.query)
        assertEquals(listOf("a"), vm.state.value.titleMatches.map { it.session.id })
        vm.onQueryChange("changed")
        assertEquals("changed", saved.get<String>("q"))
    }

    // A query arriving through the route (chat's "search all chats") searches by itself.
    @Test fun restored_query_searches_messages_after_debounce() = runTest {
        coEvery { sessionRepo.listAllProfiles() } returns emptyList()
        coEvery { sessionRepo.archivedAllProfiles() } returns emptyList()
        coEvery { sessionRepo.search("handed over", "personal") } returns listOf(SearchResultDto(sessionId = "s1", snippet = "handed over"))
        val vm = vm(SavedStateHandle(mapOf("q" to "handed over")))
        advanceUntilIdle()
        advanceTimeBy(debounce)
        advanceUntilIdle()
        assertTrue(vm.state.value.messages is MessageSearch.Results)
    }

    @Test fun foregroundRecoveryRefreshesTheVisibleQuerySources() = runTest {
        coEvery { sessionRepo.listAllProfiles() } returns
            listOf(session("old", "Old result")) andThen
            listOf(session("new", "New result"))
        coEvery { sessionRepo.archivedAllProfiles() } returns emptyList()
        val vm = vm()
        advanceUntilIdle()
        vm.onQueryChange("result")
        assertEquals(listOf("old"), vm.state.value.titleMatches.map { it.session.id })

        assertTrue(vm.recoverForForeground())

        assertEquals(listOf("new"), vm.state.value.titleMatches.map { it.session.id })
    }

    @Test fun failedForegroundRecoveryKeepsExistingSearchResults() = runTest {
        coEvery { sessionRepo.listAllProfiles() } returns
            listOf(session("old", "Old result")) andThenThrows RuntimeException("offline")
        coEvery { sessionRepo.archivedAllProfiles() } returns emptyList()
        val vm = vm()
        advanceUntilIdle()
        vm.onQueryChange("result")

        assertFalse(vm.recoverForForeground())
        assertEquals(listOf("old"), vm.state.value.titleMatches.map { it.session.id })
    }
}
