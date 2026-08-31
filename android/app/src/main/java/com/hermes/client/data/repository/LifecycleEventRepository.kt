package com.hermes.client.data.repository

import com.hermes.client.data.network.LifecycleEventDto
import com.hermes.client.data.network.LifecycleEventsSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LifecycleSessionKey(
    val deviceId: String,
    val profile: String?,
    val sessionId: String,
)

data class LifecycleSyncResult(
    val processed: Int,
    val cursor: Long,
    val hasActiveSessions: Boolean,
    val moreAvailable: Boolean,
)

/**
 * Exactly-one local consumer for the Relay's durable event inbox.
 *
 * The callback runs before the Relay delivery ACK and before the local cursor advances. If Android
 * dies in that window, the same event is replayed; notification IDs are stable, so replay updates
 * rather than stacks. This intentionally chooses at-least-once delivery over silent loss.
 */
class LifecycleEventRepository(
    private val source: LifecycleEventsSource,
    private val cursorStore: LifecycleEventCursor,
) {
    private val mutex = Mutex()
    private val _activeSessions = MutableStateFlow<Set<LifecycleSessionKey>>(emptySet())
    val activeSessions: StateFlow<Set<LifecycleSessionKey>> = _activeSessions.asStateFlow()

    suspend fun sync(
        consume: suspend (List<LifecycleEventDto>) -> Unit = {},
    ): LifecycleSyncResult = mutex.withLock {
        var cursor = cursorStore.read()
        var processed = 0
        var moreAvailable = false

        repeat(MAX_PAGES_PER_SYNC) {
            val page = source.events(cursor, PAGE_SIZE)
            require(page.nextCursor >= cursor) { "Relay lifecycle cursor moved backwards" }
            val records = page.events.sortedBy { it.sequence }
            require(!page.hasMore || records.isNotEmpty()) { "Relay lifecycle page made no progress" }
            require(records.map { it.sequence }.distinct().size == records.size) {
                "Relay lifecycle page contained duplicate sequences"
            }
            require(records.all { it.sequence > cursor && it.sequence <= page.nextCursor }) {
                "Relay lifecycle event was outside its cursor window"
            }
            val events = records.map { record ->
                require(record.event.type == "session.lifecycle" && record.event.version == 1) {
                    "Relay lifecycle event had an unsupported envelope"
                }
                record.event
            }
            if (events.isNotEmpty()) {
                reduce(events)
                consume(events)
                source.markDelivered(events.map { it.eventId })
            }
            cursor = page.nextCursor
            cursorStore.write(cursor)
            processed += events.size
            moreAvailable = page.hasMore
            if (!page.hasMore) return@withLock LifecycleSyncResult(
                processed = processed,
                cursor = cursor,
                hasActiveSessions = _activeSessions.value.isNotEmpty(),
                moreAvailable = false,
            )
        }

        LifecycleSyncResult(
            processed = processed,
            cursor = cursor,
            hasActiveSessions = _activeSessions.value.isNotEmpty(),
            moreAvailable = moreAvailable,
        )
    }

    private fun reduce(events: List<LifecycleEventDto>) {
        val next = _activeSessions.value.toMutableSet()
        for (event in events) {
            val key = LifecycleSessionKey(event.deviceId, event.profile, event.storedSessionId)
            when (event.event) {
                "run.started", "run.waiting", "run.resumed" -> next += key
                "run.completed", "run.interrupted", "run.unknown" -> next -= key
            }
        }
        _activeSessions.value = next
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_PAGES_PER_SYNC = 10
    }
}
