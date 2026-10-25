package com.hermes.client.ui.chat

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.DeliveryState
import com.hermes.client.domain.Role
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android gestures on HG-220's actions, using framework accessibility without Espresso. */
@RunWith(AndroidJUnit4::class)
class UnsentComposerDeviceTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)

    @Test fun pendingMessageActionsUseTheOriginalIdentityAndCancelRemainsReachable() {
        val actions = mutableListOf<String>()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        activity.scenario.onActivity { host ->
            host.setContent {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.ZH, LocalDeliveryMotionEnabled provides false) {
                    HermesTheme(darkTheme = false) {
                        Surface {
                            Column(Modifier.systemBarsPadding().padding(16.dp)) {
                                UserBubble(
                                    msg = ChatMessage("pending", Role.USER, "正在发送的消息", delivery = DeliveryState.SENDING),
                                    onEditResend = { actions += "edit:$it" }, onDiscardSend = { actions += "withdraw:$it" },
                                    onOpenImage = { _, _ -> }, onFileOpen = {}, onFileShare = {},
                                )
                                UnsentEditingNotice(onCancel = { actions += "cancel" })
                            }
                        }
                    }
                }
            }
        }
        tapText("正在发送的消息")
        capture("hg220-pending-menu.png")
        tapText("撤回")
        tapText("正在发送的消息")
        capture("hg220-edit-menu.png")
        tapText("编辑并重新发送")
        capture("hg220-edit-notice.png")
        tapText("取消编辑")
        activity.scenario.onActivity {
            assertEquals(listOf("withdraw:pending", "edit:pending", "cancel"), actions)
        }
    }

    @Test fun unknownOutcomeOffersOnlyStatusQueryAndCopyOnApi37() {
        val actions = mutableListOf<String>()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        activity.scenario.onActivity { host ->
            host.setContent {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.ZH, LocalDeliveryMotionEnabled provides false) {
                    HermesTheme(darkTheme = false) {
                        Surface { Column(Modifier.systemBarsPadding().padding(16.dp)) {
                            UserBubble(ChatMessage("unknown", Role.USER, "等待核对的消息", delivery = DeliveryState.UNCONFIRMED),
                                onEditResend = { actions += "edit" }, onDiscardSend = { actions += "withdraw" },
                                onCheckDelivery = { actions += "check:$it" },
                                onOpenImage = { _, _ -> }, onFileOpen = {}, onFileShare = {})
                        } }
                    }
                }
            }
        }
        tapText("重新核对")
        tapText("等待核对的消息")
        org.junit.Assert.assertNull(findText(automation.rootInActiveWindow, "撤回"))
        org.junit.Assert.assertNull(findText(automation.rootInActiveWindow, "编辑并重新发送"))
        org.junit.Assert.assertNotNull(findText(automation.rootInActiveWindow, "复制"))
        activity.scenario.onActivity { assertEquals(listOf("check:unknown"), actions) }
        val bitmap = checkNotNull(automation.takeScreenshot())
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "hg237-unknown-menu.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/HermesTest")
        }
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val uri = checkNotNull(resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        checkNotNull(resolver.openOutputStream(uri)).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    // The Compose test rule itself initializes a pinned Espresso injector that uses a removed
    // private API on Android 36. ActivityScenario + public UiAutomation need no dependency change.
    private fun tapText(text: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = android.os.SystemClock.uptimeMillis() + 5_000L
        var bounds: android.graphics.Rect? = null
        while (bounds == null && android.os.SystemClock.uptimeMillis() < deadline) {
            val node = findText(automation.rootInActiveWindow, text)
            if (node != null) {
                bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }.takeUnless { it.isEmpty }
            }
            if (bounds == null) android.os.SystemClock.sleep(50)
        }
        if (bounds == null) capture("hg220-missing-node.png")
        val target = checkNotNull(bounds) { "No visible accessibility node for $text" }
        android.util.Log.d("UnsentComposerDeviceTest", "$text at $target")
        val downTime = android.os.SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(), action,
                target.exactCenterX(), target.exactCenterY(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { check(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        // Dialog window transitions run outside Compose's clock. Let the native window settle
        // before the next lookup, otherwise a moving sheet can put a different row under a tap.
        // Accessibility can still report the PREVIOUS window as idle before the first new frame.
        android.os.SystemClock.sleep(500)
        automation.waitForIdle(750, 5_000)
    }

    private fun findText(node: android.view.accessibility.AccessibilityNodeInfo?, text: String): android.view.accessibility.AccessibilityNodeInfo? {
        if (node == null) return null
        if (!node.refresh()) return null
        if (node.text?.toString()?.trim() == text) return node
        for (index in 0 until node.childCount) findText(node.getChild(index), text)?.let { return it }
        return null
    }

    private fun capture(name: String) {
        require(name.matches(Regex("[a-z0-9-]+\\.png")))
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand("screencap -p /sdcard/Download/$name").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() }
        }
    }
}
