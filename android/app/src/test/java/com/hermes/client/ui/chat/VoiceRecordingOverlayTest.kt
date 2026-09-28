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
 * HG-146: the recording surface replaced the old above-composer banner. It still carries the HG-144
 * exit — while waiting, a tap anywhere cancels and keeps any partial transcript — and the held
 * state must not respond to taps.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-420dpi")
class VoiceRecordingOverlayTest {
    @get:Rule val compose = createComposeRule()

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f),
    )

    private fun snap(
        name: String,
        dark: Boolean,
        fontScale: Float,
        held: Boolean,
        waiting: Boolean,
        transcript: String,
        cancelZone: Boolean = false,
        editZone: Boolean = false,
    ) {
        compose.setContent {
            HermesTheme(darkTheme = dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalAppLanguage provides AppLanguage.ZH,
                    LocalDensity provides Density(density.density, fontScale),
                ) {
                    Surface {
                        VoiceRecordingOverlay(
                            language = AppLanguage.ZH,
                            held = held,
                            waiting = waiting,
                            transcript = transcript,
                            cancelZone = cancelZone,
                            editZone = editZone,
                            elapsedMs = 12_400L,
                            onDismissWaiting = {},
                            onTargetsMeasured = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/$name.png", roborazziOptions = options)
    }

    @Test fun heldLight() = snap(
        "voice-recording-held-zh-360", dark = false, fontScale = 1f,
        held = true, waiting = false, transcript = "",
    )

    @Test fun heldWithTranscriptLight() = snap(
        "voice-recording-held-transcript-zh-360", dark = false, fontScale = 1f,
        held = true, waiting = false, transcript = "把刚才生成的可视化图表发给",
    )

    @Test fun heldEditZoneDarkLargeFont() = snap(
        "voice-recording-held-edit-dark-zh-360-fs13", dark = true, fontScale = 1.3f,
        held = true, waiting = false, transcript = "", editZone = true,
    )

    @Test fun waitingLight() = snap(
        "voice-recording-waiting-zh-360", dark = false, fontScale = 1f,
        held = false, waiting = true, transcript = "",
    )

    @Test fun waitingDarkLargeFont() = snap(
        "voice-recording-waiting-dark-zh-360-fs13", dark = true, fontScale = 1.3f,
        held = false, waiting = true, transcript = "今天下午的会议",
    )

    private fun overlay(held: Boolean, waiting: Boolean, transcript: String, onDismiss: () -> Unit) {
        compose.setContent {
            HermesTheme {
                Surface {
                    VoiceRecordingOverlay(
                        language = AppLanguage.ZH,
                        held = held,
                        waiting = waiting,
                        transcript = transcript,
                        cancelZone = false,
                        editZone = false,
                        elapsedMs = 12_400L,
                        onDismissWaiting = onDismiss,
                        onTargetsMeasured = {},
                    )
                }
            }
        }
    }

    @Test fun tappingWaitingOverlayDismisses() {
        var dismissals = 0
        overlay(held = false, waiting = true, transcript = "") { dismissals++ }
        compose.onNodeWithText("正在完成识别…").performClick()
        assertEquals(1, dismissals)
    }

    @Test fun tappingHeldOverlayDoesNothing() {
        var dismissals = 0
        overlay(held = true, waiting = false, transcript = "") { dismissals++ }
        compose.onNodeWithText("正在聆听").performClick()
        assertEquals(0, dismissals)
    }

    @Test fun partialTranscriptIsShownWhileHeld() {
        overlay(held = true, waiting = false, transcript = "今天下午的会议") {}
        compose.onNodeWithText("今天下午的会议", substring = true).assertExists()
    }

    @Test fun waitingOverlayShowsPartialTranscript() {
        overlay(held = false, waiting = true, transcript = "今天下午的会议") {}
        compose.onNodeWithText("今天下午的会议", substring = true).assertExists()
    }
}
