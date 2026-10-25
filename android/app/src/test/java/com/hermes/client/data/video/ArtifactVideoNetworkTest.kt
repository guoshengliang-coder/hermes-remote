package com.hermes.client.data.video

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.data.network.testAccountConnection
import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.data.network.testHttpClient
import com.hermes.client.domain.ArtifactDownloadDescriptor
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import mockwebserver3.*
import mockwebserver3.junit4.MockWebServerRule
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArtifactVideoNetworkTest {
    @get:Rule val server = MockWebServerRule()

    @Test fun two_authenticated_HTTP_ranges_reach_the_server_before_either_response_is_released() = runBlocking {
        val block = ArtifactRangeCache.BLOCK_BYTES
        val desc = ArtifactDownloadDescriptor("a".repeat(64), 2L * block,
            "\"${"b".repeat(64)}\"", "video/mp4", 2L * block, maxConcurrentRanges = 2)
        val both = CountDownLatch(2)
        server.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertEquals("Bearer fixture-secret", request.headers["Authorization"])
                assertEquals(desc.etag, request.headers["If-Match"])
                val start = request.headers["Range"]!!.substringAfter("bytes=").substringBefore('-').toLong()
                both.countDown()
                check(both.await(5, TimeUnit.SECONDS)) { "HTTP ranges were serialized" }
                return MockResponse.Builder().code(206).addHeader("ETag", desc.etag)
                    .addHeader("Content-Range", "bytes $start-${start + block - 1}/${desc.sizeBytes}")
                    .body(Buffer().write(ByteArray(block) { (start / block + 1).toByte() })).build()
            }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.cacheDir, "video-ranges").deleteRecursively()
        val rest = HermesRestApi(testHttpClient(), Json { ignoreUnknownKeys = true }) {
            testAccountConnection(server.server.url("/").toString().trimEnd('/'), "fixture-secret")
        }
        val repository = ArtifactRangeRepository(context, rest)
        val source = ArtifactRangeRepository.Source(rest.artifactOwnerKey(null)!!, null, "/parallel.mp4", desc)
        val data = withTimeout(15_000) {
            listOf(0L, block.toLong()).map { start -> async(Dispatchers.IO) { repository.read(source, start) } }.awaitAll()
        }
        assertEquals(2, server.server.requestCount)
        assertEquals(1.toByte(), data[0][0]); assertEquals(2.toByte(), data[1][0])
    }
}
