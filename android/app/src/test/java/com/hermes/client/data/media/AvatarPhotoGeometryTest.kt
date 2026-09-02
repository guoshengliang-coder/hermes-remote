package com.hermes.client.data.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AvatarPhotoGeometryTest {
    // Subsample as far as the SHORT edge allows while staying ≥ 512, so the crop never upscales.
    @Test fun sampleSize_keeps_short_edge_at_or_above_target() {
        assertEquals(4, AvatarPhotoGeometry.sampleSize(4000, 3000))
        assertEquals(2, AvatarPhotoGeometry.sampleSize(1024, 2048))
        assertEquals(1, AvatarPhotoGeometry.sampleSize(600, 400))
        assertEquals(1, AvatarPhotoGeometry.sampleSize(0, 0))
        assertEquals(1, AvatarPhotoGeometry.sampleSize(1023, 5000))
    }

    @Test fun squareCrop_is_centred_on_the_long_edge() {
        assertArrayEquals(intArrayOf(500, 0, 3000), AvatarPhotoGeometry.squareCrop(4000, 3000))
        assertArrayEquals(intArrayOf(0, 100, 300), AvatarPhotoGeometry.squareCrop(300, 500))
        assertArrayEquals(intArrayOf(0, 0, 512), AvatarPhotoGeometry.squareCrop(512, 512))
    }
}
