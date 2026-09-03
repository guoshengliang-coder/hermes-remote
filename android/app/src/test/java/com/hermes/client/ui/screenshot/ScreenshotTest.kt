package com.hermes.client.ui.screenshot

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.hermes.client.ui.sessions.SessionSubline
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
        // Virtual time to advance before capture (delayed reveals such as the "sending" bubble).
        advanceMs: Long = 0L,
        // Drive the clock by hand: needed when the content runs an infinite transition, which
        // never lets an auto-advancing clock go idle.
        manualClock: Boolean = false,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        if (manualClock) compose.mainClock.autoAdvance = false
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
        if (advanceMs > 0) { compose.mainClock.advanceTimeBy(advanceMs); compose.waitForIdle() }
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

    // Turn navigation (docs/DESIGN.md §5.4): the pill and the prompt list rows.
    private val longPrompt = "把 gateway 的路由中间件拆成鉴权和限流两层，保持现有测试通过。"

    @Test fun turnJumpPill() = snap("turn-jump-pill") {
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.fillMaxWidth().padding(16.dp),
            contentAlignment = androidx.compose.ui.Alignment.TopCenter,
        ) {
            com.hermes.client.ui.chat.TurnJumpPill(
                label = longPrompt, showList = false, onJump = {}, onOpenList = {},
                modifier = androidx.compose.ui.Modifier.widthIn(max = 260.dp),
            )
        }
    }

    @Test fun turnJumpPillSplitDark() = snap("turn-jump-pill-split-dark", darkTheme = true) {
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.fillMaxWidth().padding(16.dp),
            contentAlignment = androidx.compose.ui.Alignment.TopCenter,
        ) {
            com.hermes.client.ui.chat.TurnJumpPill(
                label = longPrompt, showList = true, onJump = {}, onOpenList = {},
                modifier = androidx.compose.ui.Modifier.widthIn(max = 260.dp),
            )
        }
    }

    @Test fun promptListRows() = snap("prompt-list-rows") {
        val rows = listOf(
            com.hermes.client.ui.chat.PromptRow(0, "会话开始", time = null, isCurrent = false, isLeading = true),
            com.hermes.client.ui.chat.PromptRow(1, longPrompt, time = "09:12", isCurrent = true, isLeading = false),
            com.hermes.client.ui.chat.PromptRow(2, "限流阈值放到配置里。", time = "09:40", isCurrent = false, isLeading = false),
            com.hermes.client.ui.chat.PromptRow(3, "跑一遍完整测试，把失败的贴给我。", time = "昨天 10:05", isCurrent = false, isLeading = false),
        )
        com.hermes.client.ui.chat.PromptListContent(rows, onPick = {}, modifier = androidx.compose.ui.Modifier.height(320.dp))
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

    private fun historyVersion(code: Int, name: String, notes: List<String>) =
        updateVersion.copy(
            versionCode = code, versionName = name, releaseNotes = notes,
            fileName = "Hermes-Remote-$name-debug.apk",
        )

    @Test fun updateHistoryRecord() = snap("update-history") {
        val current = historyVersion(76, "0.1.75", listOf("修复决策卡回答收不到的根因。"))
        val old = historyVersion(75, "0.1.74", listOf("暴露过期的决策回答。", "全链路诊断日志。"))
        com.hermes.client.ui.settings.AppUpdateContent(
            state = com.hermes.client.update.UpdateUiState(
                checkedOnce = true,
                lastCheckedAtMs = 1_788_260_400_000,
                latest = updateRow(com.hermes.client.update.VersionEligibility.UPDATE),
                history = listOf(
                    com.hermes.client.update.UpdateRow(current, com.hermes.client.update.VersionEligibility.CURRENT),
                    com.hermes.client.update.UpdateRow(old, com.hermes.client.update.VersionEligibility.OLD),
                ),
                apkOnDisk = setOf(75, 76),
            ),
        )
    }

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

    private fun userTurn(id: String, text: String, delivery: com.hermes.client.domain.DeliveryState) =
        com.hermes.client.domain.ChatMessage(id = id, role = com.hermes.client.domain.Role.USER, text = text, delivery = delivery)

    // Delivery three-state: sent (solid), sending (dimmed + tail ring, revealed after 250ms) and
    // not-sent (dimmed + error mark + tap-to-retry line). Bubbles are laid out in a plain Column:
    // capturing the reverse-layout LazyColumn under Robolectric paints a stray copy of the last
    // row at the top of the image (a capture artifact, not visible on device). The ring's
    // breathing is switched off through LocalDeliveryMotionEnabled so the clock can settle.
    private fun snapDelivery(name: String, darkTheme: Boolean) = snap(name, darkTheme = darkTheme, advanceMs = 600L) {
        androidx.compose.runtime.CompositionLocalProvider(
            com.hermes.client.ui.chat.LocalDeliveryMotionEnabled provides false,
        ) {
            androidx.compose.foundation.layout.Column(
                modifier = androidx.compose.ui.Modifier.padding(horizontal = 22.dp, vertical = 16.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(18.dp),
            ) {
                listOf(
                    userTurn("h-1", "已发送的消息", com.hermes.client.domain.DeliveryState.SENT),
                    userTurn("u-2", "发送中的消息", com.hermes.client.domain.DeliveryState.SENDING),
                    userTurn("u-3", "未发送的消息", com.hermes.client.domain.DeliveryState.FAILED),
                ).forEach { msg ->
                    com.hermes.client.ui.chat.UserBubble(
                        msg = msg,
                        onEditResend = {},
                        onImageSave = {},
                        onImageSaveAs = {},
                        onImageShare = {},
                        savingImageId = null,
                        onFileOpen = {},
                        onFileShare = {},
                        sendDiagnostic = if (msg.delivery == com.hermes.client.domain.DeliveryState.FAILED) "code=HR-SESS-007" else null,
                    )
                }
            }
        }
    }

    // Pinned marker in the subline (DESIGN.md §5.2): every title shares the 16dp left edge, the
    // pin precedes the folder glyph, and a status line does not move the marker.
    private fun listSession(title: String, repo: String?) = com.hermes.client.domain.Session(
        id = title, title = title, model = "gpt-5.6-terra", provider = null, messageCount = 1,
        profile = "personal", cwd = repo, gitRepoRoot = repo, gitBranch = null,
    )

    @Test fun sessionRowsPinnedSubline() = snap("session-rows-pinned") {
        val defaultPath = "/Users/me"
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.widthIn(max = 360.dp)) {
            androidx.compose.material3.ListItem(
                headlineContent = { androidx.compose.material3.Text("查看机器性能负荷") },
                supportingContent = {
                    androidx.compose.foundation.layout.Column {
                        SessionSubline(listSession("a", null), defaultProjectPath = defaultPath, pinned = true)
                        androidx.compose.material3.Text("已中断", style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                    }
                },
            )
            androidx.compose.material3.ListItem(
                headlineContent = { androidx.compose.material3.Text("hermes 产研A") },
                supportingContent = { SessionSubline(listSession("b", "/u/hermes-remote"), defaultProjectPath = defaultPath, pinned = true) },
            )
            androidx.compose.material3.ListItem(
                headlineContent = { androidx.compose.material3.Text("查看起风工作室数据") },
                supportingContent = { SessionSubline(listSession("c", "/u/xiaomai-daily-report"), defaultProjectPath = defaultPath) },
            )
        }
    }

    // Startup gate (DESIGN.md §5.11). 1.5 s of virtual time settles the entrance, the delayed
    // status reveal, and the phase crossfade; the failure frame has no progress bar at all.
    private fun startup(name: String, dark: Boolean, state: com.hermes.client.ui.startup.StartupUiState) =
        snap(name, darkTheme = dark, advanceMs = 1_500L, manualClock = true) {
            com.hermes.client.ui.startup.StartupScreen(state = state, onRetry = {}, onOpenConnectionSettings = {})
        }

    @Test fun startupLoading() = startup(
        "startup-loading", dark = false,
        state = com.hermes.client.ui.startup.StartupUiState.Loading(
            com.hermes.client.ui.startup.StartupReason.COLD_START,
            com.hermes.client.ui.startup.StartupPhase.NETWORK,
        ),
    )

    @Test fun startupLoadingAppDarkWhileSystemLight() = startup(
        "startup-loading-dark", dark = true,
        state = com.hermes.client.ui.startup.StartupUiState.Loading(
            com.hermes.client.ui.startup.StartupReason.COLD_START,
            com.hermes.client.ui.startup.StartupPhase.INITIAL_DATA,
        ),
    )

    @Test fun startupFailed() = startup(
        "startup-failed", dark = false,
        state = com.hermes.client.ui.startup.StartupUiState.Failed(
            com.hermes.client.ui.startup.StartupReason.COLD_START,
            com.hermes.client.ui.startup.StartupFailure.CONNECTOR_OFFLINE,
        ),
    )

    @Test fun userBubbleDeliveryStates() = snapDelivery("user-bubble-delivery", darkTheme = false)
    @Test fun userBubbleDeliveryStatesDark() = snapDelivery("user-bubble-delivery-dark", darkTheme = true)

    @Test fun smoke() {
        compose.setContent {
            androidx.compose.material3.Text("Hermes screenshot harness OK")
        }
        compose.onRoot().captureRoboImage("screenshots/smoke.png", roborazziOptions = options)
    }
}
