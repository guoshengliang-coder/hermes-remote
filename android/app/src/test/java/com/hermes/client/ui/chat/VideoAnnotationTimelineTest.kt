package com.hermes.client.ui.chat

import com.hermes.client.data.video.VideoNote
import org.junit.Assert.*
import org.junit.Test

class VideoAnnotationTimelineTest {
    private fun note(id: String, start: Long, end: Long? = null) = VideoNote(id, start, end, "feedback")
    @Test fun near_points_and_clamped_edges_cluster_but_distinct_hit_regions_do_not_overlap() {
        val notes = listOf(note("end", 60_000), note("a", 0), note("b", 1_000), note("middle", 30_000, 40_000))
        val clusters = clusterVideoNotes(notes, 60_000, 320f)
        assertEquals(listOf(listOf("a", "b"), listOf("middle"), listOf("end")), clusters.map { it.notes.map(VideoNote::id) })
        assertEquals(24f, clusters.first().centerDp, .01f)
        assertEquals(296f, clusters.last().centerDp, .01f)
        clusters.zipWithNext().forEach { (a, b) -> assertTrue(b.centerDp - a.centerDp >= 48f) }
    }
    @Test fun rotation_reclusters_by_screen_width_and_keeps_all_ids_once() {
        val notes = (0..20).map { note("n$it", it * 3_000L) }
        val narrow = clusterVideoNotes(notes, 60_000, 320f)
        val wide = clusterVideoNotes(notes, 60_000, 1200f)
        assertTrue(wide.size > narrow.size)
        for (clusters in listOf(narrow, wide)) assertEquals(notes.map { it.id }, clusters.flatMap { it.notes }.map { it.id })
    }
    @Test fun invalid_dimensions_and_notes_are_not_fabricated() {
        assertTrue(clusterVideoNotes(listOf(note("one", 1)), 0, 320f).isEmpty())
        assertTrue(clusterVideoNotes(listOf(note("one", 1)), 20, 30f).isEmpty())
        assertTrue(clusterVideoNotes(listOf(note("one", 1)), 20, Float.NaN).isEmpty())
        assertTrue(clusterVideoNotes(listOf(note("outside", 21)), 20, 320f).isEmpty())
    }
}
