package com.hermes.client.ui.chat

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-420dpi")
class VoiceHoldButtonScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun snap(name: String, dark: Boolean, fontScale: Float) {
        compose.setContent {
            HermesTheme(darkTheme = dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    Surface {
                        VoiceHoldButton("按住说话", enabled = true, onDown = {}, onZone = {}, onRelease = {})
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(
            "screenshots/$name.png",
            roborazziOptions = RoborazziOptions(
                compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f),
            ),
        )
    }

    @Test fun light() = snap("voice-hold-light", dark = false, fontScale = 1f)
    @Test fun darkLargeText() = snap("voice-hold-dark-large", dark = true, fontScale = 1.3f)

    @Test fun upperRightReleaseEditsText() {
        val actions = mutableListOf<VoiceReleaseAction>()
        var pressed = 0
        compose.setContent {
            HermesTheme {
                VoiceHoldButton(
                    "按住说话", enabled = true,
                    onDown = { pressed++ },
                    onZone = { actions += it },
                    onRelease = { actions += it },
                )
            }
        }
        compose.onNodeWithTag("voice-hold").performTouchInput {
            down(center)
            moveTo(Offset(width * 0.9f, -height * 2f))
            up()
        }
        assertEquals(1, pressed)
        assertEquals(listOf(VoiceReleaseAction.EDIT, VoiceReleaseAction.EDIT), actions)
    }
}
