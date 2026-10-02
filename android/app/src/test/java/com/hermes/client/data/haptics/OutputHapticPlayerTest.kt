package com.hermes.client.data.haptics

import android.view.HapticFeedbackConstants
import org.junit.Assert.*
import org.junit.Test

class OutputHapticPlayerTest {
    private class Device : OutputHapticDevice {
        override var sdk = 34
        override var hasVibrator = true
        override var amplitudeControl = true
        override var touchEnabled = true
        override var foreground = true
        var accepted = true
        var fail = false
        val effects = mutableListOf<Int>()
        val pulses = mutableListOf<Pair<Int, Int>>()
        var cancels = 0
        override fun systemFeedback(effect: Int): Boolean { effects += effect; return accepted }
        override fun pulse(durationMs: Int, amplitude: Int) {
            if (fail) throw SecurityException("token=secret")
            pulses += durationMs to amplitude
        }
        override fun cancel() { cancels++ }
    }
    private val custom = OutputHapticConfig(OutputHapticType.CUSTOM_PULSE, 40, 8, 64)

    @Test fun customPulseUsesExactBoundedChoiceAndOnlyCancelsOwnActivePulse() {
        val device = Device(); var time = 100L
        val player = OutputHapticPlayer(device) { time }
        assertEquals(HapticRequestResult.REQUESTED, player.request(custom))
        assertEquals(listOf(8 to 64), device.pulses)
        player.cancel(); player.cancel()
        assertEquals(1, device.cancels)
        player.request(custom.copy(durationMs = 999, amplitude = 999)); time += 31
        player.cancel()
        assertEquals(30 to 255, device.pulses.last())
        assertEquals(1, device.cancels)
    }
    @Test fun unsupportedAmplitudeAndSystemOrForegroundSuppressionNeverFallBackToFullStrength() {
        val device = Device(); val player = OutputHapticPlayer(device) { 100 }
        device.amplitudeControl = false
        assertEquals(HapticRequestResult.UNSUPPORTED, player.request(custom))
        device.amplitudeControl = true; device.touchEnabled = false
        assertEquals(HapticRequestResult.SUPPRESSED, player.request(custom))
        device.touchEnabled = true; device.foreground = false
        assertEquals(HapticRequestResult.SUPPRESSED, player.request(custom))
        assertTrue(device.pulses.isEmpty()); assertTrue(device.effects.isEmpty())
    }
    @Test fun systemModesUsePlatformConstantsAndNeverCustomVibration() {
        val device = Device(); val player = OutputHapticPlayer(device) { 100 }
        OutputHapticType.entries.filter { it != OutputHapticType.CUSTOM_PULSE }.forEach {
            assertEquals(HapticRequestResult.REQUESTED, player.request(custom.copy(type = it)))
        }
        assertEquals(listOf(HapticFeedbackConstants.SEGMENT_TICK, HapticFeedbackConstants.SEGMENT_FREQUENT_TICK, HapticFeedbackConstants.KEYBOARD_TAP), device.effects)
        assertTrue(device.pulses.isEmpty())
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, systemHapticEffect(OutputHapticType.SYSTEM_SOFT, 26))
        assertEquals(HapticFeedbackConstants.CONTEXT_CLICK, systemHapticEffect(OutputHapticType.SYSTEM_TICK, 26))
        device.accepted = false
        assertEquals(HapticRequestResult.REJECTED, player.request(OutputHapticConfig()))
    }
    @Test fun platformExceptionIsContainedAndAvailableForRedactedDetails() {
        val device = Device().apply { fail = true }; val player = OutputHapticPlayer(device) { 100 }
        assertEquals(HapticRequestResult.FAILED, player.request(custom))
        assertTrue(player.failureCause!!.startsWith("SecurityException"))
    }
}
