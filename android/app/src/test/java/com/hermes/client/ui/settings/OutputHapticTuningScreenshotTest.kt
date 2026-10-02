package com.hermes.client.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermes.client.data.haptics.OutputHapticConfig
import com.hermes.client.data.haptics.OutputHapticType
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-420dpi")
class OutputHapticTuningScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun render(dark: Boolean = false, language: AppLanguage = AppLanguage.ZH, font: Float = 1f, supported: Boolean = true,
        onDraft: (OutputHapticConfig) -> Unit = {}, onSave: () -> Unit = {}, onCopy: () -> Unit = {}, onPreview: (Boolean) -> Unit = {}) {
        compose.setContent {
            HermesTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalAppLanguage provides language,
                    LocalDensity provides Density(LocalDensity.current.density, font)) {
                    OutputHapticTuningContent(OutputHapticsSettingsState(enabled = true, loaded = true),
                        OutputHapticConfig(OutputHapticType.CUSTOM_PULSE, 75, 9, 84), supported, false,
                        onDraft = onDraft, onSave = onSave, onCopy = onCopy, onPreview = onPreview)
                }
            }
        }
        compose.waitForIdle()
    }
    private fun scrollTo(text: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
        return compose.onNodeWithText(text).performScrollTo()
    }
    @Test fun lightCustomControlsAndActions() {
        var save = 0; var copy = 0; var draft: OutputHapticConfig? = null; var rhythm: Boolean? = null
        render(onSave = { save++ }, onCopy = { copy++ }, onDraft = { draft = it }, onPreview = { rhythm = it })
        compose.onNode(hasScrollAction()).performScrollToIndex(6)
        compose.onRoot().captureRoboImage("screenshots/haptic-tuning-custom-zh.png")
        scrollTo("试听 1 秒").performClick()
        assertEquals(true, rhythm)
        scrollTo("保存为本机默认").performClick(); assertEquals(1, save)
        scrollTo("复制参数").performClick(); assertEquals(1, copy)
        scrollTo("恢复内置参数（需保存）").performClick()
        assertEquals(OutputHapticConfig(), draft)
        assertEquals(1, save) // Reset changes the draft, never silently saves it.
    }
    @Test fun darkCustomControls() {
        render(dark = true)
        compose.onNode(hasScrollAction()).performScrollToIndex(6)
        compose.onRoot().captureRoboImage("screenshots/haptic-tuning-custom-dark.png")
    }
    @Test fun unsupportedAndEnglishLargeFontRemainScrollableAndDisableCustomPreview() {
        render(language = AppLanguage.EN, font = 1.3f, supported = false)
        compose.onNode(hasScrollAction()).performScrollToIndex(5)
        compose.onRoot().captureRoboImage("screenshots/haptic-tuning-unsupported-en-fs13.png")
        scrollTo("Preview once").assertIsNotEnabled()
        scrollTo("Save as device default").assertIsNotEnabled()
        compose.onNode(hasScrollAction()).performScrollToIndex(6)
        compose.onNodeWithContentDescription("Strength").performScrollTo().assertIsNotEnabled()
        scrollTo("Copy parameters").assertIsEnabled()
    }
}
