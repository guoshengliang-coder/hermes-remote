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
import com.hermes.client.domain.ChatFile
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatFileDeviceTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)

    @Test fun large_file_download_pause_resume_and_completed_actions_accept_real_touches() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val file = ChatFile("big", "large.mp4", "video/mp4", 126L * 1024 * 1024, remotePath = "/tmp/large.mp4")
        val state = androidx.compose.runtime.mutableStateOf(emptyList<com.hermes.client.data.downloads.ArtifactDownloadTask>())
        var starts = 0; var pauses = 0; var opens = 0; var shares = 0
        fun task(status: com.hermes.client.data.downloads.ArtifactDownloadStatus) = com.hermes.client.data.downloads.ArtifactDownloadTask(
            "id", "owner", "mac", file.remotePath!!, file.name, file.mimeType, status = status,
            sizeBytes = file.sizeBytes, downloaded = if (status == com.hermes.client.data.downloads.ArtifactDownloadStatus.COMPLETE) file.sizeBytes!! else 25L * 1024 * 1024)
        activity.scenario.onActivity { host -> host.setContent { HermesTheme {
            val metadata = androidx.compose.runtime.remember { ChatFileMetadataState { com.hermes.client.domain.ArtifactMetadata(file.sizeBytes!!, 8L * 1024 * 1024 * 1024, true) } }
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.EN, LocalChatFileMetadata provides metadata,
                LocalChatFileDownloads provides ChatFileDownloads(state.value, {
                    starts++
                    state.value = listOf(task(if (starts == 1) com.hermes.client.data.downloads.ArtifactDownloadStatus.RUNNING else com.hermes.client.data.downloads.ArtifactDownloadStatus.COMPLETE))
                }, { pauses++; state.value = listOf(task(com.hermes.client.data.downloads.ArtifactDownloadStatus.PAUSED)) }, {})) {
                Surface { Column(Modifier.systemBarsPadding().padding(16.dp)) { ChatFileList(listOf(file), { opens++ }, { shares++ }) } }
            }
        } } }
        val deadline = android.os.SystemClock.uptimeMillis() + 10_000
        while (find(automation.rootInActiveWindow, "Download before") == null && android.os.SystemClock.uptimeMillis() < deadline) android.os.SystemClock.sleep(50)
        assertNotNull(find(automation.rootInActiveWindow, "Download before"))
        val download = all(automation.rootInActiveWindow).first { it.text?.toString() == "Download" }
        tap(automation, download)
        assertEquals(1, starts)
        tap(automation, find(automation.rootInActiveWindow, "Pause")!!)
        assertEquals(1, pauses)
        tap(automation, find(automation.rootInActiveWindow, "Resume")!!)
        assertNotNull(find(automation.rootInActiveWindow, "Downloaded"))
        tap(automation, find(automation.rootInActiveWindow, "large.mp4")!!)
        tap(automation, all(automation.rootInActiveWindow).first { it.contentDescription?.toString() == "Share file" })
        activity.scenario.onActivity { assertEquals(1, opens); assertEquals(1, shares) }
        automation.executeShellCommand("screencap -p /sdcard/Download/hg222-download-cards.png").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes() }
        }
    }

    @Test fun size_limit_and_disabled_actions_are_visible_on_android() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        var opens = 0
        var shares = 0
        activity.scenario.onActivity { host ->
            host.setContent {
                HermesTheme(darkTheme = false) {
                    CompositionLocalProvider(LocalAppLanguage provides AppLanguage.EN,
                        LocalChatFileMetadata provides ChatFileMetadataState { file -> file.sizeBytes?.let { com.hermes.client.domain.ArtifactMetadata(it) } }) {
                        Surface { Column(Modifier.systemBarsPadding().padding(16.dp)) {
                            ChatFileList(listOf(
                                ChatFile("big", "large.mp4", "video/mp4", 126L * 1024 * 1024, remotePath = "/tmp/large.mp4"),
                                ChatFile("small", "notes.md", "text/markdown", 1500, remotePath = "/tmp/notes.md"),
                            ), { opens++ }, { shares++ })
                        } }
                    }
                }
            }
        }
        val deadline = android.os.SystemClock.uptimeMillis() + 10_000
        var visible: android.view.accessibility.AccessibilityNodeInfo? = null
        while (visible == null && android.os.SystemClock.uptimeMillis() < deadline) {
            visible = find(automation.rootInActiveWindow, "HR-FILE-004")
            if (visible == null) android.os.SystemClock.sleep(100)
        }
        assertNotNull("oversize explanation must be visible without tapping", visible)
        assertNotNull(find(automation.rootInActiveWindow, "126.0 MiB"))
        val big = find(automation.rootInActiveWindow, "large.mp4")!!
        assertFalse("oversized card has no click action", big.isClickable)
        val share = all(automation.rootInActiveWindow).first { it.contentDescription?.toString() == "Share file" }
        // Android exports the icon as an enabled child of a disabled button. Inspect the
        // whole ancestor chain and verify real touches do not invoke either callback.
        var ancestor: android.view.accessibility.AccessibilityNodeInfo? = share
        var disabled = false
        while (ancestor != null) {
            if (!ancestor.isEnabled) disabled = true
            ancestor = ancestor.parent
        }
        assertTrue("oversized share has disabled button semantics", disabled)
        tap(automation, share)
        tap(automation, big)
        activity.scenario.onActivity { assertEquals(0, opens); assertEquals(0, shares) }
        val small = find(automation.rootInActiveWindow, "notes.md")!!
        tap(automation, small)
        activity.scenario.onActivity { assertEquals(1, opens); assertEquals(0, shares) }
        automation.executeShellCommand("screencap -p /sdcard/Download/hg222-cards.png").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes() }
        }
    }

    private fun tap(automation: android.app.UiAutomation, node: android.view.accessibility.AccessibilityNodeInfo) {
        val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        assertFalse(bounds.isEmpty)
        val time = android.os.SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(time, android.os.SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { check(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        android.os.SystemClock.sleep(500)
    }

    private fun all(node: android.view.accessibility.AccessibilityNodeInfo?): List<android.view.accessibility.AccessibilityNodeInfo> {
        if (node == null || !node.refresh()) return emptyList()
        return listOf(node) + (0 until node.childCount).flatMap { all(node.getChild(it)) }
    }
    private fun find(root: android.view.accessibility.AccessibilityNodeInfo?, text: String) =
        all(root).firstOrNull { it.text?.toString()?.contains(text) == true }
}
