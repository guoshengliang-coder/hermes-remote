package com.hermes.client.ui.chat.imageedit

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import com.hermes.client.ui.InChinese
import com.hermes.client.ui.theme.HermesTheme
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Behaviour, asserted through hoisted callbacks rather than pixels.
 *
 * Robolectric can synthesise a drag but not the frame pacing or the two-finger races that decide
 * real feel — those are `docs/ANDROID_SMOKE.md` A-10. What it can pin is the logic: one gesture
 * commits one op, the crop tool draws nothing, and Done fires once.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class ImageEditorGestureTest {
    private companion object {
        const val SOURCE_WIDTH = 400f
        const val SOURCE_HEIGHT = 300f
    }

    @get:Rule val compose = createComposeRule()

    private fun pngBytes(width: Int = SOURCE_WIDTH.toInt(), height: Int = SOURCE_HEIGHT.toInt()): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun show(
        onCancel: () -> Unit = {},
        onDone: (ImageEditResult) -> Unit = {},
    ) {
        compose.setContent {
            InChinese {
                HermesTheme(darkTheme = true) {
                    ImageEditorContent(pngBytes(), onCancel = onCancel, onDone = onDone)
                }
            }
        }
        // The working bitmap is decoded on a real IO thread, so wait for the toolbar rather than
        // sleeping a fixed amount.
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasContentDescription("涂鸦")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun drawAcrossTheCanvas() {
        compose.onRoot().performTouchInput {
            swipe(start = Offset(centerX - 80f, centerY), end = Offset(centerX + 80f, centerY), durationMillis = 200)
        }
        compose.waitForIdle()
    }

    @Test
    fun oneDragInDoodleModeCommitsExactlyOneOp() {
        show()
        compose.onNodeWithContentDescription("撤销").assertIsNotEnabled()

        drawAcrossTheCanvas()

        compose.onNodeWithContentDescription("撤销").assertIsEnabled()
        // A second undo step would mean the drag committed more than once.
        compose.onNodeWithContentDescription("撤销").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("撤销").assertIsNotEnabled()
    }

    @Test
    fun undoingMakesRedoAvailableAndRedoRestores() {
        show()
        drawAcrossTheCanvas()

        compose.onNodeWithContentDescription("重做").assertIsNotEnabled()
        compose.onNodeWithContentDescription("撤销").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("重做").assertIsEnabled()

        compose.onNodeWithContentDescription("重做").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("撤销").assertIsEnabled()
    }

    /**
     * Mode isolation. Dragging a corner in the crop tool must change the crop and **only** the crop.
     *
     * "Reset crop" is the tell: it enables when there is a crop or a rotation but not when there is
     * a stroke. Undoing once clearing both is what proves the single drag committed one thing rather
     * than a crop and an accidental line.
     */
    @Test
    fun draggingACropHandleChangesTheCropAndNothingElse() {
        show()
        compose.onNodeWithContentDescription("裁切").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("重置裁切").assertIsNotEnabled()

        compose.onNodeWithTag(CropOverlayTestTag).performTouchInput {
            // The crop starts at the fitted image's own rectangle, so its top edge is derivable from
            // the overlay's bounds and the 4:3 source — no guessing at coordinates.
            val displayHeight = width * SOURCE_HEIGHT / SOURCE_WIDTH
            val cropTop = (height - displayHeight) / 2f
            val handle = Offset(centerX, cropTop)
            swipe(start = handle, end = handle + Offset(0f, 150f), durationMillis = 200)
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("重置裁切").assertIsEnabled()
        compose.onNodeWithContentDescription("撤销").assertIsEnabled()

        compose.onNodeWithContentDescription("撤销").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("重置裁切").assertIsNotEnabled()
        compose.onNodeWithContentDescription("撤销").assertIsNotEnabled()
    }

    /** A drag clamped against the image edge changes nothing, and must not leave an undo step. */
    @Test
    fun aCropDragThatMovesNothingAddsNoUndoStep() {
        show()
        compose.onNodeWithContentDescription("裁切").performClick()
        compose.waitForIdle()

        // From the centre this grabs MOVE, and a full-frame crop has nowhere to move to.
        drawAcrossTheCanvas()

        compose.onNodeWithContentDescription("撤销").assertIsNotEnabled()
        compose.onNodeWithContentDescription("重置裁切").assertIsNotEnabled()
    }

    @Test
    fun cancellingWithNoEditsClosesWithoutAsking() {
        var cancelled = 0
        show(onCancel = { cancelled++ })

        compose.onNodeWithText("取消").performClick()
        compose.waitForIdle()

        assertEquals(1, cancelled)
    }

    @Test
    fun cancellingWithEditsAsksFirstAndCanBeBackedOut() {
        var cancelled = 0
        show(onCancel = { cancelled++ })
        drawAcrossTheCanvas()

        compose.onNodeWithText("取消").performClick()
        compose.waitForIdle()
        assertEquals("must not discard silently", 0, cancelled)

        compose.onNodeWithText("继续编辑").performClick()
        compose.waitForIdle()
        assertEquals(0, cancelled)

        compose.onNodeWithText("取消").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("放弃").performClick()
        compose.waitForIdle()
        assertEquals(1, cancelled)
    }

    @Test
    fun finishingWithoutAnyEditReportsUnchangedSoTheOriginalBytesSurvive() {
        var result: ImageEditResult? = null
        show(onDone = { result = it })

        compose.onNodeWithText("完成").performClick()
        compose.waitUntil(5_000) { result != null }

        assertTrue("expected Unchanged but was $result", result is ImageEditResult.Unchanged)
    }

    @Test
    fun finishingAfterAnEditReturnsTheCompositedImage() {
        var result: ImageEditResult? = null
        show(onDone = { result = it })
        drawAcrossTheCanvas()

        compose.onNodeWithText("完成").performClick()
        // The bake runs on a real IO thread, so waitForIdle alone does not cover it.
        compose.waitUntil(5_000) { result != null }

        assertTrue("expected Edited but was $result", result is ImageEditResult.Edited)
    }

    /** A double tap on Done must not produce two attachments. */
    @Test
    fun doneIsGuardedAgainstADoubleTap() {
        var calls = 0
        show(onDone = { calls++ })
        drawAcrossTheCanvas()

        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithText("完成").performClick()
        compose.waitUntil(5_000) { calls > 0 }
        compose.waitForIdle()

        assertEquals(1, calls)
    }

    @Test
    fun switchingToolsKeepsWhatWasAlreadyDrawn() {
        show()
        drawAcrossTheCanvas()

        compose.onNodeWithContentDescription("打码").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("裁切").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("涂鸦").performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("撤销").assertIsEnabled()
    }

    @Test
    fun eachToolShowsItsOwnOptionRow() {
        show()
        compose.onNodeWithContentDescription("红色").assertExists()

        compose.onNodeWithContentDescription("打码").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("中号笔刷").assertExists()

        compose.onNodeWithContentDescription("裁切").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("原图").assertExists()
        compose.onNodeWithContentDescription("旋转 90 度").assertExists()
    }
}
