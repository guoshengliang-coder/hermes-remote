package com.hermes.client.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-420dpi")
class OutputHapticsSettingScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun snap(name: String, dark: Boolean, language: AppLanguage, fontScale: Float) {
        var requested: Boolean? = null
        compose.setContent {
            HermesTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalAppLanguage provides language, LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    Surface { Column {
                        OutputHapticsSetting(OutputHapticsSettingsState(enabled = true, loaded = true), { requested = it }, {})
                        OutputHapticsSetting(OutputHapticsSettingsState(error = AppError(AppErrorCode.OUTPUT_HAPTICS_SETTINGS_FAILED, true)), {}, {})
                    } }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/$name.png")
        compose.onAllNodes(isToggleable(), useUnmergedTree = false)[0].assertIsOn().performClick()
        assertEquals(false, requested)
    }

    @Test fun lightChinese() = snap("output-haptics-zh", false, AppLanguage.ZH, 1f)
    @Test fun appDarkSystemLight() = snap("output-haptics-dark-zh", true, AppLanguage.ZH, 1f)
    @Test fun englishLargeFont() = snap("output-haptics-en-fs13", false, AppLanguage.EN, 1.3f)
}
