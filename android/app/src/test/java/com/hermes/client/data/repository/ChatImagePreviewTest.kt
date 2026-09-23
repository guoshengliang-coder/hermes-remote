package com.hermes.client.data.repository

import android.content.Context
import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.domain.ChatImage
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * HG-115: a bubble shows a downscaled preview, and everything that owes the user the real picture
 * fetches it.
 *
 * Measured 2026-09-23 over this repository's own Hermes: the 103 images still on disk weigh
 * 87.9 MB, and one conversation of five screenshots was 6.93 MB against 2,310 bytes of text. At
 * 1080px the same sample comes back 11.9x smaller. The invariant that makes it safe is one line —
 * `originalPath != null` means `localPath` is a preview — and these tests are about the places
 * that would otherwise quietly hand a preview to the user as the original.
 */
class ChatImagePreviewTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val rest = mockk<HermesRestApi>(relaxed = true)

    private fun repository(): ChatMediaRepository {
        val context = mockk<Context>()
        every { context.cacheDir } returns temporaryFolder.root
        return ChatMediaRepository(context, rest)
    }

    private fun remoteImage(id: String = "i-1") =
        ChatImage(id = id, mimeType = "image/png", remotePath = "/Users/bs/Pictures/shot.png")

    private fun message(image: ChatImage) = ChatMessage("m-1", Role.ASSISTANT, "", images = listOf(image))

    /** The mocked download has to leave bytes behind, exactly as the real one does. */
    private fun answerWithBytes(size: Int = 64) {
        coEvery { rest.downloadArtifact(any(), any(), any(), any(), any()) } answers {
            val destination = secondArg<File>()
            destination.parentFile?.mkdirs()
            destination.writeBytes(ByteArray(size) { 7 })
            destination
        }
    }

    @Test fun a_bubble_asks_for_a_preview_and_remembers_where_the_original_belongs() = runTest {
        answerWithBytes()

        val hydrated = repository().hydrateMessages(listOf(message(remoteImage())), "personal")

        coVerify(exactly = 1) { rest.downloadArtifact(any(), any(), any(), 1080, any()) }
        val image = hydrated.single().images.single()
        assertNotNull("the bubble has bytes to draw", image.localPath)
        assertNotNull("and knows where the full-size copy belongs", image.originalPath)
        // The two must be different files, or "fetch the original" would find the preview waiting.
        assertTrue(image.localPath != image.originalPath)
    }

    /**
     * An install that cached full-size images before this change must not re-fetch every one of
     * them as a preview — the whole point is fewer bytes, and it already has the better answer.
     */
    @Test fun an_original_already_in_the_cache_is_used_instead_of_downloading_a_preview() = runTest {
        val repo = repository()
        // Hydrate once to learn where the original's cache file would be, then put one there.
        answerWithBytes()
        val target = File(repo.hydrateMessages(listOf(message(remoteImage())), "personal").single().images.single().originalPath!!)
        target.writeBytes(ByteArray(4096) { 3 })
        io.mockk.clearMocks(rest, answers = false)

        val image = repo.hydrateMessages(listOf(message(remoteImage())), "personal").single().images.single()

        coVerify(exactly = 0) { rest.downloadArtifact(any(), any(), any(), any(), any()) }
        assertEquals(target.absolutePath, image.localPath)
        assertNull("nothing left to fetch, so nothing claims the bubble is a preview", image.originalPath)
    }

    @Test fun opening_the_image_fetches_the_full_size_copy_and_clears_the_marker() = runTest {
        answerWithBytes()
        val repo = repository()
        val preview = repo.hydrateMessages(listOf(message(remoteImage())), "personal").single().images.single()

        val full = repo.original(preview)

        // The second request must carry no width at all — a snapped width would come back small.
        coVerify(exactly = 1) { rest.downloadArtifact(any(), any(), any(), null, any()) }
        assertEquals(preview.originalPath, full.localPath)
        assertNull(full.originalPath)
    }

    @Test fun a_second_open_reuses_the_cached_original() = runTest {
        answerWithBytes()
        val repo = repository()
        val preview = repo.hydrateMessages(listOf(message(remoteImage())), "personal").single().images.single()
        repo.original(preview)
        io.mockk.clearMocks(rest, answers = false)

        val full = repo.original(preview)

        coVerify(exactly = 0) { rest.downloadArtifact(any(), any(), any(), any(), any()) }
        assertNull(full.originalPath)
    }

    /**
     * The viewer prefers a soft picture to no picture, so [ChatMediaRepository.original] swallows a
     * failure. An export must not: saving or sharing a downscaled copy under the original's name
     * hands the user a file that is not what they asked for, and nothing tells them.
     */
    @Test fun an_export_refuses_to_fall_back_to_the_preview() = runTest {
        answerWithBytes()
        val repo = repository()
        val preview = repo.hydrateMessages(listOf(message(remoteImage())), "personal").single().images.single()
        coEvery { rest.downloadArtifact(any(), any(), any(), null, any()) } throws java.io.IOException("offline")

        assertEquals(preview, repo.original(preview))
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { repo.requiredOriginal(preview) }
        }
    }

    @Test fun an_image_that_is_already_the_original_is_passed_straight_through() = runTest {
        val repo = repository()
        val local = ChatImage(id = "i-2", mimeType = "image/png", localPath = "/tmp/x.png")

        assertEquals(local, repo.original(local))
        assertEquals(local, repo.requiredOriginal(local))
        coVerify(exactly = 0) { rest.downloadArtifact(any(), any(), any(), any(), any()) }
    }
}
