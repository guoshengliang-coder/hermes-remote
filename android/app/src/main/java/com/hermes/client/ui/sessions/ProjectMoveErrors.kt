package com.hermes.client.ui.sessions

import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.data.network.GatewayRpcException

// Upstream `session.workspace.move` / `session.cwd.set` error codes (tui_gateway/methods_session.py).
private const val RPC_SESSION_NOT_FOUND = 4007
private const val RPC_SESSION_BUSY = 4009
private const val RPC_CWD_MISSING = 4017

/** Maps a failed move-to-project RPC to the registered product error (docs/ERROR_HANDLING.md). */
fun workspaceMoveError(error: Throwable): AppError {
    val code = when ((error as? GatewayRpcException)?.code) {
        RPC_SESSION_BUSY -> AppErrorCode.SESSION_BUSY
        RPC_CWD_MISSING -> AppErrorCode.PROJECT_FOLDER_MISSING
        RPC_SESSION_NOT_FOUND -> AppErrorCode.SESSION_NOT_FOUND
        else -> AppErrorCode.PROJECT_MOVE_FAILED
    }
    return AppError(
        code = code,
        retryable = code != AppErrorCode.SESSION_NOT_FOUND,
        technicalCause = error.message,
        stage = "workspace_move",
    )
}
