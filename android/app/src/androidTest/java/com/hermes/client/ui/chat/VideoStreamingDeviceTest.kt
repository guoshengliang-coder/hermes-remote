package com.hermes.client.ui.chat

import android.content.Context
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import com.hermes.client.ui.workspace.SessionState
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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

    @Test fun continuous_reads_compare_equal_bytes_under_one_shared_bandwidth_budget(): Unit = runBlocking {
        val mp4 = InstrumentationRegistry.getInstrumentation().context.assets.open("video/generated-h264-aac.mp4").use { it.readBytes() }
        var expected: String? = null
        repeat(3) { attempt ->
            for (parallel in if (attempt % 2 == 0) listOf(1, 4) else listOf(4, 1)) {
                val source = VideoFixtureSource(mp4, 2 * 1024 * 1024, parallel, 120)
                val previous = credentials.load()
                credentials.save(GatewayConfig("http://127.0.0.1:${source.port}", "local-video-test"))
                var data: com.hermes.client.data.video.ArtifactVideoDataSource? = null
                var lease: AutoCloseable? = null
                try {
                    val file = ChatFile("pipeline", "pipeline.mp4", "video/mp4", source.size,
                        remotePath = "/fixture/pipeline-$attempt-$parallel-${System.nanoTime()}.mp4")
                    val remote = ranges.prepare(file, null)
                    lease = ranges.arbiter.playbackLease(remote.owner)
                    data = com.hermes.client.data.video.ArtifactVideoDataSource(ranges, remote)
                    data.open(androidx.media3.datasource.DataSpec.Builder()
                        .setUri(android.net.Uri.parse("hermes-video://attachment/pipeline")).build())
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(64 * 1024)
                    val started = android.os.SystemClock.elapsedRealtime()
                    var total = 0L
                    while (true) {
                        val count = data.read(buffer, 0, buffer.size)
                        if (count == androidx.media3.common.C.RESULT_END_OF_INPUT) break
                        digest.update(buffer, 0, count); total += count
                    }
                    val elapsed = android.os.SystemClock.elapsedRealtime() - started
                    val hash = digest.digest().joinToString("") { "%02x".format(it) }
                    if (expected == null) expected = hash else assertEquals(expected, hash)
                    assertEquals(source.size, total); assertEquals(source.size, source.sent.get())
                    assertTrue(source.peak.get() <= parallel)
                    android.util.Log.i("HG236_PIPELINE", "attempt=$attempt parallel=$parallel elapsedMs=$elapsed bytes=$total requests=${source.offsets.size} peak=${source.peak.get()} sha256=$hash")
                } finally {
                    data?.close(); lease?.close()
                    if (previous == null) credentials.clear() else credentials.save(previous)
                    source.close()
                }
            }
        }
    }

    /** Same reader and bytes, cold/warm cache, one shared 8 MiB/s budget; timings are evidence,
     * not a wall-clock pass/fail threshold. Pacing approximates a decoder consuming buffered bytes.
     */
    @Test fun preview_buffer_profile_cold_warm_and_consumer_pacing(): Unit = runBlocking {
        val mp4 = InstrumentationRegistry.getInstrumentation().context.assets.open("video/generated-h264-aac.mp4").use { it.readBytes() }
        for ((latency, consumerPause) in listOf(0L to 0L, 120L to 0L, 900L to 0L, 120L to 16L)) {
            profileBuffer(mp4, latency, consumerPause)
        }
    }

    @Test fun low_bandwidth_preview_records_each_required_range_wait(): Unit = runBlocking {
        val mp4 = InstrumentationRegistry.getInstrumentation().context.assets.open("video/generated-h264-aac.mp4").use { it.readBytes() }
        for (rate in listOf(128 * 1024, 512 * 1024)) {
            profileBuffer(mp4, 120, 0, bytesPerSecond = rate, windowBytes = 1024 * 1024L)
        }
    }

    private suspend fun profileBuffer(mp4: ByteArray, latency: Long, consumerPause: Long,
        bytesPerSecond: Int = 8 * 1024 * 1024, windowBytes: Long? = null) {
        repeat(3) { attempt ->
            val source = VideoFixtureSource(mp4, 2 * 1024 * 1024, 4, latency, bytesPerSecond)
            val previous = credentials.load()
            credentials.save(GatewayConfig("http://127.0.0.1:${source.port}", "local-video-test"))
            try {
                val file = ChatFile("profile", "profile.mp4", "video/mp4", source.size,
                    remotePath = "/fixture/profile-$latency-$consumerPause-$attempt-${System.nanoTime()}.mp4")
                val remote = ranges.prepare(file, null)
                val expectedBytes = windowBytes ?: source.size
                var expected: String? = null
                for (warm in listOf(false, true)) {
                    val data = com.hermes.client.data.video.ArtifactVideoDataSource(ranges, remote)
                    val beforeBytes = source.sent.get()
                    val beforeRequests = source.offsets.size
                    val waits = mutableListOf<Long>()
                    val rangeWaits = mutableListOf<Long>()
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    val start = android.os.SystemClock.elapsedRealtimeNanos()
                    var total = 0L
                    try {
                        data.open(androidx.media3.datasource.DataSpec.Builder()
                            .setUri(android.net.Uri.parse("hermes-video://attachment/profile")).setLength(expectedBytes).build())
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val at = android.os.SystemClock.elapsedRealtimeNanos()
                            val count = data.read(buffer, 0, buffer.size)
                            val waited = android.os.SystemClock.elapsedRealtimeNanos() - at
                            waits += waited
                            if (total % (256 * 1024) == 0L && count > 0) rangeWaits += waited / 1_000_000
                            if (count == androidx.media3.common.C.RESULT_END_OF_INPUT) break
                            digest.update(buffer, 0, count); total += count
                            if (consumerPause > 0) Thread.sleep(consumerPause)
                        }
                    } finally { data.close() }
                    val elapsed = (android.os.SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
                    val hash = digest.digest().joinToString("") { "%02x".format(it) }
                    if (expected == null) expected = hash else assertEquals(expected, hash)
                    assertEquals(expectedBytes, total)
                    assertEquals(if (warm) 0L else expectedBytes, source.sent.get() - beforeBytes)
                    assertTrue(source.peak.get() <= 4)
                    android.util.Log.i("VIDEO_BUFFER_PROFILE", "attempt=$attempt bytesPerSecond=$bytesPerSecond latencyMs=$latency consumerPauseMs=$consumerPause warm=$warm elapsedMs=$elapsed readWaitMs=${waits.sum() / 1_000_000} firstReadMs=${waits.first() / 1_000_000} maxReadMs=${waits.max() / 1_000_000} rangeWaitMs=${rangeWaits.joinToString(",")} requests=${source.offsets.size - beforeRequests} peak=${source.peak.get()} bytes=$total sha256=$hash")
                }
            } finally {
                if (previous == null) credentials.clear() else credentials.save(previous)
                source.close()
            }
        }
    }

    @Test fun delayed_loopback_records_serial_and_parallel_first_frame_and_seek() {
        for (latency in listOf(120L, 900L)) {
            repeat(if (latency == 120L) 3 else 1) { attempt ->
                for (parallel in if (attempt % 2 == 0) listOf(1, 4) else listOf(4, 1)) {
                    verify(2 * 1024 * 1024, false, parallel, latency, "$latency-$attempt-$parallel")
                }
            }
        }
    }

    @Test fun actual_dialog_precaches_while_paused_stops_in_background_and_on_close(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("video/generated-h264-aac.mp4").use { it.readBytes() }
        val source = VideoFixtureSource(bytes, 24 * 1024 * 1024, latency = 120, bytesPerSecond = 1024 * 1024)
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
            click("Playback speed")
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { menu ->
                java.io.File(context.getExternalFilesDir(null), "video-player-speed-menu.png").outputStream().use {
                    menu.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            click("1.5×")
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            val downAt = android.os.SystemClock.uptimeMillis()
            val screen = automation.takeScreenshot()
            fun touch(action: Int) {
                val event = android.view.MotionEvent.obtain(downAt, android.os.SystemClock.uptimeMillis(), action,
                    screen.width * .2f, screen.height * .45f, 0).apply {
                    this.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                }
                automation.injectInputEvent(event, true); event.recycle()
            }
            touch(android.view.MotionEvent.ACTION_DOWN)
            Thread.sleep(750)
            assertTrue(node("2× speed boost").isVisibleToUser)
            touch(android.view.MotionEvent.ACTION_UP)
            assertTrue(node("1.5×").isVisibleToUser)
            click("More")
            click("Download")
            assertEquals(1, downloadClicks)
            click("Pause")
            assertTrue(node("Play").isVisibleToUser)
            Thread.sleep(500); val pausedBytes = source.sent.get(); Thread.sleep(500)
            assertTrue("paused foreground did not continue caching", source.sent.get() > pausedBytes)
            val paused = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            var vividPixels = 0
            for (y in 0 until paused.height step 4) for (x in 0 until paused.width step 4) {
                val pixel = paused.getPixel(x, y)
                val channels = listOf(android.graphics.Color.red(pixel), android.graphics.Color.green(pixel), android.graphics.Color.blue(pixel))
                if (channels.max() - channels.min() > 80 && channels.max() > 150) vividPixels++
            }
            assertTrue("pause removed the decoded video frame", vividPixels > 1000)
            java.io.File(context.getExternalFilesDir(null), "video-player-paused.png").outputStream().use {
                paused.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            click("Play")
            activity.scenario.moveToState(Lifecycle.State.CREATED)
            Thread.sleep(750); val backgroundBytes = source.sent.get(); Thread.sleep(500)
            assertEquals(backgroundBytes, source.sent.get())
            activity.scenario.moveToState(Lifecycle.State.RESUMED)
            assertTrue(node("Play").isVisibleToUser)
            val returned = source.sent.get(); Thread.sleep(1_000)
            assertTrue("foreground did not resume missing cache", source.sent.get() > returned)
            click("Close")
            Thread.sleep(750); val closedBytes = source.sent.get(); Thread.sleep(750)
            assertEquals("closing kept precaching", closedBytes, source.sent.get())
        } finally {
            activity.scenario.onActivity { show.value = false }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (previous == null) credentials.clear() else credentials.save(previous)
            source.close()
        }
    }

    @Test fun fullscreen_recreation_preserves_file_position_and_pause_intent(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as android.app.Application
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("video/generated-h264-aac.mp4").use { it.readBytes() }
        val source = VideoFixtureSource(bytes, 2 * 1024 * 1024)
        val previous = credentials.load()
        val show = mutableStateOf(true)
        val position = AtomicLong()
        val initialized = java.util.concurrent.atomic.AtomicBoolean()
        credentials.save(GatewayConfig("http://127.0.0.1:${source.port}", "local-video-test"))
        var callbacks: android.app.Application.ActivityLifecycleCallbacks? = null
        try {
            val file = ChatFile("rotate", "rotation.mp4", "video/mp4", source.size, remotePath = "/fixture/rotation.mp4")
            val remote = ranges.prepare(file, null)
            fun render(host: ComponentActivity) { host.setContent {
                HermesTheme { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.EN) {
                    var selected by rememberVideoFile("rotation-fixture")
                    androidx.compose.runtime.LaunchedEffect(Unit) { if (initialized.compareAndSet(false, true)) selected = file }
                    if (show.value && selected != null) VideoPlayerDialog(selected!!.name,
                        prepare = { VideoPlayback(repository = ranges, source = remote,
                            initialPosition = position.get(), rememberPosition = position::set) },
                        download = {}, onDismiss = { selected = null })
                } }
            } }
            callbacks = object : android.app.Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(host: android.app.Activity, state: android.os.Bundle?) {
                    if (host.javaClass == ComponentActivity::class.java) render(host as ComponentActivity)
                }
                override fun onActivityStarted(host: android.app.Activity) {}
                override fun onActivityResumed(host: android.app.Activity) {}
                override fun onActivityPaused(host: android.app.Activity) {}
                override fun onActivityStopped(host: android.app.Activity) {}
                override fun onActivitySaveInstanceState(host: android.app.Activity, state: android.os.Bundle) {}
                override fun onActivityDestroyed(host: android.app.Activity) {}
            }
            app.registerActivityLifecycleCallbacks(callbacks)
            activity.scenario.onActivity(::render)
            val startedDeadline = android.os.SystemClock.elapsedRealtime() + 30_000
            while (position.get() < 1_000 && android.os.SystemClock.elapsedRealtime() < startedDeadline) Thread.sleep(50)
            click("Pause")
            click("Playback speed"); click("1.5×")
            Thread.sleep(300)
            val before = position.get()
            assertTrue(before > 0)
            click("Fullscreen")
            assertTrue(node("Exit fullscreen").isVisibleToUser)
            assertTrue(node("Play").isVisibleToUser)
            assertTrue("rotation changed paused position", kotlin.math.abs(before - position.get()) < 100)
            val landscape = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            assertTrue("fullscreen did not enter landscape", landscape.width > landscape.height)
            var vivid = 0
            for (y in 0 until landscape.height step 8) for (x in 0 until landscape.width step 8) {
                val pixel = landscape.getPixel(x, y)
                val channels = listOf(android.graphics.Color.red(pixel), android.graphics.Color.green(pixel), android.graphics.Color.blue(pixel))
                if (channels.max() - channels.min() > 80 && channels.max() > 150) vivid++
            }
            assertTrue("rotation lost the paused frame", vivid > 300)
            java.io.File(context.getExternalFilesDir(null), "video-player-landscape.png").outputStream().use {
                landscape.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            assertTrue("rotation lost selected constant speed", node("1.5×").isVisibleToUser)
            Thread.sleep(750)
            assertTrue("paused rotation started playback", kotlin.math.abs(before - position.get()) < 100)
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            assertTrue(node("Play").isVisibleToUser)
            assertTrue(node("Fullscreen").isVisibleToUser)
            click("Play")
            Thread.sleep(1_500)
            assertTrue("rotation lost playback position", position.get() >= before)
            click("Fullscreen")
            assertTrue(node("Exit fullscreen").isVisibleToUser)
            assertTrue(node("Pause").isVisibleToUser)
            click("Pause")
            click("Close")
        } finally {
            show.value = false
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            callbacks?.let(app::unregisterActivityLifecycleCallbacks)
            if (previous == null) credentials.clear() else credentials.save(previous)
            source.close()
        }
    }

    @Test fun returning_to_a_cached_page_does_not_autoplay_but_may_precache(): Unit = runBlocking {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("video/generated-h264-aac.mp4").use { it.readBytes() }
        val source = VideoFixtureSource(bytes, 2 * 1024 * 1024)
        val previous = credentials.load()
        val visible = mutableStateOf(true)
        val cache = com.hermes.client.ui.workspace.SessionUiStateCache()
        credentials.save(GatewayConfig("http://127.0.0.1:${source.port}", "local-video-test"))
        try {
            val file = ChatFile("return", "return.mp4", "video/mp4", source.size, remotePath = "/fixture/return.mp4")
            val remote = ranges.prepare(file, null)
            val savedPosition = AtomicLong()
            val video = VideoPlayback(repository = ranges, source = remote, rememberPosition = savedPosition::set)
            activity.scenario.onActivity { host -> host.setContent {
                HermesTheme { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.EN) {
                    if (visible.value) cache.SessionState("fixture") {
                        VideoPlayerDialog(file.name, prepare = { video }, download = {}, onDismiss = { visible.value = false })
                    }
                } }
            } }
            val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
            while (savedPosition.get() < 1_000 && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            assertTrue(savedPosition.get() > 0)
            activity.scenario.onActivity { cache.captureBeforeNavigation(); visible.value = false }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(500)
            val bytesBefore = source.sent.get()
            activity.scenario.onActivity { visible.value = true }
            assertTrue(node("Play").isVisibleToUser)
            Thread.sleep(500)
            assertTrue("cache progress went backwards", source.sent.get() >= bytesBefore)
            assertTrue(node("1×").isVisibleToUser)
        } finally {
            visible.value = false
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (previous == null) credentials.clear() else credentials.save(previous)
            source.close()
        }
    }


    @Test fun actual_decoder_applies_all_speeds_preserves_pitch_and_warm_position_rate(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val local = java.io.File(context.cacheDir, "speed-fixture.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("video/generated-h264-aac.mp4").use { input ->
            local.outputStream().use(input::copyTo)
        }
        var player: ExoPlayer? = null
        val ready = CountDownLatch(1)
        try {
            activity.scenario.onActivity { host ->
                val surface = SurfaceView(host); host.setContentView(surface)
                player = ExoPlayer.Builder(host).setLoadControl(VideoBufferPolicy.loadControl()).build().also { p ->
                    p.setVideoSurfaceView(surface)
                    p.addListener(object : Player.Listener { override fun onRenderedFirstFrame() { ready.countDown() } })
                    p.setMediaSource(VideoPlayback(localFile = local).mediaSource(host)); p.prepare(); p.play()
                }
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS))
            for (speed in VIDEO_SPEEDS) activity.scenario.onActivity {
                player!!.playbackParameters = androidx.media3.common.PlaybackParameters(speed, 1f)
                assertEquals(speed, player!!.playbackParameters.speed)
                assertEquals(1f, player!!.playbackParameters.pitch)
            }
            var before = 0L
            activity.scenario.onActivity { player!!.seekTo(1_000); player!!.playbackParameters = androidx.media3.common.PlaybackParameters(2f, 1f) }
            Thread.sleep(500)
            activity.scenario.onActivity { before = player!!.currentPosition }
            Thread.sleep(2_000)
            activity.scenario.onActivity {
                val advanced = player!!.currentPosition - before
                assertTrue("2x renderer clock advanced $advanced", advanced in 3_300..4_700)
                assertNull(player!!.playerError)
            }
        } finally { activity.scenario.onActivity { player?.release() }; local.delete() }
    }


    @Test fun explicit_download_preempts_precache_and_completes_after_filler_closes(): Unit = runBlocking {
        val mp4 = InstrumentationRegistry.getInstrumentation().context.assets.open("video/generated-h264-aac.mp4").use { it.readBytes() }
        val fixture = VideoFixtureSource(mp4, 4 * 1024 * 1024, 4, 120, 512 * 1024)
        val previous = credentials.load()
        credentials.save(GatewayConfig("http://127.0.0.1:${fixture.port}", "local-video-test"))
        var filler: Job? = null
        try {
            val file = ChatFile("download-priority", "download-priority.mp4", "video/mp4", fixture.size,
                remotePath = "/fixture/download-priority-${System.nanoTime()}.mp4")
            val video = VideoPlayback(repository = ranges, source = ranges.prepare(file, null))
            val session = com.hermes.client.data.video.VideoPrecacheSession()
            filler = launch { video.precache(session) { true } }
            withTimeout(10_000) { session.state.first { it.bytes >= 256 * 1024 } }
            val task = downloads.enqueue(file, null)
            val transfer = async { downloads.runTask(task.id) {} }
            delay(250)
            filler.cancelAndJoin(); filler = null
            withTimeout(60_000) { assertFalse(transfer.await()) }
            val complete = downloads.awaitTask(task.id)!!
            assertEquals(fixture.size, downloads.completedFile(complete)!!.length())
            assertTrue("cache was downloaded twice", fixture.sent.get() < fixture.size + 1024 * 1024)
        } finally {
            filler?.cancelAndJoin()
            if (previous == null) credentials.clear() else credentials.save(previous)
            fixture.close()
        }
    }

    /** Two real minutes per scenario; pass videoSpeedFixture (generated >=360s H264/AAC in the
     * app's external files directory) for continuous cold footage. Without it this is explicitly
     * a 16s looping smoke, not a long-video throughput measurement. No real account/network.
     */
    @Test fun speed_buffer_two_minute_matrix_records_rebuffer_bytes_and_peak_memory(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixturePath = InstrumentationRegistry.getArguments().getString("videoSpeedFixture")
        val continuous = fixturePath != null
        val mp4 = if (fixturePath != null) {
            val file = java.io.File(fixturePath).canonicalFile
            require(file.parentFile == context.getExternalFilesDir(null)!!.canonicalFile && file.length() in 1..(128L * 1024 * 1024))
            file.readBytes()
        } else InstrumentationRegistry.getInstrumentation().context.assets.open("video/generated-h264-aac.mp4").use { it.readBytes() }
        for ((speed, rate, latency) in listOf(Triple(1f, 128 * 1024, 120L),
            Triple(1.5f, 512 * 1024, 120L), Triple(2f, 8 * 1024 * 1024, 900L), Triple(3f, 8 * 1024 * 1024, 120L))) {
            val fixture = VideoFixtureSource(mp4, 24 * 1024 * 1024, 4, latency, rate)
            val previous = credentials.load()
            credentials.save(GatewayConfig("http://127.0.0.1:${fixture.port}", "local-video-test"))
            var player: ExoPlayer? = null
            var controller: VideoPlayerController? = null
            var filler: Job? = null
            val first = CountDownLatch(1); val sought = CountDownLatch(1)
            val failed = java.util.concurrent.atomic.AtomicReference<String>()
            val started = android.os.SystemClock.elapsedRealtime()
            val firstMs = AtomicLong(); val seekMs = AtomicLong(); val seekAt = AtomicLong()
            val bufferingAt = AtomicLong(); val rebufferMs = AtomicLong()
            val stalls = java.util.concurrent.atomic.AtomicInteger()
            var peakPss = 0; var maxRealBuffer = 0L
            try {
                val remote = ranges.prepare(ChatFile("matrix", "matrix.mp4", "video/mp4", fixture.size,
                    remotePath = "/fixture/speed-$speed-${System.nanoTime()}.mp4"), null)
                val video = VideoPlayback(repository = ranges, source = remote)
                activity.scenario.onActivity { host ->
                    val surface = SurfaceView(host); host.setContentView(surface)
                    player = ExoPlayer.Builder(host).setLoadControl(VideoBufferPolicy.loadControl()).build().also { p ->
                        p.setVideoSurfaceView(surface); p.repeatMode = Player.REPEAT_MODE_ALL
                        p.addListener(object : Player.Listener {
                            override fun onRenderedFirstFrame() {
                                val now = android.os.SystemClock.elapsedRealtime()
                                if (first.count > 0) { firstMs.set(now - started); first.countDown() }
                                else if (seekAt.get() > 0 && sought.count > 0) { seekMs.set(now - seekAt.get()); sought.countDown() }
                            }
                            override fun onPlaybackStateChanged(state: Int) {
                                if (first.count == 0L && state == Player.STATE_BUFFERING) {
                                    stalls.incrementAndGet(); bufferingAt.compareAndSet(0, android.os.SystemClock.elapsedRealtime())
                                } else if (state == Player.STATE_READY) {
                                    val at = bufferingAt.getAndSet(0)
                                    if (at > 0) rebufferMs.addAndGet(android.os.SystemClock.elapsedRealtime() - at)
                                }
                            }
                            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { failed.set(error.errorCodeName); first.countDown() }
                        })
                        p.setMediaSource(video.mediaSource(host))
                        controller = VideoPlayerController(p, 0, video::lease, {}, {}, { failed.set(it.errorCodeName) }, initialSpeed = speed)
                        controller!!.play()
                    }
                }
                val session = com.hermes.client.data.video.VideoPrecacheSession()
                val allow = java.util.concurrent.atomic.AtomicBoolean(false)
                filler = launch { video.precache(session, allow::get) }
                assertTrue("first frame speed=$speed ${failed.get()}", first.await(30, TimeUnit.SECONDS))
                assertNull(failed.get())
                activity.scenario.onActivity {
                    if (continuous) assertTrue("continuous fixture must span 120s at 3x", player!!.duration >= 360_000)
                }
                seekAt.set(android.os.SystemClock.elapsedRealtime())
                activity.scenario.onActivity { player!!.seekTo(12_000) }
                assertTrue("seek speed=$speed", sought.await(30, TimeUnit.SECONDS))
                val until = android.os.SystemClock.elapsedRealtime() + 120_000
                var reportAt = android.os.SystemClock.elapsedRealtime() + 30_000
                while (android.os.SystemClock.elapsedRealtime() < until) {
                    activity.scenario.onActivity {
                        controller!!.refresh(); allow.set(VideoBufferPolicy.mayPrecache(controller!!.state))
                        val p = player!!
                        assertEquals(speed, p.playbackParameters.speed); assertEquals(1f, p.playbackParameters.pitch)
                        assertNull(p.playerError)
                        maxRealBuffer = maxOf(maxRealBuffer, ((p.bufferedPosition - p.currentPosition) / speed).toLong())
                    }
                    val memory = android.os.Debug.MemoryInfo(); android.os.Debug.getMemoryInfo(memory)
                    peakPss = maxOf(peakPss, memory.totalPss)
                    if (android.os.SystemClock.elapsedRealtime() >= reportAt) {
                        android.util.Log.i("VIDEO_SPEED_PROGRESS", "speed=$speed remainingMs=${until - android.os.SystemClock.elapsedRealtime()} bytes=${fixture.sent.get()} peakPssKiB=$peakPss stalls=${stalls.get()}")
                        reportAt += 30_000
                    }
                    delay(250)
                }
                val at = bufferingAt.getAndSet(0)
                if (at > 0) rebufferMs.addAndGet(android.os.SystemClock.elapsedRealtime() - at)
                android.util.Log.i("VIDEO_SPEED_MATRIX", "continuous=$continuous speed=$speed rate=$rate latencyMs=$latency firstFrameMs=${firstMs.get()} seekMs=${seekMs.get()} stalls=${stalls.get()} rebufferMs=${rebufferMs.get()} bytes=${fixture.sent.get()} peakRequests=${fixture.peak.get()} peakPssKiB=$peakPss maxRealBufferMs=$maxRealBuffer cachedPercent=${session.state.value.percent}")
                assertTrue(fixture.peak.get() <= 4)
                if (speed == 3f) {
                    // Full cache must cover the virtual padding too, then reopening causes no ranges.
                    withTimeout(30_000) { session.state.first { it.percent == 100 } }
                    filler.cancelAndJoin(); filler = null
                    activity.scenario.onActivity { controller!!.pause() }
                    val beforeBytes = fixture.sent.get()
                    val warm = com.hermes.client.data.video.VideoPrecacheSession()
                    val warmed = launch { video.precache(warm) { true } }
                    withTimeout(5_000) { warm.state.first { it.percent == 100 } }
                    warmed.cancelAndJoin()
                    assertEquals(beforeBytes, fixture.sent.get())
                    android.util.Log.i("VIDEO_SPEED_MATRIX", "warm speed=3 cachePercent=100 extraBytes=${fixture.sent.get() - beforeBytes}")
                }
            } finally {
                filler?.cancelAndJoin()
                activity.scenario.onActivity { controller?.close(); if (controller == null) player?.release() }
                if (previous == null) credentials.clear() else credentials.save(previous)
                fixture.close()
            }
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

    private fun verify(padding: Int, finishDownload: Boolean, parallel: Int = 4,
        latency: Long = 0, sample: String = "default"): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val mp4 = InstrumentationRegistry.getInstrumentation().context.assets.open("video/generated-h264-aac.mp4").use { it.readBytes() }
        val source = VideoFixtureSource(mp4, padding, parallel, latency)
        val previous = credentials.load()
        credentials.save(GatewayConfig("http://127.0.0.1:${source.port}", "local-video-test"))
        var player: ExoPlayer? = null
        var lease: AutoCloseable? = null
        try {
            val file = ChatFile("v", "stream-$padding.mp4", "video/mp4", source.size, remotePath = "/fixture/stream-$padding-$sample-${System.nanoTime()}.mp4")
            val remote = ranges.prepare(file, null)
            val video = VideoPlayback(repository = ranges, source = remote)
            lease = video.lease()
            val rendered = CountDownLatch(1)
            val bytesAtFrame = AtomicLong(Long.MAX_VALUE)
            val firstFrameMs = AtomicLong()
            val playbackStarted = android.os.SystemClock.elapsedRealtime()
            val seekFrame = CountDownLatch(1)
            val seekStarted = AtomicLong()
            val seekMs = AtomicLong(-1)
            val rebuffers = java.util.concurrent.atomic.AtomicInteger()
            val bufferedAt = AtomicLong()
            val rebufferMs = AtomicLong()
            val failure = java.util.concurrent.atomic.AtomicReference<String>()
            activity.scenario.onActivity { host ->
                val surface = SurfaceView(host); host.setContentView(surface)
                player = ExoPlayer.Builder(host).build().also { p ->
                    p.setVideoSurfaceView(surface)
                    p.addListener(object : Player.Listener {
                        override fun onRenderedFirstFrame() {
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (rendered.count > 0) {
                                bytesAtFrame.set(source.sent.get()); firstFrameMs.set(now - playbackStarted); rendered.countDown()
                            } else if (seekStarted.get() > 0) {
                                seekMs.set(now - seekStarted.get()); seekFrame.countDown()
                            }
                        }
                        override fun onPlaybackStateChanged(state: Int) {
                            if (rendered.count == 0L && state == Player.STATE_BUFFERING) {
                                rebuffers.incrementAndGet(); bufferedAt.compareAndSet(0, android.os.SystemClock.elapsedRealtime())
                            } else if (state == Player.STATE_READY) {
                                val at = bufferedAt.getAndSet(0)
                                if (at > 0) rebufferMs.addAndGet(android.os.SystemClock.elapsedRealtime() - at)
                            }
                        }
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
            seekStarted.set(android.os.SystemClock.elapsedRealtime())
            activity.scenario.onActivity { player!!.seekTo(12_000); player!!.play() }
            assertTrue("seek did not render", seekFrame.await(30, TimeUnit.SECONDS))
            Thread.sleep(250)
            activity.scenario.onActivity { assertTrue(player!!.currentPosition >= 11_000); assertNull(player!!.playerError) }
            android.util.Log.i("HG222_STREAM_PROOF", "sample=$sample parallel=$parallel latencyMs=$latency size=${source.size} firstFrameMs=${firstFrameMs.get()} firstFrameBytes=${bytesAtFrame.get()} seekMs=${seekMs.get()} rebuffers=${rebuffers.get()} rebufferMs=${rebufferMs.get()} sentBytes=${source.sent.get()} requests=${source.offsets.size} peak=${source.peak.get()}")
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

private class VideoFixtureSource(mp4: ByteArray, private val padding: Int,
    private val parallel: Int = 4, private val latency: Long = 0,
    private val bytesPerSecond: Int = 4 * 1024 * 1024) : AutoCloseable {
    private val prefix: ByteArray
    private val moov: ByteArray
    val size: Long
    val sent = AtomicLong()
    val peak = java.util.concurrent.atomic.AtomicInteger()
    private val active = java.util.concurrent.atomic.AtomicInteger()
    private val paceLock = Any()
    private var nextSendNanos = 0L
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
                    var rangeActive = false
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
                                    .put("maxConcurrentRanges", parallel).put("maxRangeBytes", if (parallel == 1) 256 * 1024 else 1024 * 1024).toString()
                                output.write(("HTTP/1.1 200 OK\r\nContent-Length: ${body.toByteArray().size}\r\nx-hermes-file-channel: 1\r\nx-hermes-file-concurrency: $parallel\r\nConnection: close\r\n\r\n$body").toByteArray())
                            } else {
                                if (headers["if-match"] != etag) validatedHeaders = false
                                val range = Regex("bytes=(\\d+)-(\\d+)").matchEntire(headers["range"].orEmpty())!!
                                val start = range.groupValues[1].toLong(); val end = range.groupValues[2].toLong()
                                offsets.add(start)
                                rangeActive = true
                                peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                                Thread.sleep(latency)
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
                                    // All responses share one budget; concurrency must not multiply fixture bandwidth.
                                    val wait = synchronized(paceLock) {
                                        val now = System.nanoTime()
                                        nextSendNanos = maxOf(now, nextSendNanos) + count * 1_000_000_000L / bytesPerSecond
                                        nextSendNanos - now
                                    }
                                    TimeUnit.NANOSECONDS.sleep(wait)
                                    output.write(data); output.flush(); sent.addAndGet(count.toLong()); atByte += count
                                }
                            }
                            output.flush()
                        } catch (_: Exception) { /* Fixture cancellation closes its socket. */ }
                        finally { if (rangeActive) active.decrementAndGet() }
                    }
                }
            }
        }
    }
    override fun close() { server.close(); workers.shutdownNow() }
}
