package com.hermes.client.notifications

import androidx.core.app.NotificationCompat
import com.hermes.client.data.progress.SessionRunPhase
import com.hermes.client.data.progress.SessionRuntimeKey
import com.hermes.client.ui.chat.ApprovalRequest
import com.hermes.client.ui.chat.ApprovalTier
import com.hermes.client.ui.chat.ClarifyRequest
import com.hermes.client.ui.chat.tierFor
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.localized

/**
 * Everything the projector needs to know about one session, lifted out of [SessionRuntime] and
 * the coordinator so the mapping is a pure function with no Android or store dependency.
 */
data class SessionNotificationInput(
    val key: SessionRuntimeKey,
    val liveSessionId: String?,
    val title: String?,
    val phase: SessionRunPhase,
    val toolName: String? = null,
    val todoDone: Int = 0,
    val todoTotal: Int = 0,
    val pendingApproval: ApprovalRequest? = null,
    val pendingClarify: ClarifyRequest? = null,
    val lastAssistantText: String? = null,
    val runStartedAt: Long? = null,
    /** When the state the card describes happened (event time, not sync time). */
    val eventAt: Long = 0L,
    /** Show the identity name in the header; false when the user has a single profile. */
    val showProfile: Boolean = false,
    val actionState: NotificationActionState = NotificationActionState.NONE,
)

/**
 * Pure mapping from one session's state to its single notification card, or null when the session
 * should have no card. All copy, channel, action, and layout decisions live here so they are
 * testable without Android; [HermesNotifier] only renders and the coordinator only decides when.
 */
fun projectSessionNotification(
    input: SessionNotificationInput,
    prefs: NotificationPrefs,
    language: AppLanguage = AppLanguage.EN,
): NotificationSpec? {
    if (!prefs.enabled) return null
    val kind = kindFor(input) ?: return null
    val allowed = when (kind) {
        NotificationKind.RUNNING, NotificationKind.RECONNECTING -> prefs.runProgress
        NotificationKind.NEEDS_APPROVAL, NotificationKind.NEEDS_ANSWER, NotificationKind.NEEDS_ATTENTION -> prefs.approvals
        NotificationKind.COMPLETED -> prefs.runFinished
        NotificationKind.FAILED, NotificationKind.UNCONFIRMED -> prefs.runFailed
    }
    if (!allowed) return null

    val key = input.key
    val title = input.title?.trim()?.takeIf { it.isNotBlank() } ?: localized(language, "新会话", "New chat")
    val live = input.liveSessionId?.takeIf { it.isNotBlank() } ?: key.sessionId
    val profileLabel = key.profile?.takeIf { input.showProfile && it.isNotBlank() }
    val base = NotificationSpec(
        id = notificationIdFor(key),
        channelId = Notif.CHANNEL_ATTENTION,
        title = title,
        body = "",
        route = notificationChatRoute(key.sessionId, key.profile),
        groupKey = Notif.GROUP_SESSIONS,
        kind = kind,
        sessionKey = key,
        profileLabel = profileLabel,
        accentProfile = key.profile,
        whenMs = input.eventAt.takeIf { it > 0L },
        publicTitle = title,
    )
    fun action(label: String, action: String, extra: NotifAction.() -> NotifAction = { this }) =
        NotifAction(label, action, live, key.sessionId, key.profile).extra()

    val spec = when (kind) {
        NotificationKind.RUNNING -> {
            val step = if (input.todoTotal > 0) {
                localized(language, "第 ${input.todoDone}/${input.todoTotal} 步", "Step ${input.todoDone}/${input.todoTotal}")
            } else null
            val activity = input.toolName?.takeIf { it.isNotBlank() }
                ?.let { localized(language, "正在调用 $it", "Calling $it") }
                ?: localized(language, "正在思考…", "Thinking…")
            base.copy(
                channelId = Notif.CHANNEL_RUN_PROGRESS,
                body = listOfNotNull(activity, step).joinToString(" · "),
                stateLabel = localized(language, "运行中", "Running"),
                whenMs = input.runStartedAt ?: base.whenMs,
                chronometer = input.runStartedAt != null,
                progress = NotificationProgress(
                    done = input.todoDone,
                    total = input.todoTotal,
                    indeterminate = input.todoTotal <= 0,
                    shortText = if (input.todoTotal > 0) "${input.todoDone}/${input.todoTotal}" else null,
                ),
                ongoing = true,
                silent = true,
                onlyAlertOnce = true,
                autoCancel = false,
                category = NotificationCompat.CATEGORY_PROGRESS,
            )
        }
        NotificationKind.RECONNECTING -> base.copy(
            channelId = Notif.CHANNEL_RUN_PROGRESS,
            body = localized(language, "连接中断，正在恢复", "Connection lost, reconnecting"),
            stateLabel = localized(language, "重连中", "Reconnecting"),
            whenMs = input.runStartedAt ?: base.whenMs,
            chronometer = input.runStartedAt != null,
            progress = NotificationProgress(0, 0, indeterminate = true, shortText = null),
            ongoing = true,
            silent = true,
            onlyAlertOnce = true,
            autoCancel = false,
            category = NotificationCompat.CATEGORY_PROGRESS,
        )
        NotificationKind.NEEDS_APPROVAL -> {
            val approval = input.pendingApproval!!
            val elevated = tierFor(approval.allowPermanent) == ApprovalTier.ELEVATED
            val lead = approval.description.trim().ifBlank {
                if (elevated) localized(language, "高风险操作", "High-risk action")
                else localized(language, "要运行命令", "Wants to run a command")
            }
            val command = approval.command.trim().takeIf { it.isNotBlank() && it != lead }
            base.copy(
                body = listOfNotNull(lead, command).joinToString("\n"),
                stateLabel = localized(language, "需要审批", "Approval needed"),
                actions = if (elevated) listOf(
                    action(localized(language, "拒绝", "Deny"), Notif.ACTION_DENY),
                    action(localized(language, "打开查看", "Open"), Notif.ACTION_OPEN),
                ) else listOf(
                    action(localized(language, "允许一次", "Allow once"), Notif.ACTION_ALLOW_ONCE),
                    action(localized(language, "本会话允许", "This session"), Notif.ACTION_ALLOW_SESSION),
                    action(localized(language, "拒绝", "Deny"), Notif.ACTION_DENY),
                ),
                autoCancel = false,
                category = NotificationCompat.CATEGORY_MESSAGE,
            )
        }
        NotificationKind.NEEDS_ANSWER -> {
            val request = input.pendingClarify!!
            val question = request.currentQuestion!!
            val buttons = question.choices.size in 1..2 && !question.multiSelect
            val body = when {
                question.choices.isEmpty() || buttons -> question.question
                else -> question.question + "\n" +
                    question.choices.mapIndexed { i, c -> "${i + 1}. $c" }.joinToString("\n")
            }
            val replyLabel = localized(language, "回复…", "Reply…")
            val reply = action(replyLabel, Notif.ACTION_REPLY) {
                copy(reply = true, requestId = request.requestId, questionId = question.qid.ifBlank { null })
            }
            base.copy(
                body = body,
                stateLabel = localized(language, "需要你的回答", "Needs your answer"),
                actions = if (buttons) {
                    question.choices.map { choice ->
                        action(choiceLabel(choice), Notif.ACTION_CHOICE) {
                            copy(requestId = request.requestId, questionId = question.qid.ifBlank { null }, answer = choice)
                        }
                    } + reply
                } else listOf(reply, action(localized(language, "打开", "Open"), Notif.ACTION_OPEN)),
                autoCancel = false,
                category = NotificationCompat.CATEGORY_MESSAGE,
            )
        }
        NotificationKind.NEEDS_ATTENTION -> base.copy(
            body = localized(language, "任务已暂停，等待你的输入或审批。", "The task is paused and waiting for your input or approval."),
            stateLabel = localized(language, "等待处理", "Waiting for you"),
            actions = listOf(action(localized(language, "打开", "Open"), Notif.ACTION_OPEN)),
            autoCancel = false,
            category = NotificationCompat.CATEGORY_MESSAGE,
        )
        NotificationKind.COMPLETED -> base.copy(
            channelId = Notif.CHANNEL_COMPLETED,
            body = snippet(input.lastAssistantText) ?: localized(language, "点击查看结果", "Tap to view the result"),
            stateLabel = localized(language, "已完成", "Done"),
            subText = durationLabel(input.runStartedAt, input.eventAt, language),
            category = NotificationCompat.CATEGORY_STATUS,
        )
        NotificationKind.FAILED -> base.copy(
            channelId = Notif.CHANNEL_FAILURES,
            body = localized(language, "智能体运行失败，可以重试。", "The agent run failed. You can retry."),
            stateLabel = localized(language, "运行失败", "Run failed"),
            subText = ERROR_RUN_FAILED,
            actions = listOf(action(localized(language, "查看详情", "View details"), Notif.ACTION_OPEN)),
            category = NotificationCompat.CATEGORY_ERROR,
        )
        NotificationKind.UNCONFIRMED -> base.copy(
            channelId = Notif.CHANNEL_FAILURES,
            body = localized(language, "任务停止了，但没有确认完成。", "The task stopped without a confirmed completion."),
            stateLabel = localized(language, "需要检查", "Needs a check"),
            subText = ERROR_RUN_UNCONFIRMED,
            actions = listOf(action(localized(language, "打开检查", "Check"), Notif.ACTION_OPEN)),
            category = NotificationCompat.CATEGORY_ERROR,
        )
    }
    val withPublic = spec.copy(publicBody = spec.stateLabel)
    return when (input.actionState) {
        NotificationActionState.NONE -> withPublic
        // Feedback while the RPC runs: same card, no buttons, so a double tap cannot double-send.
        NotificationActionState.PENDING -> withPublic.copy(
            body = localized(language, "处理中…", "Working…"),
            actions = emptyList(),
            onlyAlertOnce = true,
        )
        NotificationActionState.FAILED -> withPublic.copy(
            body = withPublic.body + "\n" + localized(language, "发送失败，请重试。", "Couldn't send. Try again."),
            subText = ERROR_ACTION_FAILED,
            onlyAlertOnce = true,
        )
    }
}

/** The card kind a phase maps to, or null for a session that needs no card. */
fun kindFor(input: SessionNotificationInput): NotificationKind? = when (input.phase) {
    SessionRunPhase.IDLE -> null
    SessionRunPhase.SUBMITTING, SessionRunPhase.THINKING, SessionRunPhase.STREAMING, SessionRunPhase.USING_TOOL ->
        NotificationKind.RUNNING
    SessionRunPhase.RECONNECTING -> NotificationKind.RECONNECTING
    // A wait whose request is already answered locally (in-app or from the shade) is a run that
    // is about to continue; between the answer and the gateway's next event it must not flash a
    // generic "waiting for you" card that would re-alert on the high-importance channel.
    SessionRunPhase.WAITING_APPROVAL ->
        if (input.pendingApproval != null) NotificationKind.NEEDS_APPROVAL else NotificationKind.RUNNING
    SessionRunPhase.WAITING_CLARIFICATION ->
        if (input.pendingClarify?.currentQuestion != null) NotificationKind.NEEDS_ANSWER else NotificationKind.RUNNING
    SessionRunPhase.WAITING_ATTENTION -> NotificationKind.NEEDS_ATTENTION
    SessionRunPhase.COMPLETED_UNREAD -> NotificationKind.COMPLETED
    SessionRunPhase.FAILED -> NotificationKind.FAILED
    SessionRunPhase.INTERRUPTED -> NotificationKind.UNCONFIRMED
}

/** Group summary counts for the posted cards; null when fewer than two cards are showing. */
fun summarize(specs: Collection<NotificationSpec>): NotificationSummary? {
    if (specs.size < 2) return null
    val kinds = specs.mapNotNull { it.kind }
    return NotificationSummary(
        waiting = kinds.count { it.needsUser },
        running = kinds.count { it.ongoing },
        finished = kinds.count { it.terminal },
    )
}

fun NotificationSummary.label(language: AppLanguage): String = listOfNotNull(
    waiting.takeIf { it > 0 }?.let { localized(language, "$it 个等待处理", if (it == 1) "1 waiting for you" else "$it waiting for you") },
    running.takeIf { it > 0 }?.let { localized(language, "$it 个运行中", if (it == 1) "1 running" else "$it running") },
    finished.takeIf { it > 0 }?.let { localized(language, "$it 个已结束", if (it == 1) "1 finished" else "$it finished") },
).joinToString(" · ")

/** First ~140 characters of the final reply, whitespace collapsed; null when there is nothing. */
internal fun snippet(text: String?, max: Int = 140): String? {
    val flat = text?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return if (flat.length <= max) flat else flat.take(max - 1).trimEnd() + "…"
}

internal fun choiceLabel(choice: String, max: Int = 20): String {
    val flat = choice.replace(Regex("\\s+"), " ").trim()
    return if (flat.length <= max) flat else flat.take(max - 1).trimEnd() + "…"
}

internal fun durationLabel(startedAt: Long?, endedAt: Long, language: AppLanguage): String? {
    if (startedAt == null || endedAt <= 0L || endedAt < startedAt) return null
    val seconds = (endedAt - startedAt) / 1_000
    val minutes = seconds / 60
    val rest = seconds % 60
    return when {
        minutes >= 60 -> localized(language, "用时 ${minutes / 60} 小时 ${minutes % 60} 分", "Took ${minutes / 60}h ${minutes % 60}m")
        minutes > 0 -> localized(language, "用时 $minutes 分 $rest 秒", "Took ${minutes}m ${rest}s")
        else -> localized(language, "用时 $rest 秒", "Took ${rest}s")
    }
}

/** Registered in docs/ERROR_HANDLING.md; shown as the card's sub-text, never inside the body. */
const val ERROR_RUN_FAILED = "HR-RPC-001"
const val ERROR_RUN_UNCONFIRMED = "HR-SYNC-002"
const val ERROR_ACTION_FAILED = "HR-NOTIF-001"
