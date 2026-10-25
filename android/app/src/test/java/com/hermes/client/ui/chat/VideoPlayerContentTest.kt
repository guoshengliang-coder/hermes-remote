package com.hermes.client.ui.chat

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermes.client.domain.ChatFile
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
class VideoPlayerContentTest {
    @get:Rule val compose = createComposeRule()
    private val playing = VideoPlayerState(position = 12_000, duration = 60_000, buffered = 28_000, wantsPlay = true, hasFrame = true)
    @Test fun controls_auto_hide_and_tap_reveals_while_pause_pins_controls() {
        compose.mainClock.autoAdvance = false
        var state by mutableStateOf(playing)
        compose.setContent { HermesTheme { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.EN) {
            VideoPlayerContent("fixture.mp4", state = state)
        } } }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithContentDescription("More").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(3_100)
        compose.onNodeWithContentDescription("More").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("video-touch-surface").performClick()
        compose.onNodeWithContentDescription("More").assertIsDisplayed()
        compose.runOnIdle { state = playing.copy(wantsPlay = false) }
        compose.mainClock.advanceTimeBy(50)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(4_000)
        compose.onNodeWithContentDescription("More").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Play").assertCountEquals(2)
    }
    @Test fun seek_shows_target_time_and_commits_only_on_release() {
        var target: Long? = null
        compose.setContent { HermesTheme { VideoPlayerContent("fixture.mp4", playing.copy(wantsPlay = false), onSeek = { target = it }) } }
        compose.onNodeWithTag("video-timeline").performTouchInput {
            down(Offset(width * .2f, centerY)); moveTo(Offset(width * .7f, centerY))
        }
        compose.runOnIdle { assertNull(target) }
        compose.onNodeWithTag("video-timeline").performTouchInput { up() }
        compose.runOnIdle { assertTrue(target!! in 38_000..45_000) }
    }
    @Test fun buffering_pins_controls_and_uses_loading_or_rebuffering_copy() {
        var state by mutableStateOf(playing.copy(buffering = true, hasFrame = false))
        compose.setContent { HermesTheme { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.EN) {
            VideoPlayerContent("fixture.mp4", state)
        } } }
        compose.onNodeWithText("Loading video…").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(hasFrame = true) }
        compose.onNodeWithText("Buffering…").assertIsDisplayed()
        compose.onNodeWithContentDescription("More").assertIsDisplayed()
    }
    @Test fun metadata_survives_activity_recreation_and_explicit_close() {
        val restore = StateRestorationTester(compose)
        val file = ChatFile("v", "long.mp4", "video/mp4", 8_000_000_000L, remotePath = "/fixture/v.mp4")
        var current: ChatFile? = null
        var set: (ChatFile?) -> Unit = {}
        restore.setContent {
            var selected by rememberVideoFile("workspace")
            current = selected; set = { selected = it }
        }
        compose.runOnIdle { set(file) }; compose.waitForIdle()
        restore.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals(file, current); set(null) }; compose.waitForIdle()
        restore.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertNull(current) }
    }
    @Test fun restored_video_selection_cannot_cross_workspaces() {
        val restore = StateRestorationTester(compose)
        var scope by mutableStateOf("account-A:mac-A:chat-A")
        var selected: ChatFile? = null
        var choose: (ChatFile?) -> Unit = {}
        restore.setContent {
            var current by rememberVideoFile(scope)
            selected = current; choose = { current = it }
        }
        compose.runOnIdle { choose(ChatFile("v", "private.mp4")) }; compose.waitForIdle()
        compose.runOnIdle { scope = "account-B:mac-B:chat-B" }; compose.waitForIdle()
        restore.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertNull(selected) }
    }


    @Test fun speed_menu_selects_every_speed_and_hold_preference_is_independent() {
        var state by mutableStateOf(playing.copy(wantsPlay = false))
        var hold by mutableFloatStateOf(2f)
        compose.setContent { HermesTheme { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.EN) {
            VideoPlayerContent("fixture.mp4", state, onSpeed = { state = state.copy(speed = it) },
                holdSpeed = hold, onHoldSpeed = { hold = it })
        } } }
        VIDEO_SPEEDS.forEach { speed ->
            compose.onNodeWithContentDescription("Playback speed").performClick()
            compose.onAllNodesWithText(speedLabel(speed)).onLast().performScrollTo().performClick()
            compose.runOnIdle { assertEquals(speed, state.speed) }
        }
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Hold speed: 2×").performClick()
        compose.onNodeWithText("Hold 2.5×").performClick()
        compose.runOnIdle { assertEquals(2.5f, hold); assertEquals(3f, state.speed) }
    }
    @Test fun held_picture_releases_on_up_multitouch_cancel_and_pause_without_clicking() {
        val calls = mutableListOf<Boolean>()
        var state by mutableStateOf(playing)
        compose.setContent { HermesTheme { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.EN) {
            VideoPlayerContent("fixture.mp4", state, onBoost = { calls += it })
        } } }
        val picture = compose.onNodeWithTag("video-touch-surface")
        picture.performTouchInput { down(Offset(width * .2f, height * .45f)) }
        compose.mainClock.advanceTimeBy(650)
        compose.runOnIdle { assertEquals(listOf(true), calls) }
        picture.performTouchInput { up() }
        compose.runOnIdle { assertEquals(listOf(true, false), calls) }
        compose.onNodeWithContentDescription("More").assertIsDisplayed() // release was not a tap
        picture.performTouchInput { down(Offset(width * .2f, height * .45f)) }
        compose.mainClock.advanceTimeBy(650)
        picture.performTouchInput { down(1, Offset(width * .3f, height * .4f)); up(1); up(0) }
        compose.runOnIdle { assertEquals(listOf(true, false, true, false), calls) }
        picture.performTouchInput { down(Offset(width * .2f, height * .45f)) }
        compose.mainClock.advanceTimeBy(650)
        compose.runOnIdle { state = state.copy(wantsPlay = false) }
        picture.performTouchInput { up() }
        compose.runOnIdle { assertEquals(listOf(true, false, true, false, true, false), calls); calls.clear() }
        picture.performTouchInput { longClick(Offset(width * .2f, height * .45f)) }
        compose.runOnIdle { assertFalse(calls.contains(true)) }
    }
    @Test fun controls_and_seek_never_start_acceleration_and_cache_is_not_timeline_buffer() {
        val boosts = mutableListOf<Boolean>()
        compose.setContent { HermesTheme { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.EN) {
            VideoPlayerContent("fixture.mp4", playing.copy(wantsPlay = false), onBoost = { boosts += it },
                precache = com.hermes.client.data.video.VideoPrecacheState(999, 1_000))
        } } }
        compose.onNodeWithTag("video-cache-progress").assertTextContains("99%", substring = true)
        compose.onNodeWithContentDescription("Playback speed").performTouchInput { longClick() }
        compose.onNodeWithTag("video-timeline").performTouchInput { longClick() }
        compose.runOnIdle { assertTrue(boosts.isEmpty()) }
    }
    @Test @Config(qualifiers = "w320dp-h720dp-420dpi")
    fun narrow_speed_and_cache_large_text() {
        compose.setContent { HermesTheme(darkTheme = true) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.ZH, LocalDensity provides Density(density.density, 1.3f)) {
                VideoPlayerContent("长片.mp4", playing.copy(wantsPlay = false, duration = 7_200_000, speed = 1.75f),
                    precache = com.hermes.client.data.video.VideoPrecacheState(100_000_000, 3_000_000_000))
            }
        } }
        compose.onNodeWithContentDescription("播放倍速").assertIsDisplayed()
        compose.onNodeWithContentDescription("横屏全屏").assertIsDisplayed()
        compose.onNodeWithTag("video-cache-progress").assertIsDisplayed()
        compose.onRoot().captureRoboImage("screenshots/video-player-speed-cache-narrow-fs13.png")
    }
    @Test fun speed_menu_large_text() {
        compose.setContent { HermesTheme(darkTheme = false) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.EN, LocalDensity provides Density(density.density, 1.3f)) {
                VideoPlayerContent("movie.mp4", playing.copy(wantsPlay = false, speed = 1.5f))
            }
        } }
        compose.onNodeWithContentDescription("Playback speed").performClick()
        compose.onAllNodesWithText("1.5×").onLast().assertIsDisplayed()
        compose.onNodeWithText("0.5×").assertIsDisplayed()
    }
    private fun capture(dark: Boolean, language: AppLanguage, scale: Float, state: VideoPlayerState, name: String) {
        compose.setContent { HermesTheme(darkTheme = dark) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalAppLanguage provides language, LocalDensity provides Density(density.density, scale)) {
                VideoPlayerContent("项目演示与交付结果-很长的文件名.mp4", state)
            }
        } }
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }
    @Test fun paused_light() = capture(false, AppLanguage.ZH, 1f, playing.copy(wantsPlay = false), "video-player-paused-light-zh")
    @Test fun paused_dark_large() = capture(true, AppLanguage.ZH, 1.3f, playing.copy(wantsPlay = false), "video-player-paused-dark-zh-fs13")
    @Test fun buffering_english_large() = capture(false, AppLanguage.EN, 1.3f, playing.copy(buffering = true), "video-player-buffering-en-fs13")
    @Test @Config(qualifiers = "w800dp-h360dp-420dpi")
    fun landscape_ended() = capture(true, AppLanguage.EN, 1.3f, playing.copy(wantsPlay = false, ended = true, position = 60_000), "video-player-landscape-ended")
}
