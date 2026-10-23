package com.hermes.client.ui.chat

import android.graphics.Bitmap
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermes.client.domain.ChatImage
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.ui.InChinese
import com.hermes.client.ui.theme.HermesTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** HG-198: the displayed assistant's thumbnail must resolve into a non-empty viewer. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class MergedAnswerImageViewerTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val folder = TemporaryFolder()

    private fun image(id: String, color: Int): ChatImage {
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        val file = File(folder.root, "$id.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return ChatImage(id = id, localPath = file.absolutePath, width = 640, height = 480)
    }

    private fun exercise(dark: Boolean) {
        val first = image("first", android.graphics.Color.rgb(45, 95, 140))
        val second = image("second", android.graphics.Color.rgb(160, 100, 45))
        val messages = listOf(
            ChatMessage("before-tool", Role.ASSISTANT, "正在生成图表。"),
            ChatMessage("after-tool", Role.ASSISTANT, "图表已生成。", images = listOf(first, second)),
        )
        var saved: ChatImage? = null
        compose.setContent {
            InChinese {
                HermesTheme(darkTheme = dark) {
                    var owner by remember { mutableStateOf<String?>(null) }
                    var selected by remember { mutableStateOf<String?>(null) }
                    Surface {
                        if (owner == null) {
                            AssistantTurn(
                                msg = messages.organizedConversationTurns().single(),
                                canRegenerate = false, showActions = false,
                                onRegenerate = {}, onRetryWithModel = {}, onOpenTableFullscreen = {},
                                isSpeaking = false, onReadAloud = {}, onStopReading = {},
                                onOpenImage = { id, image -> owner = id; selected = image.id },
                                onFileOpen = {}, onFileShare = {},
                            )
                        } else {
                            ImageViewerContent(
                                items = transcriptViewerItems(messages, owner!!, selected),
                                currentId = selected,
                                chrome = ImageViewerChrome.Sent({ saved = it }, {}, {}, null),
                                onPageChange = { selected = it }, onDismiss = { owner = null },
                            )
                        }
                    }
                }
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("聊天图片").fetchSemanticsNodes().size == 2
        }
        compose.onAllNodesWithContentDescription("聊天图片")[1].performClick()
        compose.onNodeWithText("2 / 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("保存图片").performClick()
        assertEquals(second, saved)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/hg198_viewer_${if (dark) "dark" else "light"}.png")
        compose.onRoot().performTouchInput { swipeRight() }
        compose.onNodeWithText("1 / 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭").performClick()
        // Closing recreates the thumbnails; their IO decodes are not Compose idle work.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("聊天图片").fetchSemanticsNodes().size == 2
        }
        compose.onAllNodesWithContentDescription("聊天图片")[0].assertIsDisplayed()
    }

    @Test fun lightThemeThumbnailOpensSelectedPageAndCloses() = exercise(false)
    @Test fun appDarkWithSystemLightThumbnailOpensSelectedPageAndCloses() = exercise(true)
}
