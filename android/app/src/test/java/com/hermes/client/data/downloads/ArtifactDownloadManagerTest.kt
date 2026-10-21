package com.hermes.client.data.downloads

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.data.network.HermesApiException
import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.domain.ArtifactDownloadDescriptor
import com.hermes.client.domain.ChatFile
import io.mockk.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArtifactDownloadManagerTest {
    private lateinit var context: Context
    private lateinit var rest: HermesRestApi
    private val descriptor = ArtifactDownloadDescriptor("a".repeat(64), 6, "\"${"b".repeat(64)}\"", "video/mp4", 8L * 1024 * 1024 * 1024)
    private val file = ChatFile("file", "video.mp4", "video/mp4", 6, remotePath = "/tmp/video.mp4")

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("artifact-downloads.db")
        File(context.filesDir, "attachment-downloads").deleteRecursively()
        rest = mockk()
        coEvery { rest.artifactOwnerKey(any()) } returns "owner-a"
        coEvery { rest.prepareArtifactDownload(any(), any(), any()) } returns descriptor
    }

    @Test fun crash_recovery_keeps_only_committed_ranges_and_reuses_complete_files() = runBlocking {
        var calls = 0
        coEvery { rest.downloadArtifactRange(any(), any(), any(), any(), any()) } coAnswers {
            val destination = secondArg<File>()
            if (++calls == 1) destination.appendText("abc")
            else { destination.appendText("X"); throw IOException("network lost") }
        }
        val first = ArtifactDownloadManager(context, rest)
        val task = first.enqueue(file, "mac-a")
        assertTrue(first.runTask(task.id) {})
        assertEquals(3L, first.awaitTask(task.id)!!.downloaded)
        assertEquals(ArtifactDownloadStatus.WAITING, first.awaitTask(task.id)!!.status)

        val restored = ArtifactDownloadManager(context, rest)
        coEvery { rest.downloadArtifactRange(any(), any(), any(), any(), any()) } coAnswers {
            val destination = secondArg<File>()
            assertEquals("abc", destination.readText())
            destination.appendText("def")
        }
        assertFalse(restored.runTask(task.id) {})
        val complete = restored.awaitTask(task.id)!!
        assertEquals(ArtifactDownloadStatus.COMPLETE, complete.status)
        assertEquals("abcdef", restored.completedFile(complete)!!.readText())
        assertEquals(complete, restored.enqueue(file, "mac-a"))
        coEvery { rest.artifactOwnerKey("mac-b") } returns "owner-b"
        val otherMac = restored.enqueue(file, "mac-b")
        assertEquals(ArtifactDownloadManager.taskId("owner-a", file.remotePath!!), task.id)
        assertNotEquals(ArtifactDownloadManager.taskId("owner-b", file.remotePath!!), task.id)
        assertNotEquals(task.id, otherMac.id)
    }

    @Test fun a_changed_source_never_appends_to_the_old_partial() = runBlocking {
        val manager = ArtifactDownloadManager(context, rest)
        val task = manager.enqueue(file, "mac-a")
        var calls = 0
        coEvery { rest.downloadArtifactRange(any(), any(), any(), any(), any()) } coAnswers {
            if (++calls == 1) secondArg<File>().appendText("abc") else throw IOException("offline")
        }
        assertTrue(manager.runTask(task.id) {})
        coEvery { rest.prepareArtifactDownload(any(), any(), any()) } returns descriptor.copy(etag = "\"${"c".repeat(64)}\"")
        clearMocks(rest, answers = false, recordedCalls = true)
        assertFalse(manager.runTask(task.id) {})
        assertEquals("HR-FILE-011", manager.awaitTask(task.id)!!.errorCode)
        coVerify(exactly = 0) { rest.downloadArtifactRange(any(), any(), any(), any(), any()) }
    }

    @Test fun account_change_stops_a_task_before_reading_the_remote_file() = runBlocking {
        val manager = ArtifactDownloadManager(context, rest)
        val task = manager.enqueue(file, "mac-a")
        coEvery { rest.artifactOwnerKey(any()) } returns "owner-b"
        assertFalse(manager.runTask(task.id) {})
        assertEquals("HR-AUTH-001", manager.awaitTask(task.id)!!.errorCode)
        coVerify(exactly = 0) { rest.prepareArtifactDownload(any(), any(), any()) }
    }

    @Test fun transient_progress_is_never_restored_as_a_committed_checkpoint() {
        val store = ArtifactDownloadStore(context)
        val task = ArtifactDownloadTask("id", "owner", "mac", "/tmp/a", "a", null,
            downloaded = 10, progressBytes = 99)
        store.put(task); store.close()
        val reopened = ArtifactDownloadStore(context)
        assertEquals(10L, reopened.all().single().downloaded)
        assertNull(reopened.all().single().progressBytes)
        reopened.close()
    }

    @Test fun disk_shortage_is_actionable_and_does_not_start_a_transfer() = runBlocking {
        val manager = ArtifactDownloadManager(context, rest)
        val task = manager.enqueue(file, "mac-a")
        val tooMuch = context.filesDir.usableSpace + 32L * 1024 * 1024
        coEvery { rest.prepareArtifactDownload(any(), any(), any()) } returns descriptor.copy(sizeBytes = tooMuch, maxBytes = tooMuch)
        assertFalse(manager.runTask(task.id) {})
        assertEquals("HR-FILE-014", manager.awaitTask(task.id)!!.errorCode)
        coVerify(exactly = 0) { rest.downloadArtifactRange(any(), any(), any(), any(), any()) }
    }

    @Test fun pause_and_cancel_survive_an_io_exception_from_the_closed_connection() = runBlocking {
        for (cancel in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            var requests = 0
            coEvery { rest.downloadArtifactRange(any(), any(), any(), any(), any()) } coAnswers {
                if (++requests == 1) secondArg<File>().appendText("abc")
                else {
                    entered.complete(Unit)
                    try { awaitCancellation() } finally { throw IOException("socket closed by cancellation") }
                }
            }
            val manager = ArtifactDownloadManager(context, rest)
            val task = manager.enqueue(file.copy(remotePath = "/tmp/$cancel.mp4"), "mac-a")
            val running = async(Dispatchers.Default) { manager.runTask(task.id) {} }
            withTimeout(5000) { entered.await() }
            (if (cancel) manager.cancel(task.id) else manager.pause(task.id)).join()
            try { running.await() } catch (_: CancellationException) { }
            assertEquals(if (cancel) ArtifactDownloadStatus.CANCELLED else ArtifactDownloadStatus.PAUSED, manager.awaitTask(task.id)!!.status)
            assertEquals(3L, manager.awaitTask(task.id)!!.downloaded)
        }
    }
}
