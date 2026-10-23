package com.hermes.client.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermes.client.domain.ChatFile
import com.hermes.client.domain.ArtifactMetadata
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import com.hermes.client.data.downloads.ArtifactDownloadStatus
import com.hermes.client.data.downloads.ArtifactDownloadTask
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-420dpi")
class ChatFileScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun snap(name: String, dark: Boolean, language: AppLanguage, scale: Float) {
        val files = listOf(
            ChatFile("video", "视频试片.mp4", "video/mp4", 68L * 1024 * 1024, remotePath = "/tmp/video.mp4"),
            ChatFile("large", "国庆日本游-技术试片-v1.mp4", "video/mp4", 126L * 1024 * 1024, remotePath = "/tmp/large.mp4"),
            ChatFile("doc", "项目说明.md", "text/markdown", 1500, remotePath = "/tmp/a.md"),
            ChatFile("web", "网页报告.html", "text/html", remotePath = "/tmp/report.html"),
            ChatFile("pdf", "文档.pdf", "application/pdf", 64L * 1024, remotePath = "/tmp/doc.pdf"),
        )
        val metadata = ChatFileMetadataState { file -> file.sizeBytes?.let { ArtifactMetadata(it, 8L * 1024 * 1024 * 1024, true) } }
        val tasks = listOf(
            com.hermes.client.data.downloads.ArtifactDownloadTask("video", "owner", "mac", "/tmp/video.mp4", "视频试片.mp4", "video/mp4",
                status = com.hermes.client.data.downloads.ArtifactDownloadStatus.RUNNING, sizeBytes = 68L * 1024 * 1024, downloaded = 25L * 1024 * 1024),
            com.hermes.client.data.downloads.ArtifactDownloadTask("doc", "owner", "mac", "/tmp/a.md", "项目说明.md", "text/markdown",
                status = com.hermes.client.data.downloads.ArtifactDownloadStatus.COMPLETE, sizeBytes = 1500, downloaded = 1500),
            com.hermes.client.data.downloads.ArtifactDownloadTask("pdf", "owner", "mac", "/tmp/doc.pdf", "文档.pdf", "application/pdf",
                status = com.hermes.client.data.downloads.ArtifactDownloadStatus.PAUSED, sizeBytes = 64L * 1024, downloaded = 32L * 1024),
        )
        compose.setContent {
            HermesTheme(darkTheme = dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalAppLanguage provides language, LocalChatFileMetadata provides metadata,
                    LocalChatFileDownloads provides ChatFileDownloads(tasks, {}, {}, {}),
                    LocalDensity provides Density(density.density, scale)) {
                    Surface { Column(Modifier.padding(12.dp)) { ChatFileList(files, {}, {}) } }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(if (language == AppLanguage.EN) "Play online or download" else "可在线播放，也可下载").assertIsDisplayed()
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    @Test fun light() = snap("attachment-metadata-light-zh", false, AppLanguage.ZH, 1f)
    @Test fun darkLargeFont() = snap("attachment-metadata-dark-zh-fs13", true, AppLanguage.ZH, 1.3f)
    @Test fun englishLargeFont() = snap("attachment-metadata-en-fs13", false, AppLanguage.EN, 1.3f)

    private val html = ChatFile("html", "V3-旋转车与片尾修改确认-轻量版.html", "text/html",
        2_443_000, remotePath = "/tmp/report.html")

    private fun htmlStates(dark: Boolean, language: AppLanguage, scale: Float, width: Int) {
        val tasks = androidx.compose.runtime.mutableStateOf(emptyList<ArtifactDownloadTask>())
        val metadata = ChatFileMetadataState { ArtifactMetadata(html.sizeBytes!!, 8L * 1024 * 1024 * 1024, true) }
        var downloads = 0; var pauses = 0; var cancels = 0; var opens = 0; var shares = 0
        compose.setContent { HermesTheme(darkTheme = dark) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalAppLanguage provides language, LocalChatFileMetadata provides metadata,
                LocalDensity provides Density(density.density, scale),
                LocalChatFileDownloads provides ChatFileDownloads(tasks.value, { downloads++ }, { pauses++ }, { cancels++ })) {
                Surface(Modifier.width(width.dp)) {
                    Column(Modifier.padding(12.dp)) { ChatFileList(listOf(html), { opens++ }, { shares++ }) }
                }
            }
        } }
        compose.waitUntil { metadata.info(html).independentDownloads }
        val shareLabel = if (language == AppLanguage.EN) "Share file" else "分享文件"
        fun action(zh: String, en: String) = compose.onNodeWithText(if (language == AppLanguage.EN) en else zh)
        val initialShare = compose.onNodeWithContentDescription(shareLabel).fetchSemanticsNode().boundsInRoot
        val initialTitle = compose.onNodeWithText(html.name, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(html.name).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("Long filenames should use the second line", 2, layouts.single().lineCount)
        assertFalse(layouts.single().hasVisualOverflow)
        action("下载", "Download").performClick()
        assertEquals(1, downloads)
        compose.onNodeWithText(html.name).performClick()
        compose.onNodeWithContentDescription(shareLabel).performClick()
        assertEquals(1, opens); assertEquals(1, shares)
        compose.onRoot().captureRoboImage("screenshots/attachment-html-${language.name}-$dark-$scale-$width-idle.png")
        for (status in ArtifactDownloadStatus.entries) {
            tasks.value = listOf(ArtifactDownloadTask("task", "owner", "mac", html.remotePath!!, html.name, html.mimeType,
                status = status, sizeBytes = html.sizeBytes, downloaded = 262_144,
                errorCode = if (status == ArtifactDownloadStatus.FAILED) "HR-FILE-006" else null))
            compose.waitForIdle()
            val share = compose.onNodeWithContentDescription(shareLabel)
            assertEquals("Share must stay aligned when $status expands the card", initialShare.top, share.fetchSemanticsNode().boundsInRoot.top, 0.5f)
            assertEquals(initialTitle.top, compose.onNodeWithText(html.name, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top, 0.5f)
            when (status) {
                ArtifactDownloadStatus.QUEUED, ArtifactDownloadStatus.RUNNING -> {
                    share.assertIsNotEnabled()
                    action("暂停", "Pause").performClick()
                    action("取消", "Cancel").performClick()
                }
                ArtifactDownloadStatus.PAUSED, ArtifactDownloadStatus.WAITING -> {
                    action("继续", "Resume").performClick()
                    action("取消", "Cancel").performClick()
                }
                ArtifactDownloadStatus.FAILED -> {
                    compose.onNodeWithText("HR-FILE-006", substring = true).assertIsDisplayed()
                    action("继续", "Resume").performClick()
                    action("取消", "Cancel").performClick()
                }
                ArtifactDownloadStatus.CANCELLED -> action("下载", "Download").performClick()
                ArtifactDownloadStatus.COMPLETE -> {
                    action("已下载", "Downloaded").assertIsDisplayed()
                    action("下载", "Download").assertDoesNotExist()
                    compose.onNodeWithText(html.name).performClick()
                    share.assertIsEnabled().performClick()
                }
            }
            compose.onRoot().captureRoboImage("screenshots/attachment-html-${language.name}-$dark-$scale-$width-${status.name}.png")
        }
        assertEquals(5, downloads); assertEquals(2, pauses); assertEquals(5, cancels)
        assertEquals(2, opens); assertEquals(2, shares)
    }

    @Test fun html_download_states_keep_the_header_aligned() = htmlStates(false, AppLanguage.ZH, 1f, 360)
    @Test fun html_states_in_app_dark_on_a_light_system_with_large_font() = htmlStates(true, AppLanguage.ZH, 1.3f, 320)
    @Test fun html_states_in_english_with_large_font() = htmlStates(false, AppLanguage.EN, 1.3f, 320)

    @Test fun discovered_oversize_has_no_open_action_and_disables_share_before_download() {
        var opens = 0
        var shares = 0
        val file = ChatFile("video", "video.mp4", "video/mp4", remotePath = "/tmp/video.mp4")
        val metadata = ChatFileMetadataState { ArtifactMetadata(126L * 1024 * 1024) }
        compose.setContent {
            HermesTheme {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.EN, LocalChatFileMetadata provides metadata) {
                    ChatFileList(listOf(file), { opens++ }, { shares++ })
                }
            }
        }
        compose.waitUntil { metadata.info(file).tooLarge }
        compose.onNodeWithText("126.0 MiB", substring = true).assertIsDisplayed()
        compose.onNodeWithText("HR-FILE-004", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Share file").assertIsNotEnabled()
        compose.onAllNodes(hasClickAction() and isEnabled()).assertCountEquals(0)
        assertEquals(0, opens)
        assertEquals(0, shares)
    }

    @Test fun normal_unknown_size_retains_open_and_share_and_shows_unknown() {
        var opens = 0
        var shares = 0
        val file = ChatFile("doc", "notes.md", "text/markdown", remotePath = "/tmp/notes.md")
        compose.setContent {
            HermesTheme {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.EN) {
                    ChatFileList(listOf(file), { opens++ }, { shares++ })
                }
            }
        }
        compose.onNodeWithText("MD · Size unknown").assertIsDisplayed()
        compose.onNodeWithText("notes.md").performClick()
        compose.onNodeWithContentDescription("Share file").assertIsEnabled().performClick()
        assertEquals(1, opens)
        assertEquals(1, shares)
    }

    @Test fun large_video_plays_online_but_shares_only_when_complete() {
        val file = ChatFile("video", "large.mp4", "video/mp4", 126L * 1024 * 1024, remotePath = "/tmp/large.mp4")
        val metadata = ChatFileMetadataState { ArtifactMetadata(file.sizeBytes!!, 8L * 1024 * 1024 * 1024, true) }
        val tasks = androidx.compose.runtime.mutableStateOf(emptyList<com.hermes.client.data.downloads.ArtifactDownloadTask>())
        var opened = 0; var shared = 0; var downloaded = 0
        compose.setContent { HermesTheme {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.EN, LocalChatFileMetadata provides metadata,
                LocalChatFileDownloads provides ChatFileDownloads(tasks.value, { downloaded++ }, {}, {})) {
                ChatFileList(listOf(file), { opened++ }, { shared++ })
            }
        } }
        compose.waitUntil { metadata.info(file).independentDownloads }
        compose.onNodeWithContentDescription("Share file").assertIsNotEnabled()
        compose.onNodeWithText("large.mp4").performClick()
        assertEquals(1, opened); assertEquals(0, shared)
        compose.onNodeWithText("Download").performClick()
        assertEquals(1, downloaded)
        tasks.value = listOf(com.hermes.client.data.downloads.ArtifactDownloadTask("id", "owner", "mac", file.remotePath!!,
            file.name, file.mimeType, status = com.hermes.client.data.downloads.ArtifactDownloadStatus.COMPLETE,
            sizeBytes = file.sizeBytes, downloaded = file.sizeBytes!!))
        compose.waitForIdle()
        compose.onNodeWithText("Downloaded").assertIsDisplayed()
        compose.onNodeWithText("large.mp4").performClick()
        compose.onNodeWithContentDescription("Share file").assertIsEnabled().performClick()
        assertEquals(2, opened); assertEquals(1, shared)
    }

    @Test fun large_document_keeps_download_first_while_video_can_stream() {
        val file = ChatFile("doc", "large.pdf", "application/pdf", 126L * 1024 * 1024, remotePath = "/large.pdf")
        val metadata = ChatFileMetadataState { ArtifactMetadata(file.sizeBytes!!, 8L * 1024 * 1024 * 1024, true) }
        var downloaded = 0; var opened = 0
        compose.setContent { HermesTheme {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.EN, LocalChatFileMetadata provides metadata,
                LocalChatFileDownloads provides ChatFileDownloads(emptyList(), { downloaded++ }, {}, {})) {
                ChatFileList(listOf(file), { opened++ }, {})
            }
        } }
        compose.waitUntil { metadata.info(file).independentDownloads }
        compose.onNodeWithText("Download before opening or sharing").assertIsDisplayed()
        compose.onNodeWithContentDescription("Share file").assertIsNotEnabled()
        compose.onNodeWithText("large.pdf").performTouchInput { click() }
        assertEquals(0, opened)
        compose.onNodeWithText("Download").performClick()
        assertEquals(1, downloaded)
    }

    @Test fun a_restored_waiting_task_can_resume_or_cancel_without_a_live_system_job() {
        val file = ChatFile("a", "large.mp4", "video/mp4", 126L * 1024 * 1024, remotePath = "/tmp/a.mp4")
        val task = com.hermes.client.data.downloads.ArtifactDownloadTask("id", "owner", "mac", file.remotePath!!,
            file.name, file.mimeType, status = com.hermes.client.data.downloads.ArtifactDownloadStatus.WAITING,
            sizeBytes = file.sizeBytes, downloaded = 0)
        var resumed = 0; var cancelled = 0
        compose.setContent { HermesTheme {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.EN,
                LocalChatFileDownloads provides ChatFileDownloads(listOf(task), { resumed++ }, {}, { cancelled++ })) {
                ChatFileList(listOf(file), {}, {})
            }
        } }
        compose.onNodeWithText("Resume").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, resumed); assertEquals(1, cancelled)
    }
}
