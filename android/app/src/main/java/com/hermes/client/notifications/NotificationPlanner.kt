package com.hermes.client.notifications

import com.hermes.client.data.progress.SessionRuntime
import com.hermes.client.data.progress.SessionRuntimeKey
import com.hermes.client.domain.Role
import com.hermes.client.ui.localization.AppLanguage

/** The full set of session cards that should be showing right now, plus the group summary. */
data class NotificationPlan(
    val cards: Map<SessionRuntimeKey, NotificationSpec>,
    val summary: NotificationSummary?,
    /** Terminal cards the user is looking at right now; they must not come back when they leave. */
    val seen: Map<SessionRuntimeKey, NotificationKind>,
)

/** One change the renderer must make to move the shade from the previous plan to the next. */
sealed interface NotificationOp {
    data class Post(val spec: NotificationSpec) : NotificationOp
    data class Cancel(val id: Int, val key: SessionRuntimeKey) : NotificationOp
    data class Summary(val summary: NotificationSummary?) : NotificationOp
}

/** Lift the projector input out of a runtime snapshot. */
fun SessionRuntime.toNotificationInput(
    title: String?,
    showProfile: Boolean,
    actionState: NotificationActionState,
): SessionNotificationInput = SessionNotificationInput(
    key = key,
    liveSessionId = liveHandle,
    title = title,
    phase = phase,
    toolName = toolName,
    todoDone = todoDone,
    todoTotal = todoTotal,
    pendingApproval = chat.pendingApproval,
    pendingClarify = chat.pendingClarify,
    lastAssistantText = chat.messages.lastOrNull { it.role == Role.ASSISTANT && !it.isError }?.text,
    runStartedAt = runStartedAt,
    eventAt = occurredAt.takeIf { it > 0L } ?: lastEventAt,
    showProfile = showProfile,
    actionState = actionState,
)

/**
 * Pure: decide which cards exist for the current process state. Every source (WebSocket, Relay
 * inbox, a future push) has already been folded into [runtimes]; this only projects and applies
 * the suppression rules:
 *  - the chat the user is looking at (visible AND app in foreground) gets no card;
 *  - anywhere else in the foreground app, cards post silently;
 *  - a card the user swiped away does not come back while its kind is unchanged.
 */
fun planNotifications(
    runtimes: Collection<SessionRuntime>,
    visible: Set<SessionRuntimeKey>,
    appInForeground: Boolean,
    prefs: NotificationPrefs,
    language: AppLanguage,
    showProfile: Boolean,
    titleOf: (SessionRuntimeKey) -> String? = { null },
    actionStates: Map<SessionRuntimeKey, NotificationActionState> = emptyMap(),
    dismissed: Map<SessionRuntimeKey, NotificationKind> = emptyMap(),
): NotificationPlan {
    val cards = LinkedHashMap<SessionRuntimeKey, NotificationSpec>()
    val seen = LinkedHashMap<SessionRuntimeKey, NotificationKind>()
    for (runtime in runtimes) {
        val key = runtime.key
        val input = runtime.toNotificationInput(
            title = runtime.title?.takeIf { it.isNotBlank() } ?: titleOf(key),
            showProfile = showProfile,
            actionState = actionStates[key] ?: NotificationActionState.NONE,
        )
        val spec = projectSessionNotification(input, prefs, language) ?: continue
        val kind = spec.kind ?: continue
        if (appInForeground && key in visible) {
            if (kind.terminal) seen[key] = kind
            continue
        }
        if (dismissed[key] == kind) continue
        cards[key] = if (appInForeground) spec.copy(silent = true, onlyAlertOnce = true) else spec
    }
    return NotificationPlan(cards = cards, summary = summarize(cards.values), seen = seen)
}

/**
 * Pure: the operations that move the shade from [previous] to [plan]. A card whose kind is
 * unchanged is refreshed with onlyAlertOnce so tool switches and progress ticks never re-alert;
 * a kind change re-alerts through the normal channel rules.
 */
fun diffPlan(
    previous: Map<SessionRuntimeKey, NotificationSpec>,
    previousSummary: NotificationSummary?,
    plan: NotificationPlan,
): List<NotificationOp> {
    val ops = ArrayList<NotificationOp>()
    previous.forEach { (key, spec) ->
        if (key !in plan.cards) ops += NotificationOp.Cancel(spec.id, key)
    }
    plan.cards.forEach { (key, spec) ->
        val before = previous[key]
        if (before == spec) return@forEach
        val sameKind = before != null && before.kind == spec.kind
        ops += NotificationOp.Post(if (sameKind) spec.copy(onlyAlertOnce = true) else spec)
    }
    if (plan.summary != previousSummary) ops += NotificationOp.Summary(plan.summary)
    return ops
}
