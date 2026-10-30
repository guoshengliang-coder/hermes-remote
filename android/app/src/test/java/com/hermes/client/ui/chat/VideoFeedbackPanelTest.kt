package com.hermes.client.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermes.client.data.video.*
import com.hermes.client.ui.createHermesComposeRule
import com.hermes.client.ui.localization.*
import com.hermes.client.ui.theme.HermesTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-420dpi")
class VideoFeedbackPanelTest {
    @get:Rule val compose = createHermesComposeRule()
    private val note = VideoNote("one", 1_234, 4_567, "前后衔接偏快，请延长这一段。")
    private fun render(dark: Boolean, language: AppLanguage, screen: VideoFeedbackScreen = VideoFeedbackScreen.TOOLS,
        draft: VideoNoteDraft? = null, landscape: Boolean = false, fontScale: Float = 1.3f) {
        compose.setContent {
            HermesTheme(darkTheme = dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalAppLanguage provides language, LocalDensity provides Density(density.density, fontScale)) {
                    val state = VideoFeedbackState(VideoFeedbackDocument(notes = listOf(note), draft = draft), loaded = true, screen = screen)
                    val media: @Composable () -> Unit = { VideoPlayerContent("阶段二试片.mp4", VideoPlayerState(
                        position = 3_456, duration = 60_000, hasFrame = true), onAnnotate = {}, annotating = screen != VideoFeedbackScreen.LIST, annotationStartMs = state.selectionStart) }
                    val panel: @Composable (Modifier) -> Unit = { modifier ->
                        VideoFeedbackPanel(state, 60_000, 3_456, true, VideoFeedbackActions(), modifier = modifier)
                    }
                    if (landscape) Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight()) { media() }
                        panel(Modifier.weight(1f).fillMaxHeight())
                    } else Column(Modifier.fillMaxSize()) {
                        val expanded = screen in listOf(VideoFeedbackScreen.EDITOR, VideoFeedbackScreen.LIST, VideoFeedbackScreen.DRAFTS)
                        Box(Modifier.weight(if (expanded) .32f else .42f)) { media() }
                        panel(Modifier.weight(if (expanded) .68f else .58f))
                    }
                }
            }
        }
    }
    @Test fun editor_has_one_input_and_three_unified_actions_without_retiming() {
        render(false, AppLanguage.EN, VideoFeedbackScreen.EDITOR,
            VideoNoteDraft(start = "0:12", text = "Keep this moment"), fontScale = 1f)
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        compose.onNodeWithText("Submit note").assertIsDisplayed()
        compose.onNodeWithText("Finish later").assertIsDisplayed()
        compose.onNodeWithText("Cancel and return").assertIsDisplayed()
        compose.onNodeWithText("Mark point again").assertDoesNotExist()
        compose.onNodeWithText("Back to video").assertDoesNotExist()
    }
    @Test fun light_large_text_tools_are_separate_from_list_and_export() {
        render(false, AppLanguage.ZH)
        compose.onNodeWithTag("video-note-point").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("video-note-end").assertIsNotEnabled()
        compose.onNodeWithText("保存 .md").assertDoesNotExist()
        compose.onNodeWithTag("video-note-text").assertDoesNotExist()
        compose.onRoot().captureRoboImage("screenshots/video-feedback-light-zh-fs13.png")
    }
    @Test fun dark_in_light_system_large_text() {
        render(true, AppLanguage.EN)
        compose.onNodeWithTag("video-note-point").assertIsDisplayed()
        compose.onRoot().captureRoboImage("screenshots/video-feedback-dark-en-fs13.png")
    }
    @Test fun range_editor_uses_readonly_time_and_reachable_commit() {
        render(false, AppLanguage.ZH, VideoFeedbackScreen.EDITOR,
            VideoNoteDraft(start = "00:00:01.234", end = "00:00:04.567", range = true, text = "这段需要拉长"))
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        compose.onNodeWithText("提交批注").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("video-note-time").assertIsDisplayed()
        compose.onNodeWithText("重新选段").assertDoesNotExist()
        compose.onRoot().captureRoboImage("screenshots/video-feedback-range-editor-zh-fs13.png")
    }
    @Test fun range_selection_disables_point_and_enables_valid_end() {
        render(false, AppLanguage.ZH, VideoFeedbackScreen.SELECTING, VideoNoteDraft(start = "0:01.234", range = true), fontScale = 1f)
        compose.onNodeWithTag("video-note-point").assertIsNotEnabled()
        compose.onNodeWithTag("video-note-end").assertIsEnabled()
        compose.onNodeWithText("重设起点").assertIsDisplayed()
        compose.onNodeWithText("取消选段").assertIsDisplayed()
        compose.onRoot().captureRoboImage("screenshots/video-feedback-selection-zh.png")
    }
    @Test @Config(qualifiers = "w800dp-h360dp-420dpi") fun landscape_list_actions_remain_reachable() {
        render(true, AppLanguage.EN, VideoFeedbackScreen.LIST, landscape = true)
        compose.onNodeWithText("Save .md").assertIsDisplayed()
        compose.onNodeWithText("Attach to chat").assertIsDisplayed()
        compose.onRoot().captureRoboImage("screenshots/video-feedback-landscape-en-fs13.png")
    }
    @Test fun list_delete_requires_confirmation_and_time_seeks() {
        var deleted: String? = null; var target: Long? = null
        compose.setContent { HermesTheme {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.EN) {
                VideoFeedbackPanel(VideoFeedbackState(VideoFeedbackDocument(notes = listOf(note)), loaded = true, screen = VideoFeedbackScreen.LIST),
                    60_000, 2_345, true, VideoFeedbackActions(delete = { deleted = it }, seek = { target = it }))
            }
        } }
        compose.onNodeWithText("Approx. 00:01–00:04").performClick()
        assertEquals(1_234L, target)
        compose.onNodeWithText("Delete").performClick(); assertNull(deleted)
        compose.onNodeWithText("Cancel").performClick(); assertNull(deleted)
        compose.onNodeWithText("Delete").performClick()
        compose.onAllNodesWithText("Delete").onLast().performClick()
        assertEquals("one", deleted)
    }
    @Test fun summary_never_displays_drafts_or_their_actions() {
        render(false, AppLanguage.ZH, VideoFeedbackScreen.LIST,
            VideoNoteDraft(start = "0:12", text = "这是一份未确认草稿"))
        compose.onNodeWithText("这是一份未确认草稿").assertDoesNotExist()
        compose.onNodeWithText("继续填写").assertDoesNotExist()
        compose.onNodeWithText("删除草稿").assertDoesNotExist()
        compose.onNodeWithText("批注汇总 · 1").assertIsDisplayed()
        compose.onNodeWithText("保存 .md").assertIsDisplayed()
        compose.onNodeWithText("加入原会话").assertIsDisplayed()
        compose.onRoot().captureRoboImage("screenshots/video-feedback-summary-zh-fs13.png")
    }
    @Test fun drafts_have_their_own_surface_without_export_or_formal_notes() {
        render(true, AppLanguage.ZH, VideoFeedbackScreen.DRAFTS,
            VideoNoteDraft(start = "0:12", range = true, text = "等终点确认后再补充意见"))
        compose.onNodeWithText("等终点确认后再补充意见").assertIsDisplayed()
        compose.onNodeWithText(note.text).assertDoesNotExist()
        compose.onNodeWithText("保存 .md").assertDoesNotExist()
        compose.onNodeWithText("加入原会话").assertDoesNotExist()
        compose.onNodeWithText("继续填写").assertIsDisplayed()
        compose.onRoot().captureRoboImage("screenshots/video-feedback-drafts-dark-zh-fs13.png")
    }
    @Test fun summary_export_is_reachable_at_twice_normal_font_size() {
        render(true, AppLanguage.EN, VideoFeedbackScreen.LIST, fontScale = 2f)
        compose.onNodeWithText("Save .md").assertIsDisplayed()
        compose.onNodeWithText("Attach to chat").assertIsDisplayed()
        compose.onNodeWithText("Back to video").assertIsDisplayed()
        compose.onNodeWithText(note.text).assertIsDisplayed()
        compose.onRoot().captureRoboImage("screenshots/video-feedback-summary-en-fs20.png")
    }
    @Test fun unavailable_frame_and_backwards_end_never_accept_marks() {
        var ready by mutableStateOf(false); var position by mutableLongStateOf(500)
        val state = VideoFeedbackState(VideoFeedbackDocument(draft = VideoNoteDraft(start = "0:01", range = true)), loaded = true, screen = VideoFeedbackScreen.SELECTING)
        compose.setContent { HermesTheme { VideoFeedbackPanel(state, 60_000, position, ready, VideoFeedbackActions()) } }
        compose.onNodeWithTag("video-note-start").assertIsNotEnabled()
        compose.runOnIdle { ready = true }; compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        compose.onNodeWithTag("video-note-start").assertIsEnabled()
        compose.onNodeWithTag("video-note-end").assertIsNotEnabled()
        compose.runOnIdle { position = 2_000 }; compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        compose.onNodeWithTag("video-note-end").assertIsEnabled()
    }
    @Test @Config(qualifiers = "w360dp-h800dp-420dpi") fun two_times_font_still_exposes_comment_and_save() {
        render(true, AppLanguage.EN, VideoFeedbackScreen.EDITOR,
            VideoNoteDraft(start = "00:00:01.234", text = "Keep this frame longer."), fontScale = 2f)
        compose.onNodeWithTag("video-note-commit").assertIsDisplayed()
        compose.onNodeWithTag("video-note-time").assertIsDisplayed()
        compose.onRoot().captureRoboImage("screenshots/video-feedback-editor-en-fs20.png")
    }
}
