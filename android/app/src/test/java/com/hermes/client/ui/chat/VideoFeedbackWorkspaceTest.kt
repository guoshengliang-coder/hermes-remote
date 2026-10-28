package com.hermes.client.ui.chat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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
    @get:Rule val compose = createComposeRule()
    private val androidContext = ApplicationProvider.getApplicationContext<Context>()
    private val scope = "account/mac/profile/original-session"
    private val generation = "source-generation"
    private var attached: File? = null
    private var closed = 0
    private var refusal: AppError? = null
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
                        }), generation, 60_000, { 1234 }, {}, { closed++ }) { annotate, _ ->
                            if (annotate != null) Button(onClick = annotate) { Text("Annotate") }
                        }
                    }
                    if (registry == null) content() else CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) { content() }
                }
            }
        }
        compose.onNodeWithText("Annotate").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Saved on this device · This video version").fetchSemanticsNodes().isNotEmpty() }
    }
    @After fun cleanup() { attached?.delete() }
    @Test fun attach_generates_a_real_markdown_file_and_returns_to_the_original_composer() {
        fixture()
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
        refusal = AppError(AppErrorCode.UPLOAD_BUSY, false)
        fixture()
        compose.onNodeWithText("Attach to chat").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("HR-FILE-009", substring = true).fetchSemanticsNodes().isNotEmpty() }
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
        compose.onNodeWithText("Save .md").performClick()
        compose.waitUntil(5_000) { target.isFile && target.length() > 0 }
        assertTrue(target.readText().contains("延长这一段"))
        assertNull(attached); assertEquals(0, closed)
        target.delete()
    }
}
