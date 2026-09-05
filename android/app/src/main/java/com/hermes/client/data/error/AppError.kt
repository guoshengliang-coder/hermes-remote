package com.hermes.client.data.error

/** Stable product error identifiers. Meanings are registered in docs/ERROR_HANDLING.md. */
enum class AppErrorCode(val value: String) {
    CONNECTION_FAILED("HR-CONN-002"),
    CONNECTION_INTERRUPTED("HR-CONN-004"),
    CONNECTOR_OFFLINE("HR-CONN-005"),
    RPC_FAILED("HR-RPC-001"),
    RPC_TIMEOUT("HR-RPC-002"),
    MODEL_LIST_FAILED("HR-RPC-003"),
    MODEL_SWITCH_FAILED("HR-RPC-004"),
    MODEL_DEFAULT_FAILED("HR-RPC-005"),
    MODEL_REASONING_FAILED("HR-RPC-006"),
    CONFIG_READ_FAILED("HR-CONFIG-001"),
    CONFIG_WRITE_FAILED("HR-CONFIG-002"),
    CONFIG_INVALID_URL("HR-CONFIG-003"),
    AUTHENTICATION_FAILED("HR-AUTH-001"),
    UPDATE_FAILED("HR-UPDATE-001"),
    UPDATE_CHECK_FAILED("HR-UPDATE-002"),
    UPDATE_ENQUEUE_FAILED("HR-UPDATE-003"),
    UPDATE_DOWNLOAD_FAILED("HR-UPDATE-004"),
    UPDATE_VERIFICATION_FAILED("HR-UPDATE-005"),
    UPDATE_FILE_MISSING("HR-UPDATE-006"),
    UPDATE_INSTALLER_FAILED("HR-UPDATE-007"),
    UPDATE_CLEANUP_FAILED("HR-UPDATE-008"),
    UPDATE_SUPERSEDED("HR-UPDATE-009"),
    FILE_READ_FAILED("HR-FILE-001"),
    TRANSCRIPT_FILE_FAILED("HR-FILE-002"),
    // Downloading a Hermes-delivered artifact. Split by cause: a 403/413/missing file is not worth
    // retrying, and "no app can open this type" is not a transfer failure at all — collapsing them
    // into one message left both the user and the agent unable to tell which had happened.
    ARTIFACT_FORBIDDEN("HR-FILE-003"),
    ARTIFACT_TOO_LARGE("HR-FILE-004"),
    ARTIFACT_MISSING("HR-FILE-005"),
    ARTIFACT_DOWNLOAD_FAILED("HR-FILE-006"),
    ATTACHMENT_NO_VIEWER("HR-FILE-007"),
    AVATAR_PHOTO_FAILED("HR-MEDIA-002"),
    TRANSCRIPT_IMAGE_FAILED("HR-MEDIA-003"),
    PROFILE_IDENTITY_SAVE_FAILED("HR-STORE-001"),
    SESSION_NOT_FOUND("HR-SESS-001"),
    PROJECT_FOLDER_MISSING("HR-SESS-003"),
    SESSION_BUSY("HR-SESS-004"),
    PROJECT_MOVE_FAILED("HR-SESS-005"),
    PROJECT_FELL_BACK_TO_DEFAULT("HR-SESS-006"),
    MESSAGE_SEND_FAILED("HR-SESS-007"),
    INSTALL_PERMISSION_REQUIRED("HR-PERM-003"),
    RUN_UNCONFIRMED("HR-SYNC-002"),
    NOTIFICATION_ACTION_FAILED("HR-NOTIF-001"),
    SEARCH_FAILED("HR-SEARCH-001"),
    LINK_NO_HANDLER("HR-LINK-001"),
    LINK_NOT_OPENABLE("HR-LINK-002"),
    UNKNOWN("HR-UNKNOWN-001"),
    ;

    /**
     * Compact display form for tight inline surfaces (a bubble status line, a badge): the code
     * without its `HR-` prefix, e.g. `SESS-007`. Still unique and still names the area; every
     * other surface — toasts, pages, diagnostics, docs — keeps the full [value].
     */
    val compact: String get() = value.removePrefix("HR-")
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
