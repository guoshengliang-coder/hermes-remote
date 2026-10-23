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
