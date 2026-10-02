package com.hermes.client.data.haptics

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HapticPreviewTest {
    @Test fun rhythmEndsAfterOneSecondAndStopPreventsFutureRequests() = runTest {
        val requests = mutableListOf<OutputHapticConfig>(); var cancels = 0
        val preview = HapticPreview(backgroundScope, { requests += it; HapticRequestResult.REQUESTED }, { cancels++ }, { error("unexpected") })
        preview.start(OutputHapticConfig(intervalMs = 100), true)
        runCurrent(); advanceTimeBy(1000); runCurrent()
        assertEquals(10, requests.size); assertFalse(preview.running.value)
        preview.start(OutputHapticConfig(intervalMs = 40), true)
        runCurrent(); advanceTimeBy(80); runCurrent(); preview.stop()
        val count = requests.size; advanceTimeBy(2000); runCurrent()
        assertEquals(count, requests.size); assertFalse(preview.running.value); assertTrue(cancels > 0)
    }
    @Test fun replacementDoesNotLetOldCleanupStopNewPreviewAndFailureStopsImmediately() = runTest {
        var accepted = true; var count = 0; val errors = mutableListOf<HapticRequestResult>()
        val preview = HapticPreview(backgroundScope, { count++; if (accepted) HapticRequestResult.REQUESTED else HapticRequestResult.SUPPRESSED }, {}, errors::add)
        preview.start(OutputHapticConfig(), true); runCurrent()
        preview.start(OutputHapticConfig(intervalMs = 50), true); runCurrent()
        assertTrue(preview.running.value)
        advanceTimeBy(50); runCurrent(); assertEquals(3, count)
        accepted = false; advanceTimeBy(50); runCurrent()
        assertEquals(listOf(HapticRequestResult.SUPPRESSED), errors)
        assertFalse(preview.running.value)
        advanceTimeBy(2000); runCurrent(); assertEquals(4, count)
    }
    @Test fun singlePreviewMakesExactlyOneRequest() = runTest {
        var count = 0
        val preview = HapticPreview(backgroundScope, { count++; HapticRequestResult.REQUESTED }, {}, {})
        preview.start(OutputHapticConfig(), false); runCurrent()
        advanceTimeBy(1000); runCurrent()
        assertEquals(1, count); assertFalse(preview.running.value)
    }
}
