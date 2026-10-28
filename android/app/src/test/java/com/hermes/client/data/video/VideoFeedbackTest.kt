package com.hermes.client.data.video

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VideoFeedbackTest {
    @get:Rule val temporary = TemporaryFolder()
    private val note = VideoNote("one", 1_234, 4_567, "衔接需要调整\n\n保留原音轨 **不要裁切**")
    @Test fun time_round_trips_and_rejects_invalid_input_without_rounding() {
        for (time in listOf(0L, 1234, 59_999, 3_600_001, 86_400_999))
            assertEquals(time, parseVideoNoteTime(videoNoteTime(time)))
        assertEquals(62_300L, parseVideoNoteTime("1:02.3"))
        for (bad in listOf("-1:00", "1:60", "1:60:00", "NaN", "1:02.9999", "", "1"))
            assertNull(bad, parseVideoNoteTime(bad))
    }
    @Test fun valid_points_ranges_and_duration_boundaries() {
        assertTrue(note.valid(4_567))
        assertTrue(note.copy(endMs = null).valid(1_234))
        for (bad in listOf(note.copy(startMs = -1), note.copy(endMs = 1_234), note.copy(endMs = 0),
            note.copy(text = "  "), note.copy(text = "a".repeat(VIDEO_NOTE_TEXT_LIMIT + 1)), note.copy(id = "")))
            assertFalse(bad.valid(4_567))
        assertFalse(note.valid(4_566))
    }
    @Test fun markdown_sorts_notes_and_keeps_unicode_multiline_and_precise_ranges() {
        val output = videoFeedbackMarkdown("clip.mp4", "/work/clip.mp4", "剪辑意见", 8_000,
            VideoFeedbackDocument(notes = listOf(note, note.copy(id = "zero", startMs = 0, endMs = null, text = "开头"))))
        assertTrue(output.indexOf("开头") < output.indexOf(note.text))
        assertTrue(output.contains("00:00:01.234 – 00:00:04.567"))
        assertTrue(output.contains(note.text))
        assertTrue(output.contains("/work/clip.mp4"))
        assertTrue(output.contains("剪辑意见"))
    }
    @Test fun identity_is_framed_and_isolates_scope_path_and_file_generation() {
        val key = videoFeedbackKey("account/device/profile/session", "/clip", "etag1:100")
        assertEquals(key, videoFeedbackKey("account/device/profile/session", "/clip", "etag1:100"))
        for (parts in listOf(Triple("other", "/clip", "etag1:100"), Triple("account/device/profile/session", "/other", "etag1:100"),
            Triple("account/device/profile/session", "/clip", "etag2:100")))
            assertNotEquals(key, videoFeedbackKey(parts.first, parts.second, parts.third))
        assertNotEquals(videoFeedbackKey("a", "bc", "d"), videoFeedbackKey("ab", "c", "d"))
    }
    @Test fun prepared_streaming_generation_matches_completed_download_and_local_version_is_frozen() {
        val source = ArtifactRangeRepository.Source("owner", "mac", "/clip.mp4",
            com.hermes.client.domain.ArtifactDownloadDescriptor("artifact", 123, "etag-v1", "video/mp4", 1000))
        assertEquals(ArtifactRangeCache.identity("owner", "/clip.mp4", "etag-v1", 123),
            VideoPlayback(source = source).feedbackGeneration)
        val file = temporary.newFile("clip.mp4").apply { writeText("v1") }
        val first = VideoPlayback(localFile = file)
        val original = first.feedbackGeneration
        assertEquals(original, VideoPlayback(localFile = file).feedbackGeneration)
        file.writeText("different version")
        assertNotEquals(original, VideoPlayback(localFile = file).feedbackGeneration)
        assertEquals(original, first.feedbackGeneration)
    }
    @Test fun saved_edits_deletes_and_separate_versions_survive_new_store_instance() = runBlocking {
        val directory = temporary.newFolder()
        val key = videoFeedbackKey("scope", "video", "v1")
        val next = videoFeedbackKey("scope", "video", "v2")
        val store = VideoFeedbackStore(directory)
        store.write(key, VideoFeedbackDocument(notes = listOf(note)))
        assertEquals(listOf(note), VideoFeedbackStore(directory).read(key).notes)
        assertTrue(store.read(next).notes.isEmpty())
        store.write(key, VideoFeedbackDocument(notes = listOf(note.copy(text = "修改"))))
        assertEquals("修改", store.read(key).notes.single().text)
        store.write(key, VideoFeedbackDocument())
        assertTrue(VideoFeedbackStore(directory).read(key).notes.isEmpty())
    }
    @Test fun corrupt_or_future_documents_fail_instead_of_being_silently_erased() = runBlocking {
        val directory = temporary.newFolder()
        val key = videoFeedbackKey("scope", "video", "v1")
        val file = File(directory, "$key.json")
        for (content in listOf("broken", "{\"schemaVersion\":2}", "{\"notes\":[{\"id\":\"x\",\"startMs\":-1,\"text\":\"a\"}]}")) {
            file.writeText(content)
            assertTrue(runCatching { VideoFeedbackStore(directory).read(key) }.isFailure)
            assertEquals(content, file.readText())
        }
    }
    @Test fun failed_write_leaves_previous_document_and_private_data_out_of_filenames() = runBlocking {
        val directory = temporary.newFolder()
        val key = videoFeedbackKey("account/private", "/private/file", "v1")
        val store = VideoFeedbackStore(directory)
        val document = VideoFeedbackDocument(notes = listOf(note))
        store.write(key, document)
        assertTrue(runCatching { store.write(key, VideoFeedbackDocument(notes = listOf(note, note))) }.isFailure)
        assertEquals(document, store.read(key))
        assertEquals(listOf("$key.json"), directory.list()!!.toList())
        val blocked = temporary.newFile()
        assertTrue(runCatching { VideoFeedbackStore(blocked).write(key, document) }.isFailure)
    }
}
