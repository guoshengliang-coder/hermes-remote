package com.hermes.client.ui.screenshot

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermes.client.domain.ChatImage
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.ui.InChinese
import com.hermes.client.ui.chat.ImageSource
import com.hermes.client.ui.chat.ImageViewerChrome
import com.hermes.client.ui.chat.ImageViewerContent
import com.hermes.client.ui.chat.ImageViewerItem
import com.hermes.client.ui.chat.UserBubble
import com.hermes.client.ui.chat.imageedit.ImageEditorContent
import com.hermes.client.ui.theme.HermesTheme
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Goldens for HG-35: thumbnails, the viewer and the editor.
 *
 * Every fixture image is generated here rather than bundled, so no golden depends on a checked-in
 * photo. Waiting is gated on the decoded image appearing rather than on a fixed sleep — decoding
 * runs on a real IO thread under Robolectric, and a timed wait is a flake waiting to happen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class ChatImageScreenshotTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f),
    )

    /** A recognisable gradient with a grid, so crops and rotations are visible in a golden. */
    private fun fixture(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint()
        for (y in 0 until height step 8) {
            paint.color = Color.rgb(40 + (y * 180 / height), 90, 200 - (y * 150 / height))
            canvas.drawRect(0f, y.toFloat(), width.toFloat(), (y + 8).toFloat(), paint)
        }
        paint.color = Color.argb(90, 255, 255, 255)
        paint.strokeWidth = 2f
        for (x in 0 until width step width / 6) canvas.drawLine(x.toFloat(), 0f, x.toFloat(), height.toFloat(), paint)
        for (y in 0 until height step height / 6) canvas.drawLine(0f, y.toFloat(), width.toFloat(), y.toFloat(), paint)
        return bitmap
    }

    private fun fixtureBytes(width: Int, height: Int): ByteArray {
        val bitmap = fixture(width, height)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun fixtureFile(name: String, width: Int, height: Int): String {
        val file = File(temporaryFolder.root, name)
        file.writeBytes(fixtureBytes(width, height))
        return file.absolutePath
    }

    private fun chatImage(id: String, width: Int, height: Int) = ChatImage(
        id = id,
        mimeType = "image/png",
        localPath = fixtureFile("$id.png", width, height),
        width = width,
        height = height,
    )

    private fun snap(
        name: String,
        darkTheme: Boolean = false,
        fontScale: Float? = null,
        expectImages: Int = 0,
        awaitDescription: String? = null,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            InChinese {
                HermesTheme(darkTheme = darkTheme) {
                    val body = @Composable { Surface(Modifier.fillMaxWidth()) { content() } }
                    if (fontScale == null) {
                        body()
                    } else {
                        val base = LocalDensity.current
                        CompositionLocalProvider(
                            LocalDensity provides Density(base.density, fontScale),
                        ) { body() }
                    }
                }
            }
        }
        if (expectImages > 0) {
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasContentDescription("聊天图片")).fetchSemanticsNodes().size == expectImages
            }
        }
        if (awaitDescription != null) {
            // The working bitmap decodes on a real IO thread; without this the golden captures the
            // loading spinner, which is the same picture in every theme.
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasContentDescription(awaitDescription)).fetchSemanticsNodes().isNotEmpty()
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/$name.png", roborazziOptions = options)
    }

    private fun bubble(images: List<ChatImage>): @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp)) {
            UserBubble(
                msg = ChatMessage(id = "m1", role = Role.USER, text = "这张图哪里不对？", images = images),
                onEditResend = {},
                onOpenImage = { _, _ -> },
                onFileOpen = {},
                onFileShare = {},
            )
        }
    }

    /** The money shot for HG-35 part 3: a tall screenshot, whole, with the bubble narrowed to it. */
    @Test fun chatImageSingleTall() =
        snap("chat-image-single-tall", expectImages = 1, content = bubble(listOf(chatImage("tall", 914, 2048))))

    @Test fun chatImageSingleTallDark() =
        snap("chat-image-single-tall-dark", darkTheme = true, expectImages = 1, content = bubble(listOf(chatImage("tall", 914, 2048))))

    /** The one case that mats: the height floor exists so the row stays tappable. */
    @Test fun chatImageSingleWide() =
        snap("chat-image-single-wide", expectImages = 1, content = bubble(listOf(chatImage("wide", 4000, 400))))

    /** Multi-image grids deliberately stay cropped squares — pinning the trade, not just the code. */
    @Test fun chatImageGridFour() = snap(
        "chat-image-grid-4",
        expectImages = 4,
        content = bubble(
            listOf(
                chatImage("g1", 914, 2048),
                chatImage("g2", 1600, 1200),
                chatImage("g3", 1000, 1000),
                chatImage("g4", 800, 1400),
            ),
        ),
    )

    @Test fun chatImageGridThreeMixed() = snap(
        "chat-image-grid-3",
        expectImages = 3,
        content = bubble(
            listOf(chatImage("h1", 1600, 900), chatImage("h2", 900, 1600), chatImage("h3", 1200, 1200)),
        ),
    )

    private fun viewerItems(count: Int) = (1..count).map {
        ImageViewerItem(
            id = "v$it",
            source = ImageSource.Path(fixtureFile("v$it.png", 1200, 1600)),
            export = chatImage("v$it", 1200, 1600),
        )
    }

    @Test fun imageViewerSentMulti() = snap("image-viewer-sent-multi", awaitDescription = "查看原图") {
        Column(Modifier.fillMaxSize().height(891.dp)) {
            ImageViewerContent(
                items = viewerItems(5),
                currentId = "v1",
                chrome = ImageViewerChrome.Sent({}, {}, {}, savingImageId = null),
                onPageChange = {},
                onDismiss = {},
            )
        }
    }

    @Test fun imageViewerPending() = snap("image-viewer-pending", awaitDescription = "查看原图") {
        Column(Modifier.fillMaxSize().height(891.dp)) {
            ImageViewerContent(
                items = listOf(
                    ImageViewerItem("p1", ImageSource.Bytes("p1", fixtureBytes(1200, 1600))),
                    ImageViewerItem("p2", ImageSource.Bytes("p2", fixtureBytes(1600, 1200))),
                    ImageViewerItem("p3", ImageSource.Bytes("p3", fixtureBytes(1000, 1000))),
                ),
                currentId = "p2",
                chrome = ImageViewerChrome.Pending(onEdit = {}, onDelete = {}),
                onPageChange = {},
                onDismiss = {},
            )
        }
    }

    /** The decode-failure line, which used to be an unexplained black screen. */
    @Test fun imageViewerDecodeFailed() = snap("image-viewer-decode-failed") {
        Column(Modifier.fillMaxSize().height(891.dp).background(androidx.compose.ui.graphics.Color.Black)) {
            ImageViewerContent(
                items = listOf(ImageViewerItem("broken", ImageSource.Bytes("broken", byteArrayOf(1, 2, 3, 4)))),
                currentId = "broken",
                chrome = ImageViewerChrome.Sent({}, {}, {}, savingImageId = null),
                onPageChange = {},
                onDismiss = {},
            )
        }
    }

    private fun editor(): @Composable () -> Unit = {
        Column(Modifier.fillMaxSize().height(891.dp)) {
            ImageEditorContent(fixtureBytes(1200, 900), onCancel = {}, onDone = {})
        }
    }

    @Test fun imageEditorDoodle() = snap("image-editor-doodle", awaitDescription = "涂鸦", content = editor())

    /** Dark too — which is the point: chrome on a photo must not change with the theme. */
    @Test fun imageEditorDoodleDark() =
        snap("image-editor-doodle-dark", darkTheme = true, awaitDescription = "涂鸦", content = editor())

    @Test fun imageEditorDoodleLargeFont() =
        snap("image-editor-doodle-large-font", fontScale = 1.3f, awaitDescription = "涂鸦", content = editor())
}
