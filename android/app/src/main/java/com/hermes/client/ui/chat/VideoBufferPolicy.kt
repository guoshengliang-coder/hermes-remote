package com.hermes.client.ui.chat

import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl

/** Disk precaching has its own budget; these bounds only concern Media3 compressed samples. */
internal object VideoBufferPolicy {
    const val SAMPLE_BYTES = 24 * 1024 * 1024
    fun maxMediaMs(speed: Float) = (8_000 * speed.coerceIn(1f, 3f)).toLong()
    fun mayPrecache(state: VideoPlayerState): Boolean = !state.wantsPlay ||
        !state.buffering && state.duration > 0 &&
        (state.buffered >= state.duration || state.buffered - state.position >= 4_000 * state.speed)

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun loadControl(): LoadControl {
        return object : DefaultLoadControl(
            androidx.media3.exoplayer.upstream.DefaultAllocator(true, androidx.media3.common.C.DEFAULT_BUFFER_SEGMENT_SIZE),
            /* streaming/local min */ 2_000, 2_000,
            /* streaming/local max */ 24_000, 24_000,
            /* streaming/local startup */ 750, 750,
            /* streaming/local rebuffer */ 1_500, 1_500,
            SAMPLE_BYTES, false, false, /* back buffer */ 0, false,
        ) {
            override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
                // Base owns minimum/start/rebuffer scaling, allocator and all lifecycle callbacks.
                // Gating maximum dynamically avoids rebuilding the player for a held gesture.
                val loading = super.shouldContinueLoading(parameters)
                return loading && parameters.bufferedDurationUs < maxMediaMs(parameters.playbackSpeed) * 1_000
            }
        }
    }
}
