package com.hermes.client.data.progress

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionRuntimeTestScopeTest : SessionRuntimeTest() {
    @Test fun activePollerDoesNotPreventSchedulerFromBecomingIdleAndIsCancelledAtTestEnd() {
        lateinit var polling: Job
        var cancelled = false
        runTest {
            var polls = 0
            polling = eagerAppScope().launch {
                try {
                    while (isActive) {
                        polls++
                        delay(5_000)
                    }
                } finally { cancelled = true }
            }
            advanceUntilIdle()
            assertEquals(1, polls)
            assertTrue(polling.isActive)
            advanceTimeBy(15_000)
            runCurrent()
            assertEquals(4, polls)
        }
        assertTrue(polling.isCancelled)
        assertTrue(cancelled)
    }

    @Test fun failedAssertionStillCancelsAppLifetimeWork() {
        lateinit var polling: Job
        try {
            runTest {
                polling = eagerAppScope().launch { while (isActive) delay(5_000) }
                throw AssertionError("intentional test failure")
            }
            fail("the assertion must propagate")
        } catch (expected: AssertionError) {
            assertEquals("intentional test failure", expected.message)
        }
        assertTrue(polling.isCancelled)
    }

    @Test fun defaultRostersAreConcreteEmptyListsRatherThanRelaxedGenericProxies() = runTest {
        val chat = legacyChatRepositoryFixture()
        assertTrue(chat.listProcesses("live").isEmpty())
        assertTrue(chat.listSubagents("live").isEmpty())
    }
}
