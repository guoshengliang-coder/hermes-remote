package com.hermes.client.data.network

import com.hermes.client.data.diagnostics.DebugLog
import com.hermes.client.data.auth.AccountSessionManager
import com.hermes.client.data.auth.AccountTransportMode
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Fixed values only: recovery logs must not carry endpoints, credentials or exception text. */
enum class ConnectionRecoveryTrigger { AUTOMATIC, MANUAL, FOREGROUND, COLD_START, HEALTH_RECOVERED, RPC_TIMEOUT, STALLED }

object ConnectionRecoveryTrace {
    private val process = UUID.randomUUID().toString().take(8)
    private val sequence = AtomicLong()

    fun start(trigger: ConnectionRecoveryTrigger): String = "$process-${sequence.incrementAndGet()}".also {
        DebugLog.log("recovery", "cycle=$it trigger=$trigger phase=start")
    }

    fun finish(cycle: String, error: AppError? = null) {
        DebugLog.log("recovery", "cycle=$cycle phase=complete code=${error?.code?.value ?: "none"}")
    }
}

sealed interface HealthRecheckState {
    data object Idle : HealthRecheckState
    data class Checking(val cycle: String) : HealthRecheckState
    data class Finished(val cycle: String, val error: AppError?) : HealthRecheckState
}

/** Known wire failures are stronger evidence than public-entry reachability diagnostics. */
internal fun connectionProbeError(code: String?): AppError? {
    val known = AppErrorCode.fromValue(code) ?: return null
    if (!known.value.startsWith("HR-CONN-") && known !in ACCOUNT_CONNECTION_ERRORS) return null
    return AppError(known, retryable = known !in NON_RETRYABLE_CONNECTION_ERRORS, stage = "connection_check")
}

internal fun GatewayHealth.checkError(): AppError? = when (this) {
    is GatewayHealth.Healthy -> if (running) null else connectionProbeError("HR-CONN-006")
    GatewayHealth.DeviceOffline -> connectionProbeError("HR-CONN-001")
    is GatewayHealth.GatewayUnreachable -> connectionProbeError(detail)
        ?: AppError(if (detail == "unauthorized") AppErrorCode.AUTHENTICATION_FAILED else AppErrorCode.CONNECTION_FAILED,
            retryable = detail != "unauthorized", stage = "connection_check")
    GatewayHealth.Unknown -> AppError(AppErrorCode.CONNECTION_FAILED, retryable = true, stage = "connection_check")
}

private val ACCOUNT_CONNECTION_ERRORS = setOf(
    AppErrorCode.ACCOUNT_SESSION_EXPIRED, AppErrorCode.ACCOUNT_SESSION_REVOKED,
    AppErrorCode.ACCOUNT_SESSION_REUSED, AppErrorCode.ACCOUNT_RATE_LIMITED,
    AppErrorCode.ACCOUNT_DISABLED, AppErrorCode.ACCOUNT_SERVICE_UNAVAILABLE,
    AppErrorCode.ACCOUNT_DESKTOP_REQUIRED, AppErrorCode.ACCOUNT_DEVICE_REQUIRED,
    AppErrorCode.ACCOUNT_DEVICE_UNAVAILABLE,
)
private val NON_RETRYABLE_CONNECTION_ERRORS = setOf(
    AppErrorCode.ACCOUNT_SESSION_EXPIRED, AppErrorCode.ACCOUNT_SESSION_REVOKED,
    AppErrorCode.ACCOUNT_SESSION_REUSED, AppErrorCode.ACCOUNT_DISABLED,
    AppErrorCode.ACCOUNT_DEVICE_REQUIRED, AppErrorCode.ACCOUNT_DEVICE_UNAVAILABLE,
)

/** Foreground ownership is checked by the monitor; persisted authorization gates are checked here. */
internal fun recoverAccountConnection(
    sessions: AccountSessionManager,
    client: HermesGatewayClient,
    trigger: ConnectionRecoveryTrigger,
): Boolean = sessions.transportMode() == AccountTransportMode.ACCOUNT && client.recoverIfNeeded(trigger)
