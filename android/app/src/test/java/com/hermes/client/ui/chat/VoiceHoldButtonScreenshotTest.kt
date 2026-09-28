package com.hermes.client.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
                        VoiceHoldButton(
                            "按住说话", enabled = true, targets = null,
                            onDown = {}, onZone = {}, onRelease = {},
                        )
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

    /**
     * HG-153: the targets arrive in root coordinates while the finger arrives in the button's local
     * ones, so the button has to carry its own root offset into the judgement. The button is placed
     * off the screen origin here precisely so a dropped translation fails this test.
     */
    @Test fun upperRightReleaseEditsText() {
        val actions = mutableListOf<VoiceReleaseAction>()
        var pressed = 0
        var targets by mutableStateOf<VoiceTargets?>(null)
        compose.setContent {
            HermesTheme {
                Surface {
                    Box(Modifier.padding(start = 20.dp, top = 60.dp)) {
                        VoiceHoldButton(
                            "按住说话", enabled = true, targets = targets,
                            onDown = { pressed++ },
                            onZone = { actions += it },
                            onRelease = { actions += it },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        val box = compose.onNodeWithTag("voice-hold").fetchSemanticsNode().boundsInRoot
        val reach = box.width * 0.25f
        targets = VoiceTargets(
            cancel = VoiceTarget(VoiceReleaseAction.CANCEL, box.left + box.width * 0.1f, box.top - box.width * 0.3f, reach),
            edit = VoiceTarget(VoiceReleaseAction.EDIT, box.left + box.width * 0.9f, box.top - box.width * 0.3f, reach),
        )
        compose.waitForIdle()
        compose.onNodeWithTag("voice-hold").performTouchInput {
            down(center)
            moveTo(Offset(width * 0.9f, -width * 0.3f))
            up()
        }
        assertEquals(1, pressed)
        assertEquals(listOf(VoiceReleaseAction.EDIT, VoiceReleaseAction.EDIT), actions)
    }
}
