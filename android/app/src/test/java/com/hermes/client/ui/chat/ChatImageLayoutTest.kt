package com.hermes.client.ui.chat

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.hermes.client.domain.ChatImage
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.ui.InChinese
import com.hermes.client.ui.theme.HermesTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.junit.rules.TemporaryFolder

/**
 * Measured geometry for images inside a bubble.
 *
 * [ImageGridLayoutTest] pins the arithmetic; this pins that the arithmetic reaches the screen —
 * that the container really does carry the source aspect, so `Fit` neither crops nor mats.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class ChatImageLayoutTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun pngFile(name: String, width: Int, height: Int): String {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val file = File(temporaryFolder.root, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file.absolutePath
    }

    private fun image(id: String, width: Int, height: Int) = ChatImage(
        id = id,
        mimeType = "image/png",
        localPath = pngFile("$id.png", width, height),
        width = width,
        height = height,
    )

    private fun showBubble(images: List<ChatImage>, onOpen: (String, ChatImage) -> Unit = { _, _ -> }) {
        compose.setContent {
            InChinese {
                HermesTheme(darkTheme = false) {
                    Column(Modifier.fillMaxWidth()) {
                        UserBubble(
                            msg = ChatMessage(id = "m1", role = Role.USER, text = "", images = images),
                            onEditResend = {},
                            onOpenImage = onOpen,
                            onFileOpen = {},
                            onFileShare = {},
                        )
                    }
                }
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasContentDescription("聊天图片")).fetchSemanticsNodes().size == images.size
        }
    }

    /** Measured size of one thumbnail, in dp. */
    private fun thumbnailSize(index: Int = 0): Pair<Float, Float> {
        val bounds = compose.onAllNodes(hasContentDescription("聊天图片"))[index].getBoundsInRoot()
        return (bounds.right - bounds.left).value to (bounds.bottom - bounds.top).value
    }

    /** HG-35's own attachment: shown whole, and the bubble narrows around it. */
    @Test
    fun aTallScreenshotKeepsItsAspectAndIsNotCropped() {
        showBubble(listOf(image("tall", 914, 2048)))

        val (width, height) = thumbnailSize()
        val want = 2048f / 914f
        assertEquals("aspect", want, height / width, want * 0.02f)
        assertEquals("capped height", 320f, height, 1f)
    }

    @Test
    fun aLandscapePhotoFillsTheAvailableWidth() {
        showBubble(listOf(image("wide", 1600, 1200)))

        val (width, height) = thumbnailSize()
        val want = 1200f / 1600f
        assertEquals(want, height / width, want * 0.02f)
    }

    /** Multi-image grids stay uniform; that is the trade DESIGN.md §5.4 records. */
    @Test
    fun aFourImageGridUsesIdenticalCells() {
        showBubble(
            listOf(
                image("a", 914, 2048),
                image("b", 1600, 1200),
                image("c", 1000, 1000),
                image("d", 800, 1400),
            ),
        )

        val (firstWidth, firstHeight) = thumbnailSize(0)
        for (i in 1..3) {
            val (width, height) = thumbnailSize(i)
            assertEquals("cell $i width", firstWidth, width, 0.5f)
            assertEquals("cell $i height", firstHeight, height, 0.5f)
        }
        assertEquals(132f, firstHeight, 0.5f)
    }

    @Test
    fun tappingAThumbnailAsksToOpenTheViewerForThatMessageAndImage() {
        var opened: Pair<String, String>? = null
        showBubble(listOf(image("tall", 914, 2048))) { messageId, img -> opened = messageId to img.id }

        compose.onAllNodes(hasContentDescription("聊天图片")).onFirst().performClick()
        compose.waitForIdle()

        assertEquals("m1" to "tall", opened)
    }

    /** An image with no local file cannot be shown, so tapping it must do nothing. */
    @Test
    fun tappingAnImageWithNothingToShowDoesNothing() {
        var opened = 0
        compose.setContent {
            InChinese {
                HermesTheme(darkTheme = false) {
                    UserBubble(
                        msg = ChatMessage(
                            id = "m1",
                            role = Role.USER,
                            text = "",
                            images = listOf(ChatImage(id = "pending", remotePath = "/tmp/not-here.png")),
                        ),
                        onEditResend = {},
                        onOpenImage = { _, _ -> opened++ },
                        onFileOpen = {},
                        onFileShare = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().performClick()
        compose.waitForIdle()

        assertTrue(opened == 0)
    }
}
