package com.hermes.client.data.video

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.data.network.HermesApiException
import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.domain.ArtifactDownloadDescriptor
import com.hermes.client.domain.ChatFile
import io.mockk.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArtifactRangeRepositoryTest {
    private lateinit var rest: HermesRestApi
    private lateinit var repository: ArtifactRangeRepository
    private val descriptor = ArtifactDownloadDescriptor("a".repeat(64), 3L * ArtifactRangeCache.BLOCK_BYTES,
        "\"${"b".repeat(64)}\"", "video/mp4", 8L * 1024 * 1024 * 1024)
    private val file = ChatFile("v", "video.mp4", "video/mp4", remotePath = "/video.mp4")
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.cacheDir, "video-ranges").deleteRecursively()
        rest = mockk()
        coEvery { rest.artifactOwnerKey(any()) } returns "owner"
        coEvery { rest.prepareArtifactDownload(any(), any(), any()) } returns descriptor
        coEvery { rest.readArtifactRange(any(), any(), any(), any(), any()) } answers { ByteArray(thirdArg()) { 5 } }
        repository = ArtifactRangeRepository(context, rest)
    }

    @Test fun negotiated_slots_allow_parallel_reads_and_legacy_remains_serial() = runBlocking {
        withTimeout(5_000) {
            for (parallel in listOf(1, 2, 4)) {
                val desc = descriptor.copy(sizeBytes = 8L * ArtifactRangeCache.BLOCK_BYTES,
                    maxConcurrentRanges = parallel)
                coEvery { rest.prepareArtifactDownload(any(), any(), any()) } returns desc
                val source = repository.prepare(file, "mac")
                val entered = Channel<Unit>(4); val release = CompletableDeferred<Unit>()
                coEvery { rest.readArtifactRange(any(), any(), any(), any(), any()) } coAnswers {
                    entered.send(Unit); release.await(); ByteArray(thirdArg())
                }
                // Different paths keep this iteration independent of earlier cache hits.
                val isolated = ArtifactRangeRepository.Source("owner", "mac", "/$parallel.mp4", desc)
                val reads = (0..3).map { async { repository.read(isolated, it.toLong() * ArtifactRangeCache.BLOCK_BYTES) } }
                repeat(parallel) { entered.receive() }
                yield(); assertTrue(entered.tryReceive().isFailure)
                release.complete(Unit); reads.awaitAll()
                assertEquals(source.parallel, parallel)
            }
        }
    }

    @Test fun grouped_reads_skip_cached_blocks_and_publish_reusable_individual_blocks() = runBlocking {
        val block = ArtifactRangeCache.BLOCK_BYTES
        val desc = descriptor.copy(sizeBytes = 4L * block, maxRangeBytes = 4 * block)
        coEvery { rest.prepareArtifactDownload(any(), any(), any()) } returns desc
        val source = repository.prepare(file, "mac")
        repository.read(source, block.toLong())
        clearMocks(rest, answers = false)
        assertEquals(4 * block, repository.read(source, 0, 4).size)
        coVerify(exactly = 1) { rest.readArtifactRange(desc, 0, block, "mac", "owner") }
        coVerify(exactly = 1) { rest.readArtifactRange(desc, 2L * block, 2 * block, "mac", "owner") }
        coVerify(exactly = 0) { rest.readArtifactRange(desc, block.toLong(), any(), any(), any()) }
        repeat(4) { assertNotNull(repository.cached("owner", "/video.mp4", desc, it.toLong() * block)) }
    }

    @Test fun concurrent_expiry_shares_one_renewal_after_old_reference_reads_drain() = runBlocking {
        withTimeout(5_000) {
            val desc = descriptor.copy(maxConcurrentRanges = 2)
            coEvery { rest.prepareArtifactDownload(any(), any(), any()) } returns desc
            val source = repository.prepare(file, "mac")
            val entered = Channel<Unit>(2); val release = CompletableDeferred<Unit>()
            coEvery { rest.readArtifactRange(desc, any(), any(), any(), any()) } coAnswers {
                entered.send(Unit); release.await()
                throw HermesApiException(410, "expired", errorCode = "HR-FILE-015")
            }
            val renewed = desc.copy(artifactId = "c".repeat(64))
            coEvery { rest.prepareArtifactDownload(any(), any(), any()) } returns renewed
            val reads = listOf(0L, ArtifactRangeCache.BLOCK_BYTES.toLong()).map { async { repository.read(source, it) } }
            repeat(2) { entered.receive() }
            coVerify(exactly = 1) { rest.prepareArtifactDownload(any(), any(), any()) }
            release.complete(Unit); reads.awaitAll()
            coVerify(exactly = 2) { rest.prepareArtifactDownload(any(), any(), any()) }
            coVerify(exactly = 2) { rest.readArtifactRange(renewed, any(), any(), any(), any()) }
        }
    }

    @Test fun owner_change_after_network_return_does_not_publish_any_grouped_block() = runBlocking {
        val block = ArtifactRangeCache.BLOCK_BYTES
        val desc = descriptor.copy(maxRangeBytes = 4 * block)
        coEvery { rest.prepareArtifactDownload(any(), any(), any()) } returns desc
        val source = repository.prepare(file, "mac")
        coEvery { rest.readArtifactRange(any(), any(), any(), any(), any()) } coAnswers {
            coEvery { rest.artifactOwnerKey(any()) } returns "other"
            ByteArray(thirdArg())
        }
        try { repository.read(source, 0, 2); fail() } catch (error: HermesApiException) { assertEquals(401, error.code) }
        assertNull(repository.cached("owner", "/video.mp4", desc, 0))
        assertNull(repository.cached("owner", "/video.mp4", desc, block.toLong()))
    }

    @Test fun tail_seek_is_bounded_and_cache_is_reused_after_opaque_reference_renewal() = runBlocking {
        val source = repository.prepare(file, "mac")
        val tail = 2L * ArtifactRangeCache.BLOCK_BYTES
        repository.read(source, tail + 80)
        coEvery { rest.prepareArtifactDownload(any(), any(), any()) } returns descriptor.copy(artifactId = "c".repeat(64))
        repository.read(repository.prepare(file, "mac"), tail + 100)
        coVerify(exactly = 1) { rest.readArtifactRange(any(), tail, ArtifactRangeCache.BLOCK_BYTES, "mac", "owner") }
        coVerify(exactly = 0) { rest.readArtifactRange(any(), 0, any(), any(), any()) }
        assertNotNull(repository.cached("owner", "/video.mp4", descriptor, tail))
    }

    @Test fun reference_expiry_renews_only_the_same_generation() = runBlocking {
        val source = repository.prepare(file, "mac")
        coEvery { rest.readArtifactRange(descriptor, any(), any(), any(), any()) } throws HermesApiException(410, "expired", errorCode = "HR-FILE-015")
        val renewed = descriptor.copy(artifactId = "c".repeat(64))
        coEvery { rest.prepareArtifactDownload(any(), any(), any()) } returns renewed
        assertEquals(ArtifactRangeCache.BLOCK_BYTES, repository.read(source, 0).size)
        coVerify(exactly = 1) { rest.readArtifactRange(renewed, 0, ArtifactRangeCache.BLOCK_BYTES, "mac", "owner") }
    }

    @Test fun changed_generation_and_account_never_reuse_cached_bytes() = runBlocking {
        val source = repository.prepare(file, "mac")
        repository.read(source, 0)
        coEvery { rest.artifactOwnerKey(any()) } returns "other"
        try { repository.read(source, 0); fail() } catch (error: HermesApiException) { assertEquals(401, error.code) }
        coEvery { rest.artifactOwnerKey(any()) } returns "owner"
        coEvery { rest.readArtifactRange(any(), any(), any(), any(), any()) } throws HermesApiException(410, "expired", errorCode = "HR-FILE-015")
        coEvery { rest.prepareArtifactDownload(any(), any(), any()) } returns descriptor.copy(etag = "\"${"c".repeat(64)}\"")
        try { repository.read(source, ArtifactRangeCache.BLOCK_BYTES.toLong()); fail() }
        catch (error: HermesApiException) { assertEquals("HR-FILE-011", error.errorCode) }
    }
}
