package com.hermes.client.data.repository

import android.content.Context
import com.hermes.client.data.network.HermesApiException
import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.domain.ARTIFACT_DOWNLOAD_LIMIT_BYTES
import com.hermes.client.domain.ChatFile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.confirmVerified
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatFileRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun known_oversize_is_rejected_without_starting_a_network_request() = runTest {
        val context = mockk<Context>()
        every { context.cacheDir } returns temporary.root
        val rest = mockk<HermesRestApi>()
        val repository = ChatFileRepository(context, rest)
        try {
            repository.download(ChatFile("a", "large.mp4", sizeBytes = ARTIFACT_DOWNLOAD_LIMIT_BYTES + 1, remotePath = "/tmp/large.mp4"))
            fail("oversized files must be rejected before downloading")
        } catch (error: HermesApiException) { assertEquals(413, error.code) }
        confirmVerified(rest)
        assertTrue(temporary.root.resolve("chat-files").listFiles().orEmpty().isEmpty())
    }

    private fun repositoryWith(rest: HermesRestApi): ChatFileRepository {
        val context = mockk<Context>()
        every { context.cacheDir } returns temporary.root
        return ChatFileRepository(context, rest)
    }

    @Test fun sent_file_copy_lands_in_the_deterministic_slot_and_opens_without_the_mac() = runTest {
        val rest = mockk<HermesRestApi>()
        val repository = repositoryWith(rest)
        val staged = temporary.newFile("upload-1.pending").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val remote = "abc123-report.pdf"

        val adopted = repository.adoptSent(staged, remote, "协议书 report.pdf", "device-7")

        assertNotNull(adopted)
        assertFalse(staged.exists())
        val cached = java.io.File(adopted!!)
        assertTrue(cached.isFile)
        assertEquals(cached, repository.cachedCopy(ChatFile("a", "协议书 report.pdf", remotePath = remote), "device-7"))
        // A conversation rebuilt from history has no localPath — the slot itself must serve.
        val rebuilt = ChatFile("a", "协议书 report.pdf", remotePath = remote)
        assertEquals(cached, repository.download(rebuilt, "device-7"))
        confirmVerified(rest)
    }

    @Test fun adopt_sent_without_a_staged_file_returns_null_and_keeps_download_working() = runTest {
        val rest = mockk<HermesRestApi>()
        val repository = repositoryWith(rest)
        val remote = "/uploads/x.bin"
        assertNull(repository.adoptSent(null, remote, "x.bin", "device-7"))
        assertNull(repository.cachedCopy(ChatFile("a", "x.bin", remotePath = remote), "device-7"))
        coEvery { rest.downloadArtifact(remote, any(), any(), any(), any(), any()) } answers {
            arg<java.io.File>(1).also { it.writeBytes(byteArrayOf(9)) }
        }
        val downloaded = repository.download(ChatFile("a", "x.bin", remotePath = remote), "device-7")
        assertEquals(1L, downloaded.length())
        assertEquals(downloaded, repository.cachedCopy(ChatFile("a", "x.bin", remotePath = remote), "device-7"))
        coVerify { rest.downloadArtifact(remote, any(), any(), any(), any(), any()) }
        confirmVerified(rest)
    }

    @Test fun adopt_sent_into_an_occupied_slot_reuses_it_and_drops_the_new_copy() = runTest {
        val rest = mockk<HermesRestApi>()
        val repository = repositoryWith(rest)
        val staged = temporary.newFile("upload-2.pending").apply { writeBytes(byteArrayOf(5)) }
        val remote = "dup.bin"
        val firstPath = repository.adoptSent(staged, remote, "dup.bin", "device-7")
        assertNotNull(firstPath)
        val second = temporary.newFile("upload-3.pending").apply { writeBytes(byteArrayOf(6)) }
        // Same (device, remote, name) → same deterministic slot, already occupied by a real file.
        val secondPath = repository.adoptSent(second, remote, "dup.bin", "device-7")
        assertEquals(firstPath, secondPath)
        assertFalse(second.exists())
        assertTrue(java.io.File(firstPath!!).isFile)
        confirmVerified(rest)
    }
}
