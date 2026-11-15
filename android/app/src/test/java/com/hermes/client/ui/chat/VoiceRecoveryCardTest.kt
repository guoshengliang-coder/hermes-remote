package com.hermes.client.ui.chat

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermes.client.data.voice.VoiceRecording
import com.hermes.client.ui.localization.AppLanguage
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
class VoiceRecoveryCardTest {
    @get:Rule val compose = createComposeRule()
    private val recording = VoiceRecording("00000000-0000-0000-0000-000000000001", "scope", 0, 1920000, complete = true)
    @Test fun lightPendingOffersOriginalRecordingRetry() {
        var sends = 0
        compose.setContent { HermesTheme { Surface {
            VoiceRecoveryCard(recording, false, AppLanguage.ZH, false, true, { sends++ }, {}, {})
        } } }
        compose.onNodeWithText("重试发送").assertIsEnabled().performClick()
        assertEquals(1, sends)
        compose.onRoot().captureRoboImage("screenshots/voice-recovery-light.png")
    }
    @Test fun recoveredLargeEnglishCardOnlyTranscribesAndWrapsButtons() {
        compose.setContent { HermesTheme(darkTheme = true) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) { Surface {
                VoiceRecoveryCard(recording, true, AppLanguage.EN, false, true, {}, {}, {})
            } }
        } }
        compose.onNodeWithText("Retry send").assertDoesNotExist()
        compose.onNodeWithText("Transcribe").assertIsEnabled()
        compose.onRoot().captureRoboImage("screenshots/voice-recovery-dark-en-fs13.png")
    }
    @Test fun retryInProgressBlocksEveryDestructiveOrDuplicateAction() {
        compose.setContent { HermesTheme { Surface {
            VoiceRecoveryCard(recording, false, AppLanguage.ZH, true, true, {}, {}, {})
        } } }
        compose.onNodeWithText("重试发送").assertIsNotEnabled()
        compose.onNodeWithText("转文字").assertIsNotEnabled()
        compose.onNodeWithText("丢弃").assertIsNotEnabled()
    }
    @Test fun activeRecordingInAnotherViewCannotBeConsumedOrDiscarded() {
        compose.setContent { HermesTheme { Surface {
            VoiceRecoveryCard(recording.copy(complete = false, active = true), true, AppLanguage.ZH, false, true, {}, {}, {})
        } } }
        compose.onNodeWithText("转文字").assertIsNotEnabled()
        compose.onNodeWithText("丢弃").assertIsNotEnabled()
        compose.onNodeWithText("重试发送").assertDoesNotExist()
    }

}
