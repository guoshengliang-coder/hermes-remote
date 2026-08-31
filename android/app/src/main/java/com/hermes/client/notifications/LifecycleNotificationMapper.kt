package com.hermes.client.notifications

import com.hermes.client.data.network.LifecycleEventDto
import com.hermes.client.data.progress.SessionRunPhase
import com.hermes.client.data.progress.SessionRuntimeStore
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

fun toLifecycleNotificationSpec(
    event: LifecycleEventDto,
    prefs: NotificationPrefs,
    appInForeground: Boolean,
    coveredByLiveSocket: Boolean = false,
): NotificationSpec? {
    if (!prefs.enabled || coveredByLiveSocket) return null
    val route = chatRoute(event.storedSessionId, event.profile)
    return when (event.event) {
        "run.waiting" -> if (!prefs.approvals) null else NotificationSpec(
            id = stableNotificationId(Notif.EVENT_APPROVAL, event.storedSessionId),
            channelId = Notif.CHANNEL_APPROVALS,
            title = "Hermes needs attention",
            body = event.title?.takeIf { it.isNotBlank() }
                ?: "A task is waiting for input or approval.",
            route = route,
            actions = emptyList(),
            groupKey = "approval",
        )
        "run.completed" -> if (!prefs.runFinished || appInForeground) null else NotificationSpec(
            id = stableNotificationId(Notif.EVENT_MESSAGE_COMPLETE, event.storedSessionId),
            channelId = Notif.CHANNEL_ACTIVITY,
            title = "Run finished",
            body = event.title?.takeIf { it.isNotBlank() }
                ?: "Your agent finished — tap to view.",
            route = route,
            actions = emptyList(),
            groupKey = "run",
        )
        "run.interrupted", "run.unknown" -> if (!prefs.runFinished || appInForeground) null else NotificationSpec(
            id = stableNotificationId(Notif.EVENT_ERROR, event.storedSessionId),
            channelId = Notif.CHANNEL_ACTIVITY,
            title = "Run needs checking",
            body = event.title?.takeIf { it.isNotBlank() }
                ?: "The task stopped without a confirmed completion.",
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
            toLifecycleNotificationSpec(event, prefs, appInForeground, covered)?.let(notifier::post)
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
    val selectedProfile = profile?.takeIf { it.isNotBlank() } ?: return "chat/$id"
    return "chat/$id?profile=${encodeRouteValue(selectedProfile)}"
}

private fun encodeRouteValue(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
