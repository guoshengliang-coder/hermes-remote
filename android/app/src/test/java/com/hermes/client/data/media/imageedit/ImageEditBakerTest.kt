package com.hermes.client.data.media.imageedit

import android.graphics.Bitmap
import android.graphics.Color
import com.hermes.client.ui.theme.InkColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ImageEditBakerTest {

    /** Distinct colour per pixel position, so any drift in the coordinate mapping is visible. */
    private fun gradient(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, Color.rgb(x % 256, y % 256, (x + y) % 256))
            }
        }
        return bitmap
    }

    private fun doc(width: Int, height: Int) = ImageEditDocument(sourceWidth = width, sourceHeight = height)

    @Test
    fun anEmptyDocumentPassesTheImageThrough() {
        val src = gradient(60, 40)
        val out = ImageEditBaker.bake(src, doc(60, 40))

        assertEquals(60, out.width)
        assertEquals(40, out.height)
        assertEquals(src.getPixel(11, 7), out.getPixel(11, 7))
    }

    /**
     * The whole coordinate contract in one assertion: output pixel (0,0) must be the source pixel at
     * the crop origin. If the bake transform is wrong, this is what catches it.
     */
    @Test
    fun croppingTakesExactlyTheRequestedRectangle() {
        val src = gradient(80, 60)
        val out = ImageEditBaker.bake(src, doc(80, 60).copy(crop = CropBox(17, 9, 57, 39)))

        assertEquals(40, out.width)
        assertEquals(30, out.height)
        assertEquals(src.getPixel(17, 9), out.getPixel(0, 0))
        assertEquals(src.getPixel(56, 38), out.getPixel(39, 29))
    }

    @Test
    fun aStrokeIsPaintedAndLeavesTheRestAlone() {
        val src = gradient(80, 40)
        val stroke = ImageEditOp.Stroke(
            points = listOf(SourcePoint(0f, 20f), SourcePoint(79f, 20f)),
            color = InkColor.WHITE,
            weight = StrokeWeight.THICK,
        )
        val out = ImageEditBaker.bake(src, doc(80, 40).withOp(stroke))

        assertEquals(Color.WHITE, out.getPixel(40, 20))
        assertEquals(src.getPixel(40, 2), out.getPixel(40, 2))
    }

    /** A tap makes a one-point stroke, and a one-point path strokes nothing unless it is a dot. */
    @Test
    fun aSinglePointStrokeIsDrawnAsADot() {
        val src = gradient(60, 60)
        val dot = ImageEditOp.Stroke(listOf(SourcePoint(30f, 30f)), InkColor.WHITE, StrokeWeight.THICK)
        val out = ImageEditBaker.bake(src, doc(60, 60).withOp(dot))

        assertEquals(Color.WHITE, out.getPixel(30, 30))
    }

    @Test
    fun aMosaicFlattensItsRegionAndLeavesPixelsOutsideTheBrushUntouched() {
        // Checkerboard: high local variance, so flattening is unambiguous.
        val src = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        for (x in 0 until 128) {
            for (y in 0 until 128) {
                src.setPixel(x, y, if ((x + y) % 2 == 0) Color.BLACK else Color.WHITE)
            }
        }
        val mosaic = ImageEditOp.Mosaic(listOf(SourcePoint(64f, 64f)), BrushSize.LARGE)
        val out = ImageEditBaker.bake(src, doc(128, 128).withOp(mosaic))

        val a = out.getPixel(64, 64)
        val b = out.getPixel(65, 65)
        assertEquals("the mosaicked region must be locally flat", a, b)
        assertNotEquals("and must differ from the raw checkerboard", src.getPixel(64, 64), a)

        assertEquals("a far corner must be bit-identical", src.getPixel(2, 2), out.getPixel(2, 2))
    }

    /** List order is z order: the mosaic drawn after a stroke has to cover it. */
    @Test
    fun aMosaicDrawnAfterAStrokeCoversIt() {
        val src = gradient(128, 128)
        val stroke = ImageEditOp.Stroke(
            listOf(SourcePoint(20f, 64f), SourcePoint(108f, 64f)),
            InkColor.WHITE,
            StrokeWeight.THICK,
        )
        val mosaic = ImageEditOp.Mosaic(listOf(SourcePoint(64f, 64f)), BrushSize.LARGE)

        val inked = ImageEditBaker.bake(src, doc(128, 128).withOp(stroke))
        val covered = ImageEditBaker.bake(src, doc(128, 128).withOp(stroke).withOp(mosaic))

        assertEquals(Color.WHITE, inked.getPixel(64, 64))
        assertNotEquals(Color.WHITE, covered.getPixel(64, 64))
    }

    @Test
    fun quarterTurnsTransposeTheOutputAndMoveTheCorners() {
        val src = gradient(80, 40)
        val topLeft = src.getPixel(0, 0)

        val once = ImageEditBaker.bake(src, doc(80, 40).copy(quarterTurns = 1))
        assertEquals(40, once.width)
        assertEquals(80, once.height)
        // Clockwise: the old top-left ends up at the top-right.
        assertEquals(topLeft, once.getPixel(once.width - 1, 0))

        val twice = ImageEditBaker.bake(src, doc(80, 40).copy(quarterTurns = 2))
        assertEquals(80, twice.width)
        assertEquals(topLeft, twice.getPixel(79, 39))
    }

    @Test
    fun pixelateProducesBlocksThatAreUniformAndGridAligned() {
        val src = gradient(64, 64)
        val out = ImageEditBaker.pixelate(src, blockPx = 16)

        assertEquals(64, out.width)
        assertEquals(64, out.height)
        // Everything inside one block is the same colour...
        val corner = out.getPixel(0, 0)
        assertEquals(corner, out.getPixel(15, 15))
        assertEquals(corner, out.getPixel(7, 3))
        // ...and the next block over is different, so the grid is real, not a blur.
        assertNotEquals(corner, out.getPixel(16, 0))
    }

    @Test
    fun degenerateInputsDoNotThrow() {
        val tiny = gradient(1, 1)

        assertEquals(1, ImageEditBaker.bake(tiny, doc(1, 1)).width)
        assertEquals(1, ImageEditBaker.bake(tiny, doc(1, 1).copy(crop = CropBox(0, 0, 1, 1))).width)
        assertEquals(1, ImageEditBaker.pixelate(tiny, blockPx = 40).width)
    }

    @Test
    fun theSourceIsLeftIntactSoCancellingLosesNothing() {
        val src = gradient(40, 40)
        val before = src.getPixel(20, 20)
        ImageEditBaker.bake(
            src,
            doc(40, 40).withOp(ImageEditOp.Stroke(listOf(SourcePoint(20f, 20f)), InkColor.WHITE, StrokeWeight.THICK)),
        )

        assertEquals(before, src.getPixel(20, 20))
        assertFalse(src.isRecycled)
    }

    @Test
    fun theWorkingSizeBudgetRejectsAnAbsurdImageBeforeAnythingIsAllocated() {
        assertTrue(ImageEditBaker.fitsBudget(4000, 3000))
        assertFalse(ImageEditBaker.fitsBudget(20_000, 20_000))
        assertFalse(ImageEditBaker.fitsBudget(0, 100))
    }

    @Test
    fun theWorkingSampleSizeLandsTheLongEdgeUnderTheCap() {
        assertEquals(1, ImageEditBaker.workingSampleSize(2560))
        assertEquals(2, ImageEditBaker.workingSampleSize(4000))
        assertEquals(4, ImageEditBaker.workingSampleSize(10_240))
        assertTrue(10_240 / ImageEditBaker.workingSampleSize(10_240) <= ImageEditBaker.EDIT_MAX_EDGE)
    }
}
