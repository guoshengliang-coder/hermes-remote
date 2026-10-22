package com.hermes.client.ui.chat

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
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
import androidx.test.platform.app.InstrumentationRegistry
import com.hermes.client.data.downloads.ArtifactDownloadStatus
import com.hermes.client.data.downloads.ArtifactDownloadTask
import com.hermes.client.domain.ArtifactMetadata
import com.hermes.client.domain.ChatFile
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native rendering and real touch dispatch; no network, accounts, or persisted download tasks. */
@RunWith(AndroidJUnit4::class)
class ChatFileCardDeviceTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    @Test fun downloadActionsRemainReachableAndHeaderStaysFixedInBothThemes() {
        val file = ChatFile("html", "V3-旋转车与片尾修改确认-轻量版.html", "text/html", 2_443_000,
            remotePath = "/fixture/report.html")
        val metadata = ChatFileMetadataState { ArtifactMetadata(file.sizeBytes!!, 8L * 1024 * 1024 * 1024, true) }
        val tasks = mutableStateOf(emptyList<ArtifactDownloadTask>())
        val dark = mutableStateOf(false)
        val actions = mutableListOf<String>()
        activity.scenario.onActivity { host -> host.setContent {
            HermesTheme(darkTheme = dark.value) {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.EN, LocalChatFileMetadata provides metadata,
                    LocalChatFileDownloads provides ChatFileDownloads(tasks.value,
                        { actions += "download" }, { actions += "pause" }, { actions += "cancel" })) {
                    Surface { Column(Modifier.systemBarsPadding().padding(16.dp)) {
                        ChatFileList(listOf(file), { actions += "open" }, { actions += "share" })
                    } }
                }
            }
        } }
        for (theme in listOf(false, true)) {
            activity.scenario.onActivity { dark.value = theme; tasks.value = emptyList() }
            tap("Download")
            val headerTop = bounds("Share file").top
            capture("${if (theme) "dark" else "light"}-idle")
            for (status in ArtifactDownloadStatus.entries) {
                activity.scenario.onActivity {
                    tasks.value = listOf(ArtifactDownloadTask("task", "owner", "mac", file.remotePath!!,
                        file.name, file.mimeType, status = status, sizeBytes = file.sizeBytes, downloaded = 262_144,
                        errorCode = if (status == ArtifactDownloadStatus.FAILED) "HR-FILE-006" else null))
                }
                val statusLabel = when (status) {
                    ArtifactDownloadStatus.QUEUED -> "Queued ·"
                    ArtifactDownloadStatus.RUNNING -> "Downloading ·"
                    ArtifactDownloadStatus.PAUSED -> "Paused ·"
                    ArtifactDownloadStatus.WAITING -> "Waiting to retry ·"
                    ArtifactDownloadStatus.FAILED -> "Download incomplete ·"
                    ArtifactDownloadStatus.COMPLETE -> "Downloaded"
                    ArtifactDownloadStatus.CANCELLED -> "Download"
                }
                bounds(statusLabel, prefix = true)
                assertEquals("Header moved in $status", headerTop, bounds("Share file").top)
                capture("${if (theme) "dark" else "light"}-${status.name.lowercase()}")
                when (status) {
                    ArtifactDownloadStatus.QUEUED, ArtifactDownloadStatus.RUNNING -> { tap("Pause"); tap("Cancel") }
                    ArtifactDownloadStatus.PAUSED, ArtifactDownloadStatus.WAITING, ArtifactDownloadStatus.FAILED -> { tap("Resume"); tap("Cancel") }
                    ArtifactDownloadStatus.CANCELLED -> tap("Download")
                    ArtifactDownloadStatus.COMPLETE -> { tap(file.name); tap("Share file") }
                }
            }
        }
        activity.scenario.onActivity {
            assertEquals(10, actions.count { it == "download" })
            assertEquals(4, actions.count { it == "pause" })
            assertEquals(10, actions.count { it == "cancel" })
            assertEquals(2, actions.count { it == "open" })
            assertEquals(2, actions.count { it == "share" })
        }
    }

    private fun bounds(label: String, prefix: Boolean = false): Rect {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            find(automation.rootInActiveWindow, label, prefix)?.let { node ->
                val rect = Rect().also(node::getBoundsInScreen)
                if (!rect.isEmpty) return rect
            }
            SystemClock.sleep(50)
        }
        error("No visible attachment control: $label")
    }

    private fun find(node: AccessibilityNodeInfo?, label: String, prefix: Boolean): AccessibilityNodeInfo? {
        if (node == null || !node.refresh()) return null
        if (node.text?.toString()?.let { if (prefix) it.startsWith(label) else it == label } == true ||
            node.contentDescription?.toString() == label) return node
        for (i in 0 until node.childCount) find(node.getChild(i), label, prefix)?.let { return it }
        return null
    }

    private fun tap(label: String) {
        val target = bounds(label)
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, target.exactCenterX(), target.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { check(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        automation.waitForIdle(300, 5_000)
    }

    private fun capture(state: String) {
        // Accessibility can be updated before the corresponding frame is drawn. A two-frame
        // fence prevents a screenshot labelled "failed" from still showing the previous state.
        val drawn = java.util.concurrent.CountDownLatch(1)
        activity.scenario.onActivity { host -> host.window.decorView.postOnAnimation {
            host.window.decorView.postOnAnimation { drawn.countDown() }
        } }
        check(drawn.await(5, java.util.concurrent.TimeUnit.SECONDS))
        automation.executeShellCommand("screencap -p /sdcard/Download/hg227-$state.png").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() }
        }
    }
}
