package com.hermes.client.data.repository

import com.hermes.client.data.error.AppErrorCode

/**
 * The gateway's refusals for `handoff.request`, each a different thing to do about it.
 *
 * They arrived as one generic "operation failed" before, which was wrong in the most useful case:
 * 4009 means "wait for this turn to end", and telling someone to retry an in-flight conversation
 * sends them round the same loop.
 */
fun handoffErrorCode(rpcCode: Int?): AppErrorCode = when (rpcCode) {
    4009 -> AppErrorCode.HANDOFF_SESSION_BUSY
    4025 -> AppErrorCode.HANDOFF_CHANNEL_DISABLED
    4026 -> AppErrorCode.HANDOFF_NO_TARGET
    4027 -> AppErrorCode.HANDOFF_IN_FLIGHT
    else -> AppErrorCode.RPC_FAILED
}

/** Terminal states of `handoff.state`. */
enum class HandoffPhase { PENDING, RUNNING, COMPLETED, FAILED, UNKNOWN }

fun handoffPhase(raw: String?): HandoffPhase = when (raw?.trim()?.lowercase()) {
    "pending" -> HandoffPhase.PENDING
    "running" -> HandoffPhase.RUNNING
    "completed" -> HandoffPhase.COMPLETED
    "failed" -> HandoffPhase.FAILED
    else -> HandoffPhase.UNKNOWN
}
