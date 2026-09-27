package com.hermes.client.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * HG-146: the voice bar keeps the keyboard bar's layout — keyboard toggle, hold-to-talk pill, and
 * the add button in the same 48dp slot — and swaps that add button for stop while a run is
 * generating, exactly as the keyboard bar does.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-420dpi")
class VoiceComposerBarScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f),
    )

    private fun snap(
        name: String,
        dark: Boolean,
        fontScale: Float,
        holdLabel: String,
        isGenerating: Boolean,
    ) {
        compose.setContent {
            HermesTheme(darkTheme = dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalAppLanguage provides AppLanguage.ZH,
                    LocalDensity provides Density(density.density, fontScale),
                ) {
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        VoiceComposerBar(
                            language = AppLanguage.ZH,
                            holdLabel = holdLabel,
                            holdEnabled = !isGenerating,
                            keyboardEnabled = true,
                            sessionWritable = true,
                            isGenerating = isGenerating,
                            onKeyboard = {},
                            onDown = {},
                            onZone = {},
                            onRelease = {},
                            onAdd = {},
                            onStop = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/$name.png", roborazziOptions = options)
    }

    @Test fun idleLight() = snap("voice-composer-idle-zh-360", dark = false, fontScale = 1f, holdLabel = "按住说话", isGenerating = false)

    @Test fun heldDarkLargeFont() = snap("voice-composer-held-dark-zh-360-fs13", dark = true, fontScale = 1.3f, holdLabel = "松开发送", isGenerating = false)

    @Test fun generatingLight() = snap("voice-composer-generating-zh-360", dark = false, fontScale = 1f, holdLabel = "按住说话", isGenerating = true)

    private fun bar(isGenerating: Boolean) {
        compose.setContent {
            HermesTheme {
                Surface {
                    VoiceComposerBar(
                        language = AppLanguage.ZH,
                        holdLabel = "按住说话",
                        holdEnabled = !isGenerating,
                        keyboardEnabled = true,
                        sessionWritable = true,
                        isGenerating = isGenerating,
                        onKeyboard = {},
                        onDown = {},
                        onZone = {},
                        onRelease = {},
                        onAdd = {},
                        onStop = {},
                    )
                }
            }
        }
    }

    @Test fun idleBarShowsAddNotStop() {
        bar(isGenerating = false)
        compose.onNodeWithContentDescription("添加内容").assertExists()
        compose.onNodeWithContentDescription("停止").assertDoesNotExist()
    }

    @Test fun runningBarShowsStopNotAdd() {
        bar(isGenerating = true)
        compose.onNodeWithContentDescription("停止").assertExists()
        compose.onNodeWithContentDescription("添加内容").assertDoesNotExist()
    }
}
