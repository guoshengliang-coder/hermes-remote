package com.hermes.client.data.repository

import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.data.network.MessagesDto
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * The disk cache exists so a cold open paints from local bytes. Its one real hazard is drift: if
 * the stored copy were mapped by a different code path than a fresh fetch, an old payload would
 * render differently from a new one and nobody would notice until a user reported it.
 *
 * Only the transport is mocked here. Decoding and mapping run for real on both paths, which is
 * the whole point — a payload off the disk must produce the identical transcript.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionRepositoryDiskCacheTest {

    private val root: File = createTempDirectory("transcript-cache-test").toFile()
    private val scheduler = TestCoroutineScheduler()
    // The repository writes to the store off the caller's path; sharing the test scheduler is what
    // lets advanceUntilIdle() wait for that write instead of racing it.
    private val store = TranscriptStore(File(root, "history"), UnconfinedTestDispatcher(scheduler))
    private val json = Json { ignoreUnknownKeys = true }
    private val rest = mockk<HermesRestApi>()

    @After fun tearDown() { root.deleteRecursively() }

    /** Real decoding, so the DTO contract is exercised rather than stubbed around. */
    private fun serveRealPayload(sessionId: String, profile: String?, payload: String) {
        coEvery { rest.messagesRaw(sessionId, profile) } returns payload
        every { rest.parseMessages(any()) } answers {
            json.decodeFromString<MessagesDto>(firstArg()).messages
        }
    }

    private val payload = """
        {"messages":[
          {"id":1,"role":"user","content":"昨天的数据如何？","timestamp":1757000000.0},
          {"id":2,"role":"tool","tool_call_id":"call_a","tool_name":"terminal",
           "content":"{\"output\":\"ok\",\"exit_code\":0}"},
          {"id":3,"role":"assistant","content":"查完了。","timestamp":1757000009.0}
        ]}
    """.trimIndent()

    @Test fun a_fetched_transcript_comes_back_off_the_disk_mapped_identically() = runTest(scheduler) {
        serveRealPayload("s1", "default", payload)
        val online = SessionRepository(rest, this, store)

        val fresh = online.history("s1", "default")
        advanceUntilIdle() // the store write is deliberately off the caller's path

        // A second repository stands in for the next app launch: same disk, empty memory cache.
        val afterRestart = SessionRepository(rest, this, store)
        val restored = afterRestart.diskHistory("s1", "default")

        assertNotNull("the transcript must survive a process restart", restored)
        assertEquals(fresh.map { it.role }, restored!!.map { it.role })
        assertEquals(fresh.map { it.text }, restored.map { it.text })
        // Ids are what LazyColumn keys off; a divergence here is a ghost remount on reopen.
        assertEquals(fresh.map { it.id }, restored.map { it.id })
        // HG-4: history timestamps come off the wire's `timestamp` column, so they must survive
        // the round trip through the disk too, otherwise restored turns render timeless.
        assertEquals(fresh.map { it.timestamp }, restored.map { it.timestamp })
        // The tool row is joined onto the assistant turn on both paths, not rendered as a turn.
        assertEquals(fresh.map { it.tools.size }, restored.map { it.tools.size })
    }

    @Test fun the_restored_transcript_populates_the_memory_cache() = runTest(scheduler) {
        serveRealPayload("s1", "default", payload)
        SessionRepository(rest, this, store).history("s1", "default")
        advanceUntilIdle()

        val afterRestart = SessionRepository(rest, this, store)
        assertNull("nothing is in memory before the disk read", afterRestart.cachedHistory("s1", "default"))
        afterRestart.diskHistory("s1", "default")

        assertNotNull("a second open in the same run must not touch the disk again", afterRestart.cachedHistory("s1", "default"))
    }

    @Test fun a_session_never_opened_has_nothing_on_disk() = runTest(scheduler) {
        val repository = SessionRepository(rest, this, store)
        assertNull(repository.diskHistory("never-opened", "default"))
    }

    /** An app whose DTOs moved on refetches rather than showing a half-decoded transcript. */
    @Test fun a_payload_that_no_longer_parses_reads_as_null() = runTest(scheduler) {
        store.write("default/s1", """{"messages":"this used to be a list"}""")
        every { rest.parseMessages(any()) } answers {
            json.decodeFromString<MessagesDto>(firstArg()).messages
        }
        val repository = SessionRepository(rest, this, store)

        assertNull(repository.diskHistory("s1", "default"))
    }

    /** Without a store the repository is exactly what it was before: network only, no disk. */
    @Test fun a_repository_without_a_store_reports_no_cached_transcript() = runTest(scheduler) {
        serveRealPayload("s1", "default", payload)
        val repository = SessionRepository(rest, this)

        repository.history("s1", "default")
        advanceUntilIdle()

        assertNull(repository.diskHistory("s1", "default"))
        assertEquals(0, File(root, "history").listFiles().orEmpty().size)
    }
}
