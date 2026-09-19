package com.hermes.client.data.error

import com.hermes.client.data.network.GatewayReadinessTimeoutException
import com.hermes.client.data.network.GatewayRpcException

/**
 * How many connections have to die in a row before the client may say the connection "keeps"
 * dropping rather than "was" interrupted.
 *
 * Three, because one is an ordinary blip and two is a coincidence; by the third the far end has a
 * pattern, and telling the user to retry immediately would be advice we already know is wrong.
 */
private const val UNSTABLE_CONNECTION_THRESHOLD = 3

/**
 * Why starting a new conversation failed, in terms the user can act on.
 *
 * Every failure here used to print `HR-RPC-001` — a transport code that says the request failed and
 * nothing about why. In HG-65 the request had not failed in any sense the user could act on: the
 * socket was being accepted and dropped every five seconds, `session.create` died with whichever
 * one carried it, and the answer the person got was a two-second toast naming a code that could
 * equally have meant a server bug. They tapped it nine more times.
 *
 * [droppedConnections] is the client's own count of connections that died without proving
 * themselves, which is what lets this distinguish "retry now" from "this will keep happening".
 */
fun newChatFailure(error: Throwable, droppedConnections: Int): AppError = when {
    // The socket never finished its handshake, which has its own registered meaning and copy.
    error is GatewayReadinessTimeoutException -> AppError(
        code = AppErrorCode.HANDSHAKE_TIMEOUT,
        retryable = true,
        technicalCause = error.message,
        stage = "session.create",
    )
    // code 0 is the client's own "this call died with its transport", not an answer from upstream.
    error is GatewayRpcException && error.code == 0 -> AppError(
        code = if (droppedConnections >= UNSTABLE_CONNECTION_THRESHOLD) {
            AppErrorCode.CONNECTION_UNSTABLE
        } else {
            AppErrorCode.CONNECTION_INTERRUPTED
        },
        retryable = true,
        technicalCause = "${error.message} (dropped=$droppedConnections)",
        stage = "session.create",
    )
    // Upstream answered and refused. That is a real RPC failure and keeps the transport code.
    error is GatewayRpcException -> AppError(
        code = AppErrorCode.RPC_FAILED,
        retryable = true,
        technicalCause = "${error.code}: ${error.message}",
        stage = "session.create",
    )
    else -> AppError(
        code = AppErrorCode.RPC_FAILED,
        retryable = true,
        technicalCause = error.message,
        stage = "session.create",
    )
}
