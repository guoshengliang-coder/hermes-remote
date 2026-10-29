package com.hermes.client.ui.chat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-420dpi")
class VideoFeedbackWorkspaceTest {
    @get:Rule val compose = createHermesComposeRule()
    private val androidContext = ApplicationProvider.getApplicationContext<Context>()
    private val scope = "account/mac/profile/original-session"
    private val generation = "source-generation"
    private var attached: File? = null
    private var closed = 0
    private var refusal: AppError? = null
    private var position by mutableLongStateOf(1234)
    private var pauses = 0
    private var resumes = 0
    private fun fixture(registry: ActivityResultRegistryOwner? = null) {
        runBlocking { VideoFeedbackStore(File(androidContext.filesDir, "video-feedback")).write(
            videoFeedbackKey(scope, "/work/movie.mp4", generation),
            VideoFeedbackDocument(notes = listOf(VideoNote("one", 1234, 4567, "延长这一段\n保留原声")))) }
        compose.setContent {
            HermesTheme {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.EN) {
                    val content: @androidx.compose.runtime.Composable () -> Unit = {
                        VideoFeedbackWorkspace("movie.mp4", VideoFeedbackContext(scope, "/work/movie.mp4", "原会话", attach = {
                            if (refusal == null) attached = it
                            refusal
                        }), generation, 60_000, { position }, {}, { closed++ },
                            playerPosition = position, pause = { pauses++ }, resume = { resumes++ }) { annotate, editing, start, locked ->
                            VideoPlayerContent("movie.mp4", VideoPlayerState(position = 1234, duration = 60_000, hasFrame = true),
                                onAnnotate = annotate, annotating = editing, annotationStartMs = start, canPlay = !locked)
                        }
                    }
                    if (registry == null) content() else CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) { content() }
                }
            }
        }
        compose.onNodeWithText("Annotate").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("video-note-point").fetchSemanticsNodes().isNotEmpty() }
    }
    @After fun cleanup() { attached?.delete() }
    @Test fun range_editor_commits_typed_feedback_before_exporting() {
        fixture()
        compose.onNodeWithTag("video-note-start").performClick()
        assertEquals(0, pauses)
        compose.runOnIdle { position = 4_567 }
        compose.onNodeWithTag("video-note-end").performClick()
        compose.onNodeWithTag("video-note-text").performScrollTo().performTextInput("延长片段并保留原声")
        compose.onNodeWithText("Save and continue").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Notes · 2").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertEquals(1, resumes) }
        compose.onNodeWithText("Notes · 2").performClick()
        compose.onNodeWithText("Attach to chat").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { attached != null }
        assertTrue(attached!!.readText().contains("延长片段并保留原声"))
    }
    @Test fun attach_generates_a_real_markdown_file_and_returns_to_the_original_composer() {
        fixture()
        compose.onNodeWithText("Notes · 1").performClick()
        compose.onNodeWithText("Attach to chat").performClick()
        compose.waitUntil(5_000) { attached != null }
        val text = attached!!.readText()
        assertTrue(text.contains("00:00:01.234 – 00:00:04.567"))
        assertTrue(text.contains("延长这一段\n保留原声"))
        assertTrue(text.contains("/work/movie.mp4"))
        assertTrue(text.contains("source-generation"))
        assertTrue(attached!!.name.endsWith(".md"))
        compose.runOnIdle { assertEquals(1, closed) }
    }
    @Test fun attachment_refusal_preserves_notes_and_does_not_close_the_viewer() {
        refusal = AppError(AppErrorCode.VIDEO_NOTES_ATTACHMENTS_FULL, false)
        fixture()
        compose.onNodeWithText("Notes · 1").performClick()
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
        compose.onNodeWithText("Notes · 1").performClick()
        compose.onNodeWithText("Save .md").performClick()
        compose.waitUntil(5_000) { target.isFile && target.length() > 0 }
        assertTrue(target.readText().contains("延长这一段"))
        assertNull(attached); assertEquals(0, closed)
        target.delete()
    }
}
