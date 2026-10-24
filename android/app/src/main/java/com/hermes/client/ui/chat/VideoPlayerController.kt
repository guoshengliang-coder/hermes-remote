package com.hermes.client.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException

internal data class VideoPlayerState(
    val position: Long = 0,
    val duration: Long = 0,
    val buffered: Long = 0,
    val wantsPlay: Boolean = false,
    val buffering: Boolean = false,
    val hasFrame: Boolean = false,
    val ended: Boolean = false,
)

/** Owns loading independently of the persistent PlayerView/surface. All calls are on the UI thread. */
internal class VideoPlayerController(
    val player: Player,
    initialPosition: Long,
    private val lease: () -> AutoCloseable?,
    private val savePosition: (Long) -> Unit,
    private val playIntent: (Boolean) -> Unit,
    private val failure: (PlaybackException) -> Unit,
    initialDuration: Long = 0,
) : AutoCloseable {
    var state by mutableStateOf(VideoPlayerState(position = initialPosition, duration = initialDuration))
        private set
    private var priority: AutoCloseable? = null
    private var closed = false
    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                refresh()
                state = state.copy(wantsPlay = false, buffering = false, ended = true)
                savePosition(0)
                playIntent(false)
                releasePriority()
            } else state = state.copy(buffering = state.wantsPlay && playbackState == Player.STATE_BUFFERING)
        }
        override fun onRenderedFirstFrame() { state = state.copy(hasFrame = true) }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Audio focus/noisy-route pauses must also stop the network loader.
            if (!playWhenReady && state.wantsPlay) pause()
        }
        override fun onPlayerError(error: PlaybackException) { pause(); failure(error) }
    }
    init { player.addListener(listener); player.seekTo(initialPosition) }

    fun play() {
        if (closed || state.wantsPlay) return
        if (state.ended) { player.seekTo(0); state = state.copy(position = 0, ended = false) }
        state = state.copy(wantsPlay = true, buffering = true)
        playIntent(true)
        priority = lease()
        player.prepare()
        player.play()
    }

    fun pause() {
        if (closed) return
        refresh()
        state = state.copy(wantsPlay = false, buffering = false, buffered = state.position)
        playIntent(false)
        player.pause()
        // pause() alone continues prefetch. stop() cancels loading but keeps playlist/position;
        // PlayerView.keepContentOnPlayerReset keeps the last frame visible on the same surface.
        player.stop()
        releasePriority()
    }

    fun seek(position: Long) {
        val target = position.coerceIn(0, state.duration.coerceAtLeast(0))
        state = state.copy(position = target, buffered = target, ended = false)
        savePosition(target)
        player.seekTo(target)
    }

    fun refresh() {
        if (closed) return
        val position = player.currentPosition.coerceAtLeast(0)
        val duration = player.duration.takeIf { it > 0 } ?: state.duration
        state = state.copy(position = position, duration = duration,
            buffered = if (state.wantsPlay) player.bufferedPosition.coerceIn(position, duration.coerceAtLeast(position)) else position)
        savePosition(if (state.ended) 0 else position)
    }

    private fun releasePriority() { priority?.close(); priority = null }
    override fun close() {
        if (closed) return
        refresh()
        closed = true
        player.removeListener(listener)
        player.release()
        releasePriority()
    }
}

internal fun videoTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1_000
    return if (seconds >= 3_600) java.lang.String.format(java.util.Locale.ROOT, "%d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
    else java.lang.String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
