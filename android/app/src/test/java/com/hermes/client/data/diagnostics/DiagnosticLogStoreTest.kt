package com.hermes.client.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DiagnosticLogStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private fun store(maxFileBytes: Long = DiagnosticLogStore.MAX_FILE_BYTES) =
        DiagnosticLogStore(File(temp.root, "diagnostics"), maxFileBytes)

    private fun entry(millis: Long, category: String, message: String) =
        DebugLog.LogEntry(millis, category, message)

    @Test fun appended_entries_are_read_back_in_order() {
        val s = store()
        s.append(entry(1_000, "ws", "opening socket"))
        s.append(entry(2_000, "session", "open(s1)"))

        val restored = s.readRecent(10)
        assertEquals(listOf("opening socket", "open(s1)"), restored.map { it.message })
        assertEquals(listOf("ws", "session"), restored.map { it.category })
        assertEquals(listOf(1_000L, 2_000L), restored.map { it.timeMillis })
    }

    @Test fun restored_entries_are_flagged_as_previous_run() {
        val s = store()
        s.append(entry(1_000, "ws", "opening socket"))
        assertTrue(
            "an entry read back from disk belongs to an earlier process",
            s.readRecent(10).single().fromPreviousRun,
        )
    }

    @Test fun separators_inside_a_message_survive_the_round_trip() {
        val s = store()
        // A message carrying the field separator or a line break must not split into two entries
        // or swallow the ones after it.
        val awkward = "rpc#7 failed:\nCaused by: timeout\tafter 30s  path C:\\tmp\\x"
        s.append(entry(1_000, "error", awkward))
        s.append(entry(2_000, "ws", "still here"))

        val restored = s.readRecent(10)
        assertEquals(2, restored.size)
        assertEquals(awkward, restored.first().message)
        assertEquals("still here", restored.last().message)
    }

    @Test fun read_recent_returns_the_newest_within_the_limit() {
        val s = store()
        repeat(10) { s.append(entry(it.toLong(), "ws", "line $it")) }
        val restored = s.readRecent(3)
        assertEquals(listOf("line 7", "line 8", "line 9"), restored.map { it.message })
    }

    @Test fun read_all_returns_everything_the_limit_would_have_trimmed() {
        val store = DiagnosticLogStore(temp.root)
        repeat(30) { store.append(DebugLog.LogEntry(it.toLong(), "ws", "entry-$it")) }

        assertEquals(10, store.readRecent(10).size)
        val all = store.readAll()
        assertEquals(30, all.size)
        assertEquals("entry-0", all.first().message)
        assertEquals("entry-29", all.last().message)
    }

    @Test fun rotation_caps_the_pair_and_drops_the_oldest_lines() {
        // 400 bytes holds only a handful of lines, so this writes well past a full rotation.
        val s = store(maxFileBytes = 400)
        repeat(120) { s.append(entry(it.toLong(), "ws", "line $it")) }

        val restored = s.readRecent(500)
        assertTrue("rotation must drop the oldest entries", restored.size < 120)
        assertTrue("rotation must keep entries", restored.isNotEmpty())
        assertEquals("the newest entry must survive", "line 119", restored.last().message)

        val dir = File(temp.root, "diagnostics")
        val bytes = dir.listFiles().orEmpty().sumOf { it.length() }
        assertTrue("at most two files may exist", dir.listFiles().orEmpty().size <= 2)
        assertTrue("the pair stays near the cap, was $bytes", bytes <= 2 * 400 + 200)
    }

    @Test fun clear_removes_every_file() {
        val s = store(maxFileBytes = 400)
        repeat(120) { s.append(entry(it.toLong(), "ws", "line $it")) }
        s.clear()

        assertTrue(s.readRecent(500).isEmpty())
        assertEquals(0, File(temp.root, "diagnostics").listFiles().orEmpty().size)
    }

    @Test fun expired_files_are_pruned() {
        val s = store()
        s.append(entry(1_000, "ws", "old line"))
        val now = System.currentTimeMillis()
        File(temp.root, "diagnostics").listFiles().orEmpty().forEach {
            it.setLastModified(now - DiagnosticLogStore.RETENTION_MILLIS - 60_000)
        }

        s.pruneExpired(now)
        assertTrue("entries past the retention window must not be restored", s.readRecent(10).isEmpty())
    }

    @Test fun fresh_files_survive_a_prune() {
        val s = store()
        s.append(entry(1_000, "ws", "recent line"))
        s.pruneExpired(System.currentTimeMillis())
        assertEquals(1, s.readRecent(10).size)
    }

    @Test fun prune_on_an_empty_directory_is_harmless() {
        store().pruneExpired(System.currentTimeMillis())
        assertFalse(File(temp.root, "diagnostics").exists())
    }

    @Test fun unparsable_lines_are_skipped_rather_than_failing_the_read() {
        val dir = File(temp.root, "diagnostics").apply { mkdirs() }
        File(dir, "diagnostic-log.txt").writeText(
            "garbage without fields\n" +
                "notanumber\tws\thello\n" +
                DiagnosticLogStore.encode(entry(5_000, "ws", "good line")),
        )
        assertEquals(listOf("good line"), store().readRecent(10).map { it.message })
    }
}
