package com.hermes.client.notifications.push

import com.hermes.client.data.network.LifecycleEventDto
import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushMessageHandlerTest {
    private val hint = mapOf(
        "type" to "hermes.lifecycle",
        "eventId" to "evt-1",
        "event" to "run.waiting",
        "state" to "waiting",
        "deviceId" to "mac-1",
        "storedSessionId" to "stored-1",
        "runtimeSessionId" to "",
        "occurredAt" to "2026-09-22T01:02:03Z",
    )
    private var syncs = 0
    private val dispatched = mutableListOf<LifecycleEventDto>()

    private fun handler(enabled: Boolean = true, sync: suspend () -> Unit = {}) = PushMessageHandler(
        notificationsEnabled = { enabled },
        syncInbox = { syncs++; sync() },
        dispatch = { dispatched += it },
        syncBudgetMs = 1_000L,
    )

    @Test fun aSuccessfulInboxSyncIsTheOnlyPath() = runTest {
        assertEquals(PushMessageHandler.Outcome.SYNCED, handler().handle(hint))
        assertEquals(1, syncs)
        assertTrue(dispatched.isEmpty())
    }

    @Test fun aFailedSyncFoldsTheHintThroughTheSameDispatcher() = runTest {
        val outcome = handler(sync = { throw IOException("offline") }).handle(hint)
        assertEquals(PushMessageHandler.Outcome.FOLDED_HINT, outcome)
        assertEquals(listOf("evt-1"), dispatched.map { it.eventId })
        assertNull(dispatched.single().title)
    }

    @Test fun aSyncThatOverrunsTheBudgetAlsoFoldsTheHint() = runTest {
        val outcome = handler(sync = { awaitCancellation() }).handle(hint)
        assertEquals(PushMessageHandler.Outcome.FOLDED_HINT, outcome)
        assertEquals(1, dispatched.size)
    }

    @Test fun notificationsOffDoesNothing() = runTest {
        assertEquals(PushMessageHandler.Outcome.NOTIFICATIONS_OFF, handler(enabled = false).handle(hint))
        assertEquals(0, syncs)
        assertTrue(dispatched.isEmpty())
    }

    @Test fun aForeignMessageDoesNothing() = runTest {
        assertEquals(PushMessageHandler.Outcome.IGNORED, handler().handle(hint + ("type" to "other")))
        assertEquals(0, syncs)
        assertTrue(dispatched.isEmpty())
    }
}
