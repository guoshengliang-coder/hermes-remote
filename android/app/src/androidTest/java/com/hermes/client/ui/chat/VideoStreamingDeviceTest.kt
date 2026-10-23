package com.hermes.client.ui.chat

import android.content.Context
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.theme.HermesTheme
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hermes.client.data.auth.CredentialStore
import com.hermes.client.data.auth.GatewayConfig
import com.hermes.client.data.downloads.ArtifactDownloadManager
import com.hermes.client.data.video.ArtifactRangeRepository
import com.hermes.client.data.video.VideoPlayback
import com.hermes.client.domain.ChatFile
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Real decoder/surface, original generated MP4 with a tail moov behind a virtual free atom. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoStreamingDeviceTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val activity = ActivityScenarioRule(ComponentActivity::class.java)
    @Inject lateinit var ranges: ArtifactRangeRepository
    @Inject lateinit var downloads: ArtifactDownloadManager
    @Inject lateinit var credentials: CredentialStore
    @Before fun inject() { hilt.inject() }

    @Test fun small_video_renders_before_full_transfer_and_download_reuses_playback() = verify(2 * 1024 * 1024, true)
    @Test fun video_above_100_MiB_renders_and_seeks_without_downloading_the_whole_file() = verify(126 * 1024 * 1024, false)

    @Test fun actual_dialog_pauses_network_in_background_and_keeps_explicit_download_action(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("video/generated-h264-aac.mp4").use { it.readBytes() }
        val source = VideoFixtureSource(bytes, 2 * 1024 * 1024)
        val previous = credentials.load()
        val show = mutableStateOf(true)
        var downloadClicks = 0
        credentials.save(GatewayConfig("http://127.0.0.1:${source.port}", "local-video-test"))
        try {
            val file = ChatFile("ui", "视频试片.mp4", "video/mp4", source.size, remotePath = "/fixture/dialog.mp4")
            val remote = ranges.prepare(file, null)
            activity.scenario.onActivity { host -> host.setContent {
                HermesTheme { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.EN) {
                    if (show.value) VideoPlayerDialog(file.name,
                        prepare = { VideoPlayback(repository = ranges, source = remote) },
                        download = { downloadClicks++ }, onDismiss = { show.value = false })
                } }
            } }
            val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
            while (source.offsets.none { it > source.size - 512 * 1024 } && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            assertTrue(source.offsets.any { it > source.size - 512 * 1024 })
            Thread.sleep(1500)
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            java.io.File(context.getExternalFilesDir(null), "hg222-stream-player.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            assertTrue(node("Pause").isVisibleToUser)
            click("Download")
            assertEquals(1, downloadClicks)
            click("Pause")
            assertTrue(node("Play").isVisibleToUser)
            Thread.sleep(500); val pausedBytes = source.sent.get(); Thread.sleep(500)
            assertEquals(pausedBytes, source.sent.get())
            click("Play")
            activity.scenario.moveToState(Lifecycle.State.CREATED)
            Thread.sleep(750); val backgroundBytes = source.sent.get(); Thread.sleep(500)
            assertEquals(backgroundBytes, source.sent.get())
            activity.scenario.moveToState(Lifecycle.State.RESUMED)
            assertTrue(node("Play").isVisibleToUser)
        } finally {
            activity.scenario.onActivity { show.value = false }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (previous == null) credentials.clear() else credentials.save(previous)
            source.close()
        }
    }

    private fun node(text: String): android.view.accessibility.AccessibilityNodeInfo {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val end = android.os.SystemClock.elapsedRealtime() + 5_000
        var descriptions = emptyList<String>()
        do {
            val roots = automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)
            val nodes = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
            fun collect(node: android.view.accessibility.AccessibilityNodeInfo) {
                nodes.add(node)
                for (i in 0 until node.childCount) node.getChild(i)?.let(::collect)
            }
            roots.forEach(::collect)
            nodes.firstOrNull { it.text?.toString()?.trim() == text || it.contentDescription?.toString()?.trim() == text }?.let { return it }
            descriptions = nodes.mapNotNull { it.text?.toString() ?: it.contentDescription?.toString() }.distinct()
            Thread.sleep(50)
        } while (android.os.SystemClock.elapsedRealtime() < end)
        throw AssertionError("missing visible control: $text; fixture UI=$descriptions")
    }
    private fun click(text: String) {
        var target: android.view.accessibility.AccessibilityNodeInfo? = node(text)
        while (target != null && !target.isClickable) target = target.parent
        assertTrue(target?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun verify(padding: Int, finishDownload: Boolean) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val mp4 = InstrumentationRegistry.getInstrumentation().context.assets.open("video/generated-h264-aac.mp4").use { it.readBytes() }
        val source = VideoFixtureSource(mp4, padding)
        val previous = credentials.load()
        credentials.save(GatewayConfig("http://127.0.0.1:${source.port}", "local-video-test"))
        var player: ExoPlayer? = null
        var lease: AutoCloseable? = null
        try {
            val file = ChatFile("v", "stream-$padding.mp4", "video/mp4", source.size, remotePath = "/fixture/stream-$padding.mp4")
            val remote = ranges.prepare(file, null)
            val video = VideoPlayback(repository = ranges, source = remote)
            lease = video.lease()
            val rendered = CountDownLatch(1)
            val bytesAtFrame = AtomicLong(Long.MAX_VALUE)
            val failure = java.util.concurrent.atomic.AtomicReference<String>()
            activity.scenario.onActivity { host ->
                val surface = SurfaceView(host); host.setContentView(surface)
                player = ExoPlayer.Builder(host).build().also { p ->
                    p.setVideoSurfaceView(surface)
                    p.addListener(object : Player.Listener {
                        override fun onRenderedFirstFrame() { bytesAtFrame.set(source.sent.get()); rendered.countDown() }
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) { failure.set(error.toString()); rendered.countDown() }
                    })
                    p.setMediaSource(video.mediaSource(host)); p.prepare(); p.play()
                }
            }
            assertTrue("no first frame: ${failure.get()}", rendered.await(30, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertTrue("first frame required a complete file", bytesAtFrame.get() < source.size)
            assertTrue("startup buffered the entire MP4 media payload", bytesAtFrame.get() < mp4.size)
            assertTrue("tail MP4 index was not sought", source.offsets.any { it > source.size - 512 * 1024 })
            assertTrue(source.validatedHeaders)
            activity.scenario.onActivity { player!!.seekTo(12_000); player!!.play() }
            Thread.sleep(1500)
            activity.scenario.onActivity { assertTrue(player!!.currentPosition >= 11_000); assertNull(player!!.playerError) }
            android.util.Log.i("HG222_STREAM_PROOF", "size=${source.size} firstFrameBytes=${bytesAtFrame.get()} requests=${source.offsets.size}")
            activity.scenario.onActivity { player!!.release(); player = null }
            lease?.close(); lease = null
            if (finishDownload) {
                val task = downloads.enqueue(file, null)
                assertFalse(downloads.runTask(task.id) {})
                val complete = downloads.awaitTask(task.id)!!
                assertEquals(source.size, downloads.completedFile(complete)!!.length())
                // Cached playback ranges are not downloaded again as the explicit task fills gaps.
                assertTrue(source.sent.get() < source.size + 1024 * 1024)
            }
        } finally {
            activity.scenario.onActivity { player?.release() }
            lease?.close()
            if (previous == null) credentials.clear() else credentials.save(previous)
            source.close()
        }
    }
}

private class VideoFixtureSource(mp4: ByteArray, private val padding: Int) : AutoCloseable {
    private val prefix: ByteArray
    private val moov: ByteArray
    val size: Long
    val sent = AtomicLong()
    val offsets = CopyOnWriteArrayList<Long>()
    @Volatile var validatedHeaders = true
    // nosemgrep: kotlin.lang.security.unencrypted-socket.unencrypted-socket -- androidTest synthetic MP4 source on loopback only; production uses authenticated HTTPS.
    private val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
    val port get() = server.localPort
    private val workers = Executors.newCachedThreadPool()
    private val etag = "\"${"b".repeat(64)}\""
    init {
        var at = 0
        while (String(mp4, at + 4, 4, Charsets.US_ASCII) != "moov") at += ByteBuffer.wrap(mp4, at, 4).int
        prefix = mp4.copyOfRange(0, at); moov = mp4.copyOfRange(at, mp4.size)
        size = mp4.size.toLong() + padding
        workers.execute {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                workers.execute {
                    socket.use {
                        try {
                            val input = it.getInputStream().bufferedReader()
                            val request = input.readLine() ?: return@execute
                            val headers = mutableMapOf<String, String>()
                            while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break
                                headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim() }
                            if (headers["x-hermes-session-token"] != "local-video-test") validatedHeaders = false
                            val output = it.getOutputStream()
                            if (request.startsWith("POST /api/files/downloads ")) {
                                val body = org.json.JSONObject().put("artifactId", "a".repeat(64)).put("sizeBytes", size)
                                    .put("etag", etag).put("contentType", "video/mp4").put("maxBytes", 8L * 1024 * 1024 * 1024)
                                    .put("maxConcurrentRanges", 4).put("maxRangeBytes", 1024 * 1024).toString()
                                output.write(("HTTP/1.1 200 OK\r\nContent-Length: ${body.toByteArray().size}\r\nx-hermes-file-channel: 1\r\nx-hermes-file-concurrency: 4\r\nConnection: close\r\n\r\n$body").toByteArray())
                            } else {
                                if (headers["if-match"] != etag) validatedHeaders = false
                                val range = Regex("bytes=(\\d+)-(\\d+)").matchEntire(headers["range"].orEmpty())!!
                                val start = range.groupValues[1].toLong(); val end = range.groupValues[2].toLong()
                                offsets.add(start)
                                output.write(("HTTP/1.1 206 Partial Content\r\nContent-Length: ${end - start + 1}\r\nContent-Range: bytes $start-$end/$size\r\nETag: $etag\r\nConnection: close\r\n\r\n").toByteArray())
                                var atByte = start
                                val header = ByteBuffer.allocate(8).putInt(padding).put("free".toByteArray()).array()
                                while (atByte <= end) {
                                    val count = minOf(8192L, end - atByte + 1).toInt()
                                    val data = ByteArray(count) { i ->
                                        val pos = atByte + i
                                        when {
                                            pos < prefix.size -> prefix[pos.toInt()]
                                            pos < prefix.size + 8 -> header[(pos - prefix.size).toInt()]
                                            pos >= prefix.size.toLong() + padding -> moov[(pos - prefix.size - padding).toInt()]
                                            else -> 0
                                        }
                                    }
                                    output.write(data); output.flush(); sent.addAndGet(count.toLong()); atByte += count
                                    Thread.sleep(2)
                                }
                            }
                            output.flush()
                        } catch (_: Exception) { /* Fixture cancellation closes its socket. */ }
                    }
                }
            }
        }
    }
    override fun close() { server.close(); workers.shutdownNow() }
}
