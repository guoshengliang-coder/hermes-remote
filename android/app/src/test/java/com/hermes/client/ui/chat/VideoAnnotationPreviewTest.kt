package com.hermes.client.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermes.client.data.video.VideoNote
import com.hermes.client.ui.createHermesComposeRule
import com.hermes.client.ui.localization.*
import com.hermes.client.ui.theme.HermesTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-420dpi")
class VideoAnnotationPreviewTest {
    @get:Rule val compose = createHermesComposeRule()
    private val notes = listOf(VideoNote("a", 0, text = "开场多停留一秒 / Hold the opening for one more second."),
        VideoNote("b", 1_000, text = "保留原声 / Keep original audio."),
        VideoNote("range", 45_000, 55_000, "这一段衔接偏快，请延长。 / Slow this section down."))
    private fun render(dark: Boolean, language: AppLanguage, fontScale: Float, landscape: Boolean = false) {
        var ids by mutableStateOf(listOf("range"))
        compose.setContent { HermesTheme(darkTheme = dark) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalAppLanguage provides language, LocalDensity provides Density(density.density, fontScale)) {
                val media: @Composable () -> Unit = { VideoPlayerContent("视频批注.mp4", VideoPlayerState(duration = 60_000, position = 45_000, hasFrame = true),
                    onAnnotate = {}, annotating = true, annotations = VideoAnnotationTimeline(notes, select = { ids = it })) }
                val preview: @Composable (Modifier) -> Unit = { modifier -> VideoNotePreview(notes.filter { it.id in ids },
                    select = { ids = listOf(it) }, edit = {}, close = {}, modifier = modifier) }
                if (landscape) Row(Modifier.fillMaxSize()) { Box(Modifier.weight(1f).fillMaxHeight()) { media() }; preview(Modifier.weight(1f).fillMaxHeight()) }
                else Column(Modifier.fillMaxSize()) { Box(Modifier.weight(.32f)) { media() }; preview(Modifier.weight(.68f)) }
            }
        } }
    }
    @Test fun light_preview_and_cluster_have_disjoint_touch_regions_and_no_keyboard() {
        render(false, AppLanguage.ZH, 1.3f)
        compose.onNodeWithTag("video-note-marker:a,b").assertIsDisplayed().assertIsEnabled()
        val cluster = compose.onNodeWithTag("video-note-marker:a,b").fetchSemanticsNode().boundsInRoot
        val range = compose.onNodeWithTag("video-note-marker:range").fetchSemanticsNode().boundsInRoot
        val seek = compose.onNodeWithTag("video-timeline").fetchSemanticsNode().boundsInRoot
        assertTrue(cluster.right <= range.left); assertTrue(cluster.bottom <= seek.top)
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("编辑").assertIsDisplayed()
        compose.onRoot().captureRoboImage("screenshots/video-note-preview-light-zh-fs13.png")
        compose.onNodeWithTag("video-note-marker:a,b").performClick()
        compose.onNodeWithText(notes[0].text).assertIsDisplayed()
        compose.onNodeWithText(notes[1].text).assertIsDisplayed()
        compose.onRoot().captureRoboImage("screenshots/video-note-cluster-light-zh-fs13.png")
    }
    @Test fun dark_preview_keeps_fixed_actions_at_twice_normal_font_size() {
        render(true, AppLanguage.EN, 2f)
        compose.onNodeWithTag("video-note-preview-close").assertIsDisplayed()
        compose.onNodeWithText("Edit").assertIsDisplayed()
        compose.onRoot().captureRoboImage("screenshots/video-note-preview-dark-en-fs20.png")
    }
    @Test @Config(qualifiers = "w800dp-h360dp-420dpi") fun landscape_preview_keeps_time_and_close_reachable() {
        render(true, AppLanguage.EN, 1.3f, landscape = true)
        compose.onNodeWithTag("video-note-preview-time:range").assertIsDisplayed()
        compose.onNodeWithTag("video-note-preview-close").assertIsDisplayed()
        compose.onRoot().captureRoboImage("screenshots/video-note-preview-landscape-en-fs13.png")
    }
}
