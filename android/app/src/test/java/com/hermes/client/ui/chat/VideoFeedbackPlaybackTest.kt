package com.hermes.client.ui.chat

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import com.hermes.client.data.video.*
import com.hermes.client.ui.createHermesComposeRule
import com.hermes.client.ui.localization.*
import com.hermes.client.ui.theme.HermesTheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** Real Workspace callbacks around a deliberately suspended durable write. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-420dpi")
class VideoFeedbackPlaybackTest {
    @get:Rule val compose = createHermesComposeRule()
    private val entered = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var disk = VideoFeedbackDocument()
    private var fail = false
    private var epoch = 0L
    private var resumes = 0
    private var pauses = 0
    private var showing by mutableStateOf(true)
    private val mode = mutableStateOf(false)
    private lateinit var vm: VideoFeedbackViewModel
    private fun fixture() {
        val storage = object : VideoFeedbackStorage {
            override suspend fun read(key: String) = disk
            override suspend fun write(key: String, document: VideoFeedbackDocument) {
                if (document.notes.isNotEmpty() && document.draft == null) {
                    entered.complete(Unit); release.await()
                    if (fail) throw IOException("fixture write unavailable")
                }
                disk = document
            }
        }
        vm = VideoFeedbackViewModel(storage, "fixture", scope)
        compose.setContent { HermesTheme { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.EN) {
            if (showing) VideoFeedbackWorkspace("fixture.mp4", VideoFeedbackContext("scope", "source", "chat", attach = { null }),
                "version", 60_000, { 12_345 }, {}, {}, mode = mode, playerPosition = 12_345,
                pause = { pauses++ }, resume = { resumes++ }, playbackEpoch = { epoch }, feedbackModel = vm) { annotate, editing, start, locked, review ->
                VideoPlayerContent("fixture.mp4", VideoPlayerState(position = 12_345, duration = 60_000, hasFrame = true),
                    onAnnotate = annotate, annotating = editing, annotationStartMs = start, canPlay = !locked, onReviewNotes = review)
            }
        } } }
        compose.onNodeWithText("Annotate").performClick()
        compose.waitUntil(5_000) { compose.onRoot().fetchSemanticsNode(); vm.state.loaded }
        assertEquals(0, pauses) // Entering annotation mode must not pause.
        compose.onNodeWithTag("video-note-point").performClick()
        compose.onNodeWithTag("video-note-text").performTextInput("Keep the original frame.")
        compose.onNodeWithTag("video-note-commit").performClick()
        compose.waitUntil(5_000) { compose.onRoot().fetchSemanticsNode(); entered.isCompleted }
        assertTrue(vm.state.committing)
        assertNotNull(vm.state.document.draft)
        compose.onNodeWithTag("video-timeline").assertIsNotEnabled()
        compose.onAllNodesWithContentDescription("Play").onFirst().assertIsNotEnabled()
        assertEquals(0, resumes)
    }
    private fun awaitCommit() = compose.waitUntil(5_000) { compose.onRoot().fetchSemanticsNode(); !vm.state.committing }
    @After fun cleanup() { scope.cancel() }

    @Test fun playback_resumes_only_after_successful_write_and_mode_stays_open() {
        fixture(); release.complete(Unit); awaitCommit()
        compose.runOnIdle { assertEquals(1, resumes); assertTrue(mode.value) }
        compose.onNodeWithTag("video-note-point").assertIsDisplayed()
        assertEquals(12_345L, disk.notes.single().startMs)
    }
    @Test fun background_and_return_before_write_finishes_never_autoplay() {
        fixture()
        // Read at callback time, even before the next recomposition. Returning doesn't erase it.
        epoch++
        release.complete(Unit); awaitCommit()
        compose.runOnIdle { assertEquals(0, resumes) }
        assertEquals(1, disk.notes.size)
    }
    @Test fun closing_the_viewer_does_not_cancel_the_write_or_resume_playback() {
        fixture(); compose.runOnIdle { showing = false }; compose.waitForIdle()
        release.complete(Unit)
        compose.waitUntil(5_000) { compose.onRoot().fetchSemanticsNode(); disk.notes.size == 1 }
        assertEquals(0, resumes)
        assertNull(disk.draft)
    }
    @Test fun failed_write_keeps_the_editor_and_retry_saves_once_then_resumes() {
        fixture(); fail = true; release.complete(Unit); awaitCommit()
        compose.onNodeWithTag("video-note-text").assertTextContains("Keep the original frame.")
        compose.onNodeWithText("HR-ANNOT-001", substring = true).assertExists()
        assertEquals(0, resumes); assertTrue(disk.notes.isEmpty())
        fail = false
        compose.onNodeWithText("Retry").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onRoot().fetchSemanticsNode(); disk.notes.size == 1 }
        compose.runOnIdle { assertEquals(1, resumes) }
        assertNull(disk.draft)
    }
}
