package com.hermes.client.data.repository

import com.hermes.client.MainDispatcherRule
import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.ModelProviderDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelCatalogStoreTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private val models = mockk<ModelRepository>(relaxed = true)
    private val profileManager = mockk<ProfileManager>(relaxed = true)
    private val credentials = mockk<com.hermes.client.data.auth.CredentialStore>()
    private val connectivity = mockk<com.hermes.client.data.network.ConnectivityChecker>()
    private val chatRepo = mockk<ChatRepository>()
    private val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val activeProfile = MutableStateFlow<String?>("work")
    private val jobs = mutableListOf<Job>()

    private val workCatalog = listOf(
        ModelProviderDto(slug = "prov", isCurrent = true, models = listOf("m1", "m2")),
    )

    @Before fun setUp() {
        every { profileManager.active } returns activeProfile
        every { credentials.load() } returns mockk()
        every { connectivity.isOnline() } returns true
        every { chatRepo.connectionState } returns connectionState
        coEvery { models.providers(any()) } returns workCatalog
    }

    @After fun tearDown() {
        jobs.forEach(Job::cancel)
        jobs.clear()
    }

    private fun buildStore(): ModelCatalogStore {
        val job = SupervisorJob()
        jobs += job
        return ModelCatalogStore(
            models, profileManager, credentials, connectivity, chatRepo,
            CoroutineScope(job + Dispatchers.Main),
        )
    }

    // Background prefetch must be a silent no-op before the gateway is configured — no calls,
    // no error state (a fresh install shows Setup, not a model-list failure).
    @Test fun refresh_is_silent_noop_without_credentials() = runTest {
        every { credentials.load() } returns null
        val store = buildStore()

        store.onForeground()
        store.refresh(force = true)
        advanceUntilIdle()

        coVerify(exactly = 0) { models.providers(any()) }
        assertTrue(store.state.value.providers.isEmpty())
        assertFalse(store.state.value.failed)
    }

    @Test fun refresh_is_silent_noop_while_offline() = runTest {
        every { connectivity.isOnline() } returns false
        val store = buildStore()

        store.onForeground()
        advanceUntilIdle()

        coVerify(exactly = 0) { models.providers(any()) }
    }

    // The app-start trigger: foreground fetches and caches for the active profile.
    @Test fun onForeground_fetches_and_caches_active_profile() = runTest {
        val store = buildStore()

        store.onForeground()
        advanceUntilIdle()

        assertEquals(workCatalog, store.state.value.providers)
        assertTrue(store.state.value.loaded)
        assertFalse(store.state.value.refreshing)
    }

    // A fresh cache throttles background triggers (start-time pile-up costs one request), while
    // an explicit force refresh always fetches.
    @Test fun fresh_cache_throttles_background_but_not_forced_refresh() = runTest {
        val store = buildStore()
        store.onForeground()
        advanceUntilIdle()

        store.onForeground()          // warm + fresh → throttled
        store.refresh(force = false)  // sheet open → throttled
        advanceUntilIdle()
        coVerify(exactly = 1) { models.providers(any()) }

        store.refresh(force = true)   // explicit retry / after set-default
        advanceUntilIdle()
        coVerify(exactly = 2) { models.providers(any()) }
    }

    @Test fun concurrent_refreshes_for_same_profile_dedupe() = runTest {
        coEvery { models.providers(any()) } coAnswers { delay(500); workCatalog }
        val store = buildStore()

        store.refresh(force = true)
        store.refresh(force = true)
        runCurrent()
        advanceUntilIdle()

        coVerify(exactly = 1) { models.providers(any()) }
    }

    // A failed background refresh keeps the previous cache and only flags failed.
    @Test fun failure_keeps_previous_cache() = runTest {
        val store = buildStore()
        store.onForeground()
        advanceUntilIdle()

        coEvery { models.providers(any()) } throws RuntimeException("down")
        store.refresh(force = true)
        advanceUntilIdle()

        assertEquals(workCatalog, store.state.value.providers)
        assertTrue(store.state.value.failed)

        coEvery { models.providers(any()) } returns workCatalog
        store.refresh(force = true)
        advanceUntilIdle()
        assertFalse(store.state.value.failed)
    }

    // Results land under the profile captured at fetch START: a late response from a
    // switched-away profile must never render for the newly active profile.
    @Test fun late_response_never_leaks_across_profiles() = runTest {
        coEvery { models.providers("work") } coAnswers { delay(1_000); workCatalog }
        val store = buildStore()

        store.refresh(force = true)   // fetch for "work" in flight
        runCurrent()
        activeProfile.value = "personal"
        advanceUntilIdle()            // work's late response lands in work's bucket

        assertEquals("personal", store.state.value.profile)
        assertTrue("work's catalog must not show for personal", store.state.value.providers.isEmpty())
        assertFalse(store.state.value.loaded)
    }

    @Test fun profile_switch_trigger_fetches_new_profile() = runTest {
        val personalCatalog = listOf(ModelProviderDto(slug = "p", isCurrent = true, models = listOf("pm")))
        coEvery { models.providers("personal") } returns personalCatalog
        val store = buildStore()
        store.startTriggers()
        runCurrent()

        activeProfile.value = "personal"
        advanceUntilIdle()

        coVerify { models.providers("personal") }
        assertEquals(personalCatalog, store.state.value.providers)
    }

    @Test fun disconnected_to_connected_edge_triggers_fetch() = runTest {
        val store = buildStore()
        store.startTriggers()
        runCurrent()
        coVerify(exactly = 0) { models.providers(any()) }

        connectionState.value = ConnectionState.Connected
        advanceUntilIdle()

        coVerify(exactly = 1) { models.providers(any()) }
        assertEquals(workCatalog, store.state.value.providers)
    }
}
