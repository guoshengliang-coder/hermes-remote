package com.hermes.client.ui.models

import com.hermes.client.MainDispatcherRule
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.data.network.ModelProviderDto
import com.hermes.client.data.repository.favKey
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelsViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private val models = mockk<com.hermes.client.data.repository.ModelRepository>(relaxed = true)
    private val favoritesStore = mockk<com.hermes.client.data.repository.ModelFavoritesStore>(relaxed = true)
    private val profileManager = mockk<com.hermes.client.data.repository.ProfileManager>(relaxed = true)
    private val configRepo = mockk<com.hermes.client.data.repository.ConfigRepository>(relaxed = true)
    private val chatRepo = mockk<com.hermes.client.data.repository.ChatRepository> {
        every { connectionState } returns
            MutableStateFlow<com.hermes.client.data.network.ConnectionState>(
                com.hermes.client.data.network.ConnectionState.Disconnected,
            )
    }
    private val credentialStore = mockk<com.hermes.client.data.auth.CredentialStore> {
        every { load() } returns mockk()
    }
    private val connectivityChecker = mockk<com.hermes.client.data.network.ConnectivityChecker> {
        every { isOnline() } returns true
    }
    private val activeProfile = MutableStateFlow<String?>(null)
    private val storeJobs = mutableListOf<kotlinx.coroutines.Job>()

    private val catalog = listOf(
        ModelProviderDto(slug = "prov", isCurrent = true, models = listOf("def-model", "other")),
        ModelProviderDto(slug = "alt", isCurrent = false, models = listOf("alt-model")),
    )

    @Before fun setUp() {
        every { profileManager.active } returns activeProfile
        every { favoritesStore.favorites } returns MutableStateFlow(emptySet())
        coEvery { models.providers(any()) } returns catalog
        coEvery { configRepo.get(any()) } returns buildJsonObject { put("model", "def-model") }
    }

    @After fun tearDown() {
        storeJobs.forEach(kotlinx.coroutines.Job::cancel)
        storeJobs.clear()
    }

    // Real catalog store over the mocked repository so the cache semantics are exercised.
    private fun buildStore(): com.hermes.client.data.repository.ModelCatalogStore {
        val job = kotlinx.coroutines.SupervisorJob()
        storeJobs += job
        return com.hermes.client.data.repository.ModelCatalogStore(
            models, profileManager, credentialStore, connectivityChecker, chatRepo,
            kotlinx.coroutines.CoroutineScope(job + kotlinx.coroutines.Dispatchers.Main),
        )
    }

    private fun buildVm(store: com.hermes.client.data.repository.ModelCatalogStore = buildStore()) =
        ModelsViewModel(models, favoritesStore, profileManager, configRepo, store)

    // The settings page edits exactly the default slot, so it must show the current default —
    // previously it passed nulls and never marked any row.
    @Test fun load_surfaces_default_model_and_its_provider() = runTest {
        val vm = buildVm()
        advanceUntilIdle()

        assertEquals("def-model", vm.state.value.defaultModel)
        assertEquals("prov", vm.state.value.defaultProvider)
        assertNull(vm.state.value.error)
    }

    @Test fun load_failure_reports_model_list_code_with_retry() = runTest {
        coEvery { models.providers(any()) } throws RuntimeException("boom")
        val vm = buildVm()
        advanceUntilIdle()

        val error = vm.state.value.error
        assertEquals(AppErrorCode.MODEL_LIST_FAILED, error?.code)
        assertTrue(error?.retryable == true)
    }

    // A failed config read must not take the whole list down — only the summary card hides.
    @Test fun config_failure_keeps_list_usable_without_default() = runTest {
        coEvery { configRepo.get(any()) } throws RuntimeException("no config")
        val vm = buildVm()
        advanceUntilIdle()

        assertEquals(catalog, vm.state.value.providers)
        assertNull(vm.state.value.error)
        assertNull(vm.state.value.defaultModel)
        // Falls back to the provider the gateway marks current.
        assertEquals("prov", vm.state.value.defaultProvider)
    }

    // Optimistic update: the new default is marked in place instead of a full-page reload flash.
    @Test fun select_success_marks_new_default_optimistically() = runTest {
        val vm = buildVm()
        advanceUntilIdle()

        vm.select("alt", "alt-model")
        advanceUntilIdle()

        coVerify { models.set("alt", "alt-model", null) }
        assertEquals("alt-model", vm.state.value.defaultModel)
        assertEquals("alt", vm.state.value.defaultProvider)
        assertNull(vm.state.value.pendingKey)
        assertTrue(vm.state.value.message != null)
    }

    @Test fun select_ignores_second_tap_while_pending() = runTest {
        coEvery { models.set(any(), any(), any()) } coAnswers { kotlinx.coroutines.delay(5_000) }
        val vm = buildVm()
        advanceUntilIdle()

        vm.select("prov", "other")
        runCurrent()
        assertEquals(favKey("prov", "other"), vm.state.value.pendingKey)
        vm.select("alt", "alt-model")
        advanceUntilIdle()

        coVerify(exactly = 1) { models.set(any(), any(), any()) }
    }

    // A store warmed before the screen opens must render instantly — no spinner frame after
    // the first emission settles.
    @Test fun warm_store_renders_without_loading() = runTest {
        val store = buildStore()
        store.refresh(force = true)
        advanceUntilIdle()               // cache warmed before the ViewModel exists

        val vm = buildVm(store)
        advanceUntilIdle()

        assertEquals(catalog, vm.state.value.providers)
        assertEquals(false, vm.state.value.loading)
        assertNull(vm.state.value.error)
        coVerify(exactly = 1) { models.providers(any()) }  // the VM's safety net was a no-op
    }

    // Per-profile isolation: after a profile switch the old profile's list must never render.
    @Test fun profile_switch_never_shows_previous_profiles_list() = runTest {
        val workCatalog = listOf(ModelProviderDto(slug = "work-prov", isCurrent = true, models = listOf("w1")))
        coEvery { models.providers("work") } returns workCatalog
        coEvery { models.providers("personal") } coAnswers {
            kotlinx.coroutines.delay(1_000)
            listOf(ModelProviderDto(slug = "personal-prov", isCurrent = true, models = listOf("p1")))
        }
        activeProfile.value = "work"
        val store = buildStore()
        store.startTriggers()
        val vm = buildVm(store)
        advanceUntilIdle()
        assertEquals(workCatalog, vm.state.value.providers)

        activeProfile.value = "personal"
        runCurrent()
        // While personal's fetch is in flight, work's list must not be shown for personal.
        assertTrue(vm.state.value.providers.isEmpty())
        assertEquals(true, vm.state.value.loading)

        advanceUntilIdle()
        assertEquals("personal-prov", vm.state.value.providers.single().slug)
    }

    @Test fun select_failure_reports_default_set_code() = runTest {
        coEvery { models.set(any(), any(), any()) } throws RuntimeException("nope")
        val vm = buildVm()
        advanceUntilIdle()

        vm.select("prov", "other")
        advanceUntilIdle()

        assertNull(vm.state.value.pendingKey)
        val message = vm.state.value.message
        assertTrue(message != null && message.zh.contains("HR-RPC-005") && message.en.contains("HR-RPC-005"))
    }
}
