package com.hermes.client.ui.sessions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermes.client.domain.Session
import com.hermes.client.ui.InChinese
import com.hermes.client.ui.theme.HermesTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real list rows; system stays light while app theme and text scale change. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-notnight-420dpi")
@OptIn(ExperimentalRoborazziApi::class)
class DefaultProjectSublineScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun session(id: String, title: String, cwd: String, model: String? = "deepseek-v4.1-flash") =
        Session(id, title, model, null, 1, "personal", cwd = cwd)

    private fun verify(dark: Boolean, fontScale: Float) {
        val rows = listOf(
            session("root", "默认项目：近14天业务分析", "/"),
            session("markers", "默认项目：保留置顶与草稿", "/", null),
            session("named", "独立项目：知识整理", "/work/knowledge/sub"),
            session("folder", "独立目录：项目进展", "/work/other"),
            session("default", "普通默认目录：仅显示模型", "/Users/me"),
        )
        compose.setContent {
            InChinese {
                HermesTheme(darkTheme = dark) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(
                        LocalDensity provides Density(density.density, fontScale),
                        LocalProjectNames provides { s ->
                            if (s.id == "named") "个人知识库" else null
                        },
                    ) {
                        Surface {
                            Column(Modifier.width(360.dp)) {
                                rows.forEach { s ->
                                    SessionRow(
                                        session = s, isPinned = s.id == "markers", hasDraft = s.id == "markers",
                                        // Root is unknown on upgraded installs until another default create.
                                        defaultProjectPath = if (s.id == "default") "/Users/me" else null,
                                        onMoveToProject = {}, onOpen = {}, onTogglePin = {},
                                        onRename = {}, onArchive = {}, onDelete = {},
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("/", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("me", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("个人知识库", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("other", useUnmergedTree = true).assertIsDisplayed()
        // HG-262: all five roles have trailing metadata; two independent projects also separate their models.
        compose.onAllNodesWithText(" · ", useUnmergedTree = true).assertCountEquals(7)
        compose.onAllNodesWithText("deepseek-v4.1-flash", useUnmergedTree = true).assertCountEquals(4)
        compose.onNodeWithContentDescription("已置顶", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("草稿", useUnmergedTree = true).assertIsDisplayed()
        val target = File("build/outputs/hg253/subline-${if (dark) "dark" else "light"}-$fontScale.png")
        target.parentFile.mkdirs()
        compose.onRoot().captureRoboImage(target.absolutePath,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
    }

    @Test fun light() = verify(dark = false, fontScale = 1f)
    @Test fun app_dark_system_light() = verify(dark = true, fontScale = 1f)
    @Test fun large_text_light() = verify(dark = false, fontScale = 1.3f)
    @Test fun large_text_app_dark_system_light() = verify(dark = true, fontScale = 1.3f)
}
