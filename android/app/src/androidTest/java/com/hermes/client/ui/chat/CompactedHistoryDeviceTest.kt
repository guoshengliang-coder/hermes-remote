package com.hermes.client.ui.chat

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hermes.client.data.network.MessagesDto
import com.hermes.client.data.repository.TranscriptWindow
import com.hermes.client.domain.toDomain
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.theme.HermesTheme
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic archived display pages through the real mapper and prompt-sheet gestures. */
@RunWith(AndroidJUnit4::class)
class CompactedHistoryDeviceTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)

    @Test fun archivedFirstQuestionIsReachableInLightTheme() = verify(dark = false)
    @Test fun archivedFirstQuestionIsReachableInDarkTheme() = verify(dark = true)

    private fun verify(dark: Boolean) {
        val json = Json { ignoreUnknownKeys = true }
        val source = (1..26).joinToString(",", "{\"messages\":[", "]}") { n ->
            // The first logical prompt was copied into a later compaction generation.
            val id = if (n == 1) 900 else n
            val role = if (n % 2 == 1) "user" else "assistant"
            val text = if (n % 2 == 1) "归档提问 ${(n + 1) / 2}" else "回答 ${n / 2}"
            """{"id":$id,"role":"$role","content":"$text","timestamp":${1757000000L + n}}"""
        }
        val all = TranscriptWindow.rowsOf(source)
        val window = TranscriptWindow.mergeOlder(TranscriptWindow(all.takeLast(8), false), all.take(19), 101).window
        val messages = json.decodeFromString<MessagesDto>(window.payload()).messages.map { it.toDomain() }
        val rows = promptRows(turnGroups(messages), messages, 0, AppLanguage.ZH) { "10:00" }
        assertEquals(13, rows.size)
        var picked: Int? = null
        activity.scenario.onActivity { host ->
            host.setContent {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.ZH) {
                    HermesTheme(darkTheme = dark) {
                        Surface {
                            PromptListSheet(rows, onPick = { picked = it.ordinal }, onLatest = {}, onDismiss = {})
                        }
                    }
                }
            }
        }
        // Wait for the actual sheet count and old first question, then tap using native input.
        tapText("13 条")
        capture(if (dark) "hg224-prompts-dark.png" else "hg224-prompts-light.png")
        tapText("归档提问 1")
        activity.scenario.onActivity { assertEquals(1, picked) }
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
        android.util.Log.d("CompactedHistoryDeviceTest", "$text at $target")
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
