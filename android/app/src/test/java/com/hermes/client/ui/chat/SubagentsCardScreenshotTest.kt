package com.hermes.client.ui.chat

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermes.client.data.repository.SubagentPhase
import com.hermes.client.data.repository.SubagentStatus
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-420dpi")
class SubagentsCardScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f),
    )

    private fun snap(name: String, dark: Boolean, fontScale: Float) {
        compose.setContent {
            HermesTheme(darkTheme = dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalAppLanguage provides AppLanguage.ZH,
                    LocalDensity provides Density(density.density, fontScale),
                ) {
                    Surface {
                        SubagentsCard(listOf(
                            SubagentStatus(
                                id = "a", goal = "审计 Hermes Go 与 MissionGo 的交互流程",
                                status = SubagentPhase.RUNNING, currentTool = "read_file",
                            ),
                            SubagentStatus(
                                id = "b", goal = "检查自动化测试结果",
                                status = SubagentPhase.COMPLETED, result = "已完成检查",
                            ),
                        ))
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/$name.png", roborazziOptions = options)
    }

    @Test fun light() = snap("subagents-status-light-zh-360", dark = false, fontScale = 1f)
    @Test fun darkLargeFont() = snap("subagents-status-dark-zh-360-fs13", dark = true, fontScale = 1.3f)

    @Test fun failedChildUsesLocalizedCodeWithoutRawResult() {
        compose.setContent {
            HermesTheme(darkTheme = false) {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.EN) {
                    SubagentsCard(listOf(SubagentStatus(
                        id = "failed", goal = "Audit code", status = SubagentPhase.FAILED,
                        result = "token=secret",
                    )))
                }
            }
        }
        compose.onNodeWithText("Subagent did not finish. Ask again in the composer. SESS-019", substring = true)
            .assertExists()
        compose.onNodeWithText("secret", substring = true).assertDoesNotExist()
    }
}
