package com.hermes.client.data.repository

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * The transcript cache is the only thing standing between a cold open and a network round trip,
 * and it is also the first place this app writes conversation content to disk. These cover both
 * halves: that it actually returns what it stored, and that no failure mode escapes as an error.
 */
class TranscriptStoreTest {

    private val root: File = createTempDirectory("transcript-store").toFile()
    private val store = TranscriptStore(File(root, "history"))

    @After fun tearDown() { root.deleteRecursively() }

    @Test fun stores_and_returns_the_payload_unchanged() = runTest {
        // Multi-byte content matters: the payload is UTF-8 JSON full of Chinese conversation.
        val payload = """{"messages":[{"id":1,"role":"user","content":"昨天的数据如何？"}]}"""
        store.write("default/session-1", payload)

        assertEquals(payload, store.read("default/session-1"))
    }

    @Test fun keys_do_not_collide_across_profiles() = runTest {
        store.write("work/s1", "work transcript")
        store.write("personal/s1", "personal transcript")

        assertEquals("work transcript", store.read("work/s1"))
        assertEquals("personal transcript", store.read("personal/s1"))
    }

    /** A profile name is server-supplied text; it must not be able to choose a path. */
    @Test fun a_key_with_path_separators_cannot_escape_the_directory() = runTest {
        store.write("../../evil/s1", "payload")

        assertEquals("payload", store.read("../../evil/s1"))
        val files = File(root, "history").listFiles().orEmpty()
        assertEquals(1, files.size)
        assertTrue("stored name must be a plain hash", files.single().name.matches(Regex("[0-9a-f]{32}\\.json\\.gz")))
    }

    @Test fun missing_entry_reads_as_null() = runTest {
        assertNull(store.read("default/never-fetched"))
    }

    /** Half a gzip stream, an interrupted write, a truncated file: refetching beats crashing. */
    @Test fun a_corrupt_file_reads_as_null_instead_of_throwing() = runTest {
        store.write("default/s1", "payload")
        val stored = File(root, "history").listFiles()!!.single()
        stored.writeBytes(byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00, 0x00))

        assertNull(store.read("default/s1"))
    }

    /** A transcript past the entry budget would evict a large part of the cache to serve one screen. */
    @Test fun an_oversized_transcript_is_not_stored() = runTest {
        val huge = "x".repeat(TranscriptStore.MAX_ENTRY_RAW_CHARS + 1)
        store.write("default/huge", huge)

        assertNull(store.read("default/huge"))
    }

    @Test fun eviction_keeps_the_most_recently_used_not_the_most_recently_written() = runTest {
        repeat(TranscriptStore.MAX_CACHE_FILES) { store.write("default/s$it", "payload $it") }
        // Reading s0 touches it, so the next write must evict something else instead.
        assertEquals("payload 0", store.read("default/s0"))
        Thread.sleep(1_100) // filesystem mtime granularity is a second on some devices

        store.write("default/newcomer", "newcomer")

        assertEquals(TranscriptStore.MAX_CACHE_FILES, File(root, "history").listFiles()!!.size)
        assertEquals("newcomer", store.read("default/newcomer"))
        assertEquals("the entry read most recently must survive", "payload 0", store.read("default/s0"))
    }

    @Test fun clear_drops_everything() = runTest {
        store.write("default/s1", "one")
        store.write("default/s2", "two")

        store.clear()

        assertNull(store.read("default/s1"))
        assertNull(store.read("default/s2"))
    }

    /** An unwritable location is a degraded cache, never a failure the caller has to handle. */
    @Test fun an_unusable_directory_degrades_silently() = runTest {
        val blocked = File(root, "blocked")
        blocked.writeText("this is a file, so it can never become the cache directory")
        val broken = TranscriptStore(blocked)

        broken.write("default/s1", "payload")

        assertNull(broken.read("default/s1"))
    }
}
