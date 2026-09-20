package com.hermes.client.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoGalleryDialogTest {
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
