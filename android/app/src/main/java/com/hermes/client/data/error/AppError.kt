package com.hermes.client.data.error

/** Stable product error identifiers. Meanings are registered in docs/ERROR_HANDLING.md. */
enum class AppErrorCode(val value: String) {
    CONNECTION_FAILED("HR-CONN-002"),
    // The Relay accepted the socket and then never said `gateway.ready`, so the RPC was never
    // sent. Registered since the code existed; nothing produced it until HG-42, where every
    // blocked call surfaced as a generic send failure and hid the fact that the connection, not
    // the message, was the thing that had failed.
    HANDSHAKE_TIMEOUT("HR-CONN-003"),
    CONNECTION_INTERRUPTED("HR-CONN-004"),
    CONNECTOR_OFFLINE("HR-CONN-005"),
    RPC_FAILED("HR-RPC-001"),
    RPC_TIMEOUT("HR-RPC-002"),
    MODEL_LIST_FAILED("HR-RPC-003"),
    MODEL_SWITCH_FAILED("HR-RPC-004"),
    MODEL_DEFAULT_FAILED("HR-RPC-005"),
    MODEL_REASONING_FAILED("HR-RPC-006"),
    // Not a refused switch: the Mac's Hermes could not start its slash worker at all, so every
    // slash command is dead, not just this one. Telling the user to retry would be false.
    SLASH_WORKER_UNAVAILABLE("HR-RPC-007"),
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
    IMAGE_DECODE_FAILED("HR-MEDIA-004"),
    IMAGE_EDIT_SAVE_FAILED("HR-MEDIA-005"),
    PROFILE_IDENTITY_SAVE_FAILED("HR-STORE-001"),
    SESSION_NOT_FOUND("HR-SESS-001"),
    PROJECT_FOLDER_MISSING("HR-SESS-003"),
    SESSION_BUSY("HR-SESS-004"),
    PROJECT_MOVE_FAILED("HR-SESS-005"),
    PROJECT_FELL_BACK_TO_DEFAULT("HR-SESS-006"),
    MESSAGE_SEND_FAILED("HR-SESS-007"),
    SESSION_ARCHIVE_FAILED("HR-SESS-008"),
    PROJECT_NOT_FOUND("HR-SESS-009"),
    PROJECT_NAME_INVALID("HR-SESS-010"),
    PROJECT_SAVE_FAILED("HR-SESS-011"),
    FOLDER_BROWSE_FAILED("HR-SESS-012"),
    // Another client is running this conversation, so upstream refused the prompt. Retryable, but
    // only once the other side lets go — so it must say that instead of the generic send failure.
    SESSION_OWNED_ELSEWHERE("HR-SESS-013"),
    // Fetching ANOTHER conversation's transcript failed while turning it into a Markdown
    // attachment (HG-38). Not HR-SYNC-001: nothing is out of sync and the open conversation is
    // untouched — one conversation the user asked to reference could not be read.
    SESSION_TRANSCRIPT_UNAVAILABLE("HR-SESS-014"),
    INSTALL_PERMISSION_REQUIRED("HR-PERM-003"),
    HISTORY_INCOMPLETE("HR-SYNC-001"),
    RUN_UNCONFIRMED("HR-SYNC-002"),
    NOTIFICATION_ACTION_FAILED("HR-NOTIF-001"),
    SEARCH_FAILED("HR-SEARCH-001"),
    FEEDBACK_UNAVAILABLE("HR-FEEDBACK-001"),
    FEEDBACK_SUBMIT_FAILED("HR-FEEDBACK-002"),
    FEEDBACK_REJECTED("HR-FEEDBACK-003"),
    FEEDBACK_RATE_LIMITED("HR-FEEDBACK-004"),
    CRON_DELIVERY_FAILED("HR-CRON-001"),
    // The job itself failed, so the fix is the job — as opposed to HR-CRON-001, where the run
    // succeeded and only its delivery did not. The detail screen used to label this case
    // HR-RPC-001, a transport code that says nothing about a schedule.
    CRON_RUN_FAILED("HR-CRON-002"),
    MESSAGING_LIST_FAILED("HR-MSG-001"),
    MESSAGING_SAVE_FAILED("HR-MSG-002"),
    MESSAGING_PROFILE_CONFLICT("HR-MSG-003"),
    MESSAGING_PLATFORM_FAILED("HR-MSG-004"),
    MESSAGING_RESTART_FAILED("HR-MSG-005"),
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

/**
 * Strips credential-shaped substrings. Split out from [redactDiagnostic] so the diagnostic log can
 * reuse the same rules without the 1,000-character cap, which only makes sense for a summary meant
 * to be copied into a chat. Anything leaving the app goes through here: MissionGo, like any
 * diagnostic sink, keeps host-supplied text verbatim, so redaction has to happen on our side.
 */
fun redactSecrets(value: String): String = value
    // The optional scheme word matters: `Authorization: Bearer <credential>` is the common shape,
    // and a value pattern that stops at the first space eats only the word "Bearer" and leaves the
    // credential in the clear.
    .replace(
        Regex("(?i)(token|authorization|cookie|password)\\s*[:=]\\s*(?:(?:bearer|basic|digest|token)\\s+)?[^\\s,;]+"),
        "$1=<redacted>",
    )
    .replace(Regex("(?i)([?&](?:token|ticket|key|signature)=)[^&\\s]+"), "$1<redacted>")

/** Defense-in-depth redaction for copyable diagnostic summaries. */
fun redactDiagnostic(value: String): String = redactSecrets(value).take(1_000)
