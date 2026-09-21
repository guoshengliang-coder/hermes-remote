package com.hermes.client.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoGalleryDialogTest {
    @Test fun thumbnail_sampling_never_decodes_below_the_requested_crop_edge() {
        assertEquals(8, gallerySampleSize(4000, 3000, 320))
        assertEquals(4, gallerySampleSize(1200, 4000, 256))
        assertEquals(1, gallerySampleSize(240, 4000, 320))
    }

    @Test fun full_preview_targets_the_long_edge_without_cropping_or_oversized_decode() {
        assertEquals(320 to 240, galleryTargetSize(4000, 3000, 320))
        assertEquals(96 to 320, galleryTargetSize(1200, 4000, 320))
        assertEquals(19 to 320, galleryTargetSize(240, 4000, 320))
        assertEquals(8, gallerySampleSize(4000, 3000, 320, crop = false))
    }

    @Test fun selection_preserves_order_across_albums_and_honours_cap() {
        var selected = emptyList<String>()
        selected = toggleGallerySelection(selected, "content://a/1", 3)
        selected = toggleGallerySelection(selected, "content://b/2", 3)
        selected = toggleGallerySelection(selected, "content://a/3", 3)
        selected = toggleGallerySelection(selected, "content://b/4", 3)

        assertEquals(listOf("content://a/1", "content://b/2", "content://a/3"), selected)
    }

    @Test fun deselecting_frees_a_slot_without_reordering_survivors() {
        val selected = toggleGallerySelection(listOf("a", "b", "c"), "b", 3)
        assertEquals(listOf("a", "c"), selected)
        assertEquals(listOf("a", "c", "d"), toggleGallerySelection(selected, "d", 3))
    }

    @Test fun albums_use_latest_photo_as_cover_and_count_all_rows() {
        val photos = listOf(
            GalleryPhoto("new", "new.jpg", "camera", "Camera", 3),
            GalleryPhoto("old", "old.jpg", "camera", "Camera", 2),
            GalleryPhoto("shot", "shot.jpg", "screens", "Screenshots", 1),
        )

        assertEquals(
            listOf(
                GalleryAlbum("camera", "Camera", "new", 2),
                GalleryAlbum("screens", "Screenshots", "shot", 1),
            ),
            galleryAlbums(photos),
        )
    }
}
