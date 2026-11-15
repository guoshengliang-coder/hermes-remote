package com.hermes.client.ui.sessions

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.*
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.theme.HermesTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
@OptIn(ExperimentalRoborazziApi::class)
class SessionArchiveErrorDialogTest {
    @get:Rule val compose = createComposeRule()
    private fun show(language: AppLanguage, dark: Boolean, fontScale: Float = 1f) {
        var reads = 0
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(LocalAppLanguage provides language, androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, fontScale)) {
                HermesTheme(darkTheme = dark) {
                    SessionArchiveErrorDialog(AppError(AppErrorCode.SESSION_ARCHIVE_UNCONFIRMED, false,
                        "Authorization: Bearer private-secret", "session_archive"), {}, { reads++ })
                }
            }
        }
        val details = if (language == AppLanguage.ZH) "查看详情" else "View details"
        val refresh = if (language == AppLanguage.ZH) "刷新状态" else "Refresh state"
        compose.onNodeWithText(details).performClick()
        compose.onAllNodes(hasText("private-secret", substring = true)).assertCountEquals(0)
        compose.onNodeWithText("code=HR-SESS-025", substring = true).assertIsDisplayed()
        compose.onNodeWithText(if (language == AppLanguage.ZH) "重试" else "Retry").assertDoesNotExist()
        val target = File("build/outputs/hg257/archive-error-${language.name}-$dark.png").apply { parentFile?.mkdirs() }
        compose.onNode(isDialog()).captureRoboImage(target.absolutePath,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        compose.onNodeWithText(refresh).performClick()
        assertEquals(1, reads)
    }
    @Test fun chinese_light() = show(AppLanguage.ZH, false)
    @Test fun english_dark() = show(AppLanguage.EN, true)
    @Test fun chinese_dark_large() = show(AppLanguage.ZH, true, 1.4f)
}
