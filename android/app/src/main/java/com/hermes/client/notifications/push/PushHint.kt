package com.hermes.client.notifications.push

import com.hermes.client.data.network.LifecycleEventDto

/**
 * A data-only FCM wake hint (HG-94). It carries no title or content and is never the source of
 * truth: its job is to wake the phone so it reads the durable Relay inbox. Only when that read
 * fails is the hint itself folded in, so a generic card still appears.
 */
data class PushHint(
    val eventId: String,
    val event: String,
    val state: String,
    val deviceId: String,
    val storedSessionId: String,
    val runtimeSessionId: String,
    val profile: String?,
    val occurredAt: String,
) {
    /**
     * The same envelope the inbox would have delivered, minus the title the server never sends
     * over FCM. `applyObservedLifecycle` keeps an existing title when this one is null.
     */
    fun toLifecycleEventDto(): LifecycleEventDto = LifecycleEventDto(
        type = INBOX_EVENT_TYPE,
        version = INBOX_EVENT_VERSION,
        eventId = eventId,
        deviceId = deviceId,
        profile = profile,
        runtimeSessionId = runtimeSessionId,
        storedSessionId = storedSessionId,
        event = event,
        state = state,
        occurredAt = occurredAt,
        title = null,
    )

    companion object {
        const val MESSAGE_TYPE = "hermes.lifecycle"
        const val INBOX_EVENT_TYPE = "session.lifecycle"
        const val INBOX_EVENT_VERSION = 1

        /** The only events the server pushes; run.started/resumed stay on the inbox and socket. */
        val PUSHED_EVENTS = setOf("run.waiting", "run.completed", "run.interrupted", "run.unknown")

        /**
         * Parses an FCM data map. Returns null for anything that is not a complete Hermes
         * lifecycle hint — another sender's message, a newer event this build does not know, or a
         * payload missing an identity field — so a bad message can never produce a card.
         */
        fun parse(data: Map<String, String>): PushHint? {
            if (data["type"] != MESSAGE_TYPE) return null
            fun required(key: String): String? = data[key]?.trim()?.takeIf { it.isNotEmpty() }
            val event = required("event")?.takeIf { it in PUSHED_EVENTS } ?: return null
            return PushHint(
                eventId = required("eventId") ?: return null,
                event = event,
                state = required("state") ?: return null,
                deviceId = required("deviceId") ?: return null,
                storedSessionId = required("storedSessionId") ?: return null,
                runtimeSessionId = data["runtimeSessionId"]?.trim().orEmpty(),
                profile = data["profile"]?.trim()?.takeIf { it.isNotEmpty() },
                occurredAt = required("occurredAt") ?: return null,
            )
        }
    }
}
