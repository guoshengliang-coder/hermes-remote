package com.hermes.client.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziTaskType
import java.io.File
import javax.imageio.ImageIO
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
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
@Config(sdk = [34], qualifiers = "w360dp-h740dp-notnight-420dpi")
class ComposerActionButtonTest {
    @get:Rule val compose = createComposeRule()

    private fun action(stop: Boolean, enabled: Boolean, language: AppLanguage, dark: Boolean = false, nested: Boolean = false, click: () -> Unit) {
        compose.setContent {
            HermesTheme(darkTheme = dark) {
                Surface {
                    if (nested) {
                        ComposerSurface(expanded = true, modifier = Modifier.testTag("composer-surface")) {
                            Column(Modifier.padding(8.dp)) {
                                ComposerActionButton(stop, enabled, language, click, Modifier.testTag("action"))
                            }
                        }
                    } else ComposerActionButton(stop, enabled, language, click, Modifier.testTag("action"))
                }
            }
        }
        compose.onNodeWithTag("action").assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
    }

    @Test fun sendsOnceFromTheFullTouchTarget() {
        var sends = 0
        action(false, true, AppLanguage.ZH) { sends++ }
        compose.onNodeWithContentDescription("发送").performClick()
        assertEquals(1, sends)
    }

    @Test fun emptyDraftCannotSend() {
        var sends = 0
        action(false, false, AppLanguage.ZH) { sends++ }
        compose.onNodeWithContentDescription("发送").assertIsNotEnabled().performClick()
        assertEquals(0, sends)
    }

    @Test fun stopKeepsItsOwnAccessibleAction() {
        var stops = 0
        action(true, true, AppLanguage.EN) { stops++ }
        compose.onNodeWithContentDescription("Stop").performClick()
        compose.onNodeWithContentDescription("Send").assertDoesNotExist()
        assertEquals(1, stops)
    }

    @Test fun darkActionPaintsTheHairlineRatherThanOnlyRecordingItsToken() {
        action(false, true, AppLanguage.ZH, dark = true) {}
        val pixels = actionPixels()
        // A 1dp ring is smaller than the whole contact sheet's screenshot threshold.
        // The disc is 44dp, centred inside the original 48dp touch slot.
        assertEquals(0xFF0F1217.toInt(), pixels.getRGB(pixels.width / 2, 1))
        assertEquals(0xFF3A4049.toInt(), pixels.getRGB(pixels.width / 2, pixels.height / 24 + 1))
        assertEquals(0xFFFFFFFF.toInt(), pixels.getRGB(pixels.width / 2, pixels.height / 2))
        assertEquals(0xFF1E232B.toInt(), pixels.getRGB(pixels.width / 2, pixels.height - 12))
    }

    @Test fun disabledDarkActionHasAQuietDiscWithoutTheEnabledHairline() {
        action(false, false, AppLanguage.ZH, dark = true) {}
        val pixels = actionPixels()
        assertEquals(0xFF0F1217.toInt(), pixels.getRGB(pixels.width / 2, 1))
        val quiet = pixels.getRGB(pixels.width / 2, pixels.height - 12)
        // 8% secondary ink over the page, allowing one byte of native alpha rounding.
        assertColorNear(0xFF1B1E22.toInt(), quiet)
        assertColorNear(0xFF85837E.toInt(), pixels.getRGB(pixels.width / 2, pixels.height / 2))
    }

    @Test fun theTransparentTwoDpRimStillSendsExactlyOnce() {
        var sends = 0
        action(false, true, AppLanguage.ZH) { sends++ }
        compose.onNodeWithContentDescription("发送").performTouchInput { click(Offset(1f, height / 2f)) }
        assertEquals(1, sends)
    }

    @Test fun appDarkInsideTheRealComposerKeepsItsTonalLayerAndEnabledActionPaint() {
        action(false, true, AppLanguage.ZH, dark = true, nested = true) {}
        val container = actionPixels("composer-surface")
        assertColorNear(0xFF1A1D21.toInt(), container.getRGB(container.width / 2, 10))
        val pixels = actionPixels()
        assertColorNear(0xFF1A1D21.toInt(), pixels.getRGB(pixels.width / 2, 1))
        assertEquals(0xFF3A4049.toInt(), pixels.getRGB(pixels.width / 2, pixels.height / 24 + 1))
        assertEquals(0xFF1E232B.toInt(), pixels.getRGB(pixels.width / 2, pixels.height - 12))
    }

    @Test fun theQuietDiscRemainsVisibleInsideTheRealDarkComposer() {
        action(false, false, AppLanguage.ZH, dark = true, nested = true) {}
        val pixels = actionPixels()
        assertColorNear(0xFF1A1D21.toInt(), pixels.getRGB(pixels.width / 2, 1))
        assertColorNear(0xFF25282B.toInt(), pixels.getRGB(pixels.width / 2, pixels.height - 12))
    }

    private fun assertColorNear(expected: Int, actual: Int) {
        for (shift in listOf(0, 8, 16, 24)) {
            val delta = kotlin.math.abs(((expected ushr shift) and 255) - ((actual ushr shift) and 255))
            org.junit.Assert.assertTrue("expected=${expected.toUInt().toString(16)} actual=${actual.toUInt().toString(16)}", delta <= 1)
        }
    }

    private fun actionPixels(tag: String = "action"): java.awt.image.BufferedImage {
        val file = File.createTempFile("composer-pixels-", ".png")
        try {
            // Use the native Robolectric renderer, not the device-only PixelCopy path.
            compose.onNodeWithTag(tag).captureRoboImage(file.absolutePath,
                roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
            return checkNotNull(ImageIO.read(file))
        } finally {
            file.delete()
        }
    }

    private fun snap(name: String, dark: Boolean, fontScale: Float = 1f) {
        compose.setContent {
            // Config above keeps SYSTEM light, including the explicit app-dark case.
            HermesTheme(darkTheme = dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    Surface {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("44 圆底 / 48 点击区域")
                            ComposerSurface(expanded = true, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("发送 / 停止 / 空草稿", Modifier.padding(8.dp))
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        ComposerActionButton(false, true, AppLanguage.ZH, {})
                                        ComposerActionButton(true, true, AppLanguage.ZH, {})
                                        ComposerActionButton(false, false, AppLanguage.ZH, {})
                                    }
                                }
                            }
                            Text("语音输入 · 生成中")
                            ComposerSurface(expanded = false) { VoiceComposerBar(
                                language = AppLanguage.ZH, holdLabel = "按住说话", holdEnabled = false,
                                keyboardEnabled = true, sessionWritable = true, isGenerating = true,
                                targets = null, onKeyboard = {}, onDown = {}, onZone = {},
                                onRelease = {}, onAdd = {}, onStop = {},
                            ) }
                        }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("screenshots/$name.png", roborazziOptions = RoborazziOptions(
            compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f),
        ))
    }

    @Test fun lightStates() = snap("composer-action-light", dark = false)
    @Test fun appDarkSystemLightStates() = snap("composer-action-app-dark-system-light", dark = true)
    @Test fun largeText() = snap("composer-action-large-text", dark = false, fontScale = 1.3f)
    @Test @Config(qualifiers = "w320dp-h640dp-notnight-320dpi")
    fun narrowDarkLargeText() = snap("composer-action-narrow-dark-large-text", dark = true, fontScale = 1.3f)
}
