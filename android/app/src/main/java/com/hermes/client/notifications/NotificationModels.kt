package com.hermes.client.notifications

import com.hermes.client.data.progress.SessionRuntimeKey
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** User's notification preferences (persisted); off by default. */
data class NotificationPrefs(
    val enabled: Boolean = false,
    /** Approval requests and clarify questions (the "needs you" channel). */
    val approvals: Boolean = true,
    val runFinished: Boolean = true,
    /** Failed and unconfirmed runs (their own channel so completions can be muted alone). */
    val runFailed: Boolean = true,
    val runProgress: Boolean = true,
    // One-time post-pairing onboarding sheet has been shown (regardless of the choice made).
    val onboardingSeen: Boolean = false,
)

/**
 * What a session's single notification card currently represents. One session owns exactly one
 * card; a kind change is an update of that card, never a second card.
 */
enum class NotificationKind {
    RUNNING,
    RECONNECTING,
    NEEDS_APPROVAL,
    NEEDS_ANSWER,
    NEEDS_ATTENTION,
    COMPLETED,
    FAILED,
    UNCONFIRMED,
    ;

    /** Kinds that wait for the user and therefore alert on the high-importance channel. */
    val needsUser: Boolean get() = this == NEEDS_APPROVAL || this == NEEDS_ANSWER || this == NEEDS_ATTENTION

    /** Kinds that describe a finished run; once the user has looked at the chat they stay gone. */
    val terminal: Boolean get() = this == COMPLETED || this == FAILED || this == UNCONFIRMED

    /** Ongoing, silent, per-session progress card. */
    val ongoing: Boolean get() = this == RUNNING || this == RECONNECTING
}

/** Feedback state of a notification action the user pressed from the shade. */
enum class NotificationActionState { NONE, PENDING, FAILED }

/**
 * An inline notification action carrying both the live gateway handle (for the RPC) and the
 * durable session identity (for updating local state afterwards). [reply] = true marks a
 * direct-reply action (Android RemoteInput text field); [answer] carries a preset clarify choice.
 */
data class NotifAction(
    val label: String,
    val action: String,
    val sessionId: String,
    val storedSessionId: String = sessionId,
    val profile: String? = null,
    val reply: Boolean = false,
    val requestId: String? = null,
    val questionId: String? = null,
    val answer: String? = null,
)

/** Progress bar for the running card. [shortText] is the API 36+ status-bar chip text. */
data class NotificationProgress(
    val done: Int,
    val total: Int,
    val indeterminate: Boolean,
    val shortText: String?,
)

/**
 * A platform-independent description of a notification, so mapping stays unit-testable. Every
 * field maps to exactly one place on the card (see docs/DESIGN.md §5.10): [title] is the session
 * title, [profileLabel] and [stateLabel] form the header line, [body] is the state's specific
 * content, [subText] carries an error code or duration, and [publicTitle]/[publicBody] are the
 * redacted lock-screen version.
 */
data class NotificationSpec(
    val id: Int,
    val channelId: String,
    val title: String,
    val body: String,
    val route: String?,
    val actions: List<NotifAction> = emptyList(),
    val groupKey: String? = null,
    val kind: NotificationKind? = null,
    val sessionKey: SessionRuntimeKey? = null,
    val stateLabel: String? = null,
    val profileLabel: String? = null,
    val subText: String? = null,
    /** Profile whose avatar colour tints the small-icon circle; null leaves the system default. */
    val accentProfile: String? = null,
    val whenMs: Long? = null,
    val chronometer: Boolean = false,
    val progress: NotificationProgress? = null,
    val ongoing: Boolean = false,
    val category: String? = null,
    val silent: Boolean = false,
    val onlyAlertOnce: Boolean = false,
    val autoCancel: Boolean = true,
    val publicTitle: String? = null,
    val publicBody: String? = null,
)

/** Counts shown on the group summary line when two or more session cards are posted. */
data class NotificationSummary(val waiting: Int, val running: Int, val finished: Int)

/**
 * Route a notification to the durable stored conversation, optionally pinned to its profile.
 * WebSocket events may carry a short-lived runtime handle; callers must resolve that handle before
 * invoking this helper whenever [SessionRuntimeStore] already knows the mapping.
 */
internal fun notificationChatRoute(sessionId: String, profile: String? = null): String {
    val id = encodeNotificationRouteValue(sessionId)
    val selectedProfile = profile?.takeIf { it.isNotBlank() } ?: return "chat/$id"
    return "chat/$id?profile=${encodeNotificationRouteValue(selectedProfile)}"
}

private fun encodeNotificationRouteValue(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

/**
 * The one notification id a session owns. Derived only from the durable session identity so every
 * source (WebSocket, Relay inbox, a future push) updates the same card. Never collides with the
 * reserved ids owned by the service, the group summary, or the update notification.
 */
fun notificationIdFor(key: SessionRuntimeKey): Int {
    var id = ("session:" + key.profile.orEmpty() + ":" + key.sessionId).hashCode()
    while (id in Notif.RESERVED_IDS) id++
    return id
}

/** Channel ids, gateway event-type strings, and action names in one place. */
object Notif {
    /** Approval requests, clarify questions, and "waiting for you" (IMPORTANCE_HIGH). */
    const val CHANNEL_ATTENTION = "attention"
    /** Finished runs (IMPORTANCE_DEFAULT). */
    const val CHANNEL_COMPLETED = "completed"
    /** Failed and unconfirmed runs (IMPORTANCE_DEFAULT, separate so completions can be muted alone). */
    const val CHANNEL_FAILURES = "failures"
    // Live in-flight run progress. IMPORTANCE_LOW (not MIN like CHANNEL_SERVICE) so the ongoing
    // progress notification is actually glanceable in the shade and eligible for promotion to a
    // status-bar Live Update on API 36+, while still making no sound.
    const val CHANNEL_RUN_PROGRESS = "run_progress"
    const val CHANNEL_SERVICE = "service"
    /** Downloaded-and-verified app updates. */
    const val CHANNEL_UPDATES = "updates"

    /** Channels created by earlier releases; deleted on startup so their settings do not linger. */
    val LEGACY_CHANNELS = listOf("approvals", "activity")

    const val GROUP_SESSIONS = "hermes_sessions"

    const val SERVICE_NOTIFICATION_ID = 1001
    const val SUMMARY_NOTIFICATION_ID = 1002
    const val UPDATE_NOTIFICATION_ID = 990_101
    val RESERVED_IDS = setOf(SERVICE_NOTIFICATION_ID, SUMMARY_NOTIFICATION_ID, 1003, UPDATE_NOTIFICATION_ID)

    // Notifiable events on the app's WebSocket (/api/ws), verified against the gateway source:
    //  - approval.request / clarify.request -> the agent needs the user
    //  - message.complete                   -> a turn finished
    //  - error                              -> a turn failed
    //  - session.info {running:false}       -> authoritative idle backstop
    // The gateway's /api/ws emits `message.complete` once at end-of-turn (tui_gateway/server.py);
    // it does NOT emit `run.completed`/`run.failed` (those belong to the separate messaging-platform
    // HTTP/SSE API the app never connects to). These constants document the contract; the actual
    // fold into session state lives in SessionRuntimeStore and the card is projected from there.
    const val EVENT_APPROVAL = "approval.request"
    const val EVENT_CLARIFY = "clarify.request"
    const val EVENT_MESSAGE_COMPLETE = "message.complete"
    const val EVENT_ERROR = "error"
    const val EVENT_MESSAGE_START = "message.start"
    const val EVENT_TOOL_START = "tool.start"
    const val EVENT_TOOL_COMPLETE = "tool.complete"
    const val EVENT_SESSION_INFO = "session.info"

    const val ACTION_ALLOW_ONCE = "allow_once"
    const val ACTION_ALLOW_SESSION = "allow_session"
    const val ACTION_DENY = "deny"
    const val ACTION_REPLY = "reply"
    /** A clarify choice offered as a button; the chosen text travels in [NotifAction.answer]. */
    const val ACTION_CHOICE = "choice"
    /** Plain "open the chat" button; rendered with the content intent, never broadcast. */
    const val ACTION_OPEN = "open"
    /** Delete intent: the user swiped the card away. */
    const val ACTION_DISMISSED = "dismissed"

    // RemoteInput result key for the inline reply on a clarify ("Needs your answer") notification.
    const val KEY_REPLY_TEXT = "reply_text"

    const val EXTRA_SESSION_ID = "session_id"
    const val EXTRA_STORED_SESSION_ID = "stored_session_id"
    const val EXTRA_PROFILE = "profile"
    const val EXTRA_NOTIF_ID = "notif_id"
    const val EXTRA_REQUEST_ID = "request_id"
    const val EXTRA_QUESTION_ID = "question_id"
    const val EXTRA_ANSWER = "answer"
    const val EXTRA_KIND = "kind"
}
