package com.hermes.client.ui.chat

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.domain.ToolCall
import com.hermes.client.domain.ToolStatus
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** HG-230: native touch/disclosure/window checks without Espresso's removed API-36 injector. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class MessageProgressDeviceTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)

    @Test fun preview_manual_choices_tool_window_and_status_dialog() {
        val thought = (1..6).joinToString("\n") { "第 $it 步：核对来源文件与完整运行结果。" }
        val streaming = mutableStateOf(true)
        val text = mutableStateOf(thought)
        val tools = (1..4).map { ToolCall("t$it", "工具$it", ToolStatus.DONE, output = "工具结果") }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        activity.scenario.onActivity { host -> host.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.ZH) { HermesTheme(darkTheme = false) {
                Surface { Column(Modifier.systemBarsPadding().padding(16.dp)) {
                    ThinkingCard("device", text.value, streaming = streaming.value)
                    ToolTimelineCard(tools, completed = !streaming.value)
                    RunningStatusLine(ChatMessage("device", Role.ASSISTANT, "", thinking = "检查每项任务的详细运行状态并保留全部文字。".repeat(8), isStreaming = true))
                } }
            } }
        } }
        awaitText("展开思考过程")
        capture("hg230-preview.png")
        assertNull(findText(automation.rootInActiveWindow, "工具1"))
        assertNotNull(awaitText("工具4"))
        tapText("收起思考过程")
        activity.scenario.onActivity { text.value += "\n后续增量" }
        awaitText("查看思考过程")
        tapText("查看思考过程")
        activity.scenario.onActivity { streaming.value = false }
        awaitText("收起思考过程") // explicit expansion survives completion
        tapText("收起思考过程")
        tapText("查看思考过程")
        tapText("收起思考过程")
        val status = "检查每项任务的详细运行状态并保留全部文字。".repeat(8)
        tapText(status)
        awaitText("运行状态")
        capture("hg230-status-details.png")
        tapText("关闭")
        assertNull(findText(automation.rootInActiveWindow, "运行状态"))
    }

    private fun awaitText(text: String): android.view.accessibility.AccessibilityNodeInfo {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = android.os.SystemClock.uptimeMillis() + 5_000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            findText(automation.rootInActiveWindow, text)?.let { return it }
            android.os.SystemClock.sleep(50)
        }
        capture("hg230-missing-node.png")
        error("No accessibility node for expected progress control")
    }

    private fun tapText(text: String) {
        val node = awaitText(text)
        val rect = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        check(!rect.isEmpty)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val down = android.os.SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action,
                rect.exactCenterX(), rect.exactCenterY(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { check(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        android.os.SystemClock.sleep(500)
        automation.waitForIdle(500, 5_000)
    }

    private fun findText(node: android.view.accessibility.AccessibilityNodeInfo?, text: String): android.view.accessibility.AccessibilityNodeInfo? {
        if (node == null || !node.refresh()) return null
        if (node.text?.toString()?.trim() == text) return node
        for (i in 0 until node.childCount) findText(node.getChild(i), text)?.let { return it }
        return null
    }

    private fun capture(name: String) {
        require(name.matches(Regex("[a-z0-9-]+\\.png")))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Screenshot unavailable" }
        try {
            // Shared synthetic screenshots survive the runner uninstalling its target package.
            val resolver = instrumentation.targetContext.contentResolver
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/HermesProgressTests")
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = checkNotNull(resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
            checkNotNull(resolver.openOutputStream(uri)).use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
            values.clear()
            values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } finally { bitmap.recycle() }
    }
}
