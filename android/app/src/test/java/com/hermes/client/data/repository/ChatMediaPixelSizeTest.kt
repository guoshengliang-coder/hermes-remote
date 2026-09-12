package com.hermes.client.data.repository

import android.content.Context
import android.graphics.Bitmap
import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.domain.ChatImage
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The renderer sizes a thumbnail's container from [ChatImage.width]/[ChatImage.height]. If those
 * arrive later than the first frame, the bubble resizes under the user inside a reverse-layout list.
 * So they are filled where the bytes are already in hand — here.
 *
 * Needs real graphics: the sibling [ChatMediaRepositoryTest] runs on plain JVM stubs where
 * `BitmapFactory` decodes nothing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ChatMediaPixelSizeTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun repository(): ChatMediaRepository {
        val context = mockk<Context>()
        every { context.cacheDir } returns temporaryFolder.root
        return ChatMediaRepository(context, mockk<HermesRestApi>())
    }

    private fun pngBytes(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    @Test
    fun cachingAnOutgoingImageRecordsItsPixelSize() = runTest {
        val image = repository().cacheOutgoing("att-1", pngBytes(914, 2048), "image/png")

        assertEquals(914, image.width)
        assertEquals(2048, image.height)
    }

    @Test
    fun anAlreadyCachedImageIsMeasuredOnHydrate() = runTest {
        val cache = File(temporaryFolder.root, "chat-images").apply { mkdirs() }
        val file = File(cache, "restored.png").apply { writeBytes(pngBytes(1891, 2048)) }
        val message = ChatMessage(
            id = "m1",
            role = Role.USER,
            text = "",
            images = listOf(ChatImage(id = "i1", mimeType = "image/png", localPath = file.absolutePath)),
        )

        val hydrated = repository().hydrateMessages(listOf(message), profile = null).single()

        assertEquals(1891, hydrated.images.single().width)
        assertEquals(2048, hydrated.images.single().height)
    }

    /** Upstream sometimes supplies dimensions; measuring must not overwrite what we were told. */
    @Test
    fun knownDimensionsAreLeftAlone() = runTest {
        val cache = File(temporaryFolder.root, "chat-images").apply { mkdirs() }
        val file = File(cache, "known.png").apply { writeBytes(pngBytes(40, 20)) }
        val message = ChatMessage(
            id = "m1",
            role = Role.USER,
            text = "",
            images = listOf(
                ChatImage(
                    id = "i1",
                    mimeType = "image/png",
                    localPath = file.absolutePath,
                    width = 4000,
                    height = 2000,
                ),
            ),
        )

        val hydrated = repository().hydrateMessages(listOf(message), profile = null).single()

        assertEquals(4000, hydrated.images.single().width)
    }

    /** A corrupt file must degrade to "unknown size", not throw on a path that only writes bytes. */
    @Test
    fun anUndecodableFileLeavesTheSizeUnknown() = runTest {
        val image = repository().cacheOutgoing("att-2", byteArrayOf(1, 2, 3, 4), "image/png")

        assertNull(image.width)
        assertNull(image.height)
    }
}
