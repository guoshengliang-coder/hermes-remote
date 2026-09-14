package com.hermes.client.ui.cron

import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.data.network.HermesApiException
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.ToolsRepository
import com.hermes.client.ui.localization.AppLanguage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What 「立即运行」 says when it fails (HG-51).
 *
 * Every failure on this page used to read 「操作失败（HR-RPC-001）」 — one hardcoded transport code
 * standing in for a refused request, a timeout, a missing endpoint and an unconfigured gateway
 * alike. The cause was discarded and the request was never logged, so the report that opened HG-51
 * arrived with 562 KB of diagnostics in which the tap did not appear.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CronDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(tools: ToolsRepository): CronDetailViewModel {
        val profiles = mockk<ProfileManager>()
        every { profiles.active } returns MutableStateFlow<String?>("default")
        return CronDetailViewModel(tools, profiles)
    }

    private fun tools(trigger: () -> Nothing): ToolsRepository {
        val tools = mockk<ToolsRepository>()
        coEvery { tools.cronJob(any(), any()) } returns mockk(relaxed = true)
        coEvery { tools.cronRuns(any(), any()) } returns emptyList()
        coEvery { tools.triggerCron(any(), any()) } answers { trigger() }
        return tools
    }

    @Test fun a_refused_run_shows_the_code_the_server_sent() = runTest(dispatcher) {
        val vm = viewModel(
            tools { throw HermesApiException(404, "HR-SESS-001", errorCode = "HR-SESS-001") },
        )
        vm.load("job-1")
        runCurrent()

        vm.trigger()
        runCurrent()

        assertEquals(AppErrorCode.SESSION_NOT_FOUND, vm.state.value.actionError?.code)
        val shown = vm.state.value.message?.resolve(AppLanguage.ZH).orEmpty()
        assertTrue(shown, shown.contains("HR-SESS-001"))
        assertFalse(shown, shown.contains("HR-RPC-001"))
    }

    /**
     * A code this build has never heard of — a newer Gateway, or one of the `HR-BIND-*` family,
     * which is presented elsewhere and is not in [AppErrorCode]. The headline falls back rather
     * than inventing a meaning for a string it cannot read, but **the code itself is not thrown
     * away**: it reaches the details toggle, which is the whole point of HG-51. Guessing by prefix
     * would be worse than falling back.
     */
    @Test fun an_unknown_server_code_falls_back_but_is_still_reported() = runTest(dispatcher) {
        val vm = viewModel(
            tools { throw HermesApiException(409, "HR-BIND-009", errorCode = "HR-BIND-009") },
        )
        vm.load("job-1")
        runCurrent()

        vm.trigger()
        runCurrent()

        val failure = vm.state.value.actionError
        assertEquals(AppErrorCode.CRON_ACTION_FAILED, failure?.code)
        assertEquals("HR-BIND-009", failure?.technicalCause)
        assertTrue(failure!!.sanitizedDiagnostic().contains("HR-BIND-009"))
    }

    /**
     * No stable code from the server is the case HG-51 actually reported, and the honest answer is
     * the cron-action code — not a transport code claiming to know the cause.
     */
    @Test fun a_run_that_fails_without_a_code_falls_back_to_the_cron_action_code() =
        runTest(dispatcher) {
            val vm = viewModel(tools { throw HermesApiException(500, "HTTP 500") })
            vm.load("job-1")
            runCurrent()

            vm.trigger()
            runCurrent()

            assertEquals(AppErrorCode.CRON_ACTION_FAILED, vm.state.value.actionError?.code)
            val shown = vm.state.value.message?.resolve(AppLanguage.ZH).orEmpty()
            assertTrue(shown, shown.contains("HR-CRON-003"))
            assertFalse(shown, shown.contains("HR-RPC-001"))
        }

    /** A transport failure is not an HTTP status; it must not be reported as one either. */
    @Test fun a_run_that_never_reached_the_server_is_still_reported_with_a_cause() =
        runTest(dispatcher) {
            val vm = viewModel(tools { throw java.net.SocketTimeoutException("timeout") })
            vm.load("job-1")
            runCurrent()

            vm.trigger()
            runCurrent()

            val failure = vm.state.value.actionError
            assertEquals(AppErrorCode.CRON_ACTION_FAILED, failure?.code)
            assertEquals("cron_trigger", failure?.stage)
            assertEquals("timeout", failure?.technicalCause)
        }

    @Test fun a_successful_run_clears_a_previous_failure() = runTest(dispatcher) {
        val tools = mockk<ToolsRepository>()
        coEvery { tools.cronJob(any(), any()) } returns mockk(relaxed = true)
        coEvery { tools.cronRuns(any(), any()) } returns emptyList()
        coEvery { tools.triggerCron(any(), any()) } throws HermesApiException(500, "HTTP 500")
        val vm = viewModel(tools)
        vm.load("job-1")
        runCurrent()
        vm.trigger()
        runCurrent()
        assertEquals(AppErrorCode.CRON_ACTION_FAILED, vm.state.value.actionError?.code)

        coEvery { tools.triggerCron(any(), any()) } returns Unit
        vm.trigger()
        runCurrent()

        assertNull(vm.state.value.actionError)
    }
}
