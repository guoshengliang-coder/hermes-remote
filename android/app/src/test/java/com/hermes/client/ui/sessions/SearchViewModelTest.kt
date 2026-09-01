package com.hermes.client.ui.sessions

import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.SessionRepository
import com.hermes.client.domain.Session
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val sessionRepo = mockk<SessionRepository>(relaxed = true)
    private val profileManager = mockk<ProfileManager>(relaxed = true)

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        every { profileManager.active } returns MutableStateFlow<String?>("personal")
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun session(id: String, title: String, profile: String = "personal") = Session(
        id = id, title = title, model = null, provider = null,
        messageCount = 1, profile = profile, workspace = "No workspace", source = "tui",
    )

    // Scope rule: title matches cover the ACTIVE profile only — live AND archived (archived rows
    // are first-class results now that the archived list is a list segment, not its own screen).
    @Test fun title_matches_scope_to_active_profile_and_include_archived() = runTest {
        coEvery { sessionRepo.listAllProfiles() } returns listOf(
            session("a", "Fix APK signing"),
            session("b", "Fix roof", profile = "work"),
        )
        coEvery { sessionRepo.archivedAllProfiles() } returns listOf(
            session("c", "Old signing debug"),
        )
        val vm = SearchViewModel(sessionRepo, profileManager)
        advanceUntilIdle()
        vm.onQueryChange("signing")
        val m = vm.state.value.titleMatches
        assertEquals(listOf("a", "c"), m.map { it.session.id })
        assertTrue(m.first { it.session.id == "c" }.archived)
    }

    // Ported from the old list-embedded search test: the explicit Search action populates
    // message results from the repo, and clearing the query clears them.
    @Test fun searchMessages_populates_results_and_clear_resets() = runTest {
        coEvery { sessionRepo.listAllProfiles() } returns emptyList()
        coEvery { sessionRepo.archivedAllProfiles() } returns emptyList()
        coEvery { sessionRepo.search("hello", "personal") } returns listOf(
            com.hermes.client.data.network.SearchResultDto(sessionId = "s9", snippet = "hello there"),
        )
        val vm = SearchViewModel(sessionRepo, profileManager)
        advanceUntilIdle()
        vm.onQueryChange("hello")
        vm.searchMessages()
        advanceUntilIdle()
        assertEquals(listOf("s9"), vm.state.value.messageResults.map { it.sessionId })

        vm.onQueryChange("")
        assertTrue(vm.state.value.messageResults.isEmpty())
    }

    @Test fun foregroundRecoveryRefreshesTheVisibleQuerySources() = runTest {
        coEvery { sessionRepo.listAllProfiles() } returns
            listOf(session("old", "Old result")) andThen
            listOf(session("new", "New result"))
        coEvery { sessionRepo.archivedAllProfiles() } returns emptyList()
        val vm = SearchViewModel(sessionRepo, profileManager)
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
        val vm = SearchViewModel(sessionRepo, profileManager)
        advanceUntilIdle()
        vm.onQueryChange("result")

        assertFalse(vm.recoverForForeground())
        assertEquals(listOf("old"), vm.state.value.titleMatches.map { it.session.id })
    }
}
