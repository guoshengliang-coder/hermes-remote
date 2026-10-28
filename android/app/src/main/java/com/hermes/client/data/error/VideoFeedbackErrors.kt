package com.hermes.client.data.error

fun videoFeedbackError(failure: Throwable, export: Boolean = false) = AppError(
    if (export) AppErrorCode.VIDEO_NOTES_EXPORT else AppErrorCode.VIDEO_NOTES_STORAGE,
    retryable = true, stage = if (export) "video_notes_export" else "video_notes_storage",
    technicalCause = failure::class.java.simpleName,
)
