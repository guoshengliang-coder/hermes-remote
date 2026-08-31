package com.hermes.client.ui.tools

import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.ToolsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Before
import org.junit.Test

/**
 * Scope-rule regression tests: skills/toolsets are per-profile, so the VM must pass the active
 * profile on every fetch AND re-fetch when the active profile changes (a stale back-stack entry
 * previously kept showing the previous tenant's toggles).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ToolsViewModelTest {
    private val tools = mockk<ToolsRepository>(relaxed = true)
    private val profileManager = mockk<ProfileManager>(relaxed = true)
    private val active = MutableStateFlow<String?>("personal")

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        every { profileManager.active } returns active
        coEvery { tools.skills(any()) } returns emptyList()
        coEvery { tools.toolsets(any()) } returns emptyList()
    }

    @Test fun load_passes_active_profile_and_reloads_on_switch() = runTest {
        ToolsViewModel(tools, profileManager)
        advanceUntilIdle()
        coVerify(exactly = 1) { tools.skills("personal") }

        active.value = "work"
        advanceUntilIdle()
        coVerify(exactly = 1) { tools.skills("work") }
        coVerify(exactly = 1) { tools.toolsets("work") }
    }
}
