package com.hermes.client.ui.chat

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.RoborazziOptions
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

/**
 * HG-144: the waiting banner used to be a dead end — shown while waiting, tappable by nothing,
 * dismissible by nothing. It now carries its own exit ("tap to stop waiting") for the released
 * state; the held state keeps the slide-zone hints and must not respond to taps.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-420dpi")
class VoiceRecognitionBannerTest {
    @get:Rule val compose = createComposeRule()

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f),
    )

    private fun snap(name: String, dark: Boolean, fontScale: Float, held: Boolean) {
        compose.setContent {
            HermesTheme(darkTheme = dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalAppLanguage provides AppLanguage.ZH,
                    LocalDensity provides Density(density.density, fontScale),
                ) {
                    Surface {
                        VoiceRecognitionBanner(
                        language = AppLanguage.ZH,
                        held = held,
                        waiting = !held,
                        transcript = "",
                        cancelZone = false,
                        editZone = false,
                        onDismissWaiting = {},
                    )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/$name.png", roborazziOptions = options)
    }

    @Test fun waitingLight() = snap("voice-banner-waiting-zh-360", dark = false, fontScale = 1f, held = false)
    @Test fun waitingDarkLargeFont() = snap("voice-banner-waiting-dark-zh-360-fs13", dark = true, fontScale = 1.3f, held = false)
    @Test fun heldLight() = snap("voice-banner-held-zh-360", dark = false, fontScale = 1f, held = true)

    @Test fun tappingWaitingBannerDismisses() {
        var dismissals = 0
        compose.setContent {
            HermesTheme {
                Surface {
                    VoiceRecognitionBanner(
                        language = AppLanguage.ZH,
                        held = false,
                        waiting = true,
                        transcript = "",
                        cancelZone = false,
                        editZone = false,
                        onDismissWaiting = { dismissals++ },
                    )
                }
            }
        }
        compose.onNodeWithText("正在完成识别…").performClick()
        assertEquals(1, dismissals)
    }

    @Test fun tappingHeldBannerDoesNothing() {
        var dismissals = 0
        compose.setContent {
            HermesTheme {
                Surface {
                    VoiceRecognitionBanner(
                        language = AppLanguage.ZH,
                        held = true,
                        waiting = false,
                        transcript = "",
                        cancelZone = false,
                        editZone = false,
                        onDismissWaiting = { dismissals++ },
                    )
                }
            }
        }
        compose.onNodeWithText("正在听…").performClick()
        assertEquals(0, dismissals)
    }

    @Test fun waitingBannerShowsPartialTranscriptWhenPresent() {
        compose.setContent {
            HermesTheme {
                Surface {
                    VoiceRecognitionBanner(
                        language = AppLanguage.ZH,
                        held = false,
                        waiting = true,
                        transcript = "今天下午的会议",
                        cancelZone = false,
                        editZone = false,
                        onDismissWaiting = {},
                    )
                }
            }
        }
        compose.onNodeWithText("今天下午的会议").assertExists()
    }
}
