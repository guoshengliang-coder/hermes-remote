package com.hermes.client.data.error

import com.hermes.client.data.network.HermesApiException
import kotlinx.serialization.SerializationException
import java.io.IOException

/**
 * Why a conversation's transcript could not be loaded, in terms the person holding the phone can
 * act on.
 *
 * Every one of these used to print `HR-RPC-001` — a transport code that says the request failed and
 * nothing about why. On 2026-09-19 the Mac's Hermes answered 5xx for every affected transcript
 * because its database had drifted; the phone said `HR-RPC-001`, the relay's own log read
 * `outcome=streamed` because it had forwarded the 500 faithfully, and the Mac looked healthy. Two
 * hours went into finding a cause the status code names outright.
 *
 * The three answers differ in what the person should do next, which is the only reason to separate
 * them: check the Mac, wait and retry, or update the app.
 */
fun historyFailure(error: Throwable): AppError = when {
    // Upstream answered and the answer was an error. The conversation and the connection are both
    // fine; retrying sends the same request into the same fault.
    error is HermesApiException && error.code >= 500 -> AppError(
        code = AppErrorCode.HISTORY_UPSTREAM_FAILED,
        retryable = false,
        technicalCause = "${error.code} ${error.message}",
        stage = "history",
    )
    // Upstream answered with something else it refused — 4xx that is not one of the cases the
    // caller handles itself (404 for a conversation that is gone, 401, HR-BIND-011). Keeping the
    // transport code here is honest: we do not know what it means, and we say so with the number.
    error is HermesApiException -> AppError(
        code = AppErrorCode.RPC_FAILED,
        retryable = true,
        technicalCause = "${error.code} ${error.message}",
        stage = "history",
    )
    // The bytes arrived and this build cannot read them. The same bytes parse the same way next
    // time, so a retry is a lie; an app update is the actual fix (the 2026-09-18 content-block
    // arrays were exactly this).
    error is SerializationException -> AppError(
        code = AppErrorCode.HISTORY_UNREADABLE,
        retryable = false,
        technicalCause = error.message,
        stage = "history",
    )
    // The request died with its transport. This is the one where retrying genuinely helps.
    error is IOException -> AppError(
        code = AppErrorCode.CONNECTION_INTERRUPTED,
        retryable = true,
        technicalCause = error.message,
        stage = "history",
    )
    // Unclassified. The bucket stays, but it carries the exception type now — the old version
    // carried nothing at all, which is what made the 2026-09-19 diagnosis start in the wrong place.
    else -> AppError(
        code = AppErrorCode.RPC_FAILED,
        retryable = true,
        technicalCause = "${error::class.simpleName}: ${error.message}",
        stage = "history",
    )
}
