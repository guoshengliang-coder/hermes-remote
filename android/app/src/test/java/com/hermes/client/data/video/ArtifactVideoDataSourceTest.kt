package com.hermes.client.data.video

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import com.hermes.client.domain.ArtifactDownloadDescriptor
import io.mockk.*
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ArtifactVideoDataSourceTest {
    private val repository = mockk<ArtifactRangeRepository>()
    private val size = ArtifactRangeCache.BLOCK_BYTES + 8L
    private val source = ArtifactRangeRepository.Source("owner", "mac", "/video.mp4",
        ArtifactDownloadDescriptor("a".repeat(64), size, "etag", "video/mp4", size))
    private val uri = Uri.parse("hermes-video://attachment/video")

    @Test fun sequential_playback_prefetches_one_block_and_close_cancels_speculation() = runBlocking {
        val block = ArtifactRangeCache.BLOCK_BYTES
        val streaming = ArtifactRangeRepository.Source("owner", "mac", "/video.mp4",
            ArtifactDownloadDescriptor("a".repeat(64), 3L * block, "etag", "video/mp4", 3L * block))
        coEvery { repository.checkOwner(any(), any()) } returns Unit
        val prefetch = CompletableDeferred<Unit>(); val stopped = CompletableDeferred<Unit>()
        coEvery { repository.read(streaming, 0) } returns ByteArray(block) { 7 }
        coEvery { repository.read(streaming, block.toLong()) } returns ByteArray(block) { 8 }
        coEvery { repository.read(streaming, 2L * block) } coAnswers {
            prefetch.complete(Unit)
            try { awaitCancellation() } finally { stopped.complete(Unit) }
        }
        val data = ArtifactVideoDataSource(repository, streaming)
        data.open(DataSpec.Builder().setUri(uri).build())
        assertEquals(block, data.read(ByteArray(block), 0, block))
        coVerify(exactly = 0) { repository.read(streaming, block.toLong()) }
        assertEquals(1, data.read(ByteArray(1), 0, 1))
        withTimeout(5000) { prefetch.await() }
        data.close(); withTimeout(5000) { stopped.await() }
    }

    @Test fun measured_fast_source_overlaps_the_second_request_with_future_reads() = runBlocking {
        val block = ArtifactRangeCache.BLOCK_BYTES
        val streaming = ArtifactRangeRepository.Source("owner", "mac", "/fast.mp4",
            source.descriptor.copy(sizeBytes = 4L * block, maxConcurrentRanges = 4))
        coEvery { repository.checkOwner(any(), any()) } returns Unit
        coEvery { repository.read(streaming, 0) } answers {
            streaming.permitsEarlyRefill = true
            ByteArray(block)
        }
        val current = CompletableDeferred<Unit>()
        val future = CompletableDeferred<Unit>()
        coEvery { repository.read(streaming, block.toLong()) } coAnswers { current.await(); ByteArray(block) }
        coEvery { repository.read(streaming, match { it >= 2L * block }, 1) } coAnswers {
            future.complete(Unit); awaitCancellation()
        }
        val data = ArtifactVideoDataSource(repository, streaming)
        try {
            data.open(DataSpec.Builder().setUri(uri).build())
            data.read(ByteArray(block), 0, block)
            val foreground = CompletableFuture.supplyAsync {
                try { data.read(ByteArray(1), 0, 1); false } catch (_: IOException) { true }
            }
            withTimeout(5_000) { future.await() }
            assertFalse(foreground.isDone)
            data.close()
            assertTrue(foreground.get(3, TimeUnit.SECONDS))
        } finally { data.close(); current.complete(Unit) }
    }

    @Test fun a_cached_prefetch_is_consumed_once_without_another_network_read() {
        val block = ArtifactRangeCache.BLOCK_BYTES
        val streaming = ArtifactRangeRepository.Source("owner", "mac", "/video.mp4",
            ArtifactDownloadDescriptor("a".repeat(64), 2L * block + 8, "etag", "video/mp4", 3L * block))
        coEvery { repository.checkOwner(any(), any()) } returns Unit
        coEvery { repository.read(streaming, 0) } returns ByteArray(block) { 7 }
        coEvery { repository.read(streaming, block.toLong()) } returns ByteArray(block) { 8 }
        coEvery { repository.read(streaming, 2L * block) } returns ByteArray(8) { 9 }
        val data = ArtifactVideoDataSource(repository, streaming)
        data.open(DataSpec.Builder().setUri(uri).build())
        repeat(2) { assertEquals(block, data.read(ByteArray(block), 0, block)) }
        val tail = ByteArray(8); assertEquals(8, data.read(tail, 0, 8)); assertEquals(9.toByte(), tail[0])
        coVerify(exactly = 1) { repository.read(streaming, 2L * block) }
        data.close()
    }

    @Test fun reopen_cancels_the_old_window_and_first_seek_read_stays_small() = runBlocking {
        val block = ArtifactRangeCache.BLOCK_BYTES
        val desc = source.descriptor.copy(sizeBytes = 30L * block, maxConcurrentRanges = 4, maxRangeBytes = 4 * block)
        val streaming = ArtifactRangeRepository.Source("owner", "mac", "/seek.mp4", desc)
        coEvery { repository.checkOwner(any(), any()) } returns Unit
        coEvery { repository.read(streaming, 0) } returns ByteArray(block)
        coEvery { repository.read(streaming, block.toLong()) } returns ByteArray(block)
        val started = kotlinx.coroutines.channels.Channel<Unit>(2)
        val stopped = kotlinx.coroutines.channels.Channel<Unit>(2)
        coEvery { repository.read(streaming, match { it in 2L * block..3L * block }, 1) } coAnswers {
            started.send(Unit)
            try { awaitCancellation() } finally { stopped.trySend(Unit) }
        }
        coEvery { repository.read(streaming, 20L * block) } returns ByteArray(block) { 20 }
        val data = ArtifactVideoDataSource(repository, streaming)
        data.open(DataSpec.Builder().setUri(uri).build())
        repeat(2) { data.read(ByteArray(block), 0, block) }
        withTimeout(5_000) { repeat(2) { started.receive() } }
        data.close()
        withTimeout(5_000) { repeat(2) { stopped.receive() } }
        data.open(DataSpec.Builder().setUri(uri).setPosition(20L * block).build())
        val buffer = ByteArray(1); data.read(buffer, 0, 1)
        assertEquals(20.toByte(), buffer[0])
        coVerify(exactly = 1) { repository.read(streaming, 20L * block, 1) }
        coVerify(exactly = 0) { repository.read(streaming, match { it > 20L * block }, any()) }
        data.close()
    }

    @Test fun unknown_length_seek_and_eof_respect_the_requested_window() {
        coEvery { repository.checkOwner(any(), any()) } returns Unit
        coEvery { repository.read(source, any()) } answers { ByteArray(8) { (it + 1).toByte() } }
        val data = ArtifactVideoDataSource(repository, source)
        assertEquals(8L, data.open(DataSpec.Builder().setUri(uri).setPosition(size - 8).build()))
        val buffer = ByteArray(16)
        assertEquals(4, data.read(buffer, 2, 4)); assertEquals(1.toByte(), buffer[2])
        assertEquals(4, data.read(buffer, 0, 16)); assertEquals(5.toByte(), buffer[0])
        assertEquals(C.RESULT_END_OF_INPUT, data.read(buffer, 0, 16))
        coVerify(exactly = 1) { repository.read(source, size - 8) }
        data.close()
        assertEquals(0L, data.open(DataSpec.Builder().setUri(uri).setPosition(size).build()))
        assertEquals(C.RESULT_END_OF_INPUT, data.read(buffer, 0, 1)); data.close()
        assertEquals(2L, data.open(DataSpec.Builder().setUri(uri).setPosition(size - 8).setLength(2).build()))
        assertEquals(2, data.read(buffer, 0, 16)); assertEquals(C.RESULT_END_OF_INPUT, data.read(buffer, 0, 1)); data.close()
    }

    @Test fun close_cancels_an_inflight_loader_read_promptly() = runBlocking {
        coEvery { repository.checkOwner(any(), any()) } returns Unit
        val started = CompletableDeferred<Unit>()
        coEvery { repository.read(source, any()) } coAnswers { started.complete(Unit); awaitCancellation() }
        val data = ArtifactVideoDataSource(repository, source)
        data.open(DataSpec.Builder().setUri(uri).build())
        val result = CompletableFuture.supplyAsync {
            try { data.read(ByteArray(1), 0, 1); false } catch (_: IOException) { true }
        }
        withTimeout(5_000) { started.await() }; data.close()
        assertTrue(result.get(3, TimeUnit.SECONDS))
    }
}
