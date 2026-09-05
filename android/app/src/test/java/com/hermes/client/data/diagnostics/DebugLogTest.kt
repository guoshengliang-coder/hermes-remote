package com.hermes.client.data.diagnostics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executor

class DebugLogTest {
    @get:Rule val temp = TemporaryFolder()

    /** Runs the file work inline so a test never has to wait on the real background executor. */
    private val direct = Executor { it.run() }

    @Before fun setUp() {
        // DebugLog is a process-wide object; detach any file mirror a previous test attached so
        // the in-memory cases run against memory alone.
        DebugLog.detachStore()
        DebugLog.setTokenToRedact(null)
        DebugLog.setEnabled(true)
        DebugLog.clear()
    }

    @After fun tearDown() {
        DebugLog.setEnabled(false)
        DebugLog.setTokenToRedact(null)
        DebugLog.clear()
        DebugLog.detachStore()
    }

    private fun logDir(): File = File(temp.root, "diagnostics")

    @Test fun disabled_log_is_a_noop() {
        DebugLog.clear()
        DebugLog.setEnabled(false)
        DebugLog.log("ws", "should not be recorded")
        assertTrue("disabled logging must record nothing", DebugLog.entries.value.isEmpty())
    }

    @Test fun enabled_log_is_recorded_with_category_and_message() {
        DebugLog.log("session", "open(s1)")
        val e = DebugLog.entries.value
        assertEquals(1, e.size)
        assertEquals("session", e.first().category)
        assertEquals("open(s1)", e.first().message)
    }

    @Test fun ring_buffer_caps_at_max() {
        repeat(DebugLog.MAX_ENTRIES + 50) { DebugLog.log("ws", "line $it") }
        val e = DebugLog.entries.value
        assertEquals(DebugLog.MAX_ENTRIES, e.size)
        // Oldest entries are evicted; the newest must be retained.
        assertEquals("line ${DebugLog.MAX_ENTRIES + 49}", e.last().message)
    }

    @Test fun registered_token_is_redacted() {
        DebugLog.setTokenToRedact("SECRET-TOKEN-123")
        DebugLog.log("rest", "GET /api/sessions  token=SECRET-TOKEN-123 end")
        val msg = DebugLog.entries.value.single().message
        assertFalse("raw token must not appear", msg.contains("SECRET-TOKEN-123"))
        assertTrue("token must be masked", msg.contains("***"))
    }

    @Test fun blank_token_does_not_redact_everything() {
        DebugLog.setTokenToRedact("")
        DebugLog.log("rest", "GET /api/status")
        assertEquals("GET /api/status", DebugLog.entries.value.single().message)
    }

    @Test fun clear_empties_the_buffer() {
        DebugLog.log("ws", "a")
        DebugLog.log("ws", "b")
        DebugLog.clear()
        assertTrue(DebugLog.entries.value.isEmpty())
    }

    @Test fun export_contains_category_and_messages_in_order() {
        DebugLog.log("session", "open(s1)")
        DebugLog.log("error", "message not found")
        val text = DebugLog.export()
        assertTrue(text.contains("session"))
        assertTrue(text.contains("open(s1)"))
        assertTrue(text.contains("message not found"))
        // newest-last ordering
        assertTrue(text.indexOf("open(s1)") < text.indexOf("message not found"))
    }

    @Test fun entries_survive_into_the_next_process() {
        // First run: capture, then drop every in-memory trace the way a kill would.
        DebugLog.init(logDir(), direct)
        DebugLog.log("ws", "opening socket")
        DebugLog.log("session", "open(s1)")
        DebugLog.detachStore()
        DebugLog.setEnabled(false)
        DebugLog.clear()
        assertTrue(DebugLog.entries.value.isEmpty())

        // Second run: the same directory restores what the first one wrote.
        DebugLog.init(logDir(), direct)
        val restored = DebugLog.entries.value
        assertEquals(listOf("opening socket", "open(s1)"), restored.map { it.message })
        assertTrue("restored entries must be marked", restored.all { it.fromPreviousRun })
    }

    @Test fun restored_entries_precede_ones_captured_during_startup() {
        DebugLog.init(logDir(), direct)
        DebugLog.log("ws", "from the first run")
        DebugLog.detachStore()
        DebugLog.clear()

        DebugLog.log("ws", "from the new run")
        DebugLog.init(logDir(), direct)

        val entries = DebugLog.entries.value
        assertEquals(listOf("from the first run", "from the new run"), entries.map { it.message })
        assertFalse("live entries must not be flagged", entries.last().fromPreviousRun)
    }

    @Test fun disabled_logging_writes_nothing_to_disk() {
        DebugLog.init(logDir(), direct)
        DebugLog.setEnabled(false)
        DebugLog.log("ws", "should not be recorded")

        assertTrue(DiagnosticLogStore(logDir()).readRecent(10).isEmpty())
    }

    @Test fun the_registered_token_is_redacted_on_disk_too() {
        DebugLog.init(logDir(), direct)
        DebugLog.setTokenToRedact("SECRET-TOKEN-123")
        DebugLog.log("rest", "GET /api/sessions  token=SECRET-TOKEN-123 end")

        val onDisk = DiagnosticLogStore(logDir()).readRecent(10).single().message
        assertFalse("raw token must not reach the file", onDisk.contains("SECRET-TOKEN-123"))
        assertTrue(onDisk.contains("***"))
    }

    @Test fun clear_also_empties_the_file() {
        DebugLog.init(logDir(), direct)
        DebugLog.log("ws", "a")
        DebugLog.clear()

        assertTrue(DiagnosticLogStore(logDir()).readRecent(10).isEmpty())
    }

    @Test fun export_marks_entries_from_an_earlier_run() {
        DebugLog.init(logDir(), direct)
        DebugLog.log("ws", "before the kill")
        DebugLog.detachStore()
        DebugLog.clear()
        DebugLog.init(logDir(), direct)

        assertTrue(DebugLog.export().contains("(previous run)"))
    }

    @Test fun export_if_any_is_null_when_nothing_was_captured() {
        assertEquals(null, DebugLog.exportIfAny())
        DebugLog.log("ws", "something")
        assertTrue(DebugLog.exportIfAny()!!.contains("something"))
    }
}
