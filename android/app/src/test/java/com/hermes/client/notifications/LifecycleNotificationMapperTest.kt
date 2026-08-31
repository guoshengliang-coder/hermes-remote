package com.hermes.client.notifications

import com.hermes.client.data.network.LifecycleEventDto
import com.hermes.client.ui.localization.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LifecycleNotificationMapperTest {
    private val prefs = NotificationPrefs(enabled = true)

    @Test fun waiting_isHighPriorityButHasNoUnsafeCrossClientApprovalActions() {
        val spec = toLifecycleNotificationSpec(event("run.waiting"), prefs, appInForeground = false)
        assertNotNull(spec)
        assertEquals(Notif.CHANNEL_APPROVALS, spec!!.channelId)
        assertEquals(emptyList<NotifAction>(), spec.actions)
        assertEquals("chat/session-1?profile=work%20profile", spec.route)
    }

    @Test fun completion_onlyNotifiesInBackgroundAndUsesLiveSocketStableId() {
        val background = toLifecycleNotificationSpec(event("run.completed"), prefs, appInForeground = false)
        assertNotNull(background)
        assertNull(toLifecycleNotificationSpec(event("run.completed"), prefs, appInForeground = true))

        val direct = toNotificationSpec(
            com.hermes.client.data.network.ServerEvent(
                type = Notif.EVENT_MESSAGE_COMPLETE,
                sessionId = "session-1",
                payload = kotlinx.serialization.json.JsonObject(emptyMap()),
            ),
            prefs,
            appInForeground = false,
        )
        assertEquals(direct!!.id, background!!.id)
    }

    @Test fun disabledCategoriesAndLiveSocketCoverageSuppressFallback() {
        assertNull(toLifecycleNotificationSpec(
            event("run.waiting"),
            prefs.copy(approvals = false),
            appInForeground = false,
        ))
        assertNull(toLifecycleNotificationSpec(
            event("run.waiting"),
            prefs,
            appInForeground = false,
            coveredByLiveSocket = true,
        ))
        assertNull(toLifecycleNotificationSpec(event("run.started"), prefs, appInForeground = false))
    }

    @Test fun missingProfileRoutesToTheCanonicalDefaultIdentity() {
        val spec = toLifecycleNotificationSpec(
            event("run.completed").copy(profile = null),
            prefs,
            appInForeground = false,
        )
        assertEquals("chat/session-1?profile=default", spec!!.route)
    }

    @Test fun lifecycleFallbackUsesTheSelectedAppLanguage() {
        val spec = toLifecycleNotificationSpec(
            event("run.completed").copy(title = null),
            prefs,
            appInForeground = false,
            language = AppLanguage.ZH,
        )!!
        assertEquals("任务已完成", spec.title)
        assertEquals("智能体已经完成，点击查看结果。", spec.body)
    }

    private fun event(kind: String) = LifecycleEventDto(
        type = "session.lifecycle",
        version = 1,
        eventId = "event-1",
        deviceId = "mac-mini",
        profile = "work profile",
        runtimeSessionId = "runtime-1",
        storedSessionId = "session-1",
        event = kind,
        state = if (kind == "run.completed") "idle" else "waiting",
        occurredAt = "2026-08-31T08:30:00.000Z",
        title = "Research task",
    )
}
