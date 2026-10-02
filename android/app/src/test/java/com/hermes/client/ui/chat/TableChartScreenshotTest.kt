package com.hermes.client.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
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
class TableChartScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private fun snap(name: String, dark: Boolean, language: AppLanguage, fontScale: Float) {
        val controller = TableChartController("screenshot", "", "", "", ChartPreferences(ApplicationProvider.getApplicationContext()))
        compose.setContent {
            HermesTheme(darkTheme = dark) { CompositionLocalProvider(LocalAppLanguage provides language, LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                Surface { Column { TableChartToggle(controller); TableChartNotice(controller) } }
            } }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/$name.png")
        compose.onNodeWithText(if (language == AppLanguage.EN) "Chart" else "图表").performClick()
        compose.runOnIdle { assertEquals("chart", controller.view) }
        compose.onNodeWithText(if (language == AppLanguage.EN) "Table" else "表格").performClick()
        compose.runOnIdle { assertEquals("table", controller.view) }
    }
    @Test fun lightChinese() = snap("table-chart-toggle-zh", false, AppLanguage.ZH, 1f)
    @Test fun appDarkSystemLight() = snap("table-chart-toggle-dark-zh", true, AppLanguage.ZH, 1f)
    @Test fun englishLargeFont() = snap("table-chart-toggle-en-fs13", false, AppLanguage.EN, 1.3f)
}
