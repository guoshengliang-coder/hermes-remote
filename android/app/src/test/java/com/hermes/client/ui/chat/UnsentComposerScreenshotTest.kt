package com.hermes.client.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.DeliveryState
import com.hermes.client.domain.Role
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-420dpi")
class UnsentComposerScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun snap(name: String, dark: Boolean, language: AppLanguage) {
        var cancelled = false
        compose.setContent {
            HermesTheme(darkTheme = dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalAppLanguage provides language,
                    LocalDensity provides Density(density.density, 1.3f),
                ) {
                    Surface {
                        Column(Modifier.padding(14.dp)) {
                            UserBubble(
                                msg = ChatMessage("original", Role.USER, "原消息仍保留，确认重发后替换", delivery = DeliveryState.FAILED),
                                onEditResend = {}, onOpenImage = { _, _ -> }, onFileOpen = {}, onFileShare = {},
                            )
                            UnsentEditingNotice(onCancel = { cancelled = true })
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/$name.png", roborazziOptions = RoborazziOptions(
            compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f),
        ))
        compose.onNodeWithText(if (language == AppLanguage.ZH) "取消编辑" else "Cancel edit").performClick()
        assertTrue(cancelled)
    }

    @Test fun lightLargeFont() = snap("unsent-composer-zh-360-fs13", false, AppLanguage.ZH)
    @Test fun darkLargeFont() = snap("unsent-composer-dark-zh-360-fs13", true, AppLanguage.ZH)
    @Test fun englishLargeFont() = snap("unsent-composer-en-360-fs13", false, AppLanguage.EN)
}
