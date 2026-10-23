package com.hermes.client.data.downloads

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.data.network.HermesApiException
import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.data.video.ArtifactRangeCache
import com.hermes.client.data.video.ArtifactRangeRepository
import com.hermes.client.domain.ArtifactDownloadDescriptor
import com.hermes.client.domain.ChatFile
import io.mockk.*
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArtifactDownloadBatchTest {
    private val block = ArtifactRangeCache.BLOCK_BYTES
    private val descriptor = ArtifactDownloadDescriptor("a".repeat(64), 2L * 1024 * 1024,
        "\"${"b".repeat(64)}\"", "video/mp4", 8L * 1024 * 1024 * 1024,
        maxConcurrentRanges = 4, maxRangeBytes = 1024 * 1024)
    private lateinit var rest: HermesRestApi
    private lateinit var ranges: ArtifactRangeRepository
    private lateinit var target: File
    private lateinit var batch: ArtifactDownloadBatch
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.cacheDir, "video-ranges").deleteRecursively()
        rest = mockk(); coEvery { rest.artifactOwnerKey(any()) } returns "owner"
        coEvery { rest.prepareArtifactDownload(any(), any(), any()) } returns descriptor
        ranges = ArtifactRangeRepository(context, rest)
        batch = ArtifactDownloadBatch(rest, ranges)
        target = File.createTempFile("attachment-batch", ".part")
    }
    @After fun cleanup() { target.delete() }

    @Test fun out_of_order_parallel_responses_commit_only_an_ordered_complete_prefix() = runBlocking {
        val live = AtomicInteger(); val peak = AtomicInteger(); val entered = CompletableDeferred<Unit>()
        coEvery { rest.readArtifactRangeWithProgress(any(), any(), any(), any(), any(), any()) } coAnswers {
            val count = live.incrementAndGet(); peak.updateAndGet { maxOf(it, count) }
            if (count == 4) entered.complete(Unit)
            withTimeout(5000) { entered.await() }
            delay(40 - secondArg<Long>() / block * 10)
            live.decrementAndGet()
            ByteArray(thirdArg()) { (secondArg<Long>() / block + 1).toByte() }
        }
        batch.append(descriptor, target, "mac", "owner", "/video.mp4") {}
        assertEquals(4, peak.get()); assertEquals(4L * block, target.length())
        val data = target.readBytes()
        for (index in 0..3) assertEquals((index + 1).toByte(), data[index * block])
    }

    @Test fun a_failed_parallel_range_never_commits_gaps_or_overwrites_the_existing_prefix() = runBlocking {
        target.writeBytes(ByteArray(block) { 9 })
        coEvery { rest.readArtifactRangeWithProgress(any(), any(), any(), any(), any(), any()) } coAnswers {
            if (secondArg<Long>() == block.toLong()) throw IOException("network interrupted")
            delay(100); ByteArray(thirdArg())
        }
        try { batch.append(descriptor, target, "mac", "owner", "/video.mp4") {}; fail() }
        catch (_: IOException) { }
        assertEquals(block.toLong(), target.length()); assertEquals(9.toByte(), target.readBytes().last())
    }

    @Test fun playback_cache_hits_are_not_fetched_again() = runBlocking {
        coEvery { rest.readArtifactRangeWithProgress(any(), any(), any(), any(), any(), any()) } answers { ByteArray(thirdArg()) { 7 } }
        coEvery { rest.readArtifactRange(any(), any(), any(), any(), any()) } answers { ByteArray(thirdArg()) { 7 } }
        val source = ranges.prepare(ChatFile("v", "video.mp4", "video/mp4", remotePath = "/video.mp4"), "mac")
        ranges.read(source, 0); ranges.read(source, 2L * block)
        clearMocks(rest, answers = false, recordedCalls = true)
        batch.append(descriptor, target, "mac", "owner", "/video.mp4") {}
        coVerify(exactly = 0) { rest.readArtifactRangeWithProgress(any(), 0L, any(), any(), any(), any()) }
        coVerify(exactly = 0) { rest.readArtifactRangeWithProgress(any(), 2L * block, any(), any(), any(), any()) }
        coVerify(exactly = 2) { rest.readArtifactRangeWithProgress(any(), any(), any(), any(), any(), any()) }
        assertEquals(4L * block, target.length())
    }

    @Test fun owner_change_after_network_validation_stops_before_appending() = runBlocking {
        coEvery { rest.readArtifactRangeWithProgress(any(), any(), any(), any(), any(), any()) } answers {
            coEvery { rest.artifactOwnerKey(any()) } returns "other-owner"
            ByteArray(thirdArg())
        }
        try { batch.append(descriptor, target, "mac", "owner", "/video.mp4") {}; fail() }
        catch (error: HermesApiException) { assertEquals(401, error.code) }
        assertEquals(0L, target.length())
    }

    @Test fun cancellation_stops_all_ranges_without_committing_transient_progress() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val stopped = AtomicInteger()
        coEvery { rest.readArtifactRangeWithProgress(any(), any(), any(), any(), any(), any()) } coAnswers {
            entered.complete(Unit)
            try { awaitCancellation() } finally { stopped.incrementAndGet() }
        }
        val job = launch { batch.append(descriptor, target, "mac", "owner", "/video.mp4") {} }
        withTimeout(5000) { entered.await() }; job.cancelAndJoin()
        assertTrue(stopped.get() > 0); assertEquals(0L, target.length())
    }
}
