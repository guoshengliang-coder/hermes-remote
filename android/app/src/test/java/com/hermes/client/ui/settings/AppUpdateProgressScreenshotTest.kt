package com.hermes.client.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermes.client.ui.localization.*
import com.hermes.client.ui.theme.HermesTheme
import com.hermes.client.update.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-420dpi")
class AppUpdateProgressScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val version = UpdateVersion("9.0.0", 9999, "com.hermes.remote", "internal", "2026-10-24T00:00:00Z",
        "test.apk", "https://mrlgs.net/releases/test.apk", 36_999_632,
        "a".repeat(64), "b".repeat(64), 26, listOf("test"), "abcdef1")

    private fun render(dark: Boolean, language: AppLanguage, fontScale: Float, rate: Long?, name: String) {
        compose.setContent {
            HermesTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalAppLanguage provides language,
                    LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    AppUpdateContent(UpdateUiState(checkedOnce = true, task = UpdateTask(version,
                        DownloadPhase.DOWNLOADING, percent = 37, downloadedBytes = 13_700_000,
                        totalBytes = version.sizeBytes, bytesPerSecond = rate)))
                }
            }
        }
        compose.onNodeWithText(when {
            rate == null -> if (language == AppLanguage.EN) "Measuring speed…" else "正在测速…"
            language == AppLanguage.EN -> "Speed: 1.6 MB/s"
            else -> "下载速度：1.6 MB/s"
        }).assertIsDisplayed()
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    @Test fun lightChinese() = render(false, AppLanguage.ZH, 1f, 1_572_864, "update-progress-light-zh")
    @Test fun darkChinese() = render(true, AppLanguage.ZH, 1f, 1_572_864, "update-progress-dark-zh")
    @Test fun englishLargeText() = render(false, AppLanguage.EN, 1.3f, 1_572_864, "update-progress-en-fs13")
    @Test fun beforeSpeedSample() = render(false, AppLanguage.ZH, 1f, null, "update-progress-measuring")
}
