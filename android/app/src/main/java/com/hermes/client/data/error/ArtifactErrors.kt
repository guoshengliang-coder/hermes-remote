package com.hermes.client.data.error

import com.hermes.client.data.network.HermesApiException

/**
 * Map a failed artifact download onto the shared error model.
 *
 * The Connector answers `GET /api/files` with a request-scoped status: 403 when the path escapes
 * `FILES_ROOT`, 413 above `MAX_FILE_BYTES`, 404/400 when the file is gone or is not a regular file.
 * Every one of those used to surface as the same "couldn't read attachment" toast carrying
 * `HR-FILE-001` — a code whose registered meaning is an *outgoing* attachment the user picked — so
 * neither the user nor a diagnosing agent could tell a permission problem from a transfer problem.
 * Neither side logged the rejection either, which is how a 2026-09-05 investigation ended up
 * reading configuration by hand to find out why a download had failed.
 *
 * [stage] names the step for diagnostics; the technical cause is preserved but only reaches the
 * copy-diagnostics path, redacted by [AppError.sanitizedDiagnostic].
 */
fun artifactDownloadError(error: Throwable, stage: String = "artifact_download"): AppError {
    val status = (error as? HermesApiException)?.code
    val code = when (status) {
        403 -> AppErrorCode.ARTIFACT_FORBIDDEN
        413 -> AppErrorCode.ARTIFACT_TOO_LARGE
        404 -> AppErrorCode.ARTIFACT_MISSING
        // The Connector reports a path that resolved to something other than a regular file as
        // `invalid_file`, and a malformed one as `invalid_path`; both mean "not downloadable",
        // not "try again".
        400 -> AppErrorCode.ARTIFACT_MISSING
        else -> AppErrorCode.ARTIFACT_DOWNLOAD_FAILED
    }
    return AppError(
        code = code,
        retryable = code == AppErrorCode.ARTIFACT_DOWNLOAD_FAILED,
        technicalCause = buildString {
            status?.let { append("HTTP ").append(it).append(' ') }
            append(error.message ?: error::class.java.simpleName)
        }.trim(),
        stage = stage,
    )
}
