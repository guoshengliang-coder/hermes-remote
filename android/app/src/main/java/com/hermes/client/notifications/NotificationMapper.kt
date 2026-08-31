package com.hermes.client.notifications

import com.hermes.client.data.network.ServerEvent
import com.hermes.client.data.network.bool
import com.hermes.client.data.network.str
import com.hermes.client.data.progress.SessionRuntimeKey
import com.hermes.client.ui.chat.ApprovalTier
import com.hermes.client.ui.chat.tierFor
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.localized

/**
 * Pure mapping from a gateway event to a notification (or null). `approval.request` and
 * `clarify.request` always notify (they need the user's input regardless of whether the app is
 * foregrounded); `message.complete` (run finished) and `error` (run failed) only notify when the
 * app is backgrounded — see the note on [Notif] for why these are the real /api/ws events. A stable
 * id is derived from the session so a turn's repeated `message.complete`s update one notification
 * rather than stack.
 */
fun toNotificationSpec(
    event: ServerEvent,
    prefs: NotificationPrefs,
    appInForeground: Boolean,
    language: AppLanguage = AppLanguage.EN,
    routeTarget: SessionRuntimeKey? = null,
): NotificationSpec? {
    if (!prefs.enabled) return null
    val sid = event.sessionId ?: return null
    var id = (event.type + sid).hashCode()
    // Never collide with HermesNotifier.SERVICE_NOTIFICATION_ID (1001) or the run-progress
    // notification id (1003) — those ids belong to the ongoing foreground-service notification
    // and the live run-progress notification, and notify()-ing over either would clobber it.
    if (id == 1001 || id == 1003) id = 1002
    // message.complete commonly identifies the live runtime handle. Route to the durable stored
    // session whenever the runtime store has already bound that alias; actions keep the raw handle
    // because approval.respond/reply still target the live gateway session.
    val route = notificationChatRoute(
        routeTarget?.sessionId ?: sid,
        routeTarget?.profile,
    )
    return when (event.type) {
        Notif.EVENT_APPROVAL -> if (!prefs.approvals) null else {
            val elevated = tierFor(event.bool("allow_permanent") ?: false) == ApprovalTier.ELEVATED
            NotificationSpec(
                id = id,
                channelId = Notif.CHANNEL_APPROVALS,
                title = localized(language, "需要审批", "Approval needed"),
                body = event.str("description")?.ifBlank { null }
                    ?: event.str("command")?.ifBlank { null }
                    ?: localized(language, "智能体正在等待你的审批。", "The agent is waiting for your approval."),
                route = route,
                actions = if (elevated) listOf(
                    NotifAction(localized(language, "拒绝", "Deny"), Notif.ACTION_DENY, sid),
                )
                          else listOf(
                              NotifAction(localized(language, "仅允许一次", "Allow once"), Notif.ACTION_ALLOW_ONCE, sid),
                              NotifAction(localized(language, "本次会话允许", "Session"), Notif.ACTION_ALLOW_SESSION, sid),
                              NotifAction(localized(language, "拒绝", "Deny"), Notif.ACTION_DENY, sid),
                          ),
                groupKey = "approval",
            )
        }
        // Needs-you: always notify (ignores foreground); tap opens the chat to answer.
        Notif.EVENT_CLARIFY -> if (!prefs.approvals) null else NotificationSpec(
            id = id, channelId = Notif.CHANNEL_APPROVALS,
            title = localized(language, "需要你的回答", "Needs your input"),
            body = event.str("question") ?: localized(language, "智能体有一个问题。", "The agent has a question."),
            route = route,
            actions = listOf(NotifAction(
                localized(language, "回复", "Reply"),
                Notif.ACTION_REPLY,
                sid,
                reply = true,
                requestId = event.str("request_id"),
            )),
            groupKey = "approval",
        )
        // Run finished: `message.complete` is the end-of-turn event on /api/ws (the app also uses it
        // to stop the "generating" spinner). Only notify when backgrounded; the per-session id above
        // collapses a turn's repeated message.completes into one updating notification, not a stack.
        Notif.EVENT_MESSAGE_COMPLETE -> if (!prefs.runFinished || appInForeground) null else NotificationSpec(
            id = id, channelId = Notif.CHANNEL_ACTIVITY,
            title = localized(language, "任务已完成", "Run finished"),
            body = localized(language, "智能体已经完成，点击查看结果。", "Your agent finished — tap to view."),
            route = route, actions = emptyList(), groupKey = "run",
        )
        // Run failed: the gateway emits `error` on a turn-fatal failure (carries a message).
        Notif.EVENT_ERROR -> if (!prefs.runFinished || appInForeground) null else NotificationSpec(
            id = id, channelId = Notif.CHANNEL_ACTIVITY,
            title = localized(language, "任务运行失败", "Run failed"),
            body = event.str("message")?.takeIf { it.isNotBlank() }?.let { "$it (HR-RPC-001)" }
                ?: localized(
                    language,
                    "智能体运行失败（HR-RPC-001），点击查看详情。",
                    "The agent run failed (HR-RPC-001) — tap to view.",
                ),
            route = route, actions = emptyList(), groupKey = "run",
        )
        else -> null
    }
}
