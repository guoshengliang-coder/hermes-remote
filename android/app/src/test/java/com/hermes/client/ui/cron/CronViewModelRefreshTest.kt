package com.hermes.client.ui.cron

import com.hermes.client.data.network.CronJobDto
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.ToolsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CronViewModelRefreshTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun same_profile_refresh_keeps_existing_rows_until_replacement_arrives() = runTest(dispatcher) {
        val old = listOf(CronJobDto(id = "old", name = "Existing"))
        val fresh = listOf(CronJobDto(id = "new", name = "Fresh"))
        val tools = mockk<ToolsRepository>()
        coEvery { tools.cronJobs(any()) } returns old
        val profiles = mockk<ProfileManager>()
        every { profiles.active } returns MutableStateFlow<String?>("default")
        val vm = CronViewModel(tools, profiles)
        runCurrent()
        assertEquals(old, vm.state.value.jobs)

        val response = CompletableDeferred<List<CronJobDto>>()
        coEvery { tools.cronJobs(any()) } coAnswers { response.await() }
        vm.load()
        runCurrent()
        assertTrue(vm.state.value.loading)
        assertEquals(old, vm.state.value.jobs)

        response.complete(fresh)
        runCurrent()
        assertEquals(fresh, vm.state.value.jobs)
    }
}
