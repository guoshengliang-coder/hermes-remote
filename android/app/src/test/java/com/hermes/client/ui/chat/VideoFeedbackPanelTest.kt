package com.hermes.client.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermes.client.data.video.*
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
    @get:Rule val compose = createComposeRule()
    private val note = VideoNote("one", 1_234, 4_567, "前后衔接偏快，请延长这一段。")
    private fun render(dark: Boolean, language: AppLanguage, draft: VideoNoteDraft? = null, landscape: Boolean = false) {
        compose.setContent {
            HermesTheme(darkTheme = dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalAppLanguage provides language, LocalDensity provides Density(density.density, 1.3f)) {
                    val media: @Composable () -> Unit = { VideoPlayerContent("阶段二试片.mp4", VideoPlayerState(
                        position = 1_234, duration = 60_000, hasFrame = true)) }
                    val panel: @Composable (Modifier) -> Unit = { modifier ->
                        VideoFeedbackPanel(VideoFeedbackState(VideoFeedbackDocument(notes = listOf(note), draft = draft), loaded = true),
                            60_000, { 1_234 }, {}, {}, {}, {}, {}, {}, {}, {}, modifier = modifier)
                    }
                    if (landscape) Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight()) { media() }
                        panel(Modifier.weight(1f).fillMaxHeight())
                    } else Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(.42f)) { media() }
                        panel(Modifier.weight(.58f))
                    }
                }
            }
        }
    }
    @Test fun light_large_text() {
        render(false, AppLanguage.ZH)
        compose.onNodeWithText("保存 .md").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("加入原会话").assertIsDisplayed().assertIsEnabled()
        compose.onRoot().captureRoboImage("screenshots/video-feedback-light-zh-fs13.png")
    }
    @Test fun dark_in_light_system_large_text() {
        render(true, AppLanguage.EN)
        compose.onNodeWithText("Attach to chat").assertIsDisplayed()
        compose.onRoot().captureRoboImage("screenshots/video-feedback-dark-en-fs13.png")
    }
    @Test fun draft_scrolls_to_commit_and_disables_export_without_losing_text() {
        render(false, AppLanguage.ZH, VideoNoteDraft(start = "00:00:01.234", end = "00:00:04.567", range = true, text = "这段需要拉长"))
        compose.onNodeWithText("保存 .md").assertIsNotEnabled()
        compose.onNodeWithText("保存批注").performScrollTo().assertIsDisplayed()
        compose.onRoot().captureRoboImage("screenshots/video-feedback-range-editor-zh-fs13.png")
    }
    @Test @Config(qualifiers = "w800dp-h360dp-420dpi") fun landscape_actions_remain_reachable() {
        render(true, AppLanguage.EN, landscape = true)
        compose.onNodeWithText("Save .md").assertIsDisplayed()
        compose.onNodeWithText("Attach to chat").assertIsDisplayed()
        compose.onRoot().captureRoboImage("screenshots/video-feedback-landscape-en-fs13.png")
    }
    @Test fun add_uses_current_position_and_delete_requires_confirmation() {
        var draft: VideoNoteDraft? = null
        var deleted: String? = null
        var target: Long? = null
        compose.setContent { HermesTheme {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.EN) {
                VideoFeedbackPanel(VideoFeedbackState(VideoFeedbackDocument(notes = listOf(note)), loaded = true),
                    60_000, { 2_345 }, { draft = it }, {}, { deleted = it }, { target = it }, {}, {}, {}, {})
            }
        } }
        compose.onNodeWithText("Note a range").performClick()
        assertTrue(draft!!.range); assertEquals("00:00:02.345", draft!!.start)
        compose.onNodeWithText("00:00:01.234 – 00:00:04.567").performClick()
        assertEquals(1_234L, target)
        compose.onNodeWithText("Delete").performClick()
        assertNull(deleted)
        compose.onNodeWithText("Cancel").performClick()
        assertNull(deleted)
        compose.onNodeWithText("Delete").performClick()
        compose.onAllNodesWithText("Delete").onLast().performClick()
        assertEquals("one", deleted)
    }
}
