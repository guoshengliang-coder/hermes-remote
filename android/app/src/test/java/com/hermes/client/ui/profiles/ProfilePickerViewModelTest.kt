package com.hermes.client.ui.profiles

import com.hermes.client.data.progress.SessionRuntimeStore
import com.hermes.client.data.repository.AvatarColorStore
import com.hermes.client.data.repository.ProfileManager
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfilePickerViewModelTest {
    private val profileManager = mockk<ProfileManager>(relaxed = true)
    private val avatarColors = mockk<AvatarColorStore>(relaxed = true)
    private val runtimeStore = mockk<SessionRuntimeStore>(relaxed = true)

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        every { runtimeStore.runtimes } returns MutableStateFlow(emptyMap())
    }

    // The picker pops back ONLY on a successful switch; a refused switch surfaces switchFailed
    // and stays on the screen with the old profile intact.
    @Test fun switch_reports_success_and_failure_correctly() = runTest {
        coEvery { profileManager.switchTo("work") } returns false
        coEvery { profileManager.switchTo("personal") } returns true
        val vm = ProfilePickerViewModel(profileManager, avatarColors, runtimeStore)

        var result: Boolean? = null
        vm.switchProfile("work") { result = it }
        advanceUntilIdle()
        assertEquals(false, result)
        assertEquals("work", vm.switchFailed.value)

        vm.clearSwitchFailed()
        vm.switchProfile("personal") { result = it }
        advanceUntilIdle()
        assertEquals(true, result)
        assertNull(vm.switchFailed.value)
    }

    @Test fun avatar_colour_writes_and_clears_through_the_store() = runTest {
        val vm = ProfilePickerViewModel(profileManager, avatarColors, runtimeStore)
        vm.setAvatarColor("work", 0xFF1F8484.toInt())
        vm.clearAvatarColor("work")
        advanceUntilIdle()
        coVerify { avatarColors.setColor("work", 0xFF1F8484.toInt()) }
        coVerify { avatarColors.clear("work") }
        assertTrue(true)
    }
}
