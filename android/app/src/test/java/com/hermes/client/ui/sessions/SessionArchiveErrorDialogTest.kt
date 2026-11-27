package com.hermes.client.ui.sessions

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    private fun show(language: AppLanguage, dark: Boolean, fontScale: Float = 1f, code: AppErrorCode = AppErrorCode.SESSION_ARCHIVE_UNCONFIRMED) {
        var reads = 0
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(LocalAppLanguage provides language, androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, fontScale)) {
                HermesTheme(darkTheme = dark) {
                    SessionArchiveErrorDialog(AppError(code, false,
                        "Authorization: Bearer private-secret", "session_archive"), {}, { reads++ })
                }
            }
        }
        val details = if (language == AppLanguage.ZH) "查看详情" else "View details"
        val refresh = if (language == AppLanguage.ZH) "刷新状态" else "Refresh state"
        compose.onNodeWithText(details).performClick()
        compose.onAllNodes(hasText("private-secret", substring = true)).assertCountEquals(0)
        compose.onNodeWithText("code=${code.value}", substring = true).assertIsDisplayed()
        compose.onNodeWithText(if (language == AppLanguage.ZH) "重试" else "Retry").assertDoesNotExist()
        val target = File("build/outputs/hg257/archive-error-${language.name}-$dark-${code.value}.png").apply { parentFile?.mkdirs() }
        compose.onNode(isDialog()).captureRoboImage(target.absolutePath,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        compose.onNodeWithText(refresh).performClick()
        assertEquals(1, reads)
    }
    @Test fun mutation_error_has_generic_title_and_read_only_refresh() {
        show(AppLanguage.ZH, false, code = AppErrorCode.SESSION_MUTATION_UNCONFIRMED)
        compose.onNodeWithText("会话操作").assertIsDisplayed()
    }
    @Test fun busy_delete_requires_read_only_refresh_and_a_new_confirmation() {
        var writes = 0
        var reads = 0
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.ZH) {
                HermesTheme(darkTheme = false) {
                    val scope = rememberCoroutineScope()
                    val feedback = remember(scope) { SessionActionFeedback(scope) }
                    var open by remember { mutableStateOf(true) }
                    TextButton(onClick = { open = true }) { Text("打开删除确认") }
                    if (open) SessionActionDialog(
                        feedback = feedback,
                        title = "删除会话？",
                        action = "删除",
                        pendingLabel = "删除中…",
                        destructive = true,
                        onDismiss = { open = false },
                        onConfirm = {
                            feedback.submit({
                                if (++writes == 1) AppError(AppErrorCode.SESSION_DELETE_IN_USE, false)
                                else null
                            }) { open = false }
                        },
                        onRefresh = { reads++ },
                    ) { Text("测试会话") }
                }
            }
        }

        compose.onNodeWithText("删除").performClick()
        compose.onNodeWithText("此会话仍在使用", substring = true).assertIsDisplayed()
        compose.onNodeWithText("删除").assertIsNotEnabled()
        val target = File("build/outputs/hg271/delete-in-use.png").apply { parentFile?.mkdirs() }
        compose.onNode(isDialog()).captureRoboImage(target.absolutePath,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        compose.onNodeWithText("刷新状态").performClick()
        compose.onNodeWithText("删除会话？").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, reads); assertEquals(1, writes) }
        compose.onNodeWithText("打开删除确认").performClick()
        compose.onNodeWithText("删除").performClick()
        compose.onNodeWithText("删除会话？").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, writes) }
    }

    @Test fun chinese_light() = show(AppLanguage.ZH, false)
    @Test fun english_dark() = show(AppLanguage.EN, true)
    @Test fun chinese_dark_large() = show(AppLanguage.ZH, true, 1.4f)
}
