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

    private fun snap(
        name: String,
        darkTheme: Boolean = false,
        fontScale: Float? = null,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        compose.setContent {
            com.hermes.client.ui.theme.HermesTheme(darkTheme = darkTheme) {
                val density = androidx.compose.ui.platform.LocalDensity.current
                if (fontScale == null) {
                    androidx.compose.material3.Surface { content() }
                } else {
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, fontScale),
                    ) {
                        androidx.compose.material3.Surface { content() }
                    }
                }
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

    private val updateVersion = com.hermes.client.update.UpdateVersion(
        versionName = "0.1.76",
        versionCode = 77,
        applicationId = "com.hermes.remote",
        channel = com.hermes.client.update.UPDATE_CHANNEL,
        publishedAt = "2026-09-01T12:00:00Z",
        fileName = "Hermes-Remote-0.1.76-debug.apk",
        downloadUrl = "https://mrlgs.net/releases/Hermes-Remote-0.1.76-debug.apk",
        sizeBytes = 29_800_000,
        sha256 = "a".repeat(64),
        certificateSha256 = "06c18dfc4a852330654c2da040a578bccab13b71dde4ac962bb9bc2271dd32c5",
        minSdk = 26,
        releaseNotes = listOf("恢复后台下载后仍可继续安装。", "历史版本改为只读，默认推荐最新版本。"),
        sourceCommit = "abcdef1",
    )

    private fun updateRow(eligibility: com.hermes.client.update.VersionEligibility) =
        com.hermes.client.update.UpdateRow(updateVersion, eligibility)

    @Test fun updateUpToDate() = snap("update-up-to-date") {
        com.hermes.client.ui.settings.AppUpdateContent(
            state = com.hermes.client.update.UpdateUiState(
                checkedOnce = true,
                lastCheckedAtMs = 1_788_260_400_000,
                latest = updateRow(com.hermes.client.update.VersionEligibility.CURRENT),
            ),
        )
    }

    @Test fun updateAvailable() = snap("update-available") {
        com.hermes.client.ui.settings.AppUpdateContent(
            state = com.hermes.client.update.UpdateUiState(
                checkedOnce = true,
                lastCheckedAtMs = 1_788_260_400_000,
                latest = updateRow(com.hermes.client.update.VersionEligibility.UPDATE),
            ),
        )
    }

    @Test fun updateAvailableAppDarkWhileSystemLight() = snap("update-available-app-dark", darkTheme = true) {
        com.hermes.client.ui.settings.AppUpdateContent(
            state = com.hermes.client.update.UpdateUiState(
                checkedOnce = true,
                latest = updateRow(com.hermes.client.update.VersionEligibility.UPDATE),
            ),
        )
    }

    @Test fun updateAvailableLargeFont() = snap("update-available-large-font", fontScale = 1.3f) {
        com.hermes.client.ui.settings.AppUpdateContent(
            state = com.hermes.client.update.UpdateUiState(
                checkedOnce = true,
                latest = updateRow(com.hermes.client.update.VersionEligibility.UPDATE),
            ),
        )
    }

    @Test fun updateDownloading() = snap("update-downloading") {
        com.hermes.client.ui.settings.AppUpdateContent(
            state = com.hermes.client.update.UpdateUiState(
                task = com.hermes.client.update.UpdateTask(
                    updateVersion,
                    com.hermes.client.update.DownloadPhase.DOWNLOADING,
                    percent = 42,
                    downloadedBytes = 12_516_000,
                    totalBytes = updateVersion.sizeBytes,
                ),
            ),
        )
    }

    @Test fun updateInstallable() = snap("update-installable") {
        com.hermes.client.ui.settings.AppUpdateContent(
            state = com.hermes.client.update.UpdateUiState(
                task = com.hermes.client.update.UpdateTask(
                    updateVersion,
                    com.hermes.client.update.DownloadPhase.INSTALLABLE,
                    percent = 100,
                    verifiedFile = java.io.File("verified.apk"),
                ),
            ),
        )
    }

    @Test fun updateSuperseded() = snap("update-superseded") {
        val old = updateVersion.copy(
            versionName = "0.1.75",
            versionCode = 76,
            fileName = "Hermes-Remote-0.1.75-debug.apk",
            downloadUrl = "https://mrlgs.net/releases/Hermes-Remote-0.1.75-debug.apk",
        )
        com.hermes.client.ui.settings.AppUpdateContent(
            state = com.hermes.client.update.UpdateUiState(
                checkedOnce = true,
                latest = updateRow(com.hermes.client.update.VersionEligibility.UPDATE),
                task = com.hermes.client.update.UpdateTask(
                    old,
                    com.hermes.client.update.DownloadPhase.INSTALLABLE,
                    percent = 100,
                    verifiedFile = java.io.File("verified.apk"),
                ),
            ),
        )
    }

    @Test fun updateCheckFailed() = snap("update-check-failed") {
        com.hermes.client.ui.settings.AppUpdateContent(
            state = com.hermes.client.update.UpdateUiState(
                checkError = com.hermes.client.data.error.AppError(
                    com.hermes.client.data.error.AppErrorCode.UPDATE_CHECK_FAILED,
                    retryable = true,
                    technicalCause = "offline",
                    stage = "update_check",
                ),
            ),
        )
    }

    @Test fun updateTaskAndCheckFailed() = snap("update-task-check-failed") {
        com.hermes.client.ui.settings.AppUpdateContent(
            state = com.hermes.client.update.UpdateUiState(
                task = com.hermes.client.update.UpdateTask(
                    updateVersion,
                    com.hermes.client.update.DownloadPhase.INSTALLABLE,
                    percent = 100,
                    verifiedFile = java.io.File("verified.apk"),
                ),
                checkError = com.hermes.client.data.error.AppError(
                    com.hermes.client.data.error.AppErrorCode.UPDATE_CHECK_FAILED,
                    retryable = true,
                    technicalCause = "offline",
                    stage = "update_check",
                ),
            ),
        )
    }

    @Test fun smoke() {
        compose.setContent {
            androidx.compose.material3.Text("Hermes screenshot harness OK")
        }
        compose.onRoot().captureRoboImage("screenshots/smoke.png", roborazziOptions = options)
    }
}
