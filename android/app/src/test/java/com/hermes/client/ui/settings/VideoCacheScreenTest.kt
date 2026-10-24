package com.hermes.client.ui.settings

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermes.client.data.error.videoCacheError
import com.hermes.client.data.video.*
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
class VideoCacheScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun capacity_selection_and_explicit_clear_confirmation_preserve_download_explanation() {
        var selected: VideoCacheCapacity? = null; var clears = 0
        compose.setContent { HermesTheme { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.ZH) {
            VideoCacheContent(VideoCacheStats(loaded = true, usedBytes = GIB), false, null, {}, { selected = it }, { clears++ }, {})
        } } }
        compose.onNodeWithText("5GB").performClick()
        assertEquals(VideoCacheCapacity.LARGE, selected)
        compose.onNodeWithText("清理预览缓存").performClick()
        compose.onNodeWithText("取消").performClick(); assertEquals(0, clears)
        compose.onNodeWithText("清理预览缓存").performClick()
        compose.onNodeWithText("完整下载的文件不会删除", substring = true).assertIsDisplayed()
        compose.onNodeWithText("清理", substring = false).performClick(); assertEquals(1, clears)
    }
    @Test fun pending_cleanup_and_loading_disable_repeat_clear() {
        compose.setContent { HermesTheme { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.EN) {
            VideoCacheContent(VideoCacheStats(loaded = true, usedBytes = 40, clearPending = true), false, null, {}, {}, {}, {})
        } } }
        compose.onNodeWithText("Clear preview cache").assertIsNotEnabled()
        compose.onNodeWithText("Unused cache has been cleared.", substring = true).assertIsDisplayed()
    }
    private fun render(dark: Boolean, language: AppLanguage, scale: Float, pressure: Boolean, name: String) {
        compose.setContent { HermesTheme(darkTheme = dark) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalAppLanguage provides language, LocalDensity provides Density(density.density, scale)) {
                VideoCacheContent(VideoCacheStats(true, GIB * 3 / 2, 3 * GIB, pressure), false, null, {}, {}, {}, {})
            }
        } }
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }
    @Test fun light() = render(false, AppLanguage.ZH, 1f, false, "video-cache-light-zh")
    @Test fun dark_large() = render(true, AppLanguage.ZH, 1.3f, true, "video-cache-dark-zh-fs13")
    @Test fun english_large() = render(false, AppLanguage.EN, 1.3f, false, "video-cache-en-fs13")
    @Test fun management_error_exposes_retry_and_redacted_diagnostics() {
        var retries = 0
        compose.setContent { HermesTheme { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.EN) {
            VideoCacheContent(VideoCacheStats(), false, videoCacheError(java.io.IOException("token=secret /private/file")), {}, {}, {}, { retries++ })
        } } }
        compose.onNodeWithText("Retry").performScrollTo().performClick()
        assertEquals(1, retries)
        compose.onNodeWithText("View details").performScrollTo().performClick()
        compose.onNodeWithText("Copy diagnostics").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("secret", substring = true).assertCountEquals(0)
    }
}
