package com.hermes.client.data.progress

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule
import org.junit.rules.Timeout

/** Real time bounds a test even when virtual time can keep advancing forever. */
abstract class SessionRuntimeTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)
}

/**
 * App-lifetime collectors and pollers are background work, not work runTest must drain.
 * Preserve eager event delivery; keep backgroundScope's Job AND scheduler background marker.
 * runTest cancels this scope even when the assertion fails or the task remains active.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.eagerAppScope(): CoroutineScope =
    CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
