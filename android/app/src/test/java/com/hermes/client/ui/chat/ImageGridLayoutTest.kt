package com.hermes.client.ui.chat

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract these pin is "the box carries the image's own aspect ratio", because that is what
 * makes `ContentScale.Fit` neither crop nor letterbox. Most cases therefore also check the returned
 * aspect against the source aspect.
 */
class ImageGridLayoutTest {

    /** User-bubble content width on a 411dp screen: 82% cap inside 22dp page padding, less 16dp inner. */
    private val bubbleW = 305.dp
    private val maxH = 320.dp

    private fun assertKeepsAspect(srcW: Int, srcH: Int, maxW: Dp = bubbleW, capH: Dp = maxH) {
        val box = singleImageBox(srcW, srcH, maxW, capH)
        val want = srcW.toFloat() / srcH.toFloat()
        val got = box.width / box.height
        assertEquals("aspect for ${srcW}x$srcH", want, got, want * 0.02f)
    }

    @Test
    fun landscapePhotoIsLimitedByWidth() {
        val box = singleImageBox(1600, 1200, bubbleW, maxH)
        assertEquals(305f, box.width.value, 0.5f)
        assertEquals(228.75f, box.height.value, 0.5f)
        assertKeepsAspect(1600, 1200)
    }

    /**
     * HG-35's own first attachment. Under the old fixed 190dp + Crop box this showed about 22% of
     * itself, which is the complaint the item was filed about.
     */
    @Test
    fun tallPhoneScreenshotIsLimitedByHeightAndNarrowsTheBubble() {
        val box = singleImageBox(914, 2048, bubbleW, maxH)
        assertEquals(320f, box.height.value, 0.5f)
        assertEquals(142.8f, box.width.value, 1f)
        assertTrue("must be narrower than the bubble", box.width < bubbleW)
        assertKeepsAspect(914, 2048)
    }

    /** HG-35's second attachment. Nearly square, so it only just clips the height cap. */
    @Test
    fun nearSquareScreenshotClampsToTheHeightCap() {
        val box = singleImageBox(1891, 2048, bubbleW, maxH)
        assertEquals(320f, box.height.value, 0.5f)
        assertEquals(295.5f, box.width.value, 1f)
        assertKeepsAspect(1891, 2048)
    }

    @Test
    fun squareImageIsSquare() {
        val box = singleImageBox(1000, 1000, bubbleW, maxH)
        assertEquals(305f, box.width.value, 0.5f)
        assertEquals(305f, box.height.value, 0.5f)
    }

    /**
     * The floor exists so the row stays tappable, and it is the only case that mats. Raising the box
     * does not enlarge the image, so the floor is kept small on purpose.
     */
    @Test
    fun veryWideBannerKeepsFullWidthAndFloorsItsHeight() {
        val box = singleImageBox(4000, 400, bubbleW, maxH)
        assertEquals(305f, box.width.value, 0.5f)
        assertEquals(44f, box.height.value, 0.5f)
    }

    @Test
    fun aShorterViewportLowersTheCapAndTheImageNarrowsFurther() {
        val box = singleImageBox(914, 2048, bubbleW, 200.dp)
        assertEquals(200f, box.height.value, 0.5f)
        assertEquals(89.3f, box.width.value, 1f)
        assertKeepsAspect(914, 2048, bubbleW, 200.dp)
    }

    @Test
    fun unknownDimensionsFallBackToAFullWidthBox() {
        val box = singleImageBox(0, 0, bubbleW, maxH)
        assertEquals(305f, box.width.value, 0.5f)
        assertEquals(190f, box.height.value, 0.5f)

        val negative = singleImageBox(-1, 100, bubbleW, maxH)
        assertEquals(190f, negative.height.value, 0.5f)
    }

    @Test
    fun theFallbackBoxNeverExceedsTheHeightCapEither() {
        val box = singleImageBox(0, 0, bubbleW, 120.dp)
        assertEquals(120f, box.height.value, 0.5f)
    }

    @Test
    fun heightCapIsTheSmallerOf320dpAnd42PercentOfTheViewport() {
        assertEquals(320f, singleImageMaxHeight(891.dp).value, 0.5f)
        assertEquals(252f, singleImageMaxHeight(600.dp).value, 0.5f)
    }

    /** A cap below the touch-target floor must not invert the range; the cap wins. */
    @Test
    fun aCapBelowTheFloorStillProducesAValidBox() {
        val box = singleImageBox(4000, 400, bubbleW, 30.dp)
        assertEquals(30f, box.height.value, 0.5f)
        assertTrue(box.width.value > 0f)
    }

    // ---- Multi-image grid (HG-43) -------------------------------------------------------------
    // The grid had no test at all while it was cropping; these arrived with the shared-ratio grid
    // that replaced it. The cell width below is the bubble split in two with the 6dp gap removed.

    private val cellW = (bubbleW - 6.dp) / 2

    @Test
    fun theGridTakesTheMedianRatioOfItsImages() {
        // 1.0 / 1.5 / 2.0 → the middle one.
        val aspect = gridCellAspect(listOf(1000 to 1000, 1500 to 1000, 2000 to 1000))
        assertEquals(1.5f, aspect, 0.001f)
    }

    /** Even count takes the LOWER middle: a slightly tall cell mats, a short one cannot. */
    @Test
    fun anEvenCountTakesTheLowerOfTheTwoMiddleRatios() {
        val aspect = gridCellAspect(listOf(1000 to 1000, 1200 to 1000, 1600 to 1000, 1800 to 1000))
        assertEquals(1.2f, aspect, 0.001f)
    }

    /** One outlier must not set the shape of the group — that is the whole point of a median. */
    @Test
    fun oneOutlierDoesNotDragTheGrid() {
        val withoutIt = gridCellAspect(listOf(750 to 1000, 800 to 1000, 820 to 1000))
        val withIt = gridCellAspect(listOf(750 to 1000, 800 to 1000, 820 to 1000, 4000 to 400))
        assertEquals(0.8f, withoutIt, 0.001f)
        assertEquals(0.8f, withIt, 0.001f)
    }

    @Test
    fun imagesWhoseSizeIsNotKnownYetDoNotVote() {
        val aspect = gridCellAspect(listOf(0 to 0, 1800 to 1000, 0 to 0))
        assertEquals(1.8f, aspect, 0.001f)
    }

    /** All unknown is the square the grid used to be — the right shape to hold while they arrive. */
    @Test
    fun aGridOfUnknownSizesIsSquare() {
        assertEquals(1f, gridCellAspect(listOf(0 to 0, 0 to 0)), 0.001f)
        assertEquals(1f, gridCellAspect(emptyList()), 0.001f)
    }

    @Test
    fun theSharedRatioIsClampedAtBothEnds() {
        // A group of panoramas, and a group of very tall screenshots.
        assertEquals(1.9f, gridCellAspect(listOf(4000 to 400, 5000 to 400)), 0.001f)
        assertEquals(0.6f, gridCellAspect(listOf(400 to 4000, 400 to 5000)), 0.001f)
    }

    /**
     * The reported case: photographed cards, around 1.6 wide. Here the shared ratio is what
     * actually sets the height, and the cell is the shape of the pictures in it.
     */
    @Test
    fun aLandscapeGroupGetsACellShapedLikeItsImages() {
        val height = gridCellHeight(cellW, 1.6f)
        assertEquals(cellW.value / 1.6f, height.value, 0.5f)
        assertTrue(height.value < 108f)
    }

    /**
     * **The ceiling bites before the ratio does for anything squarer than ~1.4**, because a cell is
     * about 149dp wide on a 411dp screen. So a square or portrait group gets a 149×108 cell and its
     * images are matted left and right rather than being made taller.
     *
     * That is the cost of this design, stated rather than discovered: a uniform grid of complete
     * images has to mat whatever does not match the cell, and the product asked for smaller cells
     * in the same breath as complete ones. The alternative — letting a portrait group grow to
     * ~249dp per row — is the screen real estate HG-43 explicitly wanted back.
     */
    @Test
    fun aSquareOrPortraitGroupIsCappedByTheCeilingNotItsRatio() {
        assertEquals(108f, gridCellHeight(cellW, 1f).value, 0.5f)
        assertEquals(108f, gridCellHeight(cellW, 0.6f).value, 0.5f)
    }

    /** Wide ones hit the touch-target floor rather than becoming an unaimable strip. */
    @Test
    fun aWideGridCellStopsAtTheTouchFloor() {
        assertEquals(44f, gridCellHeight(cellW, 12f).value, 0.5f)
    }

    /** The cell is smaller than the 132dp it replaced whatever the group's shape (HG-43). */
    @Test
    fun noGridCellIsTallerThanTheOldFixedHeight() {
        listOf(0.5f, 0.8f, 1f, 1.5f, 2f, 3f).forEach { aspect ->
            assertTrue("aspect $aspect", gridCellHeight(cellW, aspect).value <= 108f)
        }
    }
}
