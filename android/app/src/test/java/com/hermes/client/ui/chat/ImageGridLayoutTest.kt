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
}
