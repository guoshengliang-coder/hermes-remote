package com.hermes.client.ui.screenshot

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM screenshot tests (Robolectric + Roborazzi). Golden images live under
 * app/screenshots/. Run with:  ./gradlew :app:testDebugUnitTest --tests "*Screenshot*"
 * These are NOT part of the release gate — pixel noise must never block a release.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f),
    )

    private fun snap(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            com.hermes.client.ui.theme.HermesTheme(darkTheme = false) {
                androidx.compose.material3.Surface { content() }
            }
        }
        // The markdown renderer parses asynchronously; give it real time, then settle composition.
        Thread.sleep(250)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/$name.png", roborazziOptions = options)
    }

    // Deterministic fixtures: timestamps stay null so the live elapsed suffix (wall-clock
    // dependent) never enters a golden image.
    private fun msg(text: String = "", thinking: String = "", tools: List<com.hermes.client.domain.ToolCall> = emptyList()) =
        com.hermes.client.domain.ChatMessage(
            id = "s", role = com.hermes.client.domain.Role.ASSISTANT,
            text = text, thinking = thinking, tools = tools, isStreaming = true,
        )

    @Test fun runningStatusGenerating() = snap("status-generating") {
        com.hermes.client.ui.chat.RunningStatusLine(msg(text = "partial output"))
    }

    @Test fun runningStatusThinking() = snap("status-thinking") {
        com.hermes.client.ui.chat.RunningStatusLine(msg(thinking = "先检查 nginx 配置，然后逐一验证每个 upstream 的证书链"))
    }

    @Test fun runningStatusTool() = snap("status-tool") {
        com.hermes.client.ui.chat.RunningStatusLine(
            msg(text = "x", tools = listOf(com.hermes.client.domain.ToolCall("t", "Bash", com.hermes.client.domain.ToolStatus.RUNNING, command = "npm test"))),
        )
    }

    @Test fun toolCardFailure() = snap("tool-card-failure") {
        com.hermes.client.ui.chat.SemanticToolCard(
            com.hermes.client.domain.ToolCall(
                "b", "Bash", com.hermes.client.domain.ToolStatus.DONE,
                command = "systemctl restart hermes-gateway", exitCode = 1, durationMs = 1200,
                output = "Job failed. See journalctl -xe",
            ),
        )
    }

    @Test fun tableCardNarrow() = snap("table-card-narrow") {
        val raw = "| 项目 | 期望值 | 实际值 |\n|---|---|---|\n| 证书深度 | 4 | 2 |\n| 读超时 | 75s | 75s |"
        com.hermes.client.ui.chat.ChatTableCard(raw, onOpenFullscreen = {}) {
            com.hermes.client.ui.chat.StyledMarkdownTableSample(raw)
        }
    }

    @Test fun timelineNotes() = snap("timeline-notes") {
        androidx.compose.foundation.layout.Column {
            val delegation = com.hermes.client.domain.ChatMessage(
                id = "t1", role = com.hermes.client.domain.Role.USER,
                text = "[ASYNC DELEGATION BATCH COMPLETE — deleg_1]\nresults…",
                displayKind = "async_delegation_complete", displayTaskCount = 2,
            )
            com.hermes.client.ui.chat.TimelineNoteRow(
                com.hermes.client.ui.chat.timelineNoteFor(delegation)!!, delegation,
            )
            val switch = com.hermes.client.domain.ChatMessage(
                id = "t2", role = com.hermes.client.domain.Role.USER,
                text = "[System: The active model for this chat has changed to gpt-5.6-sol via provider openai-codex.]",
                displayKind = "model_switch",
            )
            com.hermes.client.ui.chat.TimelineNoteRow(
                com.hermes.client.ui.chat.timelineNoteFor(switch)!!, switch,
            )
            val resumed = com.hermes.client.domain.ChatMessage(
                id = "t3", role = com.hermes.client.domain.Role.USER,
                text = "note", displayKind = "auto_continue",
            )
            com.hermes.client.ui.chat.TimelineNoteRow(
                com.hermes.client.ui.chat.timelineNoteFor(resumed)!!, resumed,
            )
        }
    }

    @Test fun clarifySingleChoice() = snap("clarify-single") {
        com.hermes.client.ui.chat.ClarifySheetContent(
            com.hermes.client.ui.chat.ClarifyRequest(
                "r",
                listOf(com.hermes.client.ui.chat.ClarifyQuestion("", "要用哪种发布方式？", listOf("滚动发布 (Recommended)", "蓝绿切换", "全量停机重发"))),
            ),
            onAnswer = {}, onSkip = {},
        )
    }

    @Test fun clarifyMultiSelect() = snap("clarify-multi") {
        com.hermes.client.ui.chat.ClarifySheetContent(
            com.hermes.client.ui.chat.ClarifyRequest(
                "r2",
                listOf(com.hermes.client.ui.chat.ClarifyQuestion("", "备份哪些内容？", listOf("数据库全量 (Recommended)", "上传的用户文件"), multiSelect = true)),
            ),
            onAnswer = {}, onSkip = {},
        )
    }

    @Test fun clarifyBatchProgress() = snap("clarify-batch") {
        com.hermes.client.ui.chat.ClarifySheetContent(
            com.hermes.client.ui.chat.ClarifyRequest(
                "r3",
                listOf(
                    com.hermes.client.ui.chat.ClarifyQuestion("q0", "数据库选型？", listOf("PostgreSQL")),
                    com.hermes.client.ui.chat.ClarifyQuestion("q1", "对象存储用哪个？", listOf("本地 MinIO (Recommended)", "阿里云 OSS")),
                ),
                lockedAnswers = mapOf("q0" to "PostgreSQL"),
            ),
            onAnswer = {}, onSkip = {},
        )
    }

    @Test fun smoke() {
        compose.setContent {
            androidx.compose.material3.Text("Hermes screenshot harness OK")
        }
        compose.onRoot().captureRoboImage("screenshots/smoke.png", roborazziOptions = options)
    }
}
