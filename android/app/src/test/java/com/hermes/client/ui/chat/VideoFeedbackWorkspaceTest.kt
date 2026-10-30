package com.hermes.client.ui.chat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermes.client.ui.createHermesComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.data.error.*
import com.hermes.client.data.video.*
import com.hermes.client.ui.localization.*
import com.hermes.client.ui.theme.HermesTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-420dpi")
class VideoFeedbackWorkspaceTest {
    @get:Rule val compose = createHermesComposeRule()
    private val restoration = StateRestorationTester(compose)
    private val androidContext = ApplicationProvider.getApplicationContext<Context>()
    private val scope = "account/mac/profile/original-session"
    private val generation = "source-generation"
    private var attached: File? = null
    private var closed = 0
    private var refusal: AppError? = null
    private var position by mutableLongStateOf(1234)
    private var pauses = 0
    private var resumes = 0
    private var seekTarget: Long? = null
    private var imeBottom by mutableIntStateOf(0)
    private var keyboardShows = 0
    private var showing by mutableStateOf(true)
    private lateinit var back: OnBackPressedDispatcher
    private val keyboard = object : SoftwareKeyboardController {
        override fun show() { keyboardShows++ }
        override fun hide() { imeBottom = 0 }
    }
    private fun fixture(registry: ActivityResultRegistryOwner? = null,
        document: VideoFeedbackDocument = VideoFeedbackDocument(notes = listOf(VideoNote("one", 1234, 4567, "延长这一段\n保留原声"))),
        enter: Boolean = true) {
        runBlocking { VideoFeedbackStore(File(androidContext.filesDir, "video-feedback")).write(
            videoFeedbackKey(scope, "/work/movie.mp4", generation),
            document) }
        restoration.setContent {
            HermesTheme {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.EN, LocalSoftwareKeyboardController provides keyboard) {
                    back = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
                    val content: @androidx.compose.runtime.Composable () -> Unit = {
                        if (showing) VideoFeedbackWorkspace("movie.mp4", VideoFeedbackContext(scope, "/work/movie.mp4", "原会话", attach = {
                            if (refusal == null) attached = it
                            refusal
                        }), generation, 60_000, { position }, { seekTarget = it }, { closed++ },
                            playerPosition = position, pause = { pauses++ }, resume = { resumes++ },
                            imeInsets = WindowInsets(bottom = imeBottom)) { annotate, editing, start, locked, review, annotations ->
                            VideoPlayerContent("movie.mp4", VideoPlayerState(position = 1234, duration = 60_000, hasFrame = true),
                                onAnnotate = annotate, annotating = editing, annotationStartMs = start, canPlay = !locked,
                                annotations = annotations, onReviewNotes = review, onClose = { showing = false })
                        }
                    }
                    if (registry == null) content() else CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) { content() }
                }
            }
        }
        if (enter) {
            compose.onNodeWithContentDescription("Annotate").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("video-note-point").fetchSemanticsNodes().isNotEmpty() }
        }
    }
    @Test fun marker_pauses_seeks_and_previews_without_keyboard_or_automatic_resume() {
        fixture()
        compose.onNodeWithTag("video-note-marker:one").performClick()
        compose.onNodeWithTag("video-note-preview").assertIsDisplayed()
        compose.onNodeWithText("延长这一段\n保留原声").assertIsDisplayed()
        compose.onNodeWithTag("video-note-text").assertDoesNotExist()
        assertEquals(1_234L, seekTarget); assertEquals(1, pauses); assertEquals(0, keyboardShows)
        compose.onNodeWithTag("video-note-preview-close").performClick()
        compose.onNodeWithTag("video-note-point").assertIsDisplayed()
        assertEquals(0, resumes)
    }
    @Test fun cluster_opens_all_notes_then_chosen_time_and_explicit_edit() {
        fixture(document = VideoFeedbackDocument(notes = listOf(
            VideoNote("one", 1_234, text = "First formal note"), VideoNote("two", 2_000, 4_000, "Second formal note"))))
        compose.onNodeWithTag("video-note-marker:one,two").performClick()
        assertNull(seekTarget)
        compose.onNodeWithText("First formal note").assertIsDisplayed()
        compose.onNodeWithText("Second formal note").assertIsDisplayed()
        compose.onNodeWithTag("video-note-preview-time:two").performClick()
        assertEquals(2_000L, seekTarget)
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithTag("video-note-text").assertTextContains("Second formal note")
        assertEquals(1, keyboardShows)
        compose.onNodeWithTag("video-note-marker:one,two").assertIsNotEnabled()
        compose.onNodeWithTag("video-note-cancel").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("video-note-point").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("video-note-marker:one,two").performClick()
        compose.onNodeWithText("Second formal note").assertIsDisplayed()
    }
    @Test fun preview_close_preserves_the_pending_range_and_does_not_add_drafts_to_track() {
        fixture()
        compose.onNodeWithTag("video-note-start").performClick()
        compose.onNodeWithTag("video-note-marker:one").performClick()
        compose.onNodeWithTag("video-note-preview-close").performClick()
        compose.onNodeWithText("Start marked").assertIsDisplayed()
        compose.onNodeWithTag("video-note-point").assertIsNotEnabled()
        compose.onAllNodes(hasTestTag("video-note-marker:one")).assertCountEquals(1)
        assertEquals(0, resumes)
    }
    @Test fun draft_only_video_has_no_formal_track() {
        fixture(document = VideoFeedbackDocument(draft = VideoNoteDraft(start = "0:12", text = "unfinished")))
        compose.onNodeWithTag("video-note-track").assertDoesNotExist()
        compose.onNodeWithText("Unfinished drafts", substring = true).assertIsDisplayed()
    }
    @Test fun cancel_discards_text_while_later_recovers_blank_point() {
        fixture()
        compose.onNodeWithTag("video-note-point").performClick()
        compose.onNodeWithTag("video-note-text").performTextInput("discard this")
        compose.onNodeWithTag("video-note-cancel").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("video-note-point").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Unfinished drafts", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("video-note-point").performClick()
        compose.onNodeWithTag("video-note-commit").assertIsNotEnabled()
        compose.onNodeWithTag("video-note-later").performClick()
        compose.onNodeWithText("Unfinished drafts", substring = true).performClick()
        compose.onNodeWithText("Continue draft").performClick()
        compose.onNodeWithTag("video-note-time").assertIsDisplayed()
        compose.onNodeWithTag("video-note-text").assertTextContains("")
    }
    @After fun cleanup() { attached?.delete() }
    @Test fun hiding_and_showing_ime_does_not_request_focus_again() {
        fixture()
        compose.onNodeWithTag("video-note-point").performClick()
        compose.waitForIdle()
        assertEquals(1, keyboardShows)
        compose.runOnIdle { imeBottom = 400 }
        compose.waitForIdle()
        compose.runOnIdle { imeBottom = 0 }
        compose.waitForIdle()
        assertEquals("IME layout changes must not reopen the keyboard", 1, keyboardShows)
    }
    @Test fun done_opens_export_summary_instead_of_hiding_saved_notes() {
        fixture()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Save .md").assertIsDisplayed()
        compose.onNodeWithText("Attach to chat").assertIsDisplayed()
        compose.onNodeWithTag("video-note-point").assertDoesNotExist()
        compose.onRoot().captureRoboImage("screenshots/video-feedback-workspace-summary.png")
    }
    @Test fun done_remains_available_with_only_a_range_start() {
        fixture()
        compose.onNodeWithTag("video-note-start").performClick()
        compose.onNodeWithText("Done").assertIsEnabled().performClick()
        compose.onNodeWithText("Save .md").assertIsDisplayed()
        compose.onNodeWithText("Feedback not written yet").assertDoesNotExist()
    }
    @Test fun system_back_hides_keyboard_then_returns_to_tools_without_losing_feedback() {
        fixture()
        compose.onNodeWithTag("video-note-point").performClick()
        compose.onNodeWithTag("video-note-text").performTextInput("unfinished feedback")
        compose.runOnIdle { imeBottom = 400 }
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithTag("video-note-text").assertExists().assertIsNotFocused()
        assertEquals(1, keyboardShows)
        compose.onRoot().captureRoboImage("screenshots/video-feedback-workspace-keyboard-hidden.png")
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithTag("video-note-point").assertIsDisplayed()
        compose.onNodeWithTag("video-note-text").assertDoesNotExist()
        compose.onNodeWithText("Unfinished drafts", substring = true).performClick()
        compose.onNodeWithText("unfinished feedback").assertIsDisplayed()
        compose.onNodeWithText("Save .md").assertDoesNotExist()
    }
    @Test fun later_returns_in_one_action_even_with_keyboard_visible() {
        fixture()
        compose.onNodeWithTag("video-note-point").performClick()
        compose.onNodeWithTag("video-note-text").performTextInput("unfinished feedback")
        compose.runOnIdle { imeBottom = 400 }
        compose.onNodeWithText("Finish later").performClick()
        compose.onNodeWithTag("video-note-point").assertIsDisplayed()
        compose.onNodeWithTag("video-note-text").assertDoesNotExist()
        assertEquals(1, keyboardShows)
    }
    @Test fun restoring_editor_after_keyboard_was_hidden_does_not_reopen_it() {
        fixture()
        compose.onNodeWithTag("video-note-point").performClick()
        compose.onNodeWithTag("video-note-text").performTextInput("unfinished feedback")
        compose.runOnIdle { imeBottom = 400 }
        compose.runOnIdle { back.onBackPressed() }
        assertEquals(1, keyboardShows)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("video-note-text").assertTextContains("unfinished feedback")
        assertEquals("Restoring a layout is not an explicit edit action", 1, keyboardShows)
    }
    @Test fun done_from_editor_exports_only_formal_notes_and_can_return_or_continue() {
        fixture()
        compose.onNodeWithTag("video-note-point").performClick()
        compose.onNodeWithTag("video-note-text").performTextInput("unfinished feedback")
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Notes · 1").assertIsDisplayed()
        compose.onNodeWithText("unfinished feedback").assertDoesNotExist()
        compose.onNodeWithText("Unfinished drafts", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Continue annotating").performClick()
        compose.onNodeWithTag("video-note-point").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Back to video").performClick()
        compose.onNodeWithTag("video-review-notes").assertIsDisplayed().performClick()
        compose.onNodeWithText("Attach to chat").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { attached != null }
        assertFalse(attached!!.readText().contains("unfinished feedback"))
    }
    @Test fun drafts_only_finish_goes_to_playback_without_summary_or_export() {
        fixture(document = VideoFeedbackDocument())
        compose.onNodeWithTag("video-note-start").performClick()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithContentDescription("Annotate").assertIsDisplayed()
        compose.onNodeWithText("Save .md").assertDoesNotExist()
        compose.onNodeWithTag("video-review-notes").assertDoesNotExist()
        compose.onNodeWithContentDescription("Annotate").performClick()
        compose.onNodeWithText("Unfinished drafts", substring = true).performClick()
        compose.onNodeWithText("Start around 00:01 · End not marked").assertIsDisplayed()
    }
    @Test fun closing_and_reopening_a_live_viewmodel_starts_playback_then_tools() {
        fixture()
        compose.onNodeWithTag("video-note-point").performClick()
        compose.onNodeWithTag("video-note-text").performTextInput("unfinished feedback")
        compose.onNodeWithContentDescription("Close").performClick()
        compose.runOnIdle { showing = true }
        compose.onNodeWithContentDescription("Annotate").assertIsDisplayed()
        compose.onNodeWithTag("video-note-text").assertDoesNotExist()
        compose.onNodeWithContentDescription("Annotate").performClick()
        compose.onNodeWithTag("video-note-point").assertIsDisplayed()
        compose.onNodeWithText("Unfinished drafts", substring = true).performClick()
        compose.onNodeWithText("Continue draft").performClick()
        compose.onNodeWithTag("video-note-text").assertTextContains("unfinished feedback")
    }
    @Test fun playback_offers_a_named_export_entry_without_entering_marking() {
        fixture(enter = false)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("video-review-notes").fetchSemanticsNodes().isNotEmpty() }
        compose.onRoot().captureRoboImage("screenshots/video-feedback-workspace-export-entry.png")
        compose.onNodeWithTag("video-review-notes").performClick()
        compose.onNodeWithText("Save .md").assertIsDisplayed()
        compose.onNodeWithTag("video-note-point").assertDoesNotExist()
    }
    @Test fun range_editor_commits_typed_feedback_before_exporting() {
        fixture()
        compose.onNodeWithTag("video-note-start").performClick()
        assertEquals(0, pauses)
        compose.runOnIdle { position = 4_567 }
        compose.onNodeWithTag("video-note-end").performClick()
        compose.onNodeWithTag("video-note-text").performScrollTo().performTextInput("延长片段并保留原声")
        compose.onNodeWithText("Submit note").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("View notes (2)").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertEquals(1, resumes) }
        compose.onNodeWithText("View notes (2)").performClick()
        compose.onNodeWithText("Attach to chat").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { attached != null }
        assertTrue(attached!!.readText().contains("延长片段并保留原声"))
    }
    @Test fun attach_generates_a_real_markdown_file_and_returns_to_the_original_composer() {
        fixture()
        compose.onNodeWithText("View notes (1)").performClick()
        compose.onNodeWithText("Attach to chat").performClick()
        compose.waitUntil(5_000) { attached != null }
        val text = attached!!.readText()
        assertTrue(text.contains("约 00:01–00:04"))
        assertTrue(text.contains("延长这一段\n保留原声"))
        assertTrue(text.contains("/work/movie.mp4"))
        assertTrue(text.contains("source-generation"))
        assertTrue(attached!!.name.endsWith(".md"))
        compose.runOnIdle { assertEquals(1, closed) }
    }
    @Test fun attachment_refusal_preserves_notes_and_does_not_close_the_viewer() {
        refusal = AppError(AppErrorCode.VIDEO_NOTES_ATTACHMENTS_FULL, false)
        fixture()
        compose.onNodeWithText("View notes (1)").performClick()
        compose.onNodeWithText("Attach to chat").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("HR-ANNOT-004", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Back to notes").performClick()
        assertNull(attached); assertEquals(0, closed)
        compose.onNodeWithText("Attach to chat").assertIsEnabled()
    }
    @Test fun system_document_save_writes_the_selected_file() {
        val target = File(androidContext.cacheDir, "selected-feedback.md")
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                    options: ActivityOptionsCompat?) {
                    val intent = contract.createIntent(androidContext, input)
                    assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
                    assertEquals("text/markdown", intent.type)
                    dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(Uri.fromFile(target)))
                }
            }
        }
        fixture(owner)
        compose.onNodeWithText("View notes (1)").performClick()
        compose.onNodeWithText("Save .md").performClick()
        compose.waitUntil(5_000) { target.isFile && target.length() > 0 }
        assertTrue(target.readText().contains("延长这一段"))
        assertNull(attached); assertEquals(0, closed)
        target.delete()
    }
}
