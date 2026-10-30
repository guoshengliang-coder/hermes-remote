package com.hermes.client.ui.chat

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hermes.client.data.video.*
import com.hermes.client.ui.localization.*
import com.hermes.client.ui.theme.HermesTheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Native touch/IME around the real Workspace with isolated local notes and a media-state fixture.
 * Does not claim to validate decoding or exact media-frame positioning. No production credentials. */
@RunWith(AndroidJUnit4::class)
class VideoAnnotationDeviceTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var seeks = mutableListOf<Long>()
    private var pauses = 0
    private var resumes = 0
    private var disk = VideoFeedbackDocument(notes = listOf(VideoNote("one", 12_000, text = "正式批注原文"),
        VideoNote("two", 45_000, 55_000, "片段批注原文")))
    @After fun cleanup() { scope.cancel() }

    @Test fun annotation_track_preview_edit_cancel_and_empty_draft_on_api37() {
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        activity.scenario.onActivity { host ->
            val vm = VideoFeedbackViewModel(object : VideoFeedbackStorage {
                override suspend fun read(key: String) = disk
                override suspend fun write(key: String, document: VideoFeedbackDocument) { disk = document }
            }, "device-fixture", scope)
            host.setContent { HermesTheme { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.ZH) {
                Box(Modifier.fillMaxSize().systemBarsPadding()) {
                    VideoFeedbackWorkspace("批注验证.mp4", VideoFeedbackContext("fixture", "source", "chat", attach = { null }),
                        "fixture-v1", 60_000, { 12_000 }, { seeks += it }, {},
                        playerPosition = 12_000, pause = { pauses++ }, resume = { resumes++ }, feedbackModel = vm,
                    ) { annotate, editing, start, locked, review, annotations ->
                        VideoPlayerContent("批注验证.mp4", VideoPlayerState(position = 12_000, duration = 60_000, hasFrame = true),
                            onAnnotate = annotate, annotating = editing, annotationStartMs = start, annotations = annotations,
                            canPlay = !locked, onReviewNotes = review)
                    }
                }
            } } }
        }
        tap("批注")
        tap("查看批注：约 00:12 附近")
        await("正式批注原文")
        capture("hg247-native-preview.png")
        activity.scenario.onActivity { assertEquals(listOf(12_000L), seeks); assertEquals(1, pauses); assertEquals(0, resumes) }
        tap("编辑")
        await("取消返回")
        val input = find(automation.rootInActiveWindow) { it.isEditable } ?: error("missing editable field")
        val arguments = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "本次修改应取消") }
        assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
        await("本次修改应取消")
        capture("hg246-native-editor-ime.png")
        tap("取消返回")
        tap("查看批注：约 00:12 附近")
        await("正式批注原文")
        activity.scenario.onActivity { assertNull(disk.draft); assertEquals("正式批注原文", disk.notes.first().text) }
        tap("关闭批注")
        tap("单点批注")
        tap("稍后填写")
        tap("未完成草稿 · 1　继续填写")
        tap("继续填写")
        await("取消返回")
        activity.scenario.onActivity { assertTrue(disk.draft!!.retained); assertEquals("", disk.draft!!.text); assertEquals(0, resumes) }
        tap("取消返回")
        tap("查看批注：约 00:45–00:55")
        await("片段批注原文")
        capture("hg247-native-range-preview.png")
        activity.scenario.onActivity { assertEquals(45_000L, seeks.last()); assertEquals(0, resumes) }
    }
    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
        return null
    }
    private fun await(label: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) {
            find(automation.rootInActiveWindow) { it.text?.toString() == label || it.contentDescription?.toString() == label }?.let { return it }
            SystemClock.sleep(50)
        }
        error("No accessibility node: $label")
    }
    private fun tap(label: String) {
        val node = await(label)
        val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
        assertFalse(bounds.isEmpty)
        val down = SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        SystemClock.sleep(400)
        automation.waitForIdle(500, 5_000)
    }
    private fun capture(name: String) {
        val bitmap = checkNotNull(automation.takeScreenshot())
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/HermesTest")
        }
        val uri = checkNotNull(resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        checkNotNull(resolver.openOutputStream(uri)).use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
