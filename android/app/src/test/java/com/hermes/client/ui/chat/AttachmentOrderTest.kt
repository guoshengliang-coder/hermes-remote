package com.hermes.client.ui.chat

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.hermes.client.domain.ChatFile
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Attachments render BELOW the assistant's prose (docs/DESIGN.md §5.4, decision 2026-09-05).
 *
 * Regression: the file card used to render above the body. On a long report the card scrolled out
 * of view, so the user read to the end, saw only the path the prose mentioned, and concluded the
 * file had never been delivered — which is exactly what happened on 2026-09-05.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp-420dpi")
class AttachmentOrderTest {
    @get:Rule
    val compose = createComposeRule()

    @Test fun assistant_file_card_sits_below_the_prose() {
        compose.setContent {
            com.hermes.client.ui.theme.HermesTheme(darkTheme = false) {
                androidx.compose.material3.Surface {
                    AssistantTurn(
                        msg = ChatMessage(
                            id = "a",
                            role = Role.ASSISTANT,
                            text = "第一段正文。\n\n第二段正文，用来把附件卡片推到更下面。",
                            isStreaming = false,
                            files = listOf(ChatFile(id = "f1", name = "经营快报.html")),
                        ),
                        canRegenerate = false, showActions = false,
                        onRegenerate = {}, onRetryWithModel = {}, onOpenTableFullscreen = {},
                        isSpeaking = false, onReadAloud = {}, onStopReading = {},
                        onImageSave = {}, onImageSaveAs = {}, onImageShare = {},
                        savingImageId = null, onFileOpen = {}, onFileShare = {},
                    )
                }
            }
        }
        compose.waitForIdle()

        val firstBlock = compose.onNodeWithTag("chat-block-a-0", useUnmergedTree = true).getBoundsInRoot()
        val files = compose.onNodeWithTag("chat-files-a", useUnmergedTree = true).getBoundsInRoot()
        assertTrue(
            "attachment card must start below the first prose block (was ${files.top}, block ended ${firstBlock.bottom})",
            files.top > firstBlock.bottom,
        )
    }
}
