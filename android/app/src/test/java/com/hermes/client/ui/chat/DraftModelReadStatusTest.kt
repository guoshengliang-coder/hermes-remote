package com.hermes.client.ui.chat

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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
@Config(sdk = [34], qualifiers = "w360dp-h800dp-420dpi")
class DraftModelReadStatusTest {
    @get:Rule val compose = createComposeRule()

    private fun verify(dark: Boolean, scale: Float, language: AppLanguage) {
        var retries = 0
        compose.setContent {
            HermesTheme(darkTheme = dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalAppLanguage provides language,
                    LocalDensity provides Density(density.density, scale)) {
                    Surface {
                        DraftModelReadStatus(DraftModelReadState(loading = false,
                            error = AppError(AppErrorCode.MODEL_DEFAULT_READ_FAILED, true)), onRetry = { retries++ })
                    }
                }
            }
        }
        compose.onNodeWithText("HR-MODEL-004", substring = true).assertIsDisplayed()
        compose.onNodeWithText(if (language == AppLanguage.ZH) "重试" else "Retry").assertIsDisplayed().performClick()
        assertEquals(1, retries)
        compose.onRoot().captureRoboImage("screenshots/hg263-model-read-error-${if (dark) "dark" else "light"}-$scale.png")
    }

    @Test fun lightChineseRetryIsReachable() = verify(false, 1f, AppLanguage.ZH)
    @Test fun appDarkSystemLightLargeEnglishRetryIsReachable() = verify(true, 1.3f, AppLanguage.EN)
}
