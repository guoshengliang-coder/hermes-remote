package com.hermes.client.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateDownloadEngineTest {
    @Test fun `page snapshot does not wait behind the journal monitor`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("app_update", Context.MODE_PRIVATE).edit().clear().commit()
        val client = OkHttpClient()
        val engine = UpdateDownloadEngine(context, client, Json)
        engine.prepare(UpdateVersion("test", 9999, "com.hermes.remote", "internal", "2026-10-24T00:00:00Z",
            "Hermes-Remote-query-test-debug.apk", "https://mrlgs.net/releases/query-test.apk", 16,
            "a".repeat(64), "b".repeat(64), 26, listOf("test"), "abcdef1"))
        val repository = UpdateRepository(context, Json, client, engine)
        val acquired = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        val journal = workers.submit {
            synchronized(engine) {
                acquired.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        try {
            assertTrue(acquired.await(5, TimeUnit.SECONDS))
            val page = workers.submit<UpdateDownloadSnapshot?> { runBlocking { repository.query() } }
            assertNotNull(page.get(1, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            journal.get(5, TimeUnit.SECONDS)
            workers.shutdownNow()
            engine.cancel()
        }
    }

    @Test fun `repository exposes in flight bytes before the first durable chunk`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("app_update", Context.MODE_PRIVATE).edit().clear().commit()
        MockWebServer().use { server ->
            val size = 8L * 1024 * 1024
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "HEAD") return MockResponse.Builder().code(200)
                        .addHeader("Content-Length", size).addHeader("ETag", "\"test\"").build()
                    val (start, end) = Regex("bytes=(\\d+)-(\\d+)").matchEntire(request.headers["Range"]!!)!!.destructured
                    val length = (end.toLong() - start.toLong() + 1).toInt()
                    return MockResponse.Builder().code(206).addHeader("Content-Range", "bytes $start-$end/$size")
                        .addHeader("ETag", "\"test\"").body(Buffer().write(ByteArray(length)))
                        .throttleBody(16 * 1024, 25, TimeUnit.MILLISECONDS).build()
                }
            }
            server.start()
            val client = OkHttpClient()
            val engine = UpdateDownloadEngine(context, client, Json)
            val version = UpdateVersion("test", 9999, "com.hermes.remote", "internal", "2026-10-24T00:00:00Z",
                "Hermes-Remote-progress-test-debug.apk", server.url("/apk").toString(), size,
                "a".repeat(64), "b".repeat(64), 26, listOf("test"), "abcdef1")
            val repository = UpdateRepository(context, Json, client, engine)
            engine.prepare(version)
            val observed = CompletableDeferred<UpdateDownloadSnapshot?>()
            val runner = async(Dispatchers.IO) {
                engine.run { live ->
                    if (live.downloadedBytes > 0 && engine.record.value!!.downloadedBytes == 0L) {
                        observed.complete(runBlocking { repository.query() })
                    }
                }
            }
            try {
                val snapshot = withTimeout(10_000) { observed.await() }!!
                assertTrue("the page must see bytes before a whole chunk completes", snapshot.downloaded > 0)
                assertEquals(0, engine.record.value!!.downloadedBytes)
            } finally {
                engine.pause()
                runner.cancel()
                runner.join()
                val restored = UpdateDownloadEngine(context, client, Json)
                assertEquals(UpdateDownloadStatus.PAUSED, restored.restore()!!.status)
                assertEquals(0L, restored.snapshot()!!.downloaded)
                assertNull(restored.snapshot()!!.bytesPerSecond)
                engine.cancel()
            }
        }
    }
}
