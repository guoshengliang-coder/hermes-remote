package com.hermes.client.ui.chat

import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
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
    private fun controller(): VideoPlayerController {
        every { player.addListener(capture(listener)) } just Runs
        every { player.currentPosition } returns 4_500
        every { player.duration } returns 60_000
        every { player.bufferedPosition } returns 12_000
        return VideoPlayerController(player, 4_000, { priority }, { saved = it }, { intent = it }, { failure = it })
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
    @Test fun paused_seek_changes_position_without_preparing_or_loading() {
        val c = controller(); c.refresh(); c.seek(90_000)
        verify { player.seekTo(60_000) }
        assertEquals(60_000, c.state.position); assertEquals(60_000, saved)
        verify(exactly = 0) { player.prepare(); player.play() }
    }
    @Test fun duration_format_handles_hours_and_unknown_negative_times() {
        assertEquals("0:00", videoTime(-1)); assertEquals("1:05", videoTime(65_000))
        assertEquals("1:02:03", videoTime(3_723_000))
    }
}
