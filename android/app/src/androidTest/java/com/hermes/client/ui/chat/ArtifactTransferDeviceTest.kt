package com.hermes.client.ui.chat

import android.app.NotificationManager
import android.app.job.JobScheduler
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hermes.client.data.auth.CredentialStore
import com.hermes.client.data.auth.GatewayConfig
import com.hermes.client.data.downloads.*
import com.hermes.client.domain.ChatFile
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Real JobScheduler/notification/background execution; the HTTP source is a disposable fixture. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ArtifactTransferDeviceTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val activity = ActivityScenarioRule(ComponentActivity::class.java)
    @Inject lateinit var downloads: ArtifactDownloadManager
    @Inject lateinit var credentials: CredentialStore

    @Before fun inject() { hilt.inject() }

    @Test fun user_initiated_job_pauses_resumes_and_finishes_in_background() = runBlocking {
        if (android.os.Build.VERSION.SDK_INT < 34) return@runBlocking
        val context = ApplicationProvider.getApplicationContext<Context>()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS").close()
        val source = SlowFileSource()
        val previous = credentials.load()
        credentials.save(GatewayConfig("http://127.0.0.1:${source.port}", "local-device-test"))
        var id: String? = null
        try {
            val file = ChatFile("device", "device-download.bin", "application/octet-stream", source.size.toLong(), remotePath = "/fixture/device-download.bin")
            val task = downloads.enqueue(file, null); id = task.id
            activity.scenario.onActivity { downloads.schedule(task) }
            await { downloads.tasks.value.any { it.id == task.id && it.downloaded >= 1024 * 1024 } }
            val scheduler = context.getSystemService(JobScheduler::class.java)
            assertTrue(scheduler.allPendingJobs.any { it.extras.getString("task") == task.id && it.isUserInitiated })
            downloads.pause(task.id).join()
            assertEquals(ArtifactDownloadStatus.PAUSED, downloads.awaitTask(task.id)!!.status)
            val checkpoint = downloads.awaitTask(task.id)!!.downloaded
            assertTrue(checkpoint >= 1024 * 1024)
            val requestsBeforeResume = source.offsets.size
            val resumed = downloads.enqueue(file, null)
            activity.scenario.onActivity { downloads.schedule(resumed) }
            activity.scenario.moveToState(Lifecycle.State.CREATED)
            await { downloads.tasks.value.any { it.id == task.id && it.status == ArtifactDownloadStatus.COMPLETE } }
            val complete = downloads.awaitTask(task.id)!!
            assertEquals(source.size.toLong(), downloads.completedFile(complete)!!.length())
            assertTrue(source.offsets.drop(requestsBeforeResume).first() >= checkpoint)
            assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.any {
                it.notification.extras.getCharSequence("android.text")?.toString()?.contains("complete", ignoreCase = true) == true ||
                    it.notification.extras.getCharSequence("android.text")?.toString()?.contains("下载完成") == true
            })
        } finally {
            id?.let { downloads.cancel(it).join() }
            if (previous == null) credentials.clear() else credentials.save(previous)
            source.close()
        }
    }

    private fun await(predicate: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + 30_000
        while (!predicate() && android.os.SystemClock.elapsedRealtime() < end) android.os.SystemClock.sleep(50)
        assertTrue("download state did not reach expected condition", predicate())
    }
}

private class SlowFileSource : AutoCloseable {
    val size = 4 * 1024 * 1024
    val offsets = CopyOnWriteArrayList<Long>()
    // nosemgrep: kotlin.lang.security.unencrypted-socket.unencrypted-socket -- androidTest-only synthetic source, bound exclusively to device loopback; production uses HTTPS.
    private val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
    val port get() = server.localPort
    private val workers = Executors.newCachedThreadPool()
    init {
        workers.execute {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                workers.execute {
                    socket.use {
                        try {
                            val input = it.getInputStream().bufferedReader()
                            val request = input.readLine() ?: return@execute
                            val headers = mutableMapOf<String, String>()
                            while (true) {
                                val line = input.readLine() ?: break
                                if (line.isEmpty()) break
                                headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                            }
                            val output = it.getOutputStream()
                            val etag = "\"${"b".repeat(64)}\""
                            if (request.startsWith("POST /api/files/downloads ")) {
                                val body = org.json.JSONObject().put("artifactId", "a".repeat(64)).put("sizeBytes", size)
                                    .put("etag", etag).put("contentType", "application/octet-stream")
                                    .put("maxBytes", 1073741824).put("quickOpenBytes", 104857600).toString()
                                output.write(("HTTP/1.1 200 OK\r\nContent-Length: ${body.toByteArray().size}\r\nx-hermes-file-channel: 1\r\nConnection: close\r\n\r\n$body").toByteArray())
                            } else {
                                val range = Regex("bytes=(\\d+)-(\\d+)").matchEntire(headers["range"].orEmpty())!!
                                val start = range.groupValues[1].toLong(); val end = range.groupValues[2].toLong()
                                offsets.add(start)
                                output.write(("HTTP/1.1 206 Partial Content\r\nContent-Length: ${end - start + 1}\r\nContent-Range: bytes $start-$end/$size\r\nETag: $etag\r\nConnection: close\r\n\r\n").toByteArray())
                                val buffer = ByteArray(8192) { 7 }
                                var remaining = end - start + 1
                                while (remaining > 0) {
                                    val count = minOf(buffer.size.toLong(), remaining).toInt()
                                    output.write(buffer, 0, count); output.flush()
                                    remaining -= count; Thread.sleep(10)
                                }
                            }
                            output.flush()
                        } catch (_: Exception) { /* Cancellation closes the fixture's socket. */ }
                    }
                }
            }
        }
    }
    override fun close() { server.close(); workers.shutdownNow() }
}
