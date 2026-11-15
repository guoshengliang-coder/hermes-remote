package com.hermes.client.data.repository

import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.data.network.GatewayRpcException
import com.hermes.client.data.network.HermesApiException
import com.hermes.client.data.network.SessionArchiveMode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class SessionArchiveFailure(val error: AppError) : Exception(error.code.value)

fun archiveFailure(cause: Throwable): AppError = when (cause) {
    is SessionArchiveFailure -> cause.error
    else -> AppError(
        if (cause is GatewayRpcException && cause.code != 0 || cause is HermesApiException && cause.code in 400..499)
            AppErrorCode.SESSION_ARCHIVE_REFUSED else AppErrorCode.SESSION_ARCHIVE_UNCONFIRMED,
        retryable = false, technicalCause = cause.message, stage = "session_archive",
    )
}

/** -32601 is a definite non-execution. A timeout/disconnect never permits another write. */
internal suspend fun archiveWithBackend(
    mode: SessionArchiveMode, sessionId: String, archived: Boolean,
    rpc: suspend () -> JsonObject,
    rest: suspend () -> Unit,
    read: suspend () -> Boolean?,
) {
    if (mode == SessionArchiveMode.UNKNOWN) throw SessionArchiveFailure(AppError(
        AppErrorCode.ARCHIVE_INCOMPATIBLE, false, stage = "archive_backend"))
    try {
        if (mode == SessionArchiveMode.AUTHORITY) { rest(); return }
        val result = try { rpc() } catch (error: GatewayRpcException) {
            if (error.code != -32601) throw error
            rest()
            return
        }
        val flag = result["archived"]?.jsonPrimitive
        check(flag != null && !flag.isString && flag.booleanOrNull == archived &&
            result["session_key"]?.jsonPrimitive?.contentOrNull == sessionId) { "archive acknowledgement mismatch" }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (cause: Exception) {
        val error = archiveFailure(cause)
        if (error.code == AppErrorCode.SESSION_ARCHIVE_UNCONFIRMED) {
            try { if (read() == archived) return } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* keep the unknown result, never replay */ }
        }
        throw SessionArchiveFailure(error)
    }
}
