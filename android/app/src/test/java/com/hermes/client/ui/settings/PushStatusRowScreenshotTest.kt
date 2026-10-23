package com.hermes.client.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.notifications.push.PushStatus
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The 实时推送 status row (docs/DESIGN.md §5.10, HG-94), every state stacked in one frame. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-420dpi")
class PushStatusRowScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f),
    )

    private val states = listOf(
        PushStatus.Enabled,
        PushStatus.NotConfigured,
        PushStatus.NoGooglePlayServices,
        PushStatus.Inactive,
        PushStatus.Failed(AppError(AppErrorCode.PUSH_REGISTRATION_FAILED, retryable = true)),
    )

    private fun snap(name: String, dark: Boolean, language: AppLanguage) {
        compose.setContent {
            HermesTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalAppLanguage provides language) {
                    Surface {
                        Column {
                            states.forEachIndexed { index, status ->
                                if (index > 0) HorizontalDivider()
                                PushStatusRow(status, onRetry = {})
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/$name.png", roborazziOptions = options)
    }

    @Test fun lightChinese() = snap("push-status-row-zh", dark = false, language = AppLanguage.ZH)
    @Test fun darkChinese() = snap("push-status-row-dark-zh", dark = true, language = AppLanguage.ZH)
    @Test fun lightEnglish() = snap("push-status-row-en", dark = false, language = AppLanguage.EN)
}
