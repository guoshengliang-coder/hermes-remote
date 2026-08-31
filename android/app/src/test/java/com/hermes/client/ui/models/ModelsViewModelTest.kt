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

    private val catalog = listOf(
        ModelProviderDto(slug = "prov", isCurrent = true, models = listOf("def-model", "other")),
        ModelProviderDto(slug = "alt", isCurrent = false, models = listOf("alt-model")),
    )

    @Before fun setUp() {
        every { profileManager.active } returns MutableStateFlow<String?>(null)
        every { favoritesStore.favorites } returns MutableStateFlow(emptySet())
        coEvery { models.providers(any()) } returns catalog
        coEvery { configRepo.get(any()) } returns buildJsonObject { put("model", "def-model") }
    }

    private fun buildVm() = ModelsViewModel(models, favoritesStore, profileManager, configRepo)

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
