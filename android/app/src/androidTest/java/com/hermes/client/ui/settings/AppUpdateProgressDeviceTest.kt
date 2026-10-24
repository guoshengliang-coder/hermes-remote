package com.hermes.client.ui.settings

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hermes.client.ui.localization.*
import com.hermes.client.ui.theme.HermesTheme
import com.hermes.client.update.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Compose layout/semantics; no installer or production download is launched by this fixture. */
@RunWith(AndroidJUnit4::class)
class AppUpdateProgressDeviceTest {
    @Test fun lightChinese() = exercise(false, AppLanguage.ZH, 1f)
    @Test fun darkEnglishLargeText() = exercise(true, AppLanguage.EN, 1.3f)

    private fun exercise(dark: Boolean, language: AppLanguage, scale: Float) {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val version = UpdateVersion("9.0.0", 9999, "com.hermes.remote", "internal", "2026-10-24T00:00:00Z",
            "test.apk", "https://mrlgs.net/releases/test.apk", 36_999_632,
            "a".repeat(64), "b".repeat(64), 26, listOf("test"), "abcdef1")
        var task by mutableStateOf(UpdateTask(version, DownloadPhase.DOWNLOADING, percent = 0,
            downloadedBytes = 64 * 1024, totalBytes = version.sizeBytes, bytesPerSecond = 64 * 1024))
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                HermesTheme(darkTheme = dark) {
                    CompositionLocalProvider(LocalAppLanguage provides language,
                        LocalDensity provides Density(LocalDensity.current.density, scale)) {
                        AppUpdateContent(UpdateUiState(checkedOnce = true, task = task))
                    }
                }
            } }
            awaitText(if (language == AppLanguage.EN) "Speed: 64 KB/s" else "下载速度：64 KB/s")
            awaitText("64 KB / 35.3 MB")
            scenario.onActivity { task = task.copy(percent = 37, downloadedBytes = 13_700_000, bytesPerSecond = 1_572_864) }
            awaitText(if (language == AppLanguage.EN) "Speed: 1.5 MB/s" else "下载速度：1.5 MB/s")
            val image = requireNotNull(instrument.uiAutomation.takeScreenshot())
            File(instrument.targetContext.getExternalFilesDir(null), "update-progress-device-${language.name}.png")
                .outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            image.recycle()
            scenario.onActivity { task = task.copy(phase = DownloadPhase.PAUSED, bytesPerSecond = null) }
            awaitText(if (language == AppLanguage.EN) "Resume download" else "继续下载")
            assertFalse(visibleTexts().any { it.startsWith("Speed:") || it.startsWith("下载速度：") })
        }
    }

    private fun visibleTexts(): List<String> {
        val texts = mutableListOf<String>()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.isVisibleToUser) node.text?.toString()?.let(texts::add)
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.let(::visit)
        return texts
    }

    private fun awaitText(expected: String) {
        val end = android.os.SystemClock.elapsedRealtime() + 5_000
        while (android.os.SystemClock.elapsedRealtime() < end) {
            if (visibleTexts().any { expected in it }) return
            Thread.sleep(50)
        }
        fail("Missing visible update text: $expected; found ${visibleTexts()}")
    }
}
