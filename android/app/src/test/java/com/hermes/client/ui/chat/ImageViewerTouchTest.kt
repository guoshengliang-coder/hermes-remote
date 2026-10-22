package com.hermes.client.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.swipeLeft
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermes.client.ui.InChinese
import com.hermes.client.ui.theme.HermesTheme
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** HG-225: exercise the actual pointer modifiers and assert the rendered image, not helper math. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-mdpi")
@OptIn(ExperimentalRoborazziApi::class)
class ImageViewerTouchTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val folder = TemporaryFolder()

    private fun fixture(markerColor: Int = Color.RED, multiplier: Int = 1): ByteArray {
        val bitmap = Bitmap.createBitmap(1000 * multiplier, 200 * multiplier, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.CYAN)
        Canvas(bitmap).apply {
            scale(multiplier.toFloat(), multiplier.toFloat())
            drawRect(450f, 50f, 550f, 150f, Paint().apply { color = markerColor })
        }
        return ByteArrayOutputStream().also {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            bitmap.recycle()
        }.toByteArray()
    }

    private fun open(dark: Boolean = false, multiple: Boolean = false): androidx.compose.runtime.MutableState<ImageSource> {
        val source = mutableStateOf<ImageSource>(ImageSource.Bytes("preview", fixture()))
        compose.setContent {
            InChinese {
                HermesTheme(darkTheme = dark) {
                    Surface(Modifier.fillMaxSize()) {
                        val items = listOf(ImageViewerItem("first", source.value)) +
                            if (multiple) listOf(ImageViewerItem("second", source.value)) else emptyList()
                        ImageViewerContent(items, "first", ImageViewerChrome.Pending({}, {}), {}, {})
                    }
                }
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("查看原图").fetchSemanticsNodes().isNotEmpty()
        }
        return source
    }

    private fun pinch(start: Float, end: Float) {
        compose.onRoot().performTouchInput {
            down(0, center + Offset(-start, 0f))
            down(1, center + Offset(start, 0f))
        }
        // Dispatch across frames, including recomposition while the SAME gesture is active.
        for (step in 1..12) {
            val distance = start + (end - start) * step / 12
            compose.onRoot().performTouchInput {
                moveTo(0, center + Offset(-distance, 0f), delayMillis = 0)
                moveTo(1, center + Offset(distance, 0f), delayMillis = 16)
            }
            compose.waitForIdle()
        }
        compose.onRoot().performTouchInput { up(1); up(0) }
        compose.waitForIdle()
    }

    private fun marker(target: Int = Color.RED): Rect {
        val file = File(folder.root, "frame.png")
        compose.onRoot().captureRoboImage(file.absolutePath,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            var left = bitmap.width
            var top = bitmap.height
            var right = -1
            var bottom = -1
            pixels.forEachIndexed { index, color ->
                if (kotlin.math.abs(Color.red(color) - Color.red(target)) < 15 &&
                    kotlin.math.abs(Color.green(color) - Color.green(target)) < 15 &&
                    kotlin.math.abs(Color.blue(color) - Color.blue(target)) < 15) {
                    val x = index % bitmap.width
                    val y = index / bitmap.width
                    left = minOf(left, x); right = maxOf(right, x)
                    top = minOf(top, y); bottom = maxOf(bottom, y)
                }
            }
            assertTrue("The fixture marker must be visible", right >= left && bottom >= top)
            return Rect(left, top, right + 1, bottom + 1)
        } finally { bitmap.recycle() }
    }

    @Test fun continuousPinchAccumulatesAndHoldsAfterRelease() {
        open()
        val fit = marker()
        pinch(40f, 120f)
        val zoomed = marker()
        assertEquals("A 3x finger spread must render a 3x image", 3f,
            zoomed.height().toFloat() / fit.height(), 0.15f)
        compose.mainClock.advanceTimeBy(500)
        assertEquals("Lifting the fingers must preserve zoom", zoomed, marker())
    }

    @Test fun consecutivePinchesClampAtFiveAndReturnToFitInDarkTheme() {
        open(dark = true)
        val fit = marker().height()
        pinch(40f, 120f)
        pinch(40f, 120f)
        assertEquals(5f, marker().height().toFloat() / fit, 0.15f)
        pinch(120f, 24f)
        assertEquals(1f, marker().height().toFloat() / fit, 0.15f)
        pinch(120f, 40f)
        assertEquals("Cannot shrink below fit", 1f, marker().height().toFloat() / fit, 0.15f)
    }

    @Test fun zoomedPanAccumulatesInViewportPixelsAndReverses() {
        open()
        pinch(40f, 120f)
        val initial = marker()
        compose.onRoot().performTouchInput { down(center) }
        for (step in 1..4) {
            compose.onRoot().performTouchInput { moveTo(center + Offset(step * 15f, 0f)) }
            compose.waitForIdle()
        }
        compose.onRoot().performTouchInput { up() }
        assertEquals("The image follows the finger by 60 viewport pixels", 60f,
            marker().exactCenterX() - initial.exactCenterX(), 2f)
        compose.onRoot().performTouchInput {
            down(center + Offset(60f, 0f))
            moveTo(center)
            up()
        }
        assertEquals(initial.exactCenterX(), marker().exactCenterX(), 2f)
    }

    @Test fun doubleTapResetsPinchAndPagingResumesThenZoomsAgain() {
        open(multiple = true)
        val fit = marker().height()
        pinch(40f, 120f)
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.onNodeWithText("1 / 2").assertIsDisplayed()
        compose.onRoot().performTouchInput { doubleClick() }
        assertEquals(fit.toFloat(), marker().height().toFloat(), 2f)
        compose.onRoot().performTouchInput { doubleClick() }
        assertEquals(2.5f, marker().height().toFloat() / fit, 0.15f)
        compose.onRoot().performTouchInput { doubleClick() }
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.onNodeWithText("2 / 2").assertIsDisplayed()
        assertEquals("A new page starts at fit", fit.toFloat(), marker().height().toFloat(), 2f)
    }

    @Test fun twoFingerDragAtFitNeverHandsTheGestureToThePager() {
        open(multiple = true)
        val fit = marker()
        compose.onRoot().performTouchInput {
            down(0, center + Offset(-40f, 0f))
            down(1, center + Offset(40f, 0f))
            for (step in 1..12) {
                // Move both pointers atomically so this is a pan, with no zoom delta.
                updatePointerTo(0, center + Offset(-40f - step * 12f, 0f))
                updatePointerTo(1, center + Offset(40f - step * 12f, 0f))
                move()
            }
            up(1); up(0)
        }
        compose.onNodeWithText("1 / 2").assertIsDisplayed()
        assertEquals(fit, marker())
    }

    @Test fun originalImageReplacementRetainsZoomAndFurtherGestures() {
        val source = open()
        val fit = marker().height()
        pinch(40f, 120f)
        compose.runOnIdle { source.value = ImageSource.Bytes("original", fixture(Color.GREEN, 2)) }
        // Wait for the new pixels, not just for the source state to change; decode runs on IO.
        compose.waitUntil(5_000) {
            runCatching { marker(Color.GREEN) }.isSuccess
        }
        assertEquals(3f, marker(Color.GREEN).height().toFloat() / fit, 0.15f)
        pinch(120f, 60f)
        assertEquals(1.5f, marker(Color.GREEN).height().toFloat() / fit, 0.15f)
    }
}
