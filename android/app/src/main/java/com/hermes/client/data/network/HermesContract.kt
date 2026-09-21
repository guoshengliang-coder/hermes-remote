package com.hermes.client.data.network

import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import kotlinx.serialization.Serializable

/** Connector-owned route; must match `CONTRACT_REPORT_PATH` in `connector/src/hermes-contract.ts`. */
const val HERMES_CONTRACT_REPORT_PATH = "/api/hermes-remote/contract"

/**
 * The Connector's report on the Mac's Hermes against the REST contract this app depends on
 * (docs/HERMES_CONTRACT.md §2, "Connector contract check"). Every field defaults, so a newer
 * Connector adding fields — or an older one omitting them — never makes the report unreadable.
 */
@Serializable
data class HermesContractReportDto(
    val schema: Int = 0,
    val status: String = "",
    val code: String? = null,
    val retryable: Boolean = false,
    val hermesVersion: String? = null,
    val minimumHermesVersion: String? = null,
    val versionBelowMinimum: Boolean = false,
    val missing: List<HermesContractMissingDto> = emptyList(),
    val checkedPaths: Int = 0,
    val reason: String? = null,
    val checkedAt: String? = null,
)

@Serializable
data class HermesContractMissingDto(
    val method: String = "",
    val path: String = "",
    val tier: String = "",
    val feature: String = "",
)

enum class HermesContractSeverity { BREAKING, DEGRADED }

/**
 * What the shell shows about the Mac's Hermes. [features] are the Connector's feature keys
 * (`cron`, `skills`, …) in report order, required ones first; the UI localizes them.
 */
data class HermesContractNotice(
    val severity: HermesContractSeverity,
    val error: AppError,
    val features: List<String>,
    val hermesVersion: String?,
)

private val COMPAT_CODES = setOf(
    AppErrorCode.HERMES_INCOMPATIBLE,
    AppErrorCode.HERMES_FEATURES_MISSING,
    AppErrorCode.HERMES_BELOW_MINIMUM,
)

/**
 * Null when there is nothing to show: compatible, `unknown` (the check could not look — never
 * shown as a fault), or a schema this build does not understand. A code this build does not know
 * falls back to its severity's own code rather than being invented or dropped (AppErrorCode.fromValue).
 */
fun HermesContractReportDto.toNotice(): HermesContractNotice? {
    if (schema != 1) return null
    val severity = when (status) {
        "breaking" -> HermesContractSeverity.BREAKING
        "degraded" -> HermesContractSeverity.DEGRADED
        else -> return null
    }
    val fallback = when (severity) {
        HermesContractSeverity.BREAKING -> AppErrorCode.HERMES_INCOMPATIBLE
        HermesContractSeverity.DEGRADED -> AppErrorCode.HERMES_FEATURES_MISSING
    }
    val errorCode = AppErrorCode.fromValue(code)?.takeIf { it in COMPAT_CODES } ?: fallback
    val cause = buildString {
        append("hermes=").append(hermesVersion?.take(64) ?: "?")
        minimumHermesVersion?.let { append(" minimum=").append(it.take(64)) }
        if (versionBelowMinimum) append(" belowMinimum=true")
        if (missing.isNotEmpty()) {
            append(" missing=")
            append(missing.take(MAX_DIAGNOSTIC_ENTRIES).joinToString("; ") { "${it.method} ${it.path} (${it.tier})" })
            if (missing.size > MAX_DIAGNOSTIC_ENTRIES) append("; +").append(missing.size - MAX_DIAGNOSTIC_ENTRIES)
        }
    }
    return HermesContractNotice(
        severity = severity,
        error = AppError(errorCode, retryable = false, technicalCause = cause, stage = "hermes_contract"),
        features = missing.map { it.feature }.filter { it.isNotBlank() }.distinct(),
        hermesVersion = hermesVersion,
    )
}

private const val MAX_DIAGNOSTIC_ENTRIES = 12
