package com.hermes.client.data.error

fun videoCacheError(failure: Throwable) = AppError(AppErrorCode.VIDEO_CACHE_FAILED,
    retryable = true, stage = "video_cache_management", technicalCause = failure::class.java.simpleName)

fun videoCacheLowStorage() = AppError(AppErrorCode.VIDEO_CACHE_LOW_STORAGE,
    retryable = false, stage = "video_cache_storage")
