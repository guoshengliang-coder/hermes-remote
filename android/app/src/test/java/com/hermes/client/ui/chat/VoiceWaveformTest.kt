package com.hermes.client.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HG-146: the recording card's waveform is derived from the elapsed time rather than a live
 * amplitude (there is none) or an infinite transition, so a screenshot at a fixed time is stable.
 */
class VoiceWaveformTest {
    @Test fun heightsStayWithinTheBarBox() {
        voiceWaveHeights(0).forEach { h ->
            assertTrue("height $h out of range", h >= 4f && h <= 38f)
        }
    }

    @Test fun barCountDefaultsToTwentyOne() {
        assertEquals(VOICE_WAVE_BARS, voiceWaveHeights(0).size)
    }

    @Test fun sameClockRendersTheSameBars() {
        assertEquals(voiceWaveHeights(500), voiceWaveHeights(500))
    }

    @Test fun theBarsMoveWithTheClock() {
        assertNotEquals(voiceWaveHeights(0), voiceWaveHeights(VOICE_WAVE_TICK_MS))
    }

    @Test fun clockIsMinutesAndSeconds() {
        assertEquals("00:00", voiceClock(-5L))
        assertEquals("00:04", voiceClock(4_400L))
        assertEquals("01:05", voiceClock(65_000L))
        assertEquals("12:00", voiceClock(720_000L))
    }
}
