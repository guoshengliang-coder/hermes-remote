package com.hermes.client.ui.chat

import android.content.ContentValues
import android.graphics.Rect
import android.os.SystemClock
import android.provider.MediaStore
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android touch/paint check, using public UiAutomation instead of the API-37-incompatible Espresso injector. */
@RunWith(AndroidJUnit4::class)
class ComposerActionDeviceTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    @Test fun lightAndDarkKeepTheirPaintTouchSizeAndActionOwnership() {
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val disabledBounds = java.util.concurrent.atomic.AtomicReference<Rect?>()
        var sends = 0
        var stops = 0
        for (dark in listOf(false, true)) {
            activity.scenario.onActivity { host ->
                host.setContent {
                    HermesTheme(darkTheme = dark) {
                        Surface {
                            Column(Modifier.systemBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("HG-254 · ${if (dark) "深色" else "浅色"}")
                                ComposerSurface(expanded = true) {
                                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                        ComposerActionButton(false, true, AppLanguage.ZH, { sends++ })
                                        ComposerActionButton(true, true, AppLanguage.ZH, { stops++ })
                                        ComposerActionButton(false, false, AppLanguage.ZH, { sends++ }, Modifier.onGloballyPositioned {
                                            // Android accessibility may omit disabled actions; use their actual layout bounds.
                                            val b = it.boundsInWindow()
                                            val offset = IntArray(2).also { host.window.decorView.getLocationOnScreen(it) }
                                            disabledBounds.set(Rect(b.left.toInt() + offset[0], b.top.toInt() + offset[1],
                                                b.right.toInt() + offset[0], b.bottom.toInt() + offset[1]))
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val send = awaitAction("发送", true)
            val stop = awaitAction("停止", true)
            val disabled = checkNotNull(disabledBounds.get())
            val density = instrumentation.targetContext.resources.displayMetrics.density
            assertEquals(48f, send.width() / density, 0.6f)
            assertEquals(48f, send.height() / density, 0.6f)
            assertEquals(send.width(), stop.width())
            assertEquals(send.width(), disabled.width())
            val fill = if (dark) 0xFF1E232B.toInt() else 0xFF181C24.toInt()
            var screenshot = checkNotNull(automation.takeScreenshot())
            val paintDeadline = SystemClock.uptimeMillis() + 5_000
            // Main-thread idle can precede the compositor's next frame after a theme change.
            while (screenshot.getPixel(send.centerX(), send.centerY() + (16 * density).toInt()) != fill &&
                SystemClock.uptimeMillis() < paintDeadline) {
                screenshot.recycle()
                SystemClock.sleep(50)
                screenshot = checkNotNull(automation.takeScreenshot())
            }
            assertEquals(fill, screenshot.getPixel(send.centerX(), send.centerY() + (16 * density).toInt()))
            assertColorNear(if (dark) 0xFF1A1D21.toInt() else 0xFFFAF9F6.toInt(),
                screenshot.getPixel(send.centerX(), send.top + 1))
            assertColorNear(if (dark) 0xFF25282B.toInt() else 0xFFECEBE8.toInt(),
                screenshot.getPixel(disabled.centerX(), disabled.centerY() + (16 * density).toInt()))
            val uri = instrumentation.targetContext.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "hg254-refined-${if (dark) "dark" else "light"}.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/HermesGo-hg254-refined")
                })
            assertNotNull(uri)
            instrumentation.targetContext.contentResolver.openOutputStream(uri!!)!!.use {
                check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            screenshot.recycle()
            val beforeSend = sends
            val beforeStop = stops
            tap(send, edge = true)
            tap(stop)
            tap(disabled)
            tap(disabled, edge = true)
            instrumentation.waitForIdleSync()
            assertEquals(beforeSend + 1, sends)
            assertEquals(beforeStop + 1, stops)
        }
    }

    private fun awaitAction(description: String, enabled: Boolean): Rect {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            find(automation.rootInActiveWindow, description, enabled)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Missing fixture action $description enabled=$enabled")
    }

    private fun find(node: AccessibilityNodeInfo?, description: String, enabled: Boolean): Rect? {
        if (node == null) return null
        if (node.contentDescription?.toString() == description && node.isEnabled == enabled) {
            // Compose can expose the 24dp glyph separately from its 48dp clickable ancestor.
            var target: AccessibilityNodeInfo = node
            while (!target.isClickable) target = target.parent ?: break
            return Rect().also { target.getBoundsInScreen(it) }
        }
        for (index in 0 until node.childCount) find(node.getChild(index), description, enabled)?.let { return it }
        return null
    }

    private fun assertColorNear(expected: Int, actual: Int) {
        for (shift in listOf(0, 8, 16, 24)) {
            org.junit.Assert.assertTrue("expected=${expected.toUInt().toString(16)} actual=${actual.toUInt().toString(16)}",
                kotlin.math.abs(((expected ushr shift) and 255) - ((actual ushr shift) and 255)) <= 1)
        }
    }

    private fun tap(bounds: Rect, edge: Boolean = false) {
        val start = SystemClock.uptimeMillis()
        for ((eventTime, action) in listOf(start to MotionEvent.ACTION_DOWN, start + 80 to MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(start, eventTime, action, if (edge) bounds.left + 1f else bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
                check(automation.injectInputEvent(this, true))
                recycle()
            }
        }
    }
}
