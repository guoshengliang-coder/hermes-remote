package com.hermes.client.data.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FeedbackAttachmentsTest {
    @get:Rule val temp = TemporaryFolder()

    private fun dir(): File = File(temp.root, FeedbackAttachments.DIRECTORY)

    @Test fun a_snapshot_is_written_as_a_log_file_carrying_the_text() {
        val file = FeedbackAttachments.snapshot(dir(), "diagnostic", "line one\nline two")

        assertTrue(file!!.isFile)
        // MissionGo files .log under diagnostics and .txt under documents, where a log is buried.
        assertTrue("must be attachable as a log, was ${file.name}", file.name.endsWith(".log"))
        assertTrue(file.name.startsWith("diagnostic-"))
        assertEquals("line one\nline two", file.readText())
    }

    @Test fun the_directory_is_created_on_demand() {
        assertFalse(dir().exists())
        assertTrue(FeedbackAttachments.snapshot(dir(), "diagnostic", "x")!!.isFile)
        assertTrue(dir().isDirectory)
    }

    @Test fun nothing_to_send_produces_no_file() {
        assertNull(FeedbackAttachments.snapshot(dir(), "diagnostic", ""))
        assertNull(FeedbackAttachments.snapshot(dir(), "diagnostic", "   \n  "))
        // A caller that got null must be able to submit anyway, so no empty file is left behind
        // to be uploaded as a mystery.
        assertEquals(0, dir().listFiles().orEmpty().size)
    }

    @Test fun two_snapshots_in_the_same_report_do_not_collide() {
        val a = FeedbackAttachments.snapshot(dir(), "diagnostic", "a", nowMillis = 1_000)
        val b = FeedbackAttachments.snapshot(dir(), "crash", "b", nowMillis = 1_000)
        assertTrue(a!!.name != b!!.name)
        assertEquals(2, dir().listFiles().orEmpty().size)
    }

    @Test fun snapshots_past_the_queue_expiry_are_pruned() {
        val now = System.currentTimeMillis()
        val stale = FeedbackAttachments.snapshot(dir(), "diagnostic", "old")!!
        val fresh = FeedbackAttachments.snapshot(dir(), "crash", "new")!!
        // Older than the SDK's own 24-hour queue horizon: nothing will ever upload it.
        stale.setLastModified(now - FeedbackAttachments.MAX_AGE_MILLIS - 60_000)

        FeedbackAttachments.pruneStale(dir(), now)

        assertFalse("expired snapshot must be deleted", stale.exists())
        assertTrue("a snapshot still awaiting upload must survive", fresh.exists())
    }

    @Test fun pruning_an_absent_directory_is_harmless() {
        FeedbackAttachments.pruneStale(dir())
        assertFalse(dir().exists())
    }
}
