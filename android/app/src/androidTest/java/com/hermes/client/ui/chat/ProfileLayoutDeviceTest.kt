package com.hermes.client.ui.chat

import android.content.ContentValues
import android.graphics.Rect
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hermes.client.domain.Session
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.sessions.SessionSubline
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production components on the platform, through the public accessibility API. */
@RunWith(AndroidJUnit4::class)
class ProfileLayoutDeviceTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @Test fun roleStaysBelowTitleWithAndWithoutDirectory() {
        for (dark in listOf(false, true)) for (project in listOf(false, true)) {
            val title = "${if (dark) "深色" else "浅色"}${if (project) "项目" else "普通"}会话"
            activity.scenario.onActivity { host -> host.setContent {
                HermesTheme(darkTheme = dark) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalAppLanguage provides AppLanguage.ZH,
                        LocalDensity provides Density(density.density, if (dark) 1.3f else 1f)) {
                        Surface(Modifier.fillMaxSize()) { Column {
                            ChatTopBar(title, true, false, false, "我的提问", {}, {}, {}, {}, {}, {}, {},
                                roleName = "芯芯", subtitle = if (project) { { WorkspaceSubtitle("hermes-remote", "main", true, {}, compact = true) } } else null)
                            SessionSubline(Session(id = "qa", title = "本周工作安排", model = "gpt-6.2-sol",
                                provider = null, messageCount = 1, profile = "work", cwd = "/qa/project", gitRepoRoot = "/qa/project"),
                                pinned = true, hasDraft = true, modifier = Modifier.padding(16.dp))
                        } }
                    }
                }
            } }
            val titleBounds = awaitText(title)
            val roleBounds = awaitText("芯芯")
            assertTrue("role belongs below the title", roleBounds.top >= titleBounds.bottom)
            if (project) assertTrue("project must remain visible", awaitText("hermes-remote").width() > 0)
            assertTrue(awaitText("work").width() > 0)
            SystemClock.sleep(250) // Accessibility may precede the compositor's settled frame.
            val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            val uri = instrumentation.targetContext.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "hg261262-${if (dark) "dark" else "light"}-${if (project) "project" else "plain"}.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/HermesGo-hg261262")
                })
            instrumentation.targetContext.contentResolver.openOutputStream(checkNotNull(uri))!!.use {
                check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            screenshot.recycle()
        }
    }
    private fun awaitText(text: String): Rect {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            find(instrumentation.uiAutomation.rootInActiveWindow, text)?.let { return it }
            SystemClock.sleep(50)
        }
        error("Missing fixture text: $text")
    }
    private fun find(node: AccessibilityNodeInfo?, text: String): Rect? {
        if (node == null) return null
        if (node.text?.toString() == text) return Rect().also { node.getBoundsInScreen(it) }
        for (i in 0 until node.childCount) find(node.getChild(i), text)?.let { return it }
        return null
    }
}
