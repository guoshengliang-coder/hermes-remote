package com.hermes.client.data.repository

import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.data.network.MessageOrder
import com.hermes.client.data.network.MessagesDto
import io.mockk.coEvery
import io.mockk.coVerify
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * HG-104: the repository fetches only the newest page, merges it into what it holds, pages older
 * on request, and persists the merged transcript — with real JSON and a real [TranscriptStore].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionRepositoryPagingTest {
    private val root: File = createTempDirectory("transcript-paging-test").toFile()
    private val scheduler = TestCoroutineScheduler()
    private val store = TranscriptStore(File(root, "history"), UnconfinedTestDispatcher(scheduler))
    private val json = Json { ignoreUnknownKeys = true }
    private val rest = mockk<HermesRestApi>()
    private val page = TranscriptWindow.HISTORY_PAGE_SIZE

    @After fun tearDown() { root.deleteRecursively() }

    init {
        every { rest.parseMessages(any()) } answers { json.decodeFromString<MessagesDto>(firstArg()).messages }
    }

    /** Rows [range] as a REST body; odd ids are user turns, even ids answers. */
    private fun body(range: LongRange): String = range.joinToString(",", """{"messages":[""", "]}") { id ->
        val role = if (id % 2L == 1L) "user" else "assistant"
        """{"id":$id,"role":"$role","content":"m$id","timestamp":${1_757_000_000 + id}.0}"""
    }

    private fun serveTail(range: LongRange) {
        coEvery { rest.messagesRaw("s1", "default", null, page, null, MessageOrder.LATEST, true) } returns body(range)
    }

    private fun serveOlder(offset: Int, range: LongRange) {
        coEvery { rest.messagesRaw("s1", "default", null, page, offset, MessageOrder.LATEST, true) } returns body(range)
    }

    @Test fun history_asks_only_for_the_newest_page() = runTest(scheduler) {
        serveTail(1L..10L)
        val repository = SessionRepository(rest, this, store)

        repository.history("s1", "default")

        coVerify(exactly = 1) { rest.messagesRaw("s1", "default", null, page, null, MessageOrder.LATEST, true) }
    }

    @Test fun a_refreshed_tail_keeps_the_older_pages_already_loaded() = runTest(scheduler) {
        val repository = SessionRepository(rest, this, store)
        serveTail(101L..200L)
        repository.history("s1", "default")
        serveOlder(offset = 100, 1L..100L)
        repository.olderHistory("s1", "default")

        // Two new rows; the newest page now starts at 103.
        serveTail(103L..202L)
        val merged = repository.history("s1", "default")

        assertEquals(202, merged.size)
        assertEquals("m1", merged.first().text)
        assertEquals("m202", merged.last().text)
        assertEquals((1L..202L).toList(), merged.map { it.serverId })
    }

    @Test fun older_pages_use_the_count_of_held_rows_as_offset_and_stop_at_the_start() = runTest(scheduler) {
        val repository = SessionRepository(rest, this, store)
        serveTail(151L..250L)
        repository.history("s1", "default")
        assertEquals(true, repository.hasOlderHistory("s1", "default"))

        serveOlder(offset = 100, 51L..150L)
        val first = repository.olderHistory("s1", "default")
        assertEquals(100, first.added)
        assertFalse(first.reachedStart)
        assertEquals(200, first.messages.size)

        serveOlder(offset = 200, 1L..50L)
        val second = repository.olderHistory("s1", "default")
        assertEquals(50, second.added)
        assertTrue("a short page is the first one", second.reachedStart)
        assertEquals(250, second.messages.size)
        assertEquals(false, repository.hasOlderHistory("s1", "default"))

        // At the start, no further request goes out.
        repository.olderHistory("s1", "default")
        coVerify(exactly = 1) { rest.messagesRaw("s1", "default", null, page, 200, MessageOrder.LATEST, true) }
    }

    @Test fun an_older_page_inside_held_rows_skips_a_page_instead_of_stopping() = runTest(scheduler) {
        val repository = SessionRepository(rest, this, store)
        serveTail(301L..400L)
        repository.history("s1", "default")
        // 150 rows were added upstream since (401..550), but the window has not seen them: the
        // page at offset 100 is 351..450 — all held or newer, nothing older than 301.
        serveOlder(offset = 100, 351L..450L)
        serveOlder(offset = 200, 251L..350L)

        val page = repository.olderHistory("s1", "default")

        assertEquals(50, page.added)
        assertFalse("a full page is never the start", page.reachedStart)
        assertEquals((251L..400L).toList(), page.messages.map { it.serverId })
        assertEquals(true, repository.hasOlderHistory("s1", "default"))
    }

    @Test fun skipping_is_bounded_when_upstream_ignores_offset() = runTest(scheduler) {
        val repository = SessionRepository(rest, this, store)
        serveTail(101L..200L)
        repository.history("s1", "default")
        val offsets = mutableListOf<Int>()
        for (offset in listOf(100, 200, 300, 400, 500)) {
            coEvery { rest.messagesRaw("s1", "default", null, page, offset, MessageOrder.LATEST, true) } answers {
                offsets += offset
                body(101L..200L)
            }
        }

        val older = repository.olderHistory("s1", "default")

        assertEquals(0, older.added)
        assertFalse(older.reachedStart)
        assertEquals(listOf(100, 200, 300, 400, 500), offsets)
        // Export reads unabridged oldest pages, independently of the UI preview window.
        coEvery { rest.messagesRaw("s1", "default", null, 500, 0, MessageOrder.OLDEST, false) } returns body(101L..200L)
        repository.fullHistory("s1", "default")
        assertEquals(listOf(100, 200, 300, 400, 500), offsets)
    }

    @Test fun full_history_pages_to_exhaustion() = runTest(scheduler) {
        val repository = SessionRepository(rest, this, store)
        coEvery { rest.messagesRaw("s1", "default", null, 500, 0, MessageOrder.OLDEST, false) } returns body(1L..300L)

        val all = repository.fullHistory("s1", "default")

        assertEquals(300, all.size)
        assertEquals((1L..300L).toList(), all.map { it.serverId })
    }

    @Test fun the_merged_transcript_is_what_is_persisted() = runTest(scheduler) {
        val first = SessionRepository(rest, this, store)
        serveTail(51L..150L)
        first.history("s1", "default")
        serveOlder(offset = 100, 1L..50L)
        first.olderHistory("s1", "default")
        advanceUntilIdle()

        // A new process: memory is gone, the disk holds both pages and knows the start is loaded.
        val second = SessionRepository(rest, this, store)
        val restored = second.diskHistory("s1", "default")!!
        assertEquals(150, restored.size)
        assertEquals("m1", restored.first().text)
        assertEquals(false, second.hasOlderHistory("s1", "default"))

        // And the next tail merges into the stored window rather than replacing it.
        serveTail(53L..152L)
        val merged = second.history("s1", "default")
        assertEquals(152, merged.size)
        assertEquals(false, second.hasOlderHistory("s1", "default"))
    }

    @Test fun a_tail_merges_into_the_disk_window_even_before_it_was_read_for_display() = runTest(scheduler) {
        val first = SessionRepository(rest, this, store)
        serveTail(101L..200L)
        first.history("s1", "default")
        serveOlder(offset = 100, 1L..100L)
        first.olderHistory("s1", "default")
        advanceUntilIdle()

        val second = SessionRepository(rest, this, store)
        serveTail(101L..200L)

        assertEquals(200, second.history("s1", "default").size)
    }
}
