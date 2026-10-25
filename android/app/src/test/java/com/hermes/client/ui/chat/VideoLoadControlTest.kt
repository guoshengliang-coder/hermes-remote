package com.hermes.client.ui.chat

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoLoadControlTest {
    private val timeline = androidx.media3.exoplayer.source.SinglePeriodTimeline(60_000_000, true, false, false,
        null, androidx.media3.common.MediaItem.fromUri("hermes-video://fixture"))
    private fun parameters(speed: Float, bufferedMs: Long, rebuffer: Boolean = false) =
        LoadControl.Parameters(PlayerId.UNSET, timeline, MediaPeriodId(timeline.getUidOfPeriod(0)), 0,
            bufferedMs * 1_000, speed, true, rebuffer, C.TIME_UNSET, C.TIME_UNSET)
    @Test fun actual_load_control_scales_maximum_once_and_preserves_light_startup() {
        val control = VideoBufferPolicy.loadControl(); control.onPrepared(PlayerId.UNSET)
        try {
            assertTrue(control.shouldContinueLoading(parameters(1f, 0)))
            assertFalse(control.shouldContinueLoading(parameters(1f, 8_000)))
            assertTrue(control.shouldContinueLoading(parameters(2f, 8_000)))
            assertFalse(control.shouldContinueLoading(parameters(2f, 16_000)))
            assertTrue(control.shouldContinueLoading(parameters(3f, 16_000)))
            assertFalse(control.shouldContinueLoading(parameters(3f, 24_000)))
            assertTrue(control.shouldStartPlayback(parameters(3f, 2_250)))
            assertFalse(control.shouldStartPlayback(parameters(3f, 2_000)))
            assertTrue(control.shouldStartPlayback(parameters(2f, 3_000, rebuffer = true)))
            assertFalse(control.shouldStartPlayback(parameters(2f, 2_900, rebuffer = true)))
            // Releasing a held boost lowers the ceiling without rebuilding or discarding samples.
            assertFalse(control.shouldContinueLoading(parameters(1f, 16_000)))
        } finally { control.onReleased(PlayerId.UNSET) }
    }
    @Test fun byte_ceiling_wins_even_below_time_target() {
        val control = VideoBufferPolicy.loadControl(); control.onPrepared(PlayerId.UNSET)
        val allocator = control.getAllocator(PlayerId.UNSET)
        val blocks = List((VideoBufferPolicy.SAMPLE_BYTES + allocator.individualAllocationLength - 1) /
            allocator.individualAllocationLength) { allocator.allocate() }
        try {
            assertFalse(control.shouldContinueLoading(parameters(3f, 300)))
            assertTrue(control.shouldStartPlayback(parameters(3f, 300)))
        } finally { blocks.forEach(allocator::release); control.onReleased(PlayerId.UNSET) }
    }
}
