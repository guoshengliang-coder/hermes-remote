package com.hermes.client.data.repository

import com.hermes.client.data.network.LifecycleEventDto
import com.hermes.client.data.network.LifecycleEventPageDto
import com.hermes.client.data.network.LifecycleEventsSource
import com.hermes.client.data.network.StoredLifecycleEventDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LifecycleEventRepositoryTest {
    @Test
    fun `consume happens before delivery ack and cursor commit`() = runTest {
        val operations = mutableListOf<String>()
        val source = FakeSource(page(event("e1", "run.started"), sequence = 1), operations)
        val cursor = FakeCursor(operations)
        val repository = LifecycleEventRepository(source, cursor)

        val result = repository.sync { events -> operations += "consume:${events.single().eventId}" }

        assertEquals(listOf("fetch:0", "consume:e1", "ack:e1", "cursor:1"), operations)
        assertEquals(1, result.processed)
        assertTrue(result.hasActiveSessions)
        assertEquals(setOf(LifecycleSessionKey("mac-mini", "default", "session-1")), repository.activeSessions.value)
    }

    @Test
    fun `consumer failure leaves event replayable`() = runTest {
        val operations = mutableListOf<String>()
        val source = FakeSource(page(event("e1", "run.completed"), sequence = 4), operations)
        val cursor = FakeCursor(operations, initial = 3)
        val repository = LifecycleEventRepository(source, cursor)

        val failure = runCatching { repository.sync { error("notification failed") } }.exceptionOrNull()

        assertTrue(failure?.message?.contains("notification failed") == true)
        assertEquals(listOf("fetch:3"), operations)
        assertEquals(3, cursor.value)
    }

    @Test
    fun `all sessions reduce independently across pages`() = runTest {
        val source = QueueSource(
            mutableListOf(
                page(
                    event("a-start", "run.started", session = "a"),
                    event("b-wait", "run.waiting", session = "b"),
                    sequence = 2,
                    hasMore = true,
                ),
                page(
                    event("a-done", "run.completed", session = "a"),
                    sequence = 3,
                ),
            ),
        )
        val repository = LifecycleEventRepository(source, FakeCursor(mutableListOf()))

        val result = repository.sync()

        assertEquals(3, result.processed)
        assertFalse(result.moreAvailable)
        assertEquals(setOf(LifecycleSessionKey("mac-mini", "default", "b")), repository.activeSessions.value)
    }

    @Test
    fun `invalid cursor page is rejected before consumer side effects`() = runTest {
        val operations = mutableListOf<String>()
        val source = FakeSource(page(event("stale", "run.started"), sequence = 2), operations)
        val cursor = FakeCursor(operations, initial = 3)
        val repository = LifecycleEventRepository(source, cursor)

        val failure = runCatching {
            repository.sync { operations += "consume" }
        }.exceptionOrNull()

        assertTrue(failure?.message?.contains("cursor") == true)
        assertEquals(listOf("fetch:3"), operations)
        assertEquals(3, cursor.value)
    }

    private fun event(
        id: String,
        kind: String,
        session: String = "session-1",
    ) = LifecycleEventDto(
        type = "session.lifecycle",
        version = 1,
        eventId = id,
        deviceId = "mac-mini",
        runtimeSessionId = "runtime-$session",
        storedSessionId = session,
        event = kind,
        state = if (kind == "run.completed") "idle" else "working",
        occurredAt = "2026-08-31T08:30:00.000Z",
    )

    private fun page(
        vararg events: LifecycleEventDto,
        sequence: Long,
        hasMore: Boolean = false,
    ) = LifecycleEventPageDto(
        events = events.mapIndexed { index, item ->
            StoredLifecycleEventDto(
                sequence = sequence - events.lastIndex + index,
                event = item,
                receivedAt = "2026-08-31T08:30:01.000Z",
            )
        },
        nextCursor = sequence,
        hasMore = hasMore,
    )

    private class FakeCursor(
        private val operations: MutableList<String>,
        initial: Long = 0,
    ) : LifecycleEventCursor {
        var value = initial
        override suspend fun read(): Long = value
        override suspend fun write(value: Long) {
            this.value = maxOf(this.value, value)
            operations += "cursor:$value"
        }
    }

    private class FakeSource(
        private val page: LifecycleEventPageDto,
        private val operations: MutableList<String>,
    ) : LifecycleEventsSource {
        override suspend fun events(after: Long, limit: Int): LifecycleEventPageDto {
            operations += "fetch:$after"
            return page
        }

        override suspend fun markDelivered(eventIds: List<String>) {
            operations += "ack:${eventIds.joinToString()}"
        }

        override suspend fun markRead(eventIds: List<String>) = Unit
    }

    private class QueueSource(
        private val pages: MutableList<LifecycleEventPageDto>,
    ) : LifecycleEventsSource {
        override suspend fun events(after: Long, limit: Int): LifecycleEventPageDto = pages.removeAt(0)
        override suspend fun markDelivered(eventIds: List<String>) = Unit
        override suspend fun markRead(eventIds: List<String>) = Unit
    }
}
