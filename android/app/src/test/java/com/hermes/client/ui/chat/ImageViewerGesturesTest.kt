package com.hermes.client.ui.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageViewerGesturesTest {

    private val container = Size(1080f, 2400f)

    @Test
    fun theePagerOwnsDragsOnlyWhileTheImageIsUnzoomed() {
        assertTrue(imagePagerUserScrollEnabled(1f))
        assertFalse(imagePagerUserScrollEnabled(1.2f))
        assertFalse(imagePagerUserScrollEnabled(5f))
    }

    /**
     * scale accumulates through repeated `scale * zoom`, so pinching out and back almost never lands
     * exactly on 1.0. Without the epsilon the pager stays disabled forever after the first pinch.
     */
    @Test
    fun aScaleThatOnlyAlmostReturnedToFitStillCountsAsFit() {
        assertTrue(imagePagerUserScrollEnabled(1.0005f))
        assertTrue(imagePagerUserScrollEnabled(0.9995f))
        assertFalse(imagePagerUserScrollEnabled(1.01f))
    }

    /**
     * The regression test for "the pager never sees a swipe". A single finger on an unzoomed image
     * must consume nothing at all.
     */
    @Test
    fun aSingleFingerAtFitConsumesNothing() {
        assertFalse(shouldConsumePan(pointerCount = 1, scale = 1f))
    }

    @Test
    fun pinchAlwaysBelongsToTheImageAndSoDoesPanWhileZoomed() {
        assertTrue(shouldConsumePan(pointerCount = 2, scale = 1f))
        assertTrue(shouldConsumePan(pointerCount = 3, scale = 1f))
        assertTrue(shouldConsumePan(pointerCount = 1, scale = 2f))
    }

    @Test
    fun panIsClampedToTheScaledImageBounds() {
        val displayed = Size(1080f, 2400f)
        val clamped = clampPan(Offset(9000f, 0f), scale = 2f, container = container, displayed = displayed)

        assertEquals(540f, clamped.x, 0.01f)
    }

    @Test
    fun anUnzoomedImageHasNowhereToPan() {
        val clamped = clampPan(Offset(300f, 300f), scale = 1f, container = container, displayed = container)

        assertEquals(Offset.Zero, clamped)
    }

    /** A tall image zoomed 2x pans vertically but has no horizontal slack to give. */
    @Test
    fun axesAreClampedIndependently() {
        val displayed = Size(1080f, 1000f)
        val clamped = clampPan(Offset(800f, -800f), scale = 2f, container = container, displayed = displayed)

        assertEquals(540f, clamped.x, 0.01f)
        assertEquals(0f, clamped.y, 0.01f)
    }

    /**
     * At the edge a further drag changes nothing, so it must stay unconsumed and reach the pager —
     * that is what lets a zoomed image keep swiping to the next one.
     */
    @Test
    fun aPanThatChangesNothingIsNotTreatedAsMovement() {
        val at = Offset(540f, 0f)
        val clamped = clampPan(at + Offset(200f, 0f), 2f, container, Size(1080f, 2400f))

        assertFalse(panDidMove(at, clamped))
        assertTrue(panDidMove(at, at + Offset(5f, 0f)))
    }

    @Test
    fun fittedSizeLetterboxesATallImageIntoAWideBox() {
        val fitted = fittedSize(source = Size(914f, 2048f), container = Size(1080f, 2400f))

        assertEquals(1071.1f, fitted.width, 1f)
        assertEquals(2400f, fitted.height, 1f)
    }

    @Test
    fun fittedSizeFallsBackToTheContainerForAnUnknownSource() {
        assertEquals(container, fittedSize(Size(0f, 0f), container))
    }

    @Test
    fun doubleTapZoomsOutThenBack() {
        assertEquals(IMAGE_VIEWER_DOUBLE_TAP_SCALE, doubleTapScale(1f), 0.001f)
        assertEquals(IMAGE_VIEWER_MIN_SCALE, doubleTapScale(3f), 0.001f)
    }

    @Test
    fun deletingLandsOnTheNextImageThenThePrevious() {
        val items = listOf("a", "b", "c")

        assertEquals("c", neighbourAfterRemoval(items, 1))
        assertEquals("b", neighbourAfterRemoval(items, 2))
    }

    @Test
    fun deletingTheOnlyImageLeavesNothingToShow() {
        assertNull(neighbourAfterRemoval(listOf("a"), 0))
        assertNull(neighbourAfterRemoval(emptyList<String>(), 0))
        assertNull(neighbourAfterRemoval(listOf("a", "b"), 7))
    }
}
