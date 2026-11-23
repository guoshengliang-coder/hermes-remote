package com.hermes.client.ui.chat

import android.graphics.Rect
import android.os.SystemClock
import android.provider.MediaStore
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets as PlatformInsets
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hermes.client.ui.theme.HermesTheme
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real IME + the production new-chat window. A direct ChatScreen screenshot misses this boundary. */
@RunWith(AndroidJUnit4::class)
class NewChatKeyboardDeviceTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    @Test fun dialogAvoidsTheKeyboardOnceAndKeepsDraftAcrossRepeatedOpenClose() {
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val footer = AtomicReference<Rect?>()
        val view = AtomicReference<View?>()
        val screenHeight = checkNotNull(automation.takeScreenshot()).let { bitmap -> bitmap.height.also { bitmap.recycle() } }
        fun atSystemEdge(): Boolean {
            val current = view.get() ?: return false
            val insets = current.rootWindowInsets ?: return false
            val edge = screenHeight - insets.getInsets(PlatformInsets.Type.ime() or PlatformInsets.Type.navigationBars()).bottom
            val gap = edge - (footer.get()?.bottom ?: return false)
            return gap in 0..(12 * current.resources.displayMetrics.density).toInt()
        }
        for ((dark, scale) in listOf(false to 1f, true to 1.3f)) {
            activity.scenario.onActivity { host ->
                host.setContent {
                    HermesTheme(darkTheme = dark) {
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                            NewChatWindow(onDismiss = {}) {
                                val currentView = LocalView.current
                                SideEffect { view.set(currentView) }
                                var draft by remember { mutableStateOf("HG263 draft") }
                                Scaffold(bottomBar = {
                                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
                                        .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
                                        ComposerSurface(expanded = true) {
                                            TextField(value = draft, onValueChange = { draft = it },
                                                modifier = Modifier.fillMaxWidth())
                                        }
                                        Text("HG263 footer", Modifier.onGloballyPositioned { coordinates ->
                                            val b = coordinates.boundsInWindow()
                                            val offset = IntArray(2).also(currentView::getLocationOnScreen)
                                            footer.set(Rect(b.left.toInt() + offset[0], b.top.toInt() + offset[1],
                                                b.right.toInt() + offset[0], b.bottom.toInt() + offset[1]))
                                        })
                                    }
                                }) { padding -> Text("HG263 new chat $dark", Modifier.padding(padding)) }
                            }
                        }
                    }
                }
            }
            awaitNode { it.text?.toString() == "HG263 new chat $dark" }
            awaitCondition("Initial new-chat layout did not settle", ::atSystemEdge)
            automation.waitForIdle(100, 2_000)
            repeat(2) { cycle ->
                val field = awaitNode { it.isEditable }
                val bounds = Rect().also(field::getBoundsInScreen)
                tap(bounds.centerX(), bounds.centerY())
                awaitCondition("IME did not open: dark=$dark cycle=$cycle field=$bounds") { view.get()?.rootWindowInsets?.isVisible(PlatformInsets.Type.ime()) == true }
                awaitCondition("Composer did not settle at the IME edge: footer=${footer.get()}", ::atSystemEdge)
                assertEquals("HG263 draft", awaitNode { it.isEditable }.text.toString())
                capture("hg263-keyboard-${if (dark) "dark" else "light"}-$cycle.png")
                automation.executeShellCommand("input keyevent KEYCODE_BACK").close()
                awaitCondition("IME did not close") { view.get()?.rootWindowInsets?.isVisible(PlatformInsets.Type.ime()) == false }
                // Visibility flips before the insets animation and accessibility bounds settle.
                awaitCondition("Composer did not settle after IME close", ::atSystemEdge)
                automation.waitForIdle(100, 2_000)
                assertNotNull(awaitNode { it.text?.toString() == "HG263 new chat $dark" })
            }
        }
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        do {
            if (condition()) return
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        capture("hg263-keyboard-failure.png")
        fail(message)
    }

    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        awaitCondition("Expected new-chat node missing") {
            fun search(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (predicate(node)) return node
                for (i in 0 until node.childCount) search(node.getChild(i))?.let { return it }
                return null
            }
            found = automation.windows.asSequence().mapNotNull { search(it.root) }.firstOrNull()
            found != null
        }
        return checkNotNull(found)
    }

    private fun tap(x: Int, y: Int) {
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0).also {
                it.source = InputDevice.SOURCE_TOUCHSCREEN
                assertTrue(automation.injectInputEvent(it, true))
                it.recycle()
            }
        }
    }

    private fun capture(name: String) {
        val context = instrumentation.targetContext
        val uri = checkNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            android.content.ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/HermesTests")
            }))
        val bitmap = checkNotNull(automation.takeScreenshot())
        context.contentResolver.openOutputStream(uri)!!.use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
