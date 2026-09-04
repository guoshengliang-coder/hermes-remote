package com.hermes.client.data.network

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The chat banner used to appear for every socket blip, so a run that never stopped looked broken
 * the moment the user came back from the launcher (see docs/SMOKE_TEST.md, background connection).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionBannerVisibilityTest {

    private fun kotlinx.coroutines.test.TestScope.collectBanner(
        source: MutableStateFlow<ConnectionState>,
    ): MutableList<ConnectionState?> {
        val seen = mutableListOf<ConnectionState?>()
        backgroundScope.launch {
            source.connectionBanner(graceMs = 2_500L, now = { testScheduler.currentTime }).toList(seen)
        }
        runCurrent()
        return seen
    }

    @Test fun anOutageShorterThanTheGraceIsNeverAnnounced() = runTest {
        val state = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        val seen = collectBanner(state)

        state.value = ConnectionState.Reconnecting
        advanceTimeBy(1_000)
        state.value = ConnectionState.Connected
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(listOf<ConnectionState?>(null), seen.distinct())
    }

    @Test fun anOutageLongerThanTheGraceIsAnnounced() = runTest {
        val state = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        val seen = collectBanner(state)

        state.value = ConnectionState.Reconnecting
        advanceTimeBy(2_499)
        runCurrent()
        assertEquals(null, seen.last())

        advanceTimeBy(2)
        runCurrent()
        assertEquals(ConnectionState.Reconnecting, seen.last())
    }

    /**
     * Reconnect backoff walks Reconnecting → Connecting → Reconnecting. A naive debounce restarts
     * on every change and would keep a real, lengthening outage hidden indefinitely; the grace has
     * to run from when the connection was first lost.
     */
    @Test fun backoffChurnDoesNotRestartTheGrace() = runTest {
        val state = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        val seen = collectBanner(state)

        state.value = ConnectionState.Reconnecting
        advanceTimeBy(1_000)
        state.value = ConnectionState.Connecting
        advanceTimeBy(1_000)
        state.value = ConnectionState.Reconnecting
        advanceTimeBy(600)
        runCurrent()

        assertEquals(ConnectionState.Reconnecting, seen.last())
    }

    @Test fun recoveryHidesTheBannerImmediately() = runTest {
        val state = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        val seen = collectBanner(state)

        state.value = ConnectionState.Disconnected
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(ConnectionState.Disconnected, seen.last())

        state.value = ConnectionState.Connected
        runCurrent()
        assertEquals(null, seen.last())
    }

    /** Once the banner is up, the copy must track the state without waiting out another grace. */
    @Test fun aVisibleBannerFollowsLaterStatesWithoutDelay() = runTest {
        val state = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        val seen = collectBanner(state)

        state.value = ConnectionState.Reconnecting
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(ConnectionState.Reconnecting, seen.last())

        state.value = ConnectionState.Error("relay refused")
        runCurrent()
        assertEquals(ConnectionState.Error("relay refused"), seen.last())
    }
}
