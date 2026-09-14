package com.hermes.client.data.media.imageedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageEditGeometryTest {

    @Test
    fun fitCentresAndNeverOverflowsTheBox() {
        val fit = fitTransform(CropBox(0, 0, 1000, 500), quarterTurns = 0, boxW = 800f, boxH = 800f)

        assertEquals(0.8f, fit.scale, 0.001f)
        assertEquals(0f, fit.originX, 0.01f)
        assertEquals(200f, fit.originY, 0.01f)
        assertEquals(800f, fit.displayWidth, 0.01f)
        assertEquals(400f, fit.displayHeight, 0.01f)
    }

    @Test
    fun oddQuarterTurnsTransposeTheDisplayedExtent() {
        val upright = fitTransform(CropBox(0, 0, 1000, 500), 0, 800f, 800f)
        val turned = fitTransform(CropBox(0, 0, 1000, 500), 1, 800f, 800f)

        assertEquals(upright.displayHeight, turned.displayWidth, 0.01f)
        assertEquals(upright.displayWidth, turned.displayHeight, 0.01f)
    }

    @Test
    fun aDegenerateCropDoesNotDivideByZero() {
        val fit = fitTransform(CropBox(5, 5, 5, 5), 0, 800f, 800f)

        assertEquals(1f, fit.scale, 0.001f)
    }

    /**
     * The highest-value case in the suite: a point captured from the screen and drawn back must
     * land where it started, for every rotation and for an off-centre crop.
     */
    @Test
    fun viewToSourceRoundTripsForEveryRotationAndAnOffCentreCrop() {
        val crops = listOf(CropBox(0, 0, 1200, 900), CropBox(137, 241, 903, 688))
        val boxes = listOf(1080f to 1600f, 1600f to 1080f)
        for (crop in crops) {
            for (turns in 0..3) {
                for ((bw, bh) in boxes) {
                    val fit = fitTransform(crop, turns, bw, bh)
                    for (px in listOf(0.13f, 0.5f, 0.86f)) {
                        for (py in listOf(0.2f, 0.5f, 0.77f)) {
                            val boxX = fit.originX + fit.displayWidth * px
                            val boxY = fit.originY + fit.displayHeight * py
                            val source = boxToSource(boxX, boxY, crop, turns, fit)
                            val (backX, backY) = sourceToBox(source, crop, turns, fit)
                            assertEquals("x crop=$crop turns=$turns box=$bw", boxX, backX, 0.01f)
                            assertEquals("y crop=$crop turns=$turns box=$bw", boxY, backY, 0.01f)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun theFitRectCornersMapToTheCropCorners() {
        val crop = CropBox(100, 50, 900, 650)
        val fit = fitTransform(crop, 0, 1080f, 1600f)

        val topLeft = boxToSource(fit.originX, fit.originY, crop, 0, fit)
        val bottomRight = boxToSource(
            fit.originX + fit.displayWidth,
            fit.originY + fit.displayHeight,
            crop,
            0,
            fit,
        )

        assertEquals(100f, topLeft.x, 0.01f)
        assertEquals(50f, topLeft.y, 0.01f)
        assertEquals(900f, bottomRight.x, 0.01f)
        assertEquals(650f, bottomRight.y, 0.01f)
    }

    /** A finger sliding off the photo must never write a point outside the crop into the document. */
    @Test
    fun pointsOutsideTheImageAreClampedAtCapture() {
        val crop = CropBox(100, 50, 900, 650)
        val fit = fitTransform(crop, 0, 1080f, 1600f)

        val farOut = boxToSource(-4000f, -4000f, crop, 0, fit)
        assertEquals(100f, farOut.x, 0.01f)
        assertEquals(50f, farOut.y, 0.01f)

        val farPast = boxToSource(9000f, 9000f, crop, 0, fit)
        assertEquals(900f, farPast.x, 0.01f)
        assertEquals(650f, farPast.y, 0.01f)
    }

    @Test
    fun theViewportTransformIsInvertedAboutTheBoxCentre() {
        val (x, y) = viewportToBox(540f, 800f, viewportScale = 1f, 0f, 0f, boxW = 1080f, boxH = 1600f)
        assertEquals(540f, x, 0.01f)
        assertEquals(800f, y, 0.01f)

        // Zoomed 2x about the centre, a touch on the centre is still the centre.
        val (cx, cy) = viewportToBox(540f, 800f, viewportScale = 2f, 0f, 0f, 1080f, 1600f)
        assertEquals(540f, cx, 0.01f)
        assertEquals(800f, cy, 0.01f)

        // ... and a touch one quarter to the right is an eighth in unzoomed space.
        val (rx, _) = viewportToBox(810f, 800f, viewportScale = 2f, 0f, 0f, 1080f, 1600f)
        assertEquals(675f, rx, 0.01f)
    }

    @Test
    fun aZoomedAndPannedViewportStillRoundTrips() {
        val crop = CropBox(0, 0, 1200, 900)
        val fit = fitTransform(crop, 2, 1080f, 1600f)
        val (bx, by) = viewportToBox(700f, 900f, 2.4f, 120f, -80f, 1080f, 1600f)
        val source = boxToSource(bx, by, crop, 2, fit)
        val (backX, backY) = sourceToBox(source, crop, 2, fit)

        assertEquals(bx, backX, 0.01f)
        assertEquals(by, backY, 0.01f)
    }

    @Test
    fun penAndBrushWidthsAreProportionalMonotonicAndFloored() {
        assertTrue(strokeWidthPx(StrokeWeight.THIN, 2560) < strokeWidthPx(StrokeWeight.MEDIUM, 2560))
        assertTrue(strokeWidthPx(StrokeWeight.MEDIUM, 2560) < strokeWidthPx(StrokeWeight.THICK, 2560))
        assertEquals(20.48f, strokeWidthPx(StrokeWeight.MEDIUM, 2560), 0.01f)
        assertEquals(7.2f, strokeWidthPx(StrokeWeight.MEDIUM, 900), 0.01f)
        assertEquals(2f, strokeWidthPx(StrokeWeight.THIN, 10), 0.01f)

        assertTrue(mosaicBrushPx(BrushSize.SMALL, 2560) < mosaicBrushPx(BrushSize.LARGE, 2560))
        // A mosaic brush has to be far fatter than a pen, or it cannot cover anything.
        assertTrue(mosaicBrushPx(BrushSize.SMALL, 2560) > strokeWidthPx(StrokeWeight.THICK, 2560))
        assertEquals(8f, mosaicBrushPx(BrushSize.SMALL, 10), 0.01f)
    }

    @Test
    fun mosaicBlockScalesWithTheImageAndIsFloored() {
        assertEquals(40, mosaicBlockPx(2560))
        assertEquals(14, mosaicBlockPx(900))
        assertEquals(8, mosaicBlockPx(100))
    }

    @Test
    fun smoothingProducesOneSegmentPerSampleAndHandlesShortStrokes() {
        assertTrue(smoothSegments(emptyList()).isEmpty())
        assertTrue(smoothSegments(listOf(SourcePoint(1f, 1f))).isEmpty())

        val segments = smoothSegments(listOf(SourcePoint(0f, 0f), SourcePoint(10f, 0f), SourcePoint(10f, 10f)))
        assertEquals(3, segments.size)
        // First control is the first sample; first end is the midpoint to the next.
        assertEquals(0f, segments[0][0], 0.01f)
        assertEquals(5f, segments[0][2], 0.01f)
        // The tail closes on the final sample so the stroke reaches the finger.
        assertEquals(10f, segments.last()[2], 0.01f)
        assertEquals(10f, segments.last()[3], 0.01f)
    }
}
