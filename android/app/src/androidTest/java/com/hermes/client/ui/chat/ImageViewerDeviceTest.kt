package com.hermes.client.ui.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.theme.HermesTheme
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Fullscreen Dialog + native two-pointer MotionEvents; no private Espresso injector APIs. */
@RunWith(AndroidJUnit4::class)
class ImageViewerDeviceTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private var frame = 0

    @Test fun continuousPinchInLightTheme() = verify(false)
    @Test fun continuousPinchInDarkTheme() = verify(true)

    private fun verify(dark: Boolean) {
        val bitmap = Bitmap.createBitmap(1000, 200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.CYAN)
        Canvas(bitmap).drawRect(450f, 50f, 550f, 150f, Paint().apply { color = Color.RED })
        val bytes = ByteArrayOutputStream().also {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        bitmap.recycle()
        activity.scenario.onActivity { host ->
            host.setContent {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.ZH) {
                    HermesTheme(darkTheme = dark) {
                        ImageViewer(listOf(ImageViewerItem("fixture", ImageSource.Bytes("fixture", bytes))),
                            "fixture", ImageViewerChrome.Pending({}, {}), {}, {})
                    }
                }
            }
        }
        val deadline = SystemClock.uptimeMillis() + 10_000
        var viewport: Rect? = null
        while (viewport == null && SystemClock.uptimeMillis() < deadline) {
            viewport = findImage(automation.rootInActiveWindow)
            if (viewport == null) SystemClock.sleep(50)
        }
        val area = checkNotNull(viewport) { "Fullscreen image did not appear" }
        dismissFullscreenEducation()
        SystemClock.sleep(300)
        val fit = marker()
        android.util.Log.d("ImageViewerDeviceTest", "viewport=$area fit=$fit")
        val x = area.exactCenterX()
        val y = area.exactCenterY()
        val start = area.width() * 0.1f
        val downTime = SystemClock.uptimeMillis()
        inject(downTime, MotionEvent.ACTION_DOWN, x, y, start, 1)
        inject(downTime, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            x, y, start, 2)
        for (step in 1..18) {
            SystemClock.sleep(32)
            inject(downTime, MotionEvent.ACTION_MOVE, x, y, start * (1 + 2f * step / 18), 2)
        }
        inject(downTime, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            x, y, start * 3, 2)
        inject(downTime, MotionEvent.ACTION_UP, x, y, start * 3, 1)
        SystemClock.sleep(300)
        val zoomed = marker()
        assertEquals("Continuous native pinch must accumulate to 3x", 3f,
            zoomed.height().toFloat() / fit.height(), 0.2f)
        SystemClock.sleep(300)
        assertEquals("Zoom survives finger release", zoomed, marker())
        val screenshot = checkNotNull(automation.takeScreenshot())
        try {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.getExternalFilesDir(null), "hg225-pinch-${if (dark) "dark" else "light"}.png")
                .outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { screenshot.recycle() }
    }

    private fun inject(downTime: Long, action: Int, x: Float, y: Float, spread: Float, count: Int) {
        val properties = Array(count) { index -> MotionEvent.PointerProperties().apply {
            id = index; toolType = MotionEvent.TOOL_TYPE_FINGER
        } }
        val coords = Array(count) { index -> MotionEvent.PointerCoords().apply {
            this.x = x + if (index == 0) -spread else spread
            this.y = y; pressure = 1f; size = 1f
        } }
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, count,
            properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { check(automation.injectInputEvent(event, true)) } finally { event.recycle() }
    }

    private fun findImage(node: AccessibilityNodeInfo?): Rect? {
        if (node == null) return null
        if (node.contentDescription?.toString() == "查看原图") {
            return Rect().also(node::getBoundsInScreen).takeUnless { it.isEmpty }
        }
        for (index in 0 until node.childCount) findImage(node.getChild(index))?.let { return it }
        return null
    }

    private fun dismissFullscreenEducation() {
        // Android can show this system education AFTER the first fullscreen frame. Its dim layer
        // changes screenshot pixels and intercepts touches; dismiss it by its actual accessible
        // button, without altering global immersive-mode settings or app permissions.
        val deadline = SystemClock.uptimeMillis() + 2_000
        while (SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            if (root?.findAccessibilityNodeInfosByText("Viewing full screen")?.isNotEmpty() == true) {
                val button = root.findAccessibilityNodeInfosByText("Got it").singleOrNull()
                check(button?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
                return
            }
            SystemClock.sleep(50)
        }
    }

    private fun marker(): Rect {
        val bitmap = checkNotNull(automation.takeScreenshot())
        try {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.getExternalFilesDir(null), "hg225-frame-${frame++}.png")
                .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            var left = bitmap.width
            var top = bitmap.height
            var right = -1
            var bottom = -1
            pixels.forEachIndexed { index, color ->
                if (Color.red(color) > 240 && Color.green(color) < 20 && Color.blue(color) < 20) {
                    val x = index % bitmap.width
                    val y = index / bitmap.width
                    left = minOf(left, x); right = maxOf(right, x)
                    top = minOf(top, y); bottom = maxOf(bottom, y)
                }
            }
            assertTrue("Fixture marker is visible", right >= left && bottom >= top)
            return Rect(left, top, right + 1, bottom + 1)
        } finally { bitmap.recycle() }
    }
}
