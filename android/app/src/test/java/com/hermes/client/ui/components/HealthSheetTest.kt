package com.hermes.client.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.onParent
import com.github.takahirom.roborazzi.captureRoboImage
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.data.network.GatewayHealth
import com.hermes.client.data.network.HealthRecheckState
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-420dpi")
class HealthSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun clickImmediatelyShowsBusyThenExplicitFailureAndAllowsAnotherCheck() {
        val state = mutableStateOf<HealthRecheckState>(HealthRecheckState.Idle)
        var checks = 0
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.ZH) {
                HermesTheme {
                    HealthSheet(GatewayHealth.GatewayUnreachable("HR-CONN-005"),
                        onRecheck = { checks++; state.value = HealthRecheckState.Checking("fixture-$checks") },
                        onDismiss = {}, recheckState = state.value)
                }
            }
        }
        compose.onNodeWithText("重新检查").performClick()
        assertEquals(1, checks)
        compose.onNodeWithText("检查中…").assertIsNotEnabled()
        compose.onNodeWithText("正在检查连接…").assertExists()
        compose.runOnIdle { state.value = HealthRecheckState.Finished("fixture-1", AppError(AppErrorCode.CONNECTOR_OFFLINE, true)) }
        compose.onNodeWithText("检查完成，仍需处理。", substring = true).assertExists()
        compose.onNodeWithText("HR-CONN-005").assertExists()
        compose.onNodeWithText("重新检查").assertIsEnabled().performClick()
        assertEquals(2, checks)
    }

    private fun snapshot(name: String, state: HealthRecheckState, dark: Boolean, english: Boolean, scale: Float = 1f) {
        val health = if (state is HealthRecheckState.Finished && state.error == null) GatewayHealth.Healthy("fixture", true, 20)
            else GatewayHealth.GatewayUnreachable("HR-CONN-005")
        val language = if (english) AppLanguage.EN else AppLanguage.ZH
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalAppLanguage provides language, LocalDensity provides Density(density.density, scale)) {
                HermesTheme(darkTheme = dark) {
                    HealthSheet(health, onRecheck = {}, onDismiss = {}, recheckState = state)
                }
            }
        }
        compose.mainClock.advanceTimeBy(1000)
        val title = healthStripLabel(health, !english) ?: if (english) "Connection status" else "连接状态"
        compose.onNodeWithText(title).onParent().captureRoboImage("screenshots/$name.png")
    }

    @Test fun screenshotCheckingLight() = snapshot("health-recheck-checking-light", HealthRecheckState.Checking("fixture"), false, false)
    @Test fun screenshotCheckingDarkLargeEnglish() = snapshot("health-recheck-checking-dark-en-fs13", HealthRecheckState.Checking("fixture"), true, true, 1.3f)
    @Test fun screenshotUnchangedFailureDarkLarge() = snapshot("health-recheck-failed-dark-fs13",
        HealthRecheckState.Finished("fixture", AppError(AppErrorCode.CONNECTOR_OFFLINE, true)), true, false, 1.3f)
    @Test fun screenshotRestoredLightEnglish() = snapshot("health-recheck-restored-light-en",
        HealthRecheckState.Finished("fixture", null), false, true)

}
