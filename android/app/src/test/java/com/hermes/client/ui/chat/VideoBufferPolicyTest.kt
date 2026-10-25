package com.hermes.client.ui.chat

import org.junit.Test
import org.junit.Assert.*

class VideoBufferPolicyTest {
    @Test fun target_tracks_playout_duration_without_unbounded_memory_or_slow_speed_growth() {
        assertEquals(8_000L, VideoBufferPolicy.maxMediaMs(.5f))
        assertEquals(8_000L, VideoBufferPolicy.maxMediaMs(1f))
        assertEquals(12_000L, VideoBufferPolicy.maxMediaMs(1.5f))
        assertEquals(16_000L, VideoBufferPolicy.maxMediaMs(2f))
        assertEquals(24_000L, VideoBufferPolicy.maxMediaMs(3f))
        assertEquals(24_000L, VideoBufferPolicy.maxMediaMs(100f))
        assertEquals(24 * 1024 * 1024, VideoBufferPolicy.SAMPLE_BYTES)
    }
    @Test fun paused_cache_can_fill_but_first_frame_rebuffer_and_low_real_headroom_yield() {
        val state = VideoPlayerState(position = 1_000, duration = 60_000, buffered = 7_000, wantsPlay = true)
        assertTrue(VideoBufferPolicy.mayPrecache(state))
        assertFalse(VideoBufferPolicy.mayPrecache(state.copy(speed = 2f)))
        assertFalse(VideoBufferPolicy.mayPrecache(state.copy(buffering = true)))
        assertFalse(VideoBufferPolicy.mayPrecache(state.copy(duration = 0)))
        assertTrue(VideoBufferPolicy.mayPrecache(state.copy(wantsPlay = false, buffered = 1_000)))
        assertTrue(VideoBufferPolicy.mayPrecache(state.copy(buffered = 60_000, speed = 3f)))
    }
}
