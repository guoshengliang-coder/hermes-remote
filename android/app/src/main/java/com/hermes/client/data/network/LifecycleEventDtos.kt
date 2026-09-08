package com.hermes.client.data.network

import com.hermes.client.data.auth.AccountSessionManager
import kotlinx.serialization.Serializable
import javax.inject.Inject

@Serializable
data class LifecycleEventDto(
    val type: String,
    val version: Int,
    val eventId: String,
    val deviceId: String,
    val profile: String? = null,
    val runtimeSessionId: String,
    val storedSessionId: String,
    val event: String,
    val state: String,
    val occurredAt: String,
    val title: String? = null,
)

@Serializable
data class StoredLifecycleEventDto(
    val sequence: Long,
    val event: LifecycleEventDto,
    val receivedAt: String,
    val deliveredAt: String? = null,
    val readAt: String? = null,
)

@Serializable
data class LifecycleEventPageDto(
    val events: List<StoredLifecycleEventDto>,
    val nextCursor: Long,
    val hasMore: Boolean,
)

interface LifecycleEventsSource {
    fun cursorScope(): String = LEGACY_CURSOR_SCOPE
    suspend fun events(after: Long, limit: Int = 100): LifecycleEventPageDto
    suspend fun markDelivered(eventIds: List<String>)
    suspend fun markRead(eventIds: List<String>)

    companion object {
        const val LEGACY_CURSOR_SCOPE = "legacy"
    }
}

/** Keeps the sync engine independent of the large Hermes REST facade and easy to test. */
class RelayLifecycleEventsSource @Inject constructor(
    private val rest: HermesRestApi,
    private val accountSessions: AccountSessionManager,
) : LifecycleEventsSource {
    override fun cursorScope(): String {
        accountSessions.lifecycleCursorScope()?.let { return it }
        return if (accountSessions.requiresAccountReauthentication()) {
            ACCOUNT_REAUTH_CURSOR_SCOPE
        } else {
            LifecycleEventsSource.LEGACY_CURSOR_SCOPE
        }
    }

    override suspend fun events(after: Long, limit: Int): LifecycleEventPageDto =
        rest.lifecycleEvents(after, limit)

    override suspend fun markDelivered(eventIds: List<String>) =
        rest.markLifecycleEventsDelivered(eventIds)

    override suspend fun markRead(eventIds: List<String>) =
        rest.markLifecycleEventsRead(eventIds)

    private companion object {
        const val ACCOUNT_REAUTH_CURSOR_SCOPE = "account-reauthentication-required"
    }
}
