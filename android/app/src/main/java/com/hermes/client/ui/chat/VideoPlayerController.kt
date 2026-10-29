package com.hermes.client.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode

internal val VIDEO_SPEEDS = listOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
internal val VIDEO_HOLD_SPEEDS = listOf(2f, 2.5f, 3f)
internal fun speedLabel(speed: Float) = "${java.math.BigDecimal(speed.toString()).stripTrailingZeros().toPlainString()}×"

internal data class VideoPlayerState(
    val position: Long = 0,
    val duration: Long = 0,
    val buffered: Long = 0,
    val wantsPlay: Boolean = false,
    val buffering: Boolean = false,
    val hasFrame: Boolean = false,
    val ended: Boolean = false,
    val speed: Float = 1f,
    val boosting: Boolean = false,
    val speedError: AppError? = null,
    val seeking: Boolean = false,
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
    initialSpeed: Float = 1f,
    private val speedSelection: (Float) -> Unit = {},
) : AutoCloseable {
    var state by mutableStateOf(VideoPlayerState(position = initialPosition, duration = initialDuration))
        private set
    private var priority: AutoCloseable? = null
    private var closed = false
    private var selectedSpeed = initialSpeed.takeIf { it in VIDEO_SPEEDS } ?: 1f
    private var requestedSpeed = selectedSpeed
    private var holding = false
    private var previewingSeek = false
    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                endBoost()
                refresh()
                previewingSeek = false
                state = state.copy(wantsPlay = false, buffering = false, ended = true, seeking = false)
                savePosition(0)
                playIntent(false)
                releasePriority()
            } else state = state.copy(buffering = state.wantsPlay && playbackState == Player.STATE_BUFFERING)
        }
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            val actual = playbackParameters.speed
            state = state.copy(speed = actual, boosting = holding && actual > selectedSpeed,
                speedError = if (actual != requestedSpeed || playbackParameters.pitch != 1f) speedFailure() else null)
            if (!holding) { selectedSpeed = actual; speedSelection(actual) }
        }
        override fun onRenderedFirstFrame() {
            state = state.copy(hasFrame = true, seeking = false)
            if (previewingSeek) {
                previewingSeek = false
                if (!state.wantsPlay) {
                    refresh()
                    player.stop()
                    state = state.copy(buffered = state.position)
                    releasePriority()
                }
            }
        }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Audio focus/noisy-route pauses must also stop the network loader.
            if (!playWhenReady && state.wantsPlay) pause()
        }
        override fun onPlayerError(error: PlaybackException) { pause(); failure(error) }
    }
    init { player.addListener(listener); player.seekTo(initialPosition); applySpeed(selectedSpeed) }

    private fun speedFailure() = AppError(AppErrorCode.VIDEO_SPEED_UNSUPPORTED, retryable = false, stage = "video_speed")
    private fun applySpeed(speed: Float) {
        requestedSpeed = speed
        try {
            player.playbackParameters = PlaybackParameters(speed, 1f)
            // ExoPlayer reports accepted parameters synchronously; later renderer adjustments also
            // arrive through the listener. Never label the requested value as actually applied.
            listener.onPlaybackParametersChanged(player.playbackParameters)
        } catch (_: IllegalArgumentException) { state = state.copy(speedError = speedFailure()) }
        catch (_: IllegalStateException) { state = state.copy(speedError = speedFailure()) }
    }
    fun selectSpeed(speed: Float) {
        if (closed || speed !in VIDEO_SPEEDS) return
        holding = false; selectedSpeed = speed
        applySpeed(speed)
        if (state.speedError != null) { selectedSpeed = state.speed; speedSelection(selectedSpeed) }
    }
    fun beginBoost(target: Float) {
        if (closed || !player.isPlaying || target !in VIDEO_HOLD_SPEEDS) return
        holding = true
        applySpeed(maxOf(selectedSpeed, target))
    }
    fun endBoost() {
        if (!holding || closed) return
        holding = false
        applySpeed(selectedSpeed)
        state = state.copy(boosting = false)
    }

    fun play() {
        if (closed || state.wantsPlay) return
        if (state.ended) { player.seekTo(0); state = state.copy(position = 0, ended = false) }
        state = state.copy(wantsPlay = true, buffering = true)
        previewingSeek = false
        playIntent(true)
        if (priority == null) priority = lease()
        player.prepare()
        player.play()
    }

    fun pause() {
        if (closed) return
        endBoost()
        refresh()
        previewingSeek = false
        state = state.copy(wantsPlay = false, buffering = false, buffered = state.position,
            hasFrame = state.hasFrame && !state.seeking, seeking = false)
        playIntent(false)
        player.pause()
        // pause() alone continues prefetch. stop() cancels loading but keeps playlist/position;
        // PlayerView.keepContentOnPlayerReset keeps the last frame visible on the same surface.
        player.stop()
        releasePriority()
    }

    fun seek(position: Long) {
        if (closed) return
        endBoost()
        val target = position.coerceIn(0, state.duration.coerceAtLeast(0))
        state = state.copy(position = target, buffered = target, ended = false, hasFrame = false, seeking = true)
        savePosition(target)
        if (!state.wantsPlay) player.pause() // Ended players may still have playWhenReady=true.
        player.seekTo(target)
        // A stopped player cannot render a seek. Prepare just until the target frame arrives;
        // keep audio paused, then stop loading again in onRenderedFirstFrame.
        if (!state.wantsPlay) {
            previewingSeek = true
            if (priority == null) priority = lease()
            player.prepare()
        }
    }

    fun annotationPosition(): Long? {
        if (closed || state.seeking || state.buffering || !state.hasFrame) return null
        refresh() // Read the engine at the button press, not the 250ms UI polling snapshot.
        return state.position
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
        endBoost()
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
