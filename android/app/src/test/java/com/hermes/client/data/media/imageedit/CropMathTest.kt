package com.hermes.client.data.media.imageedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CropMathTest {

    private val box = CropBox(100, 100, 500, 400)

    private fun hit(x: Float, y: Float, radius: Float = 24f) =
        hitHandle(x, y, box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat(), radius)

    @Test
    fun everyHandleCanBeGrabbed() {
        assertEquals(CropHandle.TOP_LEFT, hit(100f, 100f))
        assertEquals(CropHandle.TOP_RIGHT, hit(500f, 100f))
        assertEquals(CropHandle.BOTTOM_LEFT, hit(100f, 400f))
        assertEquals(CropHandle.BOTTOM_RIGHT, hit(500f, 400f))
        assertEquals(CropHandle.TOP, hit(300f, 100f))
        assertEquals(CropHandle.BOTTOM, hit(300f, 400f))
        assertEquals(CropHandle.LEFT, hit(100f, 250f))
        assertEquals(CropHandle.RIGHT, hit(500f, 250f))
        assertEquals(CropHandle.MOVE, hit(300f, 250f))
    }

    /** Where a corner and an edge both match, the corner wins — that is what the finger was aiming at. */
    @Test
    fun cornersBeatEdgesInTheOverlap() {
        assertEquals(CropHandle.TOP_LEFT, hit(110f, 105f))
        assertEquals(CropHandle.BOTTOM_RIGHT, hit(492f, 396f))
    }

    @Test
    fun aTouchWellOutsideGrabsNothing() {
        assertNull(hit(20f, 20f))
        assertNull(hit(700f, 250f))
    }

    @Test
    fun handlesCannotLeaveTheImage() {
        val pushedOut = dragHandle(box, CropHandle.LEFT, dx = -9000, dy = 0, imageWidth = 800, imageHeight = 600)
        assertEquals(0, pushedOut.left)

        val pushedPast = dragHandle(box, CropHandle.BOTTOM, dx = 0, dy = 9000, imageWidth = 800, imageHeight = 600)
        assertEquals(600, pushedPast.bottom)
    }

    /** The classic negative-width crash: dragging a side past its opposite must stop, not invert. */
    @Test
    fun draggingASidePastItsOppositeStopsAtTheMinimumAndNeverInverts() {
        val squeezed = dragHandle(box, CropHandle.RIGHT, dx = -9000, dy = 0, 800, 600, minSide = 16)
        assertEquals(16, squeezed.width)
        assertTrue(squeezed.right > squeezed.left)

        val flattened = dragHandle(box, CropHandle.TOP, dx = 0, dy = 9000, 800, 600, minSide = 16)
        assertEquals(16, flattened.height)
        assertTrue(flattened.bottom > flattened.top)
    }

    @Test
    fun cornerDragsMoveBothSides() {
        val dragged = dragHandle(box, CropHandle.TOP_LEFT, dx = 30, dy = 40, 800, 600)

        assertEquals(130, dragged.left)
        assertEquals(140, dragged.top)
        assertEquals(500, dragged.right)
        assertEquals(400, dragged.bottom)
    }

    @Test
    fun movingCannotPushTheBoxOffTheImage() {
        val moved = dragHandle(box, CropHandle.MOVE, dx = 9000, dy = 9000, 800, 600)

        assertEquals(800, moved.right)
        assertEquals(600, moved.bottom)
        assertEquals(box.width, moved.width)
        assertEquals(box.height, moved.height)
    }

    @Test
    fun aLockedAspectIsHeldWithinAPixel() {
        for (ratio in listOf(1f, 4f / 3f, 16f / 9f)) {
            val shaped = applyAspect(box, ratio, imageWidth = 800, imageHeight = 600)
            val got = shaped.width.toFloat() / shaped.height
            assertEquals("ratio $ratio", ratio, got, ratio * 0.02f)
            assertTrue(shaped.right <= 800 && shaped.bottom <= 600)
        }
    }

    @Test
    fun anAspectTooTallForTheRemainingSpaceShrinksInsteadOfEscaping() {
        val nearBottom = CropBox(0, 560, 700, 600)
        val shaped = applyAspect(nearBottom, 1f / 4f, imageWidth = 800, imageHeight = 600)

        assertTrue(shaped.bottom <= 600)
        assertTrue(shaped.top >= 0)
    }

    @Test
    fun aLockedAspectSwapsWhenTheImageTurns() {
        val wide = CropBox(0, 0, 320, 180)
        val turned = rotateWithAspect(wide, 16f / 9f, imageWidth = 800, imageHeight = 600)

        assertEquals(9f / 16f, turned.width.toFloat() / turned.height, 0.05f)
    }

    @Test
    fun aFreeCropIsUntouchedByRotation() {
        assertEquals(box, rotateWithAspect(box, ratio = null, imageWidth = 800, imageHeight = 600))
    }

    @Test
    fun fourQuarterTurnsReturnToTheStart() {
        var doc = ImageEditDocument(sourceWidth = 800, sourceHeight = 600)
        repeat(4) { doc = doc.rotatedQuarter() }

        assertEquals(0, doc.quarterTurns)
    }

    @Test
    fun theSnappedRectIsAlwaysInsideAndPositive() {
        val wild = cropToSourceRect(CropBox(-50, -50, 9000, 9000), 800, 600)
        assertEquals(CropBox(0, 0, 800, 600), wild)

        val inverted = cropToSourceRect(CropBox(400, 300, 100, 50), 800, 600)
        assertTrue(inverted.width > 0 && inverted.height > 0)

        val tiny = cropToSourceRect(CropBox(0, 0, 1, 1), 1, 1)
        assertEquals(CropBox(0, 0, 1, 1), tiny)
    }

    @Test
    fun originalAspectFollowsTheCurrentRotation() {
        assertEquals(2f, aspectRatioFor(CropAspect.ORIGINAL, 800, 400, quarterTurns = 0)!!, 0.001f)
        assertEquals(0.5f, aspectRatioFor(CropAspect.ORIGINAL, 800, 400, quarterTurns = 1)!!, 0.001f)
        assertNull(aspectRatioFor(CropAspect.FREE, 800, 400, 0))
        assertEquals(1f, aspectRatioFor(CropAspect.SQUARE, 800, 400, 0)!!, 0.001f)
    }
}
