package com.hermes.client.ui.cron

import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.data.network.CronFireClaimDto
import com.hermes.client.data.network.CronJobDto
import com.hermes.client.data.network.HermesApiException
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.ToolsRepository
import com.hermes.client.ui.localization.AppLanguage
import io.mockk.coEvery
import io.mockk.coVerify
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
import java.io.InterruptedIOException

/**
 * 「立即运行」 against a job that runs longer than the REST timeout.
 *
 * `POST /api/cron/jobs/{id}/trigger` executes the job synchronously upstream and only answers when
 * the run has finished, so a long job always times out on the wire while running to completion on
 * the Mac. Reported 2026-09-20 from a phone: every tap on a 6-minute job came back
 * 「操作没有成功，请查看详情后重试」 (HR-CRON-003, `cause=timeout`) even though the run had started
 * and, minutes later, succeeded. Following that advice made it worse — the second tap lost the
 * claim race against the user's own first run and failed with `Fire claim was not acquired`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CronTriggerTimeoutTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val idle = CronJobDto(id = "job-1", name = "周报汇总", lastRunAt = "2026-09-20T08:03:30+08:00")
    private val running = idle.copy(
        fireClaim = CronFireClaimDto(at = "2026-09-20T20:59:33+08:00", by = "LGS-MACMINI.local:28260:abc"),
    )
    private val finished = idle.copy(lastRunAt = "2026-09-20T21:05:38+08:00")

    private fun profiles(): ProfileManager {
        val profiles = mockk<ProfileManager>()
        every { profiles.active } returns MutableStateFlow<String?>("default")
        return profiles
    }

    private fun detailTools(afterTrigger: CronJobDto, failure: Throwable): ToolsRepository {
        val tools = mockk<ToolsRepository>()
        coEvery { tools.cronJob(any(), any()) } returns idle
        coEvery { tools.cronRuns(any(), any()) } returns emptyList()
        coEvery { tools.triggerCron(any(), any()) } answers {
            coEvery { tools.cronJob(any(), any()) } returns afterTrigger
            throw failure
        }
        return tools
    }

    private fun detailViewModel(tools: ToolsRepository) = CronDetailViewModel(tools, profiles())

    @Test fun a_timeout_on_a_job_that_is_now_running_is_reported_as_started_not_failed() =
        runTest(dispatcher) {
            val vm = detailViewModel(detailTools(running, InterruptedIOException("timeout")))
            vm.load("job-1")
            runCurrent()

            vm.trigger()
            runCurrent()

            assertNull(vm.state.value.actionError)
            val shown = vm.state.value.message?.resolve(AppLanguage.ZH).orEmpty()
            assertTrue(shown, shown.contains("正在后台运行"))
            assertFalse(shown, shown.contains("HR-CRON-003"))
        }

    /** The claim can be gone by the time we ask: a moved `last_run_at` is the same evidence. */
    @Test fun a_timeout_on_a_run_that_finished_meanwhile_is_also_reported_as_started() =
        runTest(dispatcher) {
            val vm = detailViewModel(detailTools(finished, InterruptedIOException("timeout")))
            vm.load("job-1")
            runCurrent()

            vm.trigger()
            runCurrent()

            assertNull(vm.state.value.actionError)
            assertTrue(vm.state.value.message?.resolve(AppLanguage.EN).orEmpty().contains("background"))
        }

    /** Nothing to show for the timeout — the gateway really is unreachable — stays HR-CRON-003. */
    @Test fun a_timeout_with_no_run_behind_it_is_still_a_failed_action() = runTest(dispatcher) {
        val vm = detailViewModel(detailTools(idle, InterruptedIOException("timeout")))
        vm.load("job-1")
        runCurrent()

        vm.trigger()
        runCurrent()

        val failure = vm.state.value.actionError
        assertEquals(AppErrorCode.CRON_ACTION_FAILED, failure?.code)
        assertEquals("cron_trigger", failure?.stage)
        assertEquals("timeout", failure?.technicalCause)
        assertFalse(vm.state.value.triggering)
    }

    /** A concurrent fire can win the claim before this request; the job record settles that 409. */
    @Test fun a_claim_conflict_that_is_now_running_is_reported_as_started() = runTest(dispatcher) {
        val tools = detailTools(running, HermesApiException(409, "HTTP 409"))
        val vm = detailViewModel(tools)
        vm.load("job-1")
        runCurrent()

        vm.trigger()
        runCurrent()

        assertNull(vm.state.value.actionError)
        assertTrue(vm.state.value.message?.resolve(AppLanguage.ZH).orEmpty().contains("正在后台运行"))
        coVerify(atLeast = 2) { tools.cronJob(any(), any()) }
    }

    @Test fun a_claim_conflict_without_a_run_behind_it_stays_failed() = runTest(dispatcher) {
        val vm = detailViewModel(detailTools(idle, HermesApiException(409, "HTTP 409")))
        vm.load("job-1")
        runCurrent()

        vm.trigger()
        runCurrent()

        assertEquals(AppErrorCode.CRON_ACTION_FAILED, vm.state.value.actionError?.code)
    }

    /** While a claim is held the page must not offer the tap that would lose the race. */
    @Test fun a_running_job_keeps_the_button_disabled() = runTest(dispatcher) {
        val tools = mockk<ToolsRepository>()
        coEvery { tools.cronJob(any(), any()) } returns running
        coEvery { tools.cronRuns(any(), any()) } returns emptyList()
        val vm = CronDetailViewModel(tools, profiles())

        vm.load("job-1")
        runCurrent()

        assertTrue(vm.state.value.triggering)
    }

    @Test fun the_list_reports_a_timed_out_run_as_started_too() = runTest(dispatcher) {
        val tools = mockk<ToolsRepository>()
        coEvery { tools.cronJobs(any()) } returns listOf(idle)
        coEvery { tools.cronJob(any(), any()) } returns running
        coEvery { tools.triggerCron(any(), any()) } throws InterruptedIOException("timeout")
        val vm = CronViewModel(tools, profiles())
        runCurrent()

        vm.runAction("job-1", "周报汇总", CronAction.RUN)
        runCurrent()

        val shown = vm.state.value.message?.resolve(AppLanguage.ZH).orEmpty()
        assertTrue(shown, shown.contains("正在后台运行"))
        assertFalse(shown, shown.contains("HR-CRON-003"))
    }

    @Test fun the_list_reconciles_a_claim_conflict_against_the_running_job() = runTest(dispatcher) {
        val tools = mockk<ToolsRepository>()
        coEvery { tools.cronJobs(any()) } returns listOf(idle)
        coEvery { tools.cronJob(any(), any()) } returns running
        coEvery { tools.triggerCron(any(), any()) } throws HermesApiException(409, "HTTP 409")
        val vm = CronViewModel(tools, profiles())
        runCurrent()

        vm.runAction("job-1", "周报汇总", CronAction.RUN)
        runCurrent()

        val shown = vm.state.value.message?.resolve(AppLanguage.ZH).orEmpty()
        assertTrue(shown, shown.contains("正在后台运行"))
        assertFalse(shown, shown.contains("HR-CRON-003"))
    }

    @Test fun the_list_still_reports_a_timeout_with_nothing_behind_it() = runTest(dispatcher) {
        val tools = mockk<ToolsRepository>()
        coEvery { tools.cronJobs(any()) } returns listOf(idle)
        coEvery { tools.cronJob(any(), any()) } returns idle
        coEvery { tools.triggerCron(any(), any()) } throws InterruptedIOException("timeout")
        val vm = CronViewModel(tools, profiles())
        runCurrent()

        vm.runAction("job-1", "周报汇总", CronAction.RUN)
        runCurrent()

        val shown = vm.state.value.message?.resolve(AppLanguage.ZH).orEmpty()
        assertTrue(shown, shown.contains("HR-CRON-003"))
    }

    /** Pause and resume answer immediately; a timeout there is not a run and must not be excused. */
    @Test fun a_timed_out_pause_is_not_excused_as_a_background_run() = runTest(dispatcher) {
        val tools = mockk<ToolsRepository>()
        coEvery { tools.cronJobs(any()) } returns listOf(idle)
        coEvery { tools.cronJob(any(), any()) } returns running
        coEvery { tools.pauseCron(any(), any()) } throws InterruptedIOException("timeout")
        val vm = CronViewModel(tools, profiles())
        runCurrent()

        vm.runAction("job-1", "周报汇总", CronAction.PAUSE)
        runCurrent()

        val shown = vm.state.value.message?.resolve(AppLanguage.ZH).orEmpty()
        assertTrue(shown, shown.contains("HR-CRON-003"))
        assertFalse(shown, shown.contains("正在后台运行"))
    }
}
