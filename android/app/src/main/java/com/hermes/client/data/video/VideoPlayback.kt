package com.hermes.client.data.video

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import java.io.File

/** A prepared, owner-bound generation survives seeks and recoverable network errors. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoPlayback(
    private val localFile: File? = null,
    private val repository: ArtifactRangeRepository? = null,
    private val source: ArtifactRangeRepository.Source? = null,
    initialPosition: Long = 0,
    private val rememberPosition: (Long) -> Unit = {},
) {
    var positionMs: Long = initialPosition
        set(value) { field = value; rememberPosition(value) }
    fun lease(): AutoCloseable? = source?.let { repository!!.playbackLease(it) }
    fun mediaSource(context: Context): ProgressiveMediaSource {
        val factory = if (localFile != null) DefaultDataSource.Factory(context)
            else DataSource.Factory { ArtifactVideoDataSource(repository!!, source!!) }
        val uri = localFile?.let(Uri::fromFile) ?: Uri.parse("hermes-video://attachment/video")
        return ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(uri))
    }
}
