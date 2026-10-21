package com.hermes.client.data.error

import androidx.media3.common.PlaybackException
import com.hermes.client.data.network.HermesApiException

fun videoPlaybackError(error: Throwable): AppError {
    generateSequence(error) { it.cause }.take(16).filterIsInstance<HermesApiException>().firstOrNull()?.let {
        return artifactDownloadError(it, "video_playback").copy(technicalCause = "HTTP ${it.code}; ${AppErrorCode.fromValue(it.errorCode.orEmpty())?.value.orEmpty()}")
    }
    val unsupported = (error as? PlaybackException)?.errorCode in setOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    )
    return AppError(if (unsupported) AppErrorCode.VIDEO_UNSUPPORTED else AppErrorCode.VIDEO_PLAYBACK_FAILED,
        retryable = !unsupported, technicalCause = (error as? PlaybackException)?.errorCodeName ?: error::class.java.simpleName, stage = "video_playback")
}
