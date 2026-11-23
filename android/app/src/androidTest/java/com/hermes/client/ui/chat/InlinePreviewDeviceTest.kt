package com.hermes.client.ui.chat

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hermes.client.domain.PreviewDirective
import com.hermes.client.ui.theme.HermesTheme
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InlinePreviewDeviceTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun widePreviewScrollsHorizontallyWithoutStealingPageClicksOrChatScroll() {
        val expansions = AtomicInteger()
        var chatScroll = { 0 }
        activity.scenario.onActivity { activity ->
            activity.setContent {
                HermesTheme {
                    val scope = rememberCoroutineScope()
                    val scroll = rememberScrollState()
                    chatScroll = { scroll.value }
                    val host = remember {
                        InlinePreviewHost(
                            scope = scope,
                            fetch = {
                                """<div id="wide" style="overflow-x:auto;width:100%;height:210px">
                                    <button id="control" style="width:1000px;height:180px;background:var(--accent)"
                                        onclick="if(event.isTrusted){this.dataset.clicks=Number(this.dataset.clicks||0)+1;
                                            setTimeout(()=>document.getElementById('details').hidden=false,150)}">Wide chart</button>
                                </div><div id="details" hidden style="height:140px">Details</div>"""
                            },
                            cwd = { "/" },
                            open = {},
                            share = {},
                            openFullscreen = { expansions.incrementAndGet() },
                            openLink = {},
                        )
                    }
                    CompositionLocalProvider(LocalInlinePreviewHost provides host) {
                        Column(Modifier.systemBarsPadding().verticalScroll(scroll)) {
                            InlinePreviewBlock(PreviewDirective("/fixture.html"), "fixture", false)
                            Spacer(Modifier.height(1400.dp))
                        }
                    }
                }
            }
        }
        lateinit var webView: WebView
        await("layout") {
            var found: WebView? = null
            activity.scenario.onActivity { found = findWebView(it.window.decorView) }
            found?.takeIf { it.height > 100 }?.also { webView = it } != null
        }
        fun evaluate(script: String): String? {
            val values = ArrayBlockingQueue<String>(1)
            instrumentation.runOnMainSync {
                webView.evaluateJavascript(script) { values.offer(it ?: "-1") }
            }
            return values.poll(2, TimeUnit.SECONDS)
        }
        fun position() = evaluate("document.getElementById('wide')?.scrollLeft ?? -1")?.toDoubleOrNull() ?: -1.0
        fun chatPosition(): Int {
            var value = -1
            instrumentation.runOnMainSync { value = chatScroll() }
            return value
        }
        await("document") { position() == 0.0 }
        val leftTop = IntArray(2)
        var width = 0
        var height = 0
        instrumentation.runOnMainSync {
            webView.getLocationOnScreen(leftTop)
            width = webView.width
            height = webView.height
        }
        val x = leftTop[0] + width * 0.8f
        val y = leftTop[1] + height * 0.65f
        gesture(x, y, -width * 0.6f, 0f)
        await("chart scroll") { position() > 50 }
        val maxScroll = evaluate("let w = document.getElementById('wide'); w.scrollWidth - w.clientWidth")
            ?.toDoubleOrNull() ?: error("Missing scroll width")
        repeat(3) { gesture(x, y, -width * 0.6f, 0f) }
        await("rightmost column") { position() >= maxScroll - 1 }
        assertEquals(0, chatPosition())
        fun clicks() = evaluate("Number(document.getElementById('control').dataset.clicks || 0)")?.toIntOrNull()
        assertEquals(0, expansions.get())
        assertEquals(0, clicks())
        // Interact after the initial page measurements have finished.
        SystemClock.sleep(4_200)
        gesture(x, y, 0f, 0f)
        await("trusted page click") { clicks() == 1 }
        assertEquals(0, expansions.get())
        await("expanded details are inside the inline frame") {
            var grown = false
            instrumentation.runOnMainSync { grown = webView.height > height }
            grown
        }
        var expandBounds: Rect? = null
        await("fullscreen button") {
            expandBounds = findExpand(instrumentation.uiAutomation.rootInActiveWindow)
            expandBounds != null
        }
        val bounds = checkNotNull(expandBounds)
        gesture(bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0f, 0f)
        await("expand") { expansions.get() == 1 }
        gesture(x, y, 0f, -height * 0.5f)
        await("chat scroll") { chatPosition() > 0 }
        assertEquals(1, clicks())
        assertEquals(1, expansions.get())
    }

    private fun findExpand(node: AccessibilityNodeInfo?): Rect? {
        if (node == null) return null
        if (node.contentDescription?.toString() in listOf("全屏查看", "Open full screen")) {
            var target: AccessibilityNodeInfo? = node
            while (target?.isClickable == false) target = target.parent
            return target?.let { action -> Rect().also(action::getBoundsInScreen) }
        }
        for (index in 0 until node.childCount) findExpand(node.getChild(index))?.let { return it }
        return null
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun gesture(x: Float, y: Float, dx: Float, dy: Float) {
        val start = SystemClock.uptimeMillis()
        for (step in 0..12) {
            if (dx == 0f && dy == 0f && step in 1..11) continue
            val action = when (step) {
                0 -> MotionEvent.ACTION_DOWN
                12 -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x + dx * step / 12, y + dy * step / 12, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
            SystemClock.sleep(18)
        }
    }

    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(50)
        }
        assertTrue(label, predicate())
    }
}
