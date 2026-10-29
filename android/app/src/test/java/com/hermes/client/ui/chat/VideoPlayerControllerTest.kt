package com.hermes.client.ui.chat

import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test

class VideoPlayerControllerTest {
    private val player = mockk<Player>(relaxed = true)
    private val priority = mockk<AutoCloseable>(relaxed = true)
    private val listener = slot<Player.Listener>()
    private var saved = -1L
    private var intent = false
    private var failure: PlaybackException? = null
    private fun controller(initialSpeed: Float = 1f): VideoPlayerController {
        var parameters = PlaybackParameters.DEFAULT
        every { player.playbackParameters } answers { parameters }
        every { player.playbackParameters = any() } answers { parameters = firstArg() }
        every { player.isPlaying } returns true
        every { player.addListener(capture(listener)) } just Runs
        every { player.currentPosition } returns 4_500
        every { player.duration } returns 60_000
        every { player.bufferedPosition } returns 12_000
        return VideoPlayerController(player, 4_000, { priority }, { saved = it }, { intent = it }, { failure = it }, initialSpeed = initialSpeed)
    }
    @Test fun pause_stops_loading_and_releases_priority_without_releasing_player_or_losing_frame() {
        val c = controller(); c.play(); listener.captured.onRenderedFirstFrame(); c.refresh()
        assertEquals(12_000, c.state.buffered)
        c.pause()
        verifyOrder { player.pause(); player.stop(); priority.close() }
        verify(exactly = 0) { player.release() }
        assertTrue(c.state.hasFrame); assertFalse(c.state.wantsPlay); assertFalse(intent)
        assertEquals(4_500, saved); assertEquals(60_000, c.state.duration)
        assertEquals(4_500, c.state.buffered)
        c.play(); assertTrue(intent)
        verify(exactly = 2) { player.prepare() }
        c.close(); c.close()
        verify(exactly = 1) { player.release() }
        verify(exactly = 2) { priority.close() }
    }
    @Test fun audio_focus_pause_and_error_also_cancel_loading() {
        val c = controller(); c.play()
        listener.captured.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)
        verify { player.stop() }; assertFalse(intent)
        c.play()
        val error = PlaybackException("fixture", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        listener.captured.onPlayerError(error)
        assertSame(error, failure); assertFalse(c.state.wantsPlay)
        verify(exactly = 2) { priority.close() }
    }
    @Test fun ended_replays_from_zero_and_close_does_not_save_end_as_resume_position() {
        val c = controller(); c.play()
        every { player.currentPosition } returns 60_000
        listener.captured.onPlaybackStateChanged(Player.STATE_ENDED)
        assertTrue(c.state.ended); assertFalse(intent); assertEquals(0, saved)
        c.close(); assertEquals(0, saved)
        val second = controller(); second.play(); listener.captured.onPlaybackStateChanged(Player.STATE_ENDED)
        second.play(); verify { player.seekTo(0) }; assertFalse(second.state.ended)
    }
    @Test fun paused_seek_prepares_only_until_target_frame_and_never_starts_audio() {
        val c = controller(); c.refresh(); c.seek(90_000)
        verify { player.seekTo(60_000); player.prepare() }
        verifyOrder { player.pause(); player.seekTo(60_000); player.prepare() }
        verify(exactly = 0) { player.play() }
        assertTrue(c.state.seeking); assertNull(c.annotationPosition())
        every { player.currentPosition } returns 60_000
        listener.captured.onRenderedFirstFrame()
        assertFalse(c.state.seeking); assertFalse(c.state.wantsPlay)
        verify { player.stop(); priority.close() }
        assertEquals(60_000L, c.annotationPosition())
    }
    @Test fun annotation_reads_engine_position_at_press_and_background_cancels_pending_seek() {
        val c = controller(); c.refresh(); listener.captured.onRenderedFirstFrame()
        every { player.currentPosition } returns 5_123
        assertEquals(5_123L, c.annotationPosition())
        c.seek(7_000); c.pause()
        assertNull(c.annotationPosition()); assertFalse(c.state.seeking)
        verify { player.stop(); priority.close() }
    }
    @Test fun duration_format_handles_hours_and_unknown_negative_times() {
        assertEquals("0:00", videoTime(-1)); assertEquals("1:05", videoTime(65_000))
        assertEquals("1:02:03", videoTime(3_723_000))
    }
    @Test fun normal_speed_defaults_to_one_and_all_nine_choices_preserve_pitch() {
        val c = controller()
        assertEquals(1f, c.state.speed)
        VIDEO_SPEEDS.forEach { speed ->
            c.selectSpeed(speed)
            assertEquals(speed, c.state.speed)
            assertEquals(1f, player.playbackParameters.pitch)
            assertNull(c.state.speedError)
        }
        c.pause(); c.play(); assertEquals(3f, c.state.speed)
        c.close()
        assertEquals(1f, controller().state.speed)
        assertEquals(1.5f, controller(1.5f).state.speed)
    }
    @Test fun held_speed_is_absolute_never_slows_and_releases_to_selected_speed() {
        val c = controller(); c.play(); c.selectSpeed(1.5f)
        c.beginBoost(2f); assertEquals(2f, c.state.speed); assertTrue(c.state.boosting)
        c.endBoost(); assertEquals(1.5f, c.state.speed); assertFalse(c.state.boosting)
        c.selectSpeed(3f); c.beginBoost(2f); assertEquals(3f, c.state.speed); assertFalse(c.state.boosting)
        c.endBoost(); c.selectSpeed(1.25f); c.beginBoost(3f); c.pause()
        assertEquals(1.25f, c.state.speed); assertFalse(c.state.boosting)
        every { player.isPlaying } returns false
        c.beginBoost(2.5f); assertEquals(1.25f, c.state.speed)
    }
    @Test fun cancellation_seek_end_and_close_restore_constant_speed() {
        val c = controller(); c.play(); c.selectSpeed(.75f)
        c.beginBoost(2.5f); c.seek(1_000); assertEquals(.75f, c.state.speed)
        c.beginBoost(2f); listener.captured.onPlaybackStateChanged(Player.STATE_ENDED)
        assertEquals(.75f, c.state.speed)
        c.play(); c.beginBoost(3f); c.close()
        assertEquals(.75f, player.playbackParameters.speed)
    }
    @Test fun rejected_speed_reports_actual_parameters_and_keeps_playing() {
        val c = controller(); c.play()
        every { player.playbackParameters = any() } just Runs // Engine declines the change.
        c.selectSpeed(2f)
        assertEquals(1f, c.state.speed)
        assertEquals("HR-FILE-018", c.state.speedError!!.code.value)
        assertTrue(c.state.wantsPlay)
        every { player.playbackParameters = any() } throws IllegalArgumentException("private")
        c.selectSpeed(3f)
        assertEquals(1f, c.state.speed)
        assertFalse(c.state.speedError!!.sanitizedDiagnostic().contains("private"))
    }

}
