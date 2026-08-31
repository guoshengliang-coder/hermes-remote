package com.hermes.client.notifications

import com.hermes.client.data.network.LifecycleEventDto
import com.hermes.client.data.progress.SessionRunPhase
import com.hermes.client.data.progress.SessionRuntimeStore
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.AppLanguageProvider
import com.hermes.client.ui.localization.localized
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

fun toLifecycleNotificationSpec(
    event: LifecycleEventDto,
    prefs: NotificationPrefs,
    appInForeground: Boolean,
    coveredByLiveSocket: Boolean = false,
    language: AppLanguage = AppLanguage.EN,
): NotificationSpec? {
    if (!prefs.enabled || coveredByLiveSocket) return null
    val route = chatRoute(event.storedSessionId, event.profile)
    return when (event.event) {
        "run.waiting" -> if (!prefs.approvals) null else NotificationSpec(
            id = stableNotificationId(Notif.EVENT_APPROVAL, event.storedSessionId),
            channelId = Notif.CHANNEL_APPROVALS,
            title = localized(language, "Hermes 需要你处理", "Hermes needs attention"),
            body = event.title?.takeIf { it.isNotBlank() }
                ?: localized(language, "任务正在等待输入或审批。", "A task is waiting for input or approval."),
            route = route,
            actions = emptyList(),
            groupKey = "approval",
        )
        "run.completed" -> if (!prefs.runFinished || appInForeground) null else NotificationSpec(
            id = stableNotificationId(Notif.EVENT_MESSAGE_COMPLETE, event.storedSessionId),
            channelId = Notif.CHANNEL_ACTIVITY,
            title = localized(language, "任务已完成", "Run finished"),
            body = event.title?.takeIf { it.isNotBlank() }
                ?: localized(language, "智能体已经完成，点击查看结果。", "Your agent finished — tap to view."),
            route = route,
            actions = emptyList(),
            groupKey = "run",
        )
        "run.interrupted", "run.unknown" -> if (!prefs.runFinished || appInForeground) null else NotificationSpec(
            id = stableNotificationId(Notif.EVENT_ERROR, event.storedSessionId),
            channelId = Notif.CHANNEL_ACTIVITY,
            title = localized(language, "任务需要检查", "Run needs checking"),
            body = event.title?.takeIf { it.isNotBlank() }?.let { "$it (HR-SYNC-001)" }
                ?: localized(
                    language,
                    "任务停止但未确认完成（HR-SYNC-001），点击检查。",
                    "The task stopped without a confirmed completion (HR-SYNC-001).",
                ),
            route = route,
            actions = emptyList(),
            groupKey = "run",
        )
        else -> null
    }
}

@Singleton
class LifecycleNotificationDispatcher @Inject constructor(
    private val notifier: HermesNotifier,
    private val runtimes: SessionRuntimeStore,
    private val languages: AppLanguageProvider,
) {
    fun dispatch(events: List<LifecycleEventDto>, prefs: NotificationPrefs, appInForeground: Boolean) {
        events.forEach { event ->
            val covered = runtimes.runtimes.value.values.any { runtime ->
                runtime.key.sessionId == event.storedSessionId &&
                    (event.profile.isNullOrBlank() || runtime.key.profile == event.profile) &&
                    when (event.event) {
                        "run.waiting" -> runtime.phase == SessionRunPhase.WAITING_APPROVAL ||
                            runtime.phase == SessionRunPhase.WAITING_CLARIFICATION ||
                            runtime.phase == SessionRunPhase.WAITING_ATTENTION
                        "run.completed" -> runtime.phase == SessionRunPhase.COMPLETED_UNREAD ||
                            (runtime.phase == SessionRunPhase.IDLE &&
                                System.currentTimeMillis() - runtime.lastEventAt < LIVE_EVENT_COVERAGE_MS)
                        else -> false
                    }
            }
            runtimes.applyObservedLifecycle(event)
            toLifecycleNotificationSpec(
                event,
                prefs,
                appInForeground,
                coveredByLiveSocket = covered,
                language = languages.current,
            )?.let(notifier::post)
        }
    }

    private companion object {
        const val LIVE_EVENT_COVERAGE_MS = 30_000L
    }
}

private fun stableNotificationId(eventType: String, sessionId: String): Int {
    var id = (eventType + sessionId).hashCode()
    if (id == HermesNotifier.SERVICE_NOTIFICATION_ID || id == HermesNotifier.RUN_PROGRESS_NOTIFICATION_ID) {
        id = 1002
    }
    return id
}

private fun chatRoute(sessionId: String, profile: String?): String {
    val id = encodeRouteValue(sessionId)
    // A missing profile on a Connector event means Hermes' default identity, not "whichever
    // profile happens to be active when the notification is tapped".
    val selectedProfile = profile?.takeIf { it.isNotBlank() } ?: "default"
    return "chat/$id?profile=${encodeRouteValue(selectedProfile)}"
}

private fun encodeRouteValue(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
