package com.hermes.client.data.error

/** Stable product error identifiers. Meanings are registered in docs/ERROR_HANDLING.md. */
enum class AppErrorCode(val value: String) {
    CONNECTION_FAILED("HR-CONN-002"),
    CONNECTION_INTERRUPTED("HR-CONN-004"),
    UNKNOWN("HR-UNKNOWN-001"),
}

/** Language-independent error data passed from a boundary to UI/notification renderers. */
data class AppError(
    val code: AppErrorCode,
    val retryable: Boolean,
    val technicalCause: String? = null,
    val stage: String? = null,
) {
    fun sanitizedDiagnostic(): String = buildString {
        append("code=").append(code.value)
        stage?.let { append("\nstage=").append(it.take(80)) }
        technicalCause?.let { append("\ncause=").append(redactDiagnostic(it)) }
    }
}

/** Defense-in-depth redaction for copyable diagnostic summaries. */
fun redactDiagnostic(value: String): String = value
    .replace(Regex("(?i)(token|authorization|cookie|password)\\s*[:=]\\s*[^\\s,;]+"), "$1=<redacted>")
    .replace(Regex("(?i)([?&](?:token|ticket|key|signature)=)[^&\\s]+"), "$1<redacted>")
    .take(1_000)
