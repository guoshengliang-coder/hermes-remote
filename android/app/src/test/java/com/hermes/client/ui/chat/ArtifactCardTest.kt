package com.hermes.client.ui.chat

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.hermes.client.domain.*
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The artifacts sheet drops the video playback hint (the whole card plays and the download button
 * sits right below it), while the chat attachment card keeps it. Pinned because the two surfaces
 * share [ChatFileList] and the hint is decided by a parameter (user decision 2026-10-30).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-420dpi")
class ArtifactCardTest {
    @get:Rule val compose = createComposeRule()

    private val video = ChatFile("v", "clip.mp4", "video/mp4", 10_000_000, remotePath = "/tmp/clip.mp4")

    @Test fun artifactCardKeepsTheFooterAndDropsThePlaybackHint() {
        val artifacts = conversationArtifacts(listOf(ChatMessage("m", Role.ASSISTANT, "", files = listOf(video))))
        compose.setContent { HermesTheme { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.ZH) {
            Surface {
                ConversationArtifactsContent(artifacts, OlderHistoryUiState(), rememberLazyListState(),
                    {}, { _, _ -> }, {}, {}, {}, {})
            }
        } } }
        compose.onNodeWithText("可在线播放，也可下载").assertDoesNotExist()
        compose.onNodeWithText("查看上下文").assertIsDisplayed()
    }

    @Test fun chatAttachmentCardStillExplainsThatTheCardPlays() {
        compose.setContent { HermesTheme { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.ZH) {
            Surface { ChatFileList(listOf(video), {}, {}) }
        } } }
        compose.onNodeWithText("可在线播放，也可下载").assertIsDisplayed()
    }
}
