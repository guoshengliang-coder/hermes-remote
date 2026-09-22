package com.hermes.client.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.DeliveryState
import com.hermes.client.domain.Role
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * HG-95: long press on a message selects text in place; it no longer opens the action menu.
 * The menu moved to the action row (assistant, every settled turn) and to a tap (user bubble).
 *
 * Regression: long press used to open MessageActionSheet, and the only way to select part of a
 * message was that sheet's 「选择文本」 → a fullscreen dialog.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp-420dpi")
class InlineTextSelectionTest {
    @get:Rule
    val compose = createComposeRule()

    private fun assistant(
        streaming: Boolean = false,
        isError: Boolean = false,
        canRegenerate: Boolean = false,
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.ZH) {
                com.hermes.client.ui.theme.HermesTheme(darkTheme = false) {
                    androidx.compose.material3.Surface {
                        AssistantTurn(
                            msg = ChatMessage(
                                id = "a",
                                role = Role.ASSISTANT,
                                text = "第一段正文，里面有一句想单独摘出来的话。",
                                isStreaming = streaming,
                                isError = isError,
                            ),
                            canRegenerate = canRegenerate, showActions = true,
                            onRegenerate = {}, onRetryWithModel = {}, onOpenTableFullscreen = {},
                            isSpeaking = false, onReadAloud = {}, onStopReading = {},
                            onOpenImage = { _, _ -> }, onFileOpen = {}, onFileShare = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun user(delivery: DeliveryState = DeliveryState.SENT, onRetry: (String) -> Unit = {}) {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.ZH) {
                com.hermes.client.ui.theme.HermesTheme(darkTheme = false) {
                    androidx.compose.material3.Surface {
                        UserBubble(
                            msg = ChatMessage(id = "u", role = Role.USER, text = "帮我看看这段话", delivery = delivery),
                            onEditResend = {},
                            onOpenImage = { _, _ -> },
                            onFileOpen = {},
                            onFileShare = {},
                            onRetrySend = onRetry,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    // Asserted on semantics rather than by performing a long press: the gesture would start a
    // real selection, whose handle magnifier is android.widget.Magnifier and has no Surface under
    // Robolectric. The old combinedClickable exposed OnLongClick, so this still fails on the old code.
    @Test fun a_settled_assistant_turn_exposes_no_long_press_action() {
        assistant(canRegenerate = true)
        compose.onAllNodes(hasLongClickAction, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("换个模型重试").assertCountEquals(0)
    }

    @Test fun settled_assistant_prose_is_selectable() {
        assistant()
        compose.onAllNodesWithTag("chat-selectable-a", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test fun streaming_assistant_prose_is_not_selectable() {
        assistant(streaming = true)
        compose.onAllNodesWithTag("chat-selectable-a", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun an_earlier_turn_reaches_its_menu_through_the_action_row() {
        assistant(canRegenerate = false)
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("查看原文 / 选择").assertExists()
        compose.onAllNodesWithText("重新生成").assertCountEquals(0)
    }

    @Test fun an_error_turn_keeps_a_way_to_regenerate() {
        assistant(isError = true, canRegenerate = true)
        compose.onAllNodesWithContentDescriptionCount("有帮助", 0)
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("换个模型重试").assertExists()
    }

    @Test fun tapping_a_user_bubble_opens_its_menu_and_long_press_is_not_an_action() {
        user()
        compose.onAllNodes(hasLongClickAction, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag("chat-selectable-u", useUnmergedTree = true).assertCountEquals(1)

        compose.onNodeWithText("帮我看看这段话").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("编辑并重新发送").assertExists()
        compose.onNodeWithText("查看原文 / 选择").assertExists()
    }

    @Test fun tapping_a_failed_retryable_bubble_retries_instead_of_opening_the_menu() {
        val retried = mutableListOf<String>()
        user(delivery = DeliveryState.FAILED) { retried += it }
        compose.onNodeWithText("帮我看看这段话").performClick()
        compose.waitForIdle()
        assertEquals(listOf("u"), retried)
        compose.onAllNodesWithText("编辑并重新发送").assertCountEquals(0)
    }

    private val hasLongClickAction = SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithContentDescriptionCount(
        description: String,
        expected: Int,
    ) = onAllNodes(androidx.compose.ui.test.hasContentDescription(description)).assertCountEquals(expected)
}
