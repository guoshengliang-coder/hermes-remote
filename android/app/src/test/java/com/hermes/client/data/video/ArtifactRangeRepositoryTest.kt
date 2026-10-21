package com.hermes.client.data.video

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.data.network.HermesApiException
import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.domain.ArtifactDownloadDescriptor
import com.hermes.client.domain.ChatFile
import io.mockk.*
import java.io.File
import kotlinx.coroutines.runBlocking
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
