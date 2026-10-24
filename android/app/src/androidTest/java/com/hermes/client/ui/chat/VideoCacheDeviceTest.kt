package com.hermes.client.ui.chat

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hermes.client.data.video.ArtifactRangeRepository
import com.hermes.client.data.video.VideoCacheCapacity
import com.hermes.client.domain.ArtifactDownloadDescriptor
import com.hermes.client.ui.settings.VideoCacheContent
import com.hermes.client.ui.localization.*
import com.hermes.client.ui.theme.HermesTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class VideoCacheDeviceTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val activity = ActivityScenarioRule(ComponentActivity::class.java)
    @Inject lateinit var ranges: ArtifactRangeRepository
    @Before fun inject() { hilt.inject() }

    @Test fun capacity_and_manual_cleanup_keep_download_intact(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ranges.clearCache(); ranges.setCacheCapacity(VideoCacheCapacity.DEFAULT)
        val complete = File(context.filesDir, "video-cache-device-fixture.mp4")
        val bytes = ByteArray(256 * 1024) { 42 }; complete.writeBytes(bytes)
        val descriptor = ArtifactDownloadDescriptor("c".repeat(64), bytes.size.toLong(), "\"${"d".repeat(64)}\"", "video/mp4", 8L * 1024 * 1024 * 1024)
        ranges.publish("test-owner", "/fixture/cache.mp4", descriptor, complete, 0)
        ranges.refreshCache()
        activity.scenario.onActivity { host -> host.setContent {
            HermesTheme(darkTheme = true) { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.EN) {
                val state by ranges.cacheState.collectAsState()
                val scope = rememberCoroutineScope()
                VideoCacheContent(state, false, null, {},
                    { scope.launch { ranges.setCacheCapacity(it) } }, { scope.launch { ranges.clearCache() } }, {})
            } }
        } }
        try {
            click("5GB")
            await { ranges.cacheState.value.maxBytes == VideoCacheCapacity.LARGE.bytes }
            val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(context.getExternalFilesDir(null), "video-cache-settings.png").outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            click("Clear preview cache"); click("Clear")
            await { ranges.cacheState.value.usedBytes == 0L }
            assertArrayEquals(bytes, complete.readBytes())
        } finally { ranges.setCacheCapacity(VideoCacheCapacity.DEFAULT); complete.delete() }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
        while (!condition() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertTrue(condition())
    }
    private fun click(text: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
        do {
            fun find(n: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
                if (n.text?.toString() == text || n.contentDescription?.toString() == text) return n
                for (i in 0 until n.childCount) n.getChild(i)?.let { find(it)?.let { found -> return found } }
                return null
            }
            val roots = automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)
            var target = roots.firstNotNullOfOrNull(::find)
            while (target != null && !target.isClickable) target = target.parent
            if (target?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true) return
            Thread.sleep(50)
        } while (android.os.SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("missing cache control: $text")
    }
}
