package com.hermes.client.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
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

/**
 * Goldens for the search marks themselves (docs/DESIGN.md §5.4, HG-45).
 *
 * Before HG-45 nothing in the repo rendered a search mark into a golden — the whole feature was
 * pinned at the level of "the bar has a counter". That is how the marks could stay per-TURN for a
 * day while the mock said per-occurrence, and how a reader ended up at `42/77` with two identical
 * marks on screen and no way to tell which one the counter meant.
 *
 * What these pin is exactly that: in one turn with several marks, precisely one is the solid
 * brand fill. The Markdown case additionally crosses a block boundary, because the focused mark is
 * found by ORDINAL and the per-block ordinal offset is the part that can silently go wrong.
 *
 * Goldens live under app/screenshots/. Not part of the release gate — pixel noise must never block
 * a release.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class ChatSearchMarksScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f),
    )

    private val query = "网关"

    private fun snap(name: String, darkTheme: Boolean, focus: Int?, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            HermesTheme(darkTheme = darkTheme) {
                CompositionLocalProvider(
                    LocalAppLanguage provides AppLanguage.ZH,
                    LocalChatSearch provides ChatSearchContext(
                        query = query,
                        currentMessageId = "m",
                        currentOccurrence = focus,
                    ),
                    LocalTurnIsCurrentHit provides true,
                ) {
                    Surface { Column(Modifier.padding(12.dp)) { content() } }
                }
            }
        }
        Thread.sleep(600)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/$name.png", roborazziOptions = options)
    }

    private val userText = "网关那边超时了，是不是网关证书过期？先看看网关日志。"

    private fun userBubbleContent(): @androidx.compose.runtime.Composable () -> Unit = {
        UserBubble(
            msg = ChatMessage(id = "m", role = Role.USER, text = userText),
            onEditResend = {},
            onOpenImage = { _, _ -> },
            onFileOpen = {},
            onFileShare = {},
        )
    }

    private val assistantText = """
        网关的证书链深度不够，握手在第二跳断开。

        修复后重启网关即可；网关本身的配置没有别的问题。
    """.trimIndent()

    /** The real turn, so the per-block ordinal offsets are the production ones. */
    private fun assistantBlocksContent(): @androidx.compose.runtime.Composable () -> Unit = {
        AssistantTurn(
            msg = ChatMessage(id = "m", role = Role.ASSISTANT, text = assistantText, isStreaming = false),
            canRegenerate = false, showActions = false,
            onRegenerate = {}, onRetryWithModel = {}, onOpenTableFullscreen = {},
            isSpeaking = false, onReadAloud = {}, onStopReading = {},
            onOpenImage = { _, _ -> }, onFileOpen = {}, onFileShare = {},
        )
    }

    // Three marks, the second one focused: one solid fill, two pale stickers.
    @Test fun userBubble() = snap("chat-search-marks-user", darkTheme = false, focus = 1, content = userBubbleContent())

    @Test fun userBubbleDark() = snap("chat-search-marks-user-dark", darkTheme = true, focus = 1, content = userBubbleContent())

    // Focus sits in the SECOND block (occurrence 1 of 3), which only lands right if each block is
    // told how many marks precede it.
    @Test fun markdownAcrossBlocks() =
        snap("chat-search-marks-markdown", darkTheme = false, focus = 1, content = assistantBlocksContent())

    @Test fun markdownAcrossBlocksDark() =
        snap("chat-search-marks-markdown-dark", darkTheme = true, focus = 1, content = assistantBlocksContent())

    // Nothing focused (the counter is on another turn): every mark is the pale tier.
    @Test fun noFocusInThisTurn() =
        snap("chat-search-marks-unfocused", darkTheme = false, focus = null, content = userBubbleContent())
}
