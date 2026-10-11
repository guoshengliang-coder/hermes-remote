package com.hermes.client.ui.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceRecordingDeadlineTest {
    @Test fun startsWithActualAudio_notGesture_andExpiresOnceAtFiveMinutes() = runTest {
        var limits = 0
        val deadline = VoiceRecordingDeadline(backgroundScope) { limits++ }
        advanceTimeBy(10_000)
        assertEquals(0, limits)
        deadline.audioReceived(); runCurrent()
        advanceTimeBy(60_000); deadline.audioReceived()
        assertEquals(0, limits)
        advanceTimeBy(239_999); runCurrent()
        assertEquals(0, limits)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, limits)
        deadline.audioReceived(); advanceTimeBy(300_000); runCurrent()
        assertEquals(1, limits)
    }
    @Test fun releaseNearLimit_cancelsRecordingDeadline() = runTest {
        var limits = 0
        val deadline = VoiceRecordingDeadline(backgroundScope) { limits++ }
        deadline.audioReceived(); runCurrent(); advanceTimeBy(299_999)
        deadline.stop(); advanceTimeBy(8_000); runCurrent()
        assertEquals(0, limits)
    }
    @Test fun cancelledBeforeAudio_doesNotArmALateDeadline() = runTest {
        var limits = 0
        val deadline = VoiceRecordingDeadline(backgroundScope) { limits++ }
        deadline.stop(); deadline.audioReceived(); advanceTimeBy(330_000); runCurrent()
        assertEquals(0, limits)
    }
}
