package com.hermes.client.data.repository

import android.content.Context
import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.domain.ChatImage
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatMediaRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun repository(): ChatMediaRepository {
        val context = mockk<Context>()
        every { context.cacheDir } returns temporaryFolder.root
        return ChatMediaRepository(context, mockk<HermesRestApi>())
    }

    @Test fun generated_image_keeps_original_chinese_filename_and_format() {
        val image = ChatImage(
            id = "image-1",
            mimeType = "image/png",
            remotePath = "/Users/bs/.hermes/cache/images/戴眼镜的猫 01.png",
        )

        assertEquals("戴眼镜的猫 01.png", repository().exportDisplayName(image, Date(0)))
        assertEquals("image/png", repository().exportMimeType(image))
    }

    @Test fun external_url_filename_is_decoded_and_preserved() {
        val image = ChatImage(
            id = "image-2",
            mimeType = "image/webp",
            sourceUrl = "https://cdn.example.com/%E7%8B%90%E7%8B%B8%20final.webp?size=large",
        )

        assertEquals("狐狸 final.webp", repository().exportDisplayName(image, Date(0)))
    }

    @Test fun missing_or_unsupported_name_uses_safe_mime_extension() {
        val image = ChatImage(id = "image-3", mimeType = "image/gif")

        val name = repository().exportDisplayName(image, Date(0))
        assertTrue(name.startsWith("Hermes_19700101_"))
        assertTrue(name.endsWith(".gif"))
    }

    @Test fun only_files_from_the_managed_image_cache_can_be_exported() {
        val outside = temporaryFolder.newFile("outside.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val image = ChatImage(id = "image-4", mimeType = "image/png", localPath = outside.absolutePath)

        assertThrows(IllegalStateException::class.java) { repository().requireLocalImage(image) }
    }

    @Test fun hydrated_cache_file_is_accepted_for_export() {
        val cache = File(temporaryFolder.root, "chat-images").apply { mkdirs() }
        val source = File(cache, "ready.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val image = ChatImage(id = "image-5", mimeType = "image/png", localPath = source.absolutePath)

        assertEquals(source.canonicalFile, repository().requireLocalImage(image))
    }
}
