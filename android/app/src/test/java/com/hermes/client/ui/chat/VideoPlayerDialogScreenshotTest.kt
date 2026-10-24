package com.hermes.client.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermes.client.data.network.HermesApiException
import com.hermes.client.ui.localization.*
import com.hermes.client.ui.theme.HermesTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-420dpi")
class VideoPlayerDialogScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private fun render(dark: Boolean, language: AppLanguage, scale: Float, name: String) {
        var downloads = 0
        compose.setContent {
            HermesTheme(darkTheme = dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalAppLanguage provides language,
                    LocalDensity provides Density(density.density, scale)) {
                    VideoPlayerDialog("视频试片.mp4", prepare = { throw HermesApiException(503, "busy", errorCode = "HR-FILE-013") },
                        download = { downloads++ }, onDismiss = {})
                }
            }
        }
        compose.waitUntil { compose.onAllNodesWithText("HR-FILE-013", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(if (language == AppLanguage.EN) "Retry" else "重试").assertIsDisplayed()
        compose.onNodeWithText(if (language == AppLanguage.EN) "Copy diagnostics" else "复制诊断").assertIsDisplayed()
        compose.onNodeWithText(if (language == AppLanguage.EN) "Download" else "下载").performClick()
        assertEquals(1, downloads)
        compose.onNode(isDialog()).captureRoboImage("screenshots/$name.png")
    }
    @Test fun complete_file_exposes_external_recovery_without_restarting_a_download() {
        var opened = 0; var downloads = 0
        compose.setContent { HermesTheme {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.EN) {
                VideoPlayerDialog("video.mp4", prepare = { throw HermesApiException(403, "fixture") },
                    download = { downloads++ }, onDismiss = {}, downloaded = true, openExternally = { opened++ })
            }
        } }
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Downloaded").assertIsNotEnabled()
        compose.onAllNodesWithText("Other player").onLast().performClick()
        assertEquals(1, opened); assertEquals(0, downloads)
    }

    @Test fun light() = render(false, AppLanguage.ZH, 1f, "video-player-error-light-zh")
    @Test fun darkLargeText() = render(true, AppLanguage.ZH, 1.3f, "video-player-error-dark-zh-fs13")
    @Test fun englishLargeText() = render(false, AppLanguage.EN, 1.3f, "video-player-error-en-fs13")
}
