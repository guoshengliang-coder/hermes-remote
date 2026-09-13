package com.hermes.client.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
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

    @Test fun runningStatusGenerating() = snap("status-generating", manualClock = true) {
        com.hermes.client.ui.chat.RunningStatusLine(msg(text = "partial output"))
    }

    @Test fun runningStatusThinking() = snap("status-thinking", manualClock = true) {
        com.hermes.client.ui.chat.RunningStatusLine(msg(thinking = "先检查 nginx 配置，然后逐一验证每个 upstream 的证书链"))
    }

    @Test fun runningStatusTool() = snap("status-tool", manualClock = true) {
        com.hermes.client.ui.chat.RunningStatusLine(
            msg(text = "x", tools = listOf(com.hermes.client.domain.ToolCall("t", "Bash", com.hermes.client.domain.ToolStatus.RUNNING, command = "npm test"))),
        )
    }

    /** Before the first token: the mark alone, no dots and no "Generating…" to read twice. */
    @Test fun runningStatusPreparing() = snap("status-preparing", manualClock = true) {
        com.hermes.client.ui.chat.RunningStatusLine(msg())
    }

    // Brand loading motion (docs/DESIGN.md §5.6). The clock is frozen so the sweep is deterministic.
    @Test fun listSkeleton() = snap("loading-skeleton", manualClock = true) {
        com.hermes.client.ui.components.SkeletonRows()
    }

    @Test fun listSkeletonDark() = snap("loading-skeleton-dark", darkTheme = true, manualClock = true) {
        com.hermes.client.ui.components.SkeletonRows()
    }

    @Test fun pageLoadingMark() = snap("loading-mark", manualClock = true) {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxWidth().height(120.dp)) {
            com.hermes.client.ui.components.HermesMark(
                size = 32.dp,
                modifier = androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.Center),
            )
        }
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

    /**
     * Row 1 wraps to two lines ON PURPOSE: the mock truncates to one, this app keeps ≤2, and this
     * golden is the only layer that can hold that line — plain Robolectric measures text with a
     * stub font that never wraps.
     */
    private val promptRows = listOf(
        com.hermes.client.ui.chat.PromptRow(0, ordinal = null, "会话开始", time = null, isCurrent = false, isLeading = true),
        com.hermes.client.ui.chat.PromptRow(1, ordinal = 1, longPrompt, time = "09:12", isCurrent = false, isLeading = false),
        com.hermes.client.ui.chat.PromptRow(2, ordinal = 2, "限流阈值放到配置里。", time = null, isCurrent = true, isLeading = false),
        com.hermes.client.ui.chat.PromptRow(3, ordinal = 3, "跑一遍完整测试，把失败的贴给我。", time = "昨天 10:05", isCurrent = false, isLeading = false),
        com.hermes.client.ui.chat.PromptRow(4, ordinal = 4, "把 chrome 关掉", time = null, isCurrent = false, isLeading = false),
    )

    @Test fun promptListRows() = snap("prompt-list-rows") { PromptSheetBody() }

    /**
     * The dark tier of the same rows.
     *
     * Added with the 2026-09-12 de-blueing, which is where it is easiest to get dark wrong: the
     * current row's ordinal disc INVERTS between tiers (ink-on-paper in light, paper-on-ink in
     * dark) and the chips gain a hairline ring that light does not draw at all.
     */
    @Test fun promptListRowsDark() = snap("prompt-list-rows-dark", darkTheme = true) { PromptSheetBody() }

    /**
     * On the sheet's own fill, not the page's — the sheet recesses to `chat.sheet.fill`, and in
     * dark that is a smaller step away from the current row than `surface` would be. Capturing on
     * the wrong ground would flatter exactly the contrast this golden exists to watch.
     */
    @androidx.compose.runtime.Composable
    private fun PromptSheetBody() {
        androidx.compose.material3.Surface(color = com.hermes.client.ui.theme.chatSheetColor()) {
            androidx.compose.foundation.layout.Column {
                com.hermes.client.ui.chat.PromptListHeader(count = 4, onLatest = {})
                com.hermes.client.ui.chat.PromptListContent(promptRows, onPick = {}, modifier = androidx.compose.ui.Modifier.height(360.dp))
            }
        }
    }

    /**
     * The header alone, at fontScale 1.3.
     *
     * Nothing covered the header's structure before, and it is now the busiest row on the sheet:
     * a title, a count chip and two icon buttons competing for one line. Large text is where that
     * line breaks first.
     */
    @Test fun promptListHeaderLargeFont() = snap("prompt-list-header-large-font", fontScale = 1.3f) {
        com.hermes.client.ui.chat.PromptListHeader(count = 12, onLatest = {})
    }

    // The composer's saved-prompt sheet. Settings no longer has a 常用提示 row (HG-33), so the
    // 「管理」 button in this header is the only door into the prompt library — these two goldens
    // are what keeps it from being dropped by a later layout edit.
    private val savedPrompts = listOf(
        com.hermes.client.data.repository.SavedPrompt("1", "Code review", "Review this diff for correctness bugs."),
        com.hermes.client.data.repository.SavedPrompt("2", "翻译成中文", "把下面的内容翻译成简体中文，保留代码块。"),
        com.hermes.client.data.repository.SavedPrompt("3", "写提交信息", "根据暂存区的改动写一条提交信息。"),
    )

    @Test fun savedPromptSheet() = snap("saved-prompt-sheet-zh") {
        androidx.compose.runtime.CompositionLocalProvider(
            com.hermes.client.ui.localization.LocalAppLanguage provides com.hermes.client.ui.localization.AppLanguage.ZH,
        ) {
            com.hermes.client.ui.chat.SavedPromptSheetContent(
                prompts = savedPrompts, onPick = {}, onManage = {},
            )
        }
    }

    // Empty state in English: the copy has to name the button that replaced the Settings row, and
    // "tap Manage" is the longer of the two languages.
    @Test fun savedPromptSheetEmptyDark() = snap("saved-prompt-sheet-empty-dark", darkTheme = true) {
        com.hermes.client.ui.chat.SavedPromptSheetContent(
            prompts = emptyList(), onPick = {}, onManage = {},
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

    // docs/DESIGN.md §5.4 (HG-15): after a completed turn these two are quiet TEXT, not a chip and
    // not a bordered card. 0.1.94 folded the content and left the container, which is why the
    // complaint outlived that fix — this golden is what stops the weight creeping back.
    @Test fun quietFoldSummaries() = snap("turn-fold-quiet") {
        androidx.compose.foundation.layout.Column {
            com.hermes.client.ui.chat.QuietFoldSummary(
                label = "查看思考过程", expanded = false, contentDescription = "思考过程", onClick = {},
            )
            com.hermes.client.ui.chat.ToolTimelineCard(
                listOf(
                    com.hermes.client.domain.ToolCall("a", "skill_view", com.hermes.client.domain.ToolStatus.DONE, output = "---", durationMs = 1_200),
                    com.hermes.client.domain.ToolCall("b", "terminal", com.hermes.client.domain.ToolStatus.DONE, command = "date", exitCode = 0, durationMs = 300),
                    com.hermes.client.domain.ToolCall("c", "bi_query", com.hermes.client.domain.ToolStatus.DONE, output = "rows: 42", durationMs = 900),
                ),
                completed = true,
                stateKey = "quiet",
            )
            androidx.compose.material3.Text(
                "是。广点通 09-04 的“平台侧”收入和展示数据没有回来。",
                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
            )
        }
    }

    // The same list after the run ended: item 3 was left in_progress by Hermes and must no longer
    // be drawn as if work were still happening (HG-16).
    @Test fun settledTaskList() = snap("task-list-settled") {
        com.hermes.client.ui.chat.TodoCard(
            com.hermes.client.domain.ToolCall(
                "t", "todo", com.hermes.client.domain.ToolStatus.DONE,
                todos = listOf(
                    com.hermes.client.domain.TodoItem("复核国内 09-04 与 09-03 毛利桥", "completed"),
                    com.hermes.client.domain.TodoItem("下钻工作室与产品系列", "completed"),
                    com.hermes.client.domain.TodoItem("下钻产品、媒体与推广计划并形成归因结论", "in_progress"),
                ),
            ),
            completed = true,
        )
    }

    /**
     * A table cell carrying a link. The glyph beside it is the evidence that this surface shares
     * the conversation's annotator and inline content: before HermesMarkdown it did not, and the
     * gallery, the fullscreen dialog and both exports all drew links bare.
     */
    @Test fun tableCardLinks() = snap("table-card-links") {
        val raw = "| \u63d0\u6848 | \u94fe\u63a5 |\n| --- | --- |\n| PR | [#30332](https://example.com/pull/30332) |\n"
        com.hermes.client.ui.chat.ChatTableCard(raw, onOpenFullscreen = {}) {
            com.hermes.client.ui.chat.StyledMarkdownTableSample(raw)
        }
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

    @Test fun newChatGreeting() = snap("new-chat-greeting") {
        com.hermes.client.ui.chat.NewChatGreeting(
            profile = "default", identityName = "国盛",
            modelLabel = "gpt-5.6-sol · 高",
            connection = com.hermes.client.data.network.ConnectionState.Connected,
            imeVisible = false,
            hourOfDay = 15,
        )
    }

    @Test fun newChatGreetingDark() = snap("new-chat-greeting-dark", darkTheme = true) {
        com.hermes.client.ui.chat.NewChatGreeting(
            profile = "default", identityName = "国盛",
            modelLabel = "gpt-5.6-sol · 高",
            connection = com.hermes.client.data.network.ConnectionState.Connected,
            imeVisible = false,
            hourOfDay = 15,
        )
    }

    @Test fun newChatGreetingOffline() = snap("new-chat-greeting-offline") {
        com.hermes.client.ui.chat.NewChatGreeting(
            profile = "default", identityName = null,
            modelLabel = "gpt-5.6-sol · 高",
            connection = com.hermes.client.data.network.ConnectionState.Disconnected,
            imeVisible = false,
            hourOfDay = 15,
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

    // Delivery states: sent (solid), sending (dimmed + tail ring, revealed after 250ms), not-sent
    // (dimmed + error mark + tap-to-retry line) and undeliverable — which must be visibly NOT the
    // same offer as not-sent: same dimming and error mark, different copy, no retry (HG-29).
    // Bubbles are laid out in a plain Column:
    // capturing the reverse-layout LazyColumn under Robolectric paints a stray copy of the last
    // row at the top of the image (a capture artifact, not visible on device). The ring's
    // breathing is switched off through LocalDeliveryMotionEnabled so the clock can settle.
    private fun snapDelivery(
        name: String,
        darkTheme: Boolean,
        fontScale: Float? = null,
        language: com.hermes.client.ui.localization.AppLanguage =
            com.hermes.client.ui.localization.AppLanguage.EN,
    ) = snap(name, darkTheme = darkTheme, fontScale = fontScale, advanceMs = 600L) {
        androidx.compose.runtime.CompositionLocalProvider(
            com.hermes.client.ui.chat.LocalDeliveryMotionEnabled provides false,
            com.hermes.client.ui.localization.LocalAppLanguage provides language,
        ) {
            androidx.compose.foundation.layout.Column(
                modifier = androidx.compose.ui.Modifier.padding(horizontal = 22.dp, vertical = 16.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(18.dp),
            ) {
                // The two FAILED rows differ only by error code — that is the point of the
                // golden: same retryable state, different sentence (HG-30).
                listOf(
                    userTurn("h-1", "已发送的消息", com.hermes.client.domain.DeliveryState.SENT) to null,
                    userTurn("u-2", "发送中的消息", com.hermes.client.domain.DeliveryState.SENDING) to null,
                    userTurn("u-3", "未发送的消息", com.hermes.client.domain.DeliveryState.FAILED) to
                        com.hermes.client.data.error.AppErrorCode.MESSAGE_SEND_FAILED,
                    userTurn("u-5", "会话被别处占用的消息", com.hermes.client.domain.DeliveryState.FAILED) to
                        com.hermes.client.data.error.AppErrorCode.SESSION_OWNED_ELSEWHERE,
                    userTurn("u-4", "会话已消失的消息", com.hermes.client.domain.DeliveryState.UNDELIVERABLE) to
                        com.hermes.client.data.error.AppErrorCode.SESSION_NOT_FOUND,
                ).forEach { (msg, code) ->
                    com.hermes.client.ui.chat.UserBubble(
                        msg = msg,
                        onEditResend = {},
                        onOpenImage = { _, _ -> },
                        onFileOpen = {},
                        onFileShare = {},
                        sendDiagnostic = code?.let { "code=${it.value}" },
                        sendErrorCode = code,
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

    private fun probeRuntime(id: String, phase: com.hermes.client.data.progress.SessionRunPhase) =
        com.hermes.client.data.progress.SessionRuntime(
            key = com.hermes.client.data.progress.SessionRuntimeKey("personal", id),
            phase = phase,
            toolName = "top-monitor",
        )

    /**
     * The REAL production row, one tint each so the rectangles can be measured off the PNG.
     *
     * It used to assemble its own `ListItem` with the same styles, which stopped being the real
     * thing the moment the row was drawn by hand — a probe that mirrors production by copying it
     * only mirrors production until someone changes one of the two.
     */
    @androidx.compose.runtime.Composable
    private fun ProbeRow(
        tint: androidx.compose.ui.graphics.Color,
        title: String,
        repo: String?,
        runtime: com.hermes.client.data.progress.SessionRuntime? = null,
        pinned: Boolean = false,
        unread: Boolean = false,
        hasDraft: Boolean = false,
    ) {
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.background(tint),
        ) {
            com.hermes.client.ui.sessions.SessionRow(
                session = listSession(title, repo),
                isPinned = pinned,
                defaultProjectPath = "/Users/me",
                onMoveToProject = {},
                runtime = runtime,
                unread = unread,
                hasDraft = hasDraft,
                onOpen = {}, onTogglePin = {}, onRename = {}, onArchive = {}, onDelete = {},
            )
        }
    }

    /**
     * The 「草稿」 marker (HG-41). It rides in the subline, ahead of 项目 · 模型 and after the pin —
     * NOT in the row's trailing 28dp column, where a neutral mark reads as unread (DESIGN.md §5.2).
     * The rows below pair it with everything it has to coexist with, and the last one is the case
     * with no subline content at all, where the marker is the only thing on the line.
     */
    @androidx.compose.runtime.Composable
    private fun DraftRows() {
        androidx.compose.runtime.CompositionLocalProvider(
            com.hermes.client.ui.localization.LocalAppLanguage provides
                com.hermes.client.ui.localization.AppLanguage.ZH,
        ) {
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.widthIn(max = 360.dp)) {
            ProbeRow(androidx.compose.ui.graphics.Color(0x00000000), "起风工作室数据", "/u/xiaomai", hasDraft = true)
            ProbeRow(
                androidx.compose.ui.graphics.Color(0x00000000), "重构网关心跳", "/u/hermes-remote",
                pinned = true, hasDraft = true,
            )
            // Draft AND unread: one on the left, one on the right, deliberately never the same mark.
            ProbeRow(
                androidx.compose.ui.graphics.Color(0x00000000), "查看机器性能负荷", "/u/xiaomai",
                unread = true, hasDraft = true,
            )
            ProbeRow(
                androidx.compose.ui.graphics.Color(0x00000000), "等待你的确认", "/u/xiaomai",
                runtime = probeRuntime("draft-run", com.hermes.client.data.progress.SessionRunPhase.WAITING_APPROVAL),
                hasDraft = true,
            )
            ProbeRow(androidx.compose.ui.graphics.Color(0x00000000), "默认项目里的会话", null, hasDraft = true)
            // The control: the same row without a draft.
            ProbeRow(androidx.compose.ui.graphics.Color(0x00000000), "没有草稿", "/u/xiaomai")
        }
        }
    }

    @Test fun sessionRowsDraft() = snap("session-rows-draft") { DraftRows() }

    /**
     * The session picker's rows (HG-38). The four states that can appear at once: selected,
     * selectable, an archived hit from search, and a row disabled because the six-attachment
     * budget is already spoken for — the last one is the whole point of deciding the cap up front
     * rather than reporting it afterwards.
     */
    @androidx.compose.runtime.Composable
    private fun PickerRows() {
        androidx.compose.runtime.CompositionLocalProvider(
            com.hermes.client.ui.localization.LocalAppLanguage provides
                com.hermes.client.ui.localization.AppLanguage.ZH,
        ) {
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.widthIn(max = 360.dp)) {
                com.hermes.client.ui.sessions.SessionPickerRow(
                    session = listSession("重构 gateway 路由中间件", "/u/hermes-remote"),
                    checked = true, enabled = true, archived = false, onToggle = {},
                )
                com.hermes.client.ui.sessions.SessionPickerRow(
                    session = listSession("翻译 Android 文案", "/u/xiaomai"),
                    checked = false, enabled = true, archived = false, onToggle = {},
                )
                com.hermes.client.ui.sessions.SessionPickerRow(
                    session = listSession("去年的排查记录", "/u/hk"),
                    checked = false, enabled = true, archived = true, onToggle = {},
                )
                com.hermes.client.ui.sessions.SessionPickerRow(
                    session = listSession("名额用完了选不动", "/u/xiaomai"),
                    checked = false, enabled = false, archived = false, onToggle = {},
                )
            }
        }
    }

    @Test fun sessionPickerRows() = snap("session-picker-rows") { PickerRows() }

    /**
     * The delivery picker's rows (HG-40): no checkboxes, because one tap is the whole decision,
     * and no archived hits, because delivering into an archived conversation would revive it
     * somewhere the list does not show.
     */
    @androidx.compose.runtime.Composable
    private fun DeliverRows() {
        androidx.compose.runtime.CompositionLocalProvider(
            com.hermes.client.ui.localization.LocalAppLanguage provides
                com.hermes.client.ui.localization.AppLanguage.ZH,
        ) {
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.widthIn(max = 360.dp)) {
                com.hermes.client.ui.sessions.SessionPickerRow(
                    session = listSession("重构 gateway 路由中间件", "/u/hermes-remote"),
                    checked = false, enabled = true, archived = false, onToggle = {}, showCheckbox = false,
                )
                com.hermes.client.ui.sessions.SessionPickerRow(
                    session = listSession("翻译 Android 文案", "/u/xiaomai"),
                    checked = false, enabled = true, archived = false, onToggle = {}, showCheckbox = false,
                )
            }
        }
    }

    @Test fun sessionDeliverRows() = snap("session-deliver-rows") { DeliverRows() }

    @Test fun sessionPickerRowsDark() = snap("session-picker-rows-dark", darkTheme = true) { PickerRows() }

    @Test fun sessionRowsDraftDark() = snap("session-rows-draft-dark", darkTheme = true) { DraftRows() }

    @Test fun rowHeightProbe() = snap("row-height-probe") {
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.widthIn(max = 360.dp)) {
            ProbeRow(androidx.compose.ui.graphics.Color(0xFFFFE0E0), "确认是否正常", null)
            ProbeRow(androidx.compose.ui.graphics.Color(0xFFE0FFE0), "起风工作室数据", "/u/xiaomai")
            // A title far past the width. It wraps no longer — `truncate` in the mock — so this row
            // must measure the same as the two above it.
            ProbeRow(
                androidx.compose.ui.graphics.Color(0xFFE0E0FF),
                "哎，现在 DeepSeek 说它发了一个最新的 Flash 4.1，我在这个 Hermes 里",
                "/u/xiaomai",
            )
            // ANDROID_SMOKE A-05: a Chinese project name used to wrap the subline and tip the row
            // into `ListItem`'s 88dp three-line tier while the ASCII rows sat at 72dp. Every
            // fixture here used ASCII, which is exactly why nothing caught it for a week.
            ProbeRow(androidx.compose.ui.graphics.Color(0xFFD0F0FF), "中文项目名", "/u/赫尔墨斯远程")
            ProbeRow(
                androidx.compose.ui.graphics.Color(0xFFFFF0D0), "查看机器性能负荷", null,
                runtime = probeRuntime("probe-done", com.hermes.client.data.progress.SessionRunPhase.COMPLETED_UNREAD),
            )
            ProbeRow(androidx.compose.ui.graphics.Color(0xFFF0D0FF), "无状态槽", "/u/xiaomai")
        }
    }

    /**
     * The running row, which nothing used to cover.
     *
     * The spinner rides in the row's fixed 28dp trailing column and must sit on the row's vertical
     * centre. Under `ListItem` it did not: a row with a status line is three lines, three-line
     * items are TOP-aligned, and the spinner sat high with a hole beneath it. The product owner
     * found that on a device on 2026-09-12 because no screenshot here had ever rendered a running
     * row at all.
     */
    @Test fun sessionRowRunningStates() = snap("session-rows-running") {
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.widthIn(max = 360.dp)) {
            ProbeRow(
                androidx.compose.ui.graphics.Color(0xFFE0FFE0), "查看机器性能负荷", "/u/xiaomai",
                runtime = probeRuntime("run-1", com.hermes.client.data.progress.SessionRunPhase.USING_TOOL),
            )
            // Pinned AND running: the two-tone pin on the left, the spinner on the right, both
            // centred on the same line.
            ProbeRow(
                androidx.compose.ui.graphics.Color(0xFFE0E0FF), "重构网关心跳", "/u/hermes-remote",
                runtime = probeRuntime("run-2", com.hermes.client.data.progress.SessionRunPhase.STREAMING),
                pinned = true,
            )
            ProbeRow(
                androidx.compose.ui.graphics.Color(0xFFFFE0E0), "等待你的确认", "/u/xiaomai",
                runtime = probeRuntime("run-3", com.hermes.client.data.progress.SessionRunPhase.WAITING_APPROVAL),
            )
        }
    }

    // All four group headers in one picture. The amber one is the only coloured pillar in the
    // product, and it only ever appears when a session is actually waiting on you — which the
    // local mock can hold for about six seconds, so it has never been caught on a device
    // (docs/ANDROID_SMOKE.md A-01). This is the one place its colour can be looked at.
    @androidx.compose.runtime.Composable
    private fun SectionHeaders() {
        androidx.compose.foundation.layout.Column {
            com.hermes.client.ui.components.SectionHeader(
                "需要你处理", 2, com.hermes.client.ui.components.SectionTone.NEEDS_YOU, onToggle = {},
            )
            com.hermes.client.ui.components.SectionHeader(
                "已置顶", 1, com.hermes.client.ui.components.SectionTone.PINNED, note = "仅此设备", onToggle = {},
            )
            com.hermes.client.ui.components.SectionHeader(
                "今天", 4, com.hermes.client.ui.components.SectionTone.TODAY, onToggle = {},
            )
            com.hermes.client.ui.components.SectionHeader(
                "前 7 天", 19, com.hermes.client.ui.components.SectionTone.OLDER, collapsed = true, onToggle = {},
            )
        }
    }

    @Test fun sectionHeaderTones() = snap("section-header-tones") { SectionHeaders() }

    @Test fun sectionHeaderTonesDark() = snap("section-header-tones-dark", darkTheme = true) { SectionHeaders() }

    // The two title tiers side by side, same string, so the ONLY difference in the picture is the
    // weight (docs/DESIGN.md §5.2: unread 600, read 500). Worth a golden of its own because the
    // difference is easy to doubt on a screen — CJK at Medium already reads fairly heavy — and
    // because nothing else pins that the read tier is the one a list of read rows gets.
    // ── Card page (docs/DESIGN.md §5.1; Stitch 基线-卡片页 / 暗夜, keys card.default.*) ──────
    // The drawer's content at the sheet's width, in the state the mock shows plus the ones it does
    // not: a job count with an alert dot, all three update states, a long device name, offline.
    private fun cardPage(
        name: String,
        darkTheme: Boolean = false,
        fontScale: Float? = null,
        deviceId: String? = "mac-mini",
        latencyMs: Long = 29L,
        updateState: com.hermes.client.update.UpdateBadgeState = com.hermes.client.update.UpdateBadgeState.UpToDate,
    ) = snap(name, darkTheme = darkTheme, fontScale = fontScale) {
        // Chinese, like the mock, so the reference render and the golden carry the same strings.
        androidx.compose.runtime.CompositionLocalProvider(
            com.hermes.client.ui.localization.LocalAppLanguage provides com.hermes.client.ui.localization.AppLanguage.ZH,
        ) {
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier
                .widthIn(max = 340.dp)
                .height(844.dp)
                .background(com.hermes.client.ui.theme.cardDrawerColor()),
        ) {
            com.hermes.client.ui.nav.CardPageContent(
                activeProfile = "default",
                state = com.hermes.client.ui.nav.CardPageUiState(
                    cronAlerts = 1,
                    cronJobCount = 7,
                    deviceId = deviceId,
                    defaultModel = "claude-opus-5",
                ),
                health = com.hermes.client.data.network.GatewayHealth.Healthy(version = null, running = true, latencyMs = latencyMs),
                themeMode = com.hermes.client.data.repository.ThemeMode.SYSTEM,
                updateState = updateState,
                buildBadge = "DEV",
                onNavigate = {},
                onTheme = {},
                onFeedback = {},
            )
        }
        }
    }

    @Test fun cardPageLight() = cardPage("card.default.light")

    @Test fun cardPageDark() = cardPage("card.default.dark", darkTheme = true)

    /** Large font + a long Mac name + an update waiting: the design-scale lock and the amber dot. */
    @Test fun cardPageLightLargeFont() = cardPage(
        "card.default.light-fs13",
        fontScale = 1.3f,
        deviceId = "guoshengliang-macbook-pro",
        latencyMs = 231L,
        updateState = com.hermes.client.update.UpdateBadgeState.Available("0.1.117"),
    )

    /** Connector offline, and an update check that has never succeeded: no dot on either row. */
    @Test fun cardPageLightOffline() = cardPage(
        "card.default.light-offline",
        deviceId = null,
        updateState = com.hermes.client.update.UpdateBadgeState.Unknown,
    )

    // ── Card page · theme sheet (§5.1 主题弹层; keys card.default.theme-sheet.*) ──────────────
    // The sheet's body, not the ModalBottomSheet around it: a sheet renders in its own window and
    // onRoot() cannot reach it. The grab bar above this is the shared SheetCloseHandle, unchanged.
    private fun themeSheet(
        name: String,
        darkTheme: Boolean = false,
        fontScale: Float? = null,
        language: com.hermes.client.ui.localization.AppLanguage =
            com.hermes.client.ui.localization.AppLanguage.ZH,
        inUse: com.hermes.client.data.repository.ThemeMode = com.hermes.client.data.repository.ThemeMode.SYSTEM,
        pending: com.hermes.client.data.repository.ThemeMode = inUse,
    ) = snap(name, darkTheme = darkTheme, fontScale = fontScale) {
        androidx.compose.runtime.CompositionLocalProvider(
            com.hermes.client.ui.localization.LocalAppLanguage provides language,
        ) {
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier
                    .widthIn(max = 390.dp)
                    .background(com.hermes.client.ui.theme.cardThemeSheetColor()),
            ) {
                com.hermes.client.ui.settings.ThemeSheetContent(
                    inUse = inUse,
                    pending = pending,
                    onPendingChange = {},
                    onSave = {},
                    onClose = {},
                )
            }
        }
    }

    @Test fun themeSheetLight() = themeSheet("card.default.theme-sheet.light")

    @Test fun themeSheetDark() = themeSheet("card.default.theme-sheet.dark", darkTheme = true)

    /**
     * The state the whole redesign turns on: the radio has been moved to 黑曜石深色 but nothing has
     * been written yet, so 「当前使用」 stays on 跟随系统. Selection and effect are two different
     * things here, and this is the only picture that can prove it.
     */
    @Test fun themeSheetPending() = themeSheet(
        "card.default.theme-sheet.pending",
        pending = com.hermes.client.data.repository.ThemeMode.DARK,
    )

    /** English at fontScale 1.3: the longest names and the descriptions wrapping under them. */
    @Test fun themeSheetEnglishLargeFont() = themeSheet(
        "card.default.theme-sheet.en-fs13",
        fontScale = 1.3f,
        language = com.hermes.client.ui.localization.AppLanguage.EN,
        pending = com.hermes.client.data.repository.ThemeMode.DARK,
    )

    // ── Settings → 外观 (the other home of the same option list) ──────────────────────────────
    private fun appearanceOptions(name: String, darkTheme: Boolean = false) =
        snap(name, darkTheme = darkTheme) {
            androidx.compose.runtime.CompositionLocalProvider(
                com.hermes.client.ui.localization.LocalAppLanguage provides
                    com.hermes.client.ui.localization.AppLanguage.ZH,
            ) {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.widthIn(max = 390.dp).padding(16.dp),
                ) {
                    com.hermes.client.ui.settings.ThemeOptionList(
                        selected = com.hermes.client.data.repository.ThemeMode.LIGHT,
                        onSelect = {},
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp),
                    )
                }
            }
        }

    /** No 「当前使用」 badge here: on a page the tap IS the effect, so the badge would only echo the radio. */
    @Test fun appearanceColorModeLight() = appearanceOptions("settings.appearance.color-mode")

    @Test fun appearanceColorModeDark() =
        appearanceOptions("settings.appearance.color-mode-dark", darkTheme = true)

    @Test fun sessionRowTitleTiers() = snap("session-row-title-tiers") {
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.widthIn(max = 360.dp)) {
            for ((label, style) in listOf(
                "未读 600" to com.hermes.client.ui.theme.SessionRowTitle,
                "已读 500" to com.hermes.client.ui.theme.SessionRowTitleRead,
            )) {
                androidx.compose.material3.Text(
                    label,
                    style = com.hermes.client.ui.theme.SessionGroupHeader,
                    modifier = androidx.compose.ui.Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                androidx.compose.material3.ListItem(
                    headlineContent = {
                        androidx.compose.material3.Text("重构 gateway 路由中间件 Refactor", style = style)
                    },
                    supportingContent = {
                        SessionSubline(listSession("t-$label", "/u/hermes-remote"), defaultProjectPath = "/Users/me")
                    },
                )
            }
        }
    }

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

    // Turn-jump landing feedback vs search highlight (DESIGN.md §5.4): the landed bubble gets an
    // outline only (shown at full alpha, i.e. the first frame); the search hit keeps fill + outline.
    // ── 会话行长按操作单 (docs/DESIGN.md §5.5, Stitch 基线-会话列表页/长按下拉菜单) ─────────────
    //
    // The body below the grab bar, for the reason themeSheet() does the same: a ModalBottomSheet
    // renders in its own window and onRoot() cannot reach it. Everything the mock draws from the
    // title row down is here; the hairline top edge and the 36×4dp bar live in the sheet's
    // dragHandle slot and are pinned by the overlay check against the rendered mock instead.
    //
    // Named after the lock file's keys so the golden, the snapshot and the overlay all say the
    // same thing.
    private fun rowMenu(
        name: String,
        darkTheme: Boolean = false,
        fontScale: Float? = null,
        language: com.hermes.client.ui.localization.AppLanguage =
            com.hermes.client.ui.localization.AppLanguage.ZH,
        // The running case: the gateway refuses to move a running session, so the row is greyed.
        // Drawn in the default golden rather than in one of its own — a disabled row costs nothing
        // to include and an extra picture would have to be kept in step with this one.
        moveEnabled: Boolean = true,
        content: (@androidx.compose.runtime.Composable () -> Unit)? = null,
    ) = snap(name, darkTheme = darkTheme, fontScale = fontScale) {
        androidx.compose.runtime.CompositionLocalProvider(
            com.hermes.client.ui.localization.LocalAppLanguage provides language,
        ) {
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier
                    .widthIn(max = 390.dp)
                    .background(com.hermes.client.ui.theme.rowMenuSheetColor()),
            ) {
                com.hermes.client.ui.components.RowActionSheetContent(
                    typeLabel = if (language == com.hermes.client.ui.localization.AppLanguage.ZH) "会话" else "Chat",
                    title = "生成三个审核测试选项",
                    onClose = {},
                ) {
                    if (content != null) {
                        content()
                    } else {
                        com.hermes.client.ui.sessions.SessionActionItems(
                            isPinned = false,
                            currentProjectLabel = if (language == com.hermes.client.ui.localization.AppLanguage.ZH) "默认项目" else "Default project",
                            moveEnabled = moveEnabled,
                            onTogglePin = {}, onRename = {}, onMoveToProject = {},
                            onArchive = {}, onDelete = {},
                        )
                    }
                }
            }
        }
    }

    @Test fun rowMenuLight() = rowMenu("sessions.chats.default.row-menu.light")

    @Test fun rowMenuDark() = rowMenu("sessions.chats.default.row-menu.dark", darkTheme = true)

    /**
     * The row that has to survive: English is longer than Chinese in every label here, fontScale
     * 1.3 grows the label and the trailing hint together, and the trailing hint is right-aligned
     * against a label that is left-aligned. If anything in this sheet collides, it collides here.
     */
    @Test fun rowMenuEnLargeFont() = rowMenu(
        "sessions.chats.default.row-menu.en-fs13",
        fontScale = 1.3f,
        language = com.hermes.client.ui.localization.AppLanguage.EN,
    )

    /** A session that is running: 「移动到项目」 is the one row the gateway will refuse (4009). */
    @Test fun rowMenuMoveDisabled() = rowMenu(
        "sessions.chats.default.row-menu.move-disabled",
        moveEnabled = false,
    )

    /**
     * The archived list's two-action version of the same sheet — the reason it is a shared
     * component. The type chip is what tells them apart, which is the whole argument for making it
     * carry the source instead of the mock's constant 「会话」.
     */
    @Test fun rowMenuArchived() = snap("sessions.chats.default.row-menu.archived") {
        androidx.compose.runtime.CompositionLocalProvider(
            com.hermes.client.ui.localization.LocalAppLanguage provides
                com.hermes.client.ui.localization.AppLanguage.ZH,
        ) {
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier
                    .widthIn(max = 390.dp)
                    .background(com.hermes.client.ui.theme.rowMenuSheetColor()),
            ) {
                com.hermes.client.ui.components.RowActionSheetContent(
                    typeLabel = "已归档",
                    title = "自动化巡检报告导出",
                    onClose = {},
                ) {
                    com.hermes.client.ui.sessions.ArchivedActionItems(onUnarchive = {}, onDelete = {})
                }
            }
        }
    }

    private fun snapLanding(name: String, darkTheme: Boolean) = snap(name, darkTheme = darkTheme) {
        androidx.compose.foundation.layout.Column(
            modifier = androidx.compose.ui.Modifier.padding(horizontal = 22.dp, vertical = 16.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(18.dp),
        ) {
            // The third variant used to be "search", contrasting the landing outline against the
            // search fill+outline. HG-46 removed the search decoration entirely, so that variant
            // would now be a second copy of "plain" — what is left to pin is that the landing
            // outline, which is a different feature, still draws.
            listOf("plain" to 0f, "landing" to 1f).forEach { (id, landing) ->
                com.hermes.client.ui.chat.UserBubble(
                    msg = userTurn(id, "可以进一步加大虚拟内存什么的吗", com.hermes.client.domain.DeliveryState.SENT),
                    onEditResend = {}, onOpenImage = { _, _ -> },
                    onFileOpen = {}, onFileShare = {}, landingAlpha = landing,
                )
            }
        }
    }

    @Test fun userBubbleLandingOutline() = snapLanding("user-bubble-landing", darkTheme = false)
    @Test fun userBubbleLandingOutlineDark() = snapLanding("user-bubble-landing-dark", darkTheme = true)

    @Test fun userBubbleDeliveryStates() = snapDelivery("user-bubble-delivery", darkTheme = false)
    @Test fun userBubbleDeliveryStatesDark() = snapDelivery("user-bubble-delivery-dark", darkTheme = true)

    /**
     * The worst case for the status line: Chinese copy (longer than the English), fontScale 1.3,
     * and the narrowest phone we support. The two failure sentences plus their compact code share
     * one un-wrapping Row, so this is where a new sentence would push the code off the edge.
     */
    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h740dp-420dpi")
    fun userBubbleDeliveryStatesZhNarrowLargeFont() = snapDelivery(
        "user-bubble-delivery-zh-360-fs13",
        darkTheme = false,
        fontScale = 1.3f,
        language = com.hermes.client.ui.localization.AppLanguage.ZH,
    )

    @Test fun smoke() {
        compose.setContent {
            androidx.compose.material3.Text("Hermes screenshot harness OK")
        }
        compose.onRoot().captureRoboImage("screenshots/smoke.png", roborazziOptions = options)
    }

    // ── Chats top bar and segments. The four-segment squeeze is gone (Projects and Archive
    // moved to the overflow menu), so what needs pinning now is the CENTRED title against an
    // avatar on the left and two actions on the right — the imbalance that only shows on a device.
    private fun tabs(zh: Boolean) =
        com.hermes.client.ui.sessions.chatsSegmentModes(showBots = true).map {
            it to when (it) {
                com.hermes.client.ui.sessions.ViewMode.SESSIONS -> if (zh) "会话" else "Chats"
                com.hermes.client.ui.sessions.ViewMode.BOTS -> if (zh) "机器人" else "Bots"
            }
        }

    @Test fun segmentsTwoZh() = snap("segments-2-zh") {
        com.hermes.client.ui.sessions.ChatsSegmentedRow(
            tabs(zh = true), com.hermes.client.ui.sessions.ViewMode.SESSIONS, {},
        )
    }

    // The capsule's stated ceiling is three options (docs/DESIGN.md §5.2), and this pins the worst
    // case that ceiling has to survive at fontScale 1.3.
    //
    // SYNTHETIC since 主题弹层 landed: these used to be the Appearance screen's colour-mode switch,
    // the longest three-option capsule that actually shipped. That screen now draws the theme
    // option list instead, and the longest capsule left in the app is the usage range (7/30/90 天),
    // which proves nothing. Kept rather than deleted, with made-up labels, because the ceiling it
    // guards is a rule about the component and not about any one screen — the next three-option
    // capsule someone adds needs this to already be failing if the rule is wrong.
    @Test fun segmentsThreeZhLargeFont() = snap("segments-3-zh-fs13", fontScale = 1.3f) {
        val options = listOf("跟随系统", "浅色", "深色")
        com.hermes.client.ui.components.SegmentedCapsule(
            options = options,
            selected = options[0],
            onSelect = {},
            label = { it },
        )
    }

    @Test fun segmentsTwoEnLargeFontDark() = snap("segments-2-en-fs13-dark", darkTheme = true, fontScale = 1.3f) {
        com.hermes.client.ui.sessions.ChatsSegmentedRow(
            tabs(zh = false), com.hermes.client.ui.sessions.ViewMode.BOTS, {},
        )
    }

    private fun topBar(language: com.hermes.client.ui.localization.AppLanguage) =
        @androidx.compose.runtime.Composable {
            androidx.compose.runtime.CompositionLocalProvider(
                com.hermes.client.ui.localization.LocalAppLanguage provides language,
            ) {
                com.hermes.client.ui.sessions.ChatsTopBar(
                    activeProfile = "default",
                    onOpenCard = {},
                    onOpenSearch = {},
                    onOpenProjects = {},
                    onOpenArchived = {},
                )
            }
        }

    /**
     * The chat top bar, which had no golden at all until HG-37 gave it a visibility rule to hold.
     * `new` is an empty new session: 返回 and the title block, nothing else — no ＋ pointing at the
     * conversation you are already in, no ⋮ whose five items all act on a transcript that does not
     * exist yet.
     */
    @androidx.compose.runtime.Composable
    private fun ChatBar(title: String, actionsVisible: Boolean) {
        androidx.compose.runtime.CompositionLocalProvider(
            com.hermes.client.ui.localization.LocalAppLanguage provides
                com.hermes.client.ui.localization.AppLanguage.ZH,
        ) {
            com.hermes.client.ui.chat.ChatTopBar(
                title = title,
                actionsVisible = actionsVisible,
                creatingNewChat = false,
                refreshingConversation = false,
                promptsLabel = "我的提问",
                onBack = {},
                onNewChat = {},
                onSearch = {},
                onPrompts = {},
                onRefresh = {},
                onShare = {},
                onArchive = {},
            )
        }
    }

    @Test fun chatTopBarNewSession() = snap("chat-topbar-new") { ChatBar("新会话", actionsVisible = false) }

    @Test fun chatTopBarExistingSession() =
        snap("chat-topbar-existing") { ChatBar("查看机器性能负荷", actionsVisible = true) }

    @Test fun chatTopBarExistingSessionDark() =
        snap("chat-topbar-existing-dark", darkTheme = true) { ChatBar("查看机器性能负荷", actionsVisible = true) }

    @Test fun chatsTopBarZh() =
        snap("chats-topbar-zh") { topBar(com.hermes.client.ui.localization.AppLanguage.ZH)() }

    @Test fun chatsTopBarEnLargeFont() =
        snap("chats-topbar-en-fs13", fontScale = 1.3f) { topBar(com.hermes.client.ui.localization.AppLanguage.EN)() }

    // ── Bot conversation bubbles: with the composer open, the right-hand column carries two
    // speakers. Signing them apart is the whole point, and it is only visible in a picture.
    private val dingTalkDm =
        com.hermes.client.ui.sessions.BotOrigin("dingtalk", displayName = null, chatType = "dm")

    @androidx.compose.runtime.Composable
    private fun BotBubblePair() {
        androidx.compose.runtime.CompositionLocalProvider(
            com.hermes.client.ui.chat.LocalBotOrigin provides dingTalkDm,
            com.hermes.client.ui.chat.LocalLocallySentIds provides setOf("mine"),
        ) {
            androidx.compose.foundation.layout.Column(
                androidx.compose.ui.Modifier.padding(12.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
            ) {
                com.hermes.client.ui.chat.UserBubble(
                    msg = com.hermes.client.domain.ChatMessage(
                        id = "theirs",
                        role = com.hermes.client.domain.Role.USER,
                        text = "帮我看下这个报错",
                    ),
                    onEditResend = {}, onOpenImage = { _, _ -> }, onFileOpen = {}, onFileShare = {},
                )
                com.hermes.client.ui.chat.UserBubble(
                    msg = com.hermes.client.domain.ChatMessage(
                        id = "mine",
                        role = com.hermes.client.domain.Role.USER,
                        text = "我从手机补一句",
                    ),
                    onEditResend = {}, onOpenImage = { _, _ -> }, onFileOpen = {}, onFileShare = {},
                )
            }
        }
    }

    @Test fun botBubblesZh() = snap("bot-bubbles-zh") { BotBubblePair() }

    @Test fun botBubblesZhLargeFont() = snap("bot-bubbles-zh-fs13", fontScale = 1.3f) { BotBubblePair() }

    @Test fun botBubblesDark() = snap("bot-bubbles-dark", darkTheme = true) { BotBubblePair() }

    // ── Project glyphs. Hand-drawn 1.7dp strokes (docs/DESIGN.md §4), so a malformed path is
    // invisible to a unit test and obvious here — the first `repo` draft rendered as a bracket.
    @Test fun projectIcons() = snap("project-icons") {
        androidx.compose.foundation.layout.Row(
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(8.dp),
        ) {
            com.hermes.client.ui.components.PROJECT_ICONS.forEach { name ->
                androidx.compose.material3.Icon(
                    com.hermes.client.ui.components.projectIconFor(name),
                    contentDescription = name,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}
