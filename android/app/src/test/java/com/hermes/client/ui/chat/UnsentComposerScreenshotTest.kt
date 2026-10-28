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
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.isRoot
import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.assertFalse
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

    private fun unknown(name: String, dark: Boolean, language: AppLanguage) {
        var checked = false
        var removed = false
        compose.setContent {
            HermesTheme(darkTheme = dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalAppLanguage provides language,
                    LocalDensity provides Density(density.density, 1.3f)) {
                    Surface {
                        Column(Modifier.padding(14.dp)) {
                            UserBubble(ChatMessage("unknown", Role.USER, "A message with an unknown delivery result", delivery = DeliveryState.UNCONFIRMED),
                                onEditResend = { error("must not edit") }, onOpenImage = { _, _ -> }, onFileOpen = {}, onFileShare = {},
                                onCheckDelivery = { checked = true }, onDiscardSend = { error("must not withdraw") },
                                onRemoveUnknownNotice = { removed = it == "unknown" })
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(if (language == AppLanguage.ZH) "撤回" else "Withdraw").assertDoesNotExist()
        compose.onNodeWithText(if (language == AppLanguage.ZH) "编辑并重新发送" else "Edit & resend").assertDoesNotExist()
        compose.onRoot().captureRoboImage("screenshots/$name.png", roborazziOptions = RoborazziOptions(
            compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f)))
        compose.onNodeWithText(if (language == AppLanguage.ZH) "重新核对" else "Check status").performClick()
        assertTrue(checked)
        val remove = if (language == AppLanguage.ZH) "移除本机提示" else "Remove local notice"
        compose.onNodeWithText(remove).performClick()
        compose.onNodeWithText(if (language == AppLanguage.ZH) "移除本机提示？" else "Remove local notice?").assertExists()
        assertFalse(removed)
        compose.onNodeWithText(if (language == AppLanguage.ZH) "取消" else "Cancel").performClick()
        assertFalse(removed)
        compose.onNodeWithText(remove).performClick()
        compose.onAllNodesWithText(remove).onLast().performClick()
        assertTrue(removed)
    }

    private fun recoveryStates(dark: Boolean, language: AppLanguage, suffix: String) {
        val recovery = mutableStateOf(MessageDeliveryRecovery(checking = true))
        compose.setContent {
            HermesTheme(darkTheme = dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalAppLanguage provides language,
                    LocalDensity provides Density(density.density, 1.3f)) {
                    Surface { Column(Modifier.padding(14.dp)) {
                        UserBubble(ChatMessage("unknown", Role.USER, "等待确认的原消息", delivery = DeliveryState.UNCONFIRMED),
                            onEditResend = { error("must not edit") }, onOpenImage = { _, _ -> }, onFileOpen = {}, onFileShare = {},
                            deliveryRecovery = recovery.value)
                    } }
                }
            }
        }
        compose.onNodeWithText(if (language == AppLanguage.ZH) "正在核对…" else "Checking…").assertIsNotEnabled()
        compose.onRoot().captureRoboImage("screenshots/unknown-checking-$suffix.png")
        compose.runOnIdle { recovery.value = MessageDeliveryRecovery(checked = true) }
        compose.onNodeWithText(if (language == AppLanguage.ZH) "已同步会话，仍无法确认这条消息是否送达。"
            else "Conversation synced. This message's delivery still cannot be confirmed.").assertExists()
        compose.onRoot().captureRoboImage("screenshots/unknown-checked-$suffix.png")
        compose.runOnIdle { recovery.value = MessageDeliveryRecovery(error = com.hermes.client.data.error.AppError(
            com.hermes.client.data.error.AppErrorCode.UNKNOWN_NOTICE_REMOVE_FAILED, true)) }
        compose.onNodeWithText("STORE-003").assertExists()
        compose.onRoot().captureRoboImage("screenshots/unknown-remove-failed-$suffix.png")
        compose.runOnIdle { recovery.value = MessageDeliveryRecovery(error = com.hermes.client.data.error.AppError(
            com.hermes.client.data.error.AppErrorCode.HISTORY_INCOMPLETE, true)) }
        compose.onNodeWithText(if (language == AppLanguage.ZH) "核对未完成" else "Status check failed").assertExists()
        compose.onRoot().captureRoboImage("screenshots/unknown-check-failed-$suffix.png")
        compose.runOnIdle { recovery.value = MessageDeliveryRecovery() }
        compose.onNodeWithText(if (language == AppLanguage.ZH) "移除本机提示" else "Remove local notice").performClick()
        compose.onAllNodes(isRoot()).onLast().captureRoboImage("screenshots/unknown-remove-confirm-$suffix.png")
    }

    @Test fun recoveryStatesLight() = recoveryStates(false, AppLanguage.ZH, "zh-light-fs13")
    @Test fun recoveryStatesDark() = recoveryStates(true, AppLanguage.ZH, "zh-dark-fs13")
    @Test fun recoveryStatesEnglish() = recoveryStates(false, AppLanguage.EN, "en-light-fs13")

    @Test fun unknownLightLargeFont() = unknown("unknown-delivery-zh-360-fs13", false, AppLanguage.ZH)
    @Test fun unknownDarkLargeFont() = unknown("unknown-delivery-dark-zh-360-fs13", true, AppLanguage.ZH)
    @Test fun unknownEnglishLargeFont() = unknown("unknown-delivery-en-360-fs13", false, AppLanguage.EN)

    @Test fun lightLargeFont() = snap("unsent-composer-zh-360-fs13", false, AppLanguage.ZH)
    @Test fun darkLargeFont() = snap("unsent-composer-dark-zh-360-fs13", true, AppLanguage.ZH)
    @Test fun englishLargeFont() = snap("unsent-composer-en-360-fs13", false, AppLanguage.EN)
}
