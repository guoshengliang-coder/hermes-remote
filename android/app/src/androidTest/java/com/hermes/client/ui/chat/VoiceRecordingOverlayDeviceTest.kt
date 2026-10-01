package com.hermes.client.ui.chat

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Assert.assertTrue
import org.junit.Test

/** HG-188: Android layout/semantics; tactile strength still needs physical hardware. */
@SdkSuppress(minSdkVersion = 29)
class VoiceRecordingOverlayDeviceTest {
    @Test fun releaseCopyTracksSendCancelAndEdit() {
        val action = mutableStateOf(VoiceReleaseAction.SEND)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    HermesTheme(darkTheme = true) {
                        Surface {
                            VoiceRecordingOverlay(
                                language = AppLanguage.ZH, held = true, waiting = false,
                                transcript = "", cancelZone = action.value == VoiceReleaseAction.CANCEL,
                                editZone = action.value == VoiceReleaseAction.EDIT, elapsedMs = 12_400L,
                                onDismissWaiting = {}, onTargetsMeasured = {},
                            )
                        }
                    }
                }
            }
            fun hasText(node: AccessibilityNodeInfo?, text: String): Boolean {
                if (node == null) return false
                if (node.text?.toString() == text) return true
                return (0 until node.childCount).any { hasText(node.getChild(it), text) }
            }
            for ((selection, copy) in listOf(
                VoiceReleaseAction.SEND to "松手发送", VoiceReleaseAction.CANCEL to "松手取消",
                VoiceReleaseAction.EDIT to "松手转文字",
            )) {
                scenario.onActivity { action.value = selection }
                instrumentation.waitForIdleSync()
                instrumentation.uiAutomation.waitForIdle(500L, 5_000L)
                val deadline = android.os.SystemClock.uptimeMillis() + 5_000L
                while (!hasText(instrumentation.uiAutomation.rootInActiveWindow, copy)
                    && android.os.SystemClock.uptimeMillis() < deadline) {
                    Thread.sleep(50L)
                }
                assertTrue(copy, hasText(instrumentation.uiAutomation.rootInActiveWindow, copy))
            }
            // Test-generated screenshot remains available after AGP uninstalls the test app.
            val resolver = instrumentation.targetContext.contentResolver
            val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "hg188-voice-edit.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/hermes-ui-verification")
            }))
            val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            checkNotNull(resolver.openOutputStream(uri)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
