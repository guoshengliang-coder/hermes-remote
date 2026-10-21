package com.hermes.client.ui.chat

import com.hermes.client.domain.*
import com.hermes.client.ui.components.*
import com.hermes.client.ui.localization.AppLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ChatFileMetadataTest {
    private val file = ChatFile("a", "video.mp4", remotePath = "/tmp/video.mp4")

    @Test fun exact_limit_is_allowed_but_one_more_byte_is_not() {
        assertFalse(FileCardInfo(ARTIFACT_DOWNLOAD_LIMIT_BYTES).tooLarge)
        assertTrue(FileCardInfo(ARTIFACT_DOWNLOAD_LIMIT_BYTES + 1, limitKnown = true).tooLarge)
        assertFalse(FileCardInfo(ARTIFACT_DOWNLOAD_LIMIT_BYTES + 1).tooLarge)
        assertFalse(FileCardInfo().tooLarge)
        assertTrue(FileCardInfo(51, 50, limitKnown = true).tooLarge)
        assertEquals("100.1 MiB", artifactSizeLabel(ARTIFACT_DOWNLOAD_LIMIT_BYTES + 1))
    }

    @Test fun inspection_is_reused_only_within_one_screen_and_actual_size_wins() = runTest {
        var calls = 0
        val state = ChatFileMetadataState { calls++; ArtifactMetadata(126L * 1024 * 1024) }
        state.load(file.copy(sizeBytes = 10))
        state.load(file)
        assertTrue(state.info(file).tooLarge)
        assertEquals(1, calls)
        val anotherScreen = ChatFileMetadataState { ArtifactMetadata(50) }
        anotherScreen.load(file)
        assertFalse(anotherScreen.info(file).tooLarge)
    }

    @Test fun unknown_metadata_keeps_download_available_but_actual_413_disables_it() = runTest {
        val state = ChatFileMetadataState { null }
        state.load(file)
        assertNull(state.info(file).sizeBytes)
        assertFalse(state.info(file).tooLarge)
        state.reject(file)
        assertTrue(state.info(file).tooLarge)
        for (language in AppLanguage.entries.filter { it == AppLanguage.ZH || it == AppLanguage.EN }) {
            val message = artifactLimitMessage(state.info(file), language)
            assertTrue(message.contains("HR-FILE-004"))
            assertTrue(message.contains("100.0 MiB"))
        }
    }

    @Test fun failed_inspection_does_not_become_a_false_size_or_limit() = runTest {
        val state = ChatFileMetadataState { throw java.io.IOException("unavailable") }
        state.load(file)
        assertNull(state.info(file).sizeBytes)
        assertFalse(state.info(file).tooLarge)
    }

    @Test fun cancelled_inspection_can_be_retried() = runTest {
        var attempts = 0
        val state = ChatFileMetadataState {
            if (++attempts == 1) throw CancellationException("left screen")
            ArtifactMetadata(32)
        }
        try { state.load(file); fail("cancellation must propagate") } catch (_: CancellationException) { }
        state.load(file)
        assertEquals(32L, state.info(file).sizeBytes)
    }

    @Test fun file_types_are_distinct_and_case_insensitive_with_mime_and_unknown_fallback() {
        val cases = mapOf("MP4" to FileIconKind.VIDEO, "mp3" to FileIconKind.AUDIO,
            "md" to FileIconKind.DOCUMENT, "html" to FileIconKind.CODE, "pdf" to FileIconKind.PDF,
            "xlsx" to FileIconKind.TABLE, "pptx" to FileIconKind.SLIDES, "zip" to FileIconKind.ARCHIVE,
            "unknown" to FileIconKind.OTHER)
        cases.forEach { (ext, expected) -> assertEquals(expected, fileIconKind("中文文件.$ext", null)) }
        assertEquals(FileIconKind.VIDEO, fileIconKind("video", "video/mp4"))
        assertEquals(FileIconKind.OTHER, fileIconKind("file", null))
        assertEquals(FileIconKind.VIDEO, fileIconKind("movie.mp4", "application/octet-stream"))
    }
    @Test fun duplicate_inspections_are_coalesced_and_concurrency_is_bounded() = runTest {
        var active = 0
        var peak = 0
        var calls = 0
        val state = ChatFileMetadataState {
            calls++
            active++
            peak = maxOf(peak, active)
            kotlinx.coroutines.delay(100)
            active--
            ArtifactMetadata(16)
        }
        kotlinx.coroutines.coroutineScope {
            val jobs = (0..7).flatMap { i ->
                val ref = file.copy(remotePath = "/tmp/$i.mp4")
                listOf(async { state.load(ref) }, async { state.load(ref) })
            }
            jobs.forEach { it.await() }
        }
        assertEquals(8, calls)
        assertEquals(3, peak)
    }

}
