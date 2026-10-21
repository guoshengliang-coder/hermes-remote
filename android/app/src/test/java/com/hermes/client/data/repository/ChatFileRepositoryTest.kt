package com.hermes.client.data.repository

import android.content.Context
import com.hermes.client.data.network.HermesApiException
import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.domain.ARTIFACT_DOWNLOAD_LIMIT_BYTES
import com.hermes.client.domain.ChatFile
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
}
