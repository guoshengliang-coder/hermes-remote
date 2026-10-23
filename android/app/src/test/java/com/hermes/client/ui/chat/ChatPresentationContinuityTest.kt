package com.hermes.client.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class ChatPresentationContinuityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun completingVisibleStreamDoesNotRaiseColdOpenSkeleton() {
        val state = mutableStateOf(
            ChatUiState(
                messages = listOf(
                    ChatMessage("u1", Role.USER, "问题"),
                    ChatMessage("a1", Role.ASSISTANT, "已经显示的回答", isStreaming = true),
                ),
                isGenerating = true,
                historyLoading = true,
                historyLoaded = false,
            ),
        )
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.ZH) {
                HermesTheme(darkTheme = false) {
                    androidx.compose.material3.Surface(Modifier) {
                        ChatMessageList(state = state.value, sessionId = "completion-continuity")
                    }
                }
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("chat-history-skeleton").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("已经显示的回答").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("已经显示的回答").assertIsDisplayed()

        state.value = state.value.copy(
            messages = state.value.messages.map {
                if (it.id == "a1") it.copy(isStreaming = false) else it
            },
            isGenerating = false,
        )
        compose.waitForIdle()

        compose.onAllNodesWithTag("chat-history-skeleton").assertCountEquals(0)
        compose.onNodeWithText("已经显示的回答").assertIsDisplayed()
    }
}
