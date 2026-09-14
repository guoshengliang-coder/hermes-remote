package com.hermes.client.data.media.imageedit

import com.hermes.client.ui.theme.InkColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageEditDocumentTest {

    private fun doc() = ImageEditDocument(sourceWidth = 1200, sourceHeight = 900)

    private fun stroke(vararg xs: Float) =
        ImageEditOp.Stroke(xs.map { SourcePoint(it, it) }, InkColor.RED, StrokeWeight.MEDIUM)

    @Test
    fun listOrderIsDrawOrderSoAMosaicCoversAnEarlierStroke() {
        val d = doc()
            .withOp(stroke(0f, 10f))
            .withOp(ImageEditOp.Mosaic(listOf(SourcePoint(5f, 5f)), BrushSize.MEDIUM))

        assertTrue(d.ops.first() is ImageEditOp.Stroke)
        assertTrue(d.ops.last() is ImageEditOp.Mosaic)
    }

    @Test
    fun resettingAnnotationsKeepsTheCropAndViceVersa() {
        val d = doc().withOp(stroke(0f, 10f)).copy(crop = CropBox(10, 10, 100, 100), quarterTurns = 1)

        assertEquals(CropBox(10, 10, 100, 100), d.clearedOps().crop)
        assertEquals(1, d.clearedOps().quarterTurns)

        assertEquals(1, d.clearedCrop().ops.size)
        assertEquals(null, d.clearedCrop().crop)
        assertEquals(0, d.clearedCrop().quarterTurns)
    }

    @Test
    fun anUnmodifiedDocumentIsRecognisedSoTheOriginalBytesCanBeReturned() {
        assertTrue(doc().isUntouched)
        assertFalse(doc().withOp(stroke(0f, 10f)).isUntouched)
        assertFalse(doc().copy(crop = CropBox(0, 0, 10, 10)).isUntouched)
        assertFalse(doc().rotatedQuarter().isUntouched)
    }

    @Test
    fun theEffectiveCropIsTheWholeFrameWhenNoneIsSet() {
        assertEquals(CropBox(0, 0, 1200, 900), doc().effectiveCrop)
    }

    @Test
    fun pointsTooCloseTogetherAreDropped() {
        var points = listOf(SourcePoint(0f, 0f))
        points = points.appendDecimated(SourcePoint(1f, 0f))
        assertEquals(1, points.size)

        points = points.appendDecimated(SourcePoint(3f, 0f))
        assertEquals(2, points.size)
    }

    @Test
    fun aStrokeStopsGrowingAtTheCapWithoutLosingWhatItHas() {
        var points = emptyList<SourcePoint>()
        repeat(MAX_STROKE_POINTS + 500) { i -> points = points.appendDecimated(SourcePoint(i * 10f, 0f)) }

        assertEquals(MAX_STROKE_POINTS, points.size)
    }

    /** A tap produces one point, and that has to survive as a dot rather than an empty stroke. */
    @Test
    fun aSinglePointStrokeIsKept() {
        val points = emptyList<SourcePoint>().appendDecimated(SourcePoint(7f, 7f))

        assertEquals(1, points.size)
        assertEquals(7f, points.single().x, 0.001f)
    }
}
