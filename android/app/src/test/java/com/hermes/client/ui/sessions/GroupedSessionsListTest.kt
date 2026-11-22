package com.hermes.client.ui.sessions

import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.hermes.client.data.progress.SessionRunPhase
import com.hermes.client.data.progress.SessionRuntime
import com.hermes.client.data.progress.SessionRuntimeKey
import com.hermes.client.domain.Session
import com.hermes.client.ui.InChinese
import com.hermes.client.ui.theme.HermesTheme
import java.io.File
import java.time.Instant
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * HG-252: exercise the SAME grouped list as SessionsScreen, with its real transparent SessionRows.
 * Semantics alone misses disappearing items: LazyColumn can still draw an old row after its node
 * has gone. Compare actual pixels at intermediate frames to the settled frame instead. Fixtures
 * deliberately use verdict/waiting states, not an infinite spinner, so any pixel change is a list
 * transition rather than time-dependent indicator motion. No golden recording flag is required.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-notnight-420dpi")
@OptIn(ExperimentalMaterial3Api::class)
class GroupedSessionsListTest {
    @get:Rule val compose = createComposeRule()
    private val now = Instant.parse("2026-11-14T04:00:00Z").toEpochMilli()
    private fun session(id: String, title: String = id) = Session(
        id, title, "gpt-5.6-terra", null, 3, "personal", lastActive = now,
        cwd = "/work/知识库", deviceId = "mac-a",
    )
    private val a = session("a", "magpie我想给另一个人一个独立的配置")
    private val b = session("b", "Personal AI Knowledge OS 方案")
    private val c = session("c", "Token费用查询")
    private val rows = mutableStateOf(listOf(c, a, b))
    private val pins = mutableStateOf(setOf("a", "b", "c"))
    private val phases = mutableStateOf(emptyMap<String, SessionRunPhase>())
    private var opened: Session? = null

    private fun show(dark: Boolean = false, fontScale: Float = 1f) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            InChinese {
                HermesTheme(darkTheme = dark) {
                    val density = LocalDensity.current
                    // Click ripples are intentional, independent of list placement/fade. Exclude
                    // them so pixel equality still detects row motion rather than pointer feedback.
                    CompositionLocalProvider(
                        LocalDensity provides Density(density.density, fontScale),
                        LocalRippleConfiguration provides null,
                    ) {
                        Surface {
                            GroupedSessionsList(
                                sessions = rows.value,
                                isPinned = { it.id in pins.value },
                                runtimeFor = { s -> phases.value[s.id]?.let {
                                    SessionRuntime(SessionRuntimeKey(s.profile, s.id, s.deviceId), phase = it)
                                } },
                                unreadTokens = emptySet(), draftTokens = emptySet(), unsentTokens = emptySet(),
                                defaultProjectPath = null, nowMs = now,
                                onOpen = { opened = it }, onMoveToProject = {}, onTogglePin = {},
                                onRename = { _, _ -> }, onArchive = {}, onDelete = {},
                            )
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun capture(name: String): IntArray {
        val target = File("build/outputs/hg252/$name.png").apply { parentFile?.mkdirs() }
        compose.onRoot().captureRoboImage(target.absolutePath,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        val image = checkNotNull(ImageIO.read(target))
        return image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
    }

    private fun assertImmediatelySettled(name: String, change: () -> Unit) {
        compose.runOnIdle {
            change()
            Snapshot.sendApplyNotifications()
        }
        // With a manually driven clock, snapshot notification and recomposition occupy separate
        // frames. The first tick may still show the intact previous list. Inspect the first
        // UPDATED frame, not that scheduling delay; this is still inside the old fade/spring.
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val first = capture("$name-first")
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        val middle = capture("$name-middle")
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val settled = capture("$name-settled")
        assertTrue("$name: first frame still draws moving/fading content", first.contentEquals(settled))
        assertTrue("$name: intermediate frame still draws moving/fading content", middle.contentEquals(settled))
        rows.value.forEach { row ->
            // A folded section deliberately has no rows; callers check it separately.
            val nodes = compose.onAllNodesWithText(row.title).fetchSemanticsNodes()
            assertTrue("${row.title} has duplicate semantic rows", nodes.size <= 1)
        }
    }

    @Test fun pinnedToNeedsYouNeverLeavesTheOldRowOverItsNeighbour() {
        show()
        assertImmediatelySettled("pinned-to-needs-light") {
            phases.value = mapOf("a" to SessionRunPhase.WAITING_ATTENTION)
        }
        compose.onNodeWithText(a.title).performScrollTo().performClick()
        assertEquals(a, opened)
        assertImmediatelySettled("needs-to-pinned-light") { phases.value = emptyMap() }
    }

    @Test fun appDarkSystemLightLargeFontAlsoUpdatesWithoutGhosts() {
        show(dark = true, fontScale = 1.3f)
        assertImmediatelySettled("pinned-to-needs-dark-fs13") {
            phases.value = mapOf("a" to SessionRunPhase.WAITING_APPROVAL)
        }
        assertImmediatelySettled("needs-to-today-dark-fs13") {
            phases.value = emptyMap()
            pins.value = setOf("b", "c")
        }
    }

    @Test fun reorderingAndStatusHeightChangesNeverCrossAnotherTitle() {
        show()
        assertImmediatelySettled("reorder-light") { rows.value = listOf(b, a, c) }
        assertImmediatelySettled("status-added-light") { phases.value = mapOf("b" to SessionRunPhase.FAILED) }
        assertImmediatelySettled("status-removed-light") { phases.value = emptyMap() }
    }

    @Test fun collapseAndRemovalOfASectionLeaveNoFadingRows() {
        show()
        compose.onNodeWithText("已置顶").performClick()
        assertImmediatelySettled("collapse-light") { }
        compose.onNodeWithText(a.title).assertDoesNotExist()
        compose.onNodeWithText("已置顶").performClick()
        assertImmediatelySettled("expand-light") { }
        compose.onNodeWithText(a.title).assertExists()
        assertImmediatelySettled("remove-section-light") { pins.value = emptySet() }
        compose.onNodeWithText("已置顶").assertDoesNotExist()
        compose.onNodeWithText("今天").assertExists()
    }

    @Test fun allTimeBucketsCanPromoteAndReturnWithoutGhosts() {
        pins.value = emptySet()
        rows.value = listOf(a)
        show()
        listOf(0 to "今天", 1 to "昨天", 3 to "前 7 天", 10 to "更早").forEach { (days, label) ->
            assertImmediatelySettled("bucket-$days") {
                rows.value = listOf(a.copy(lastActive = now - days * 86_400_000L))
            }
            compose.onNodeWithText(label).assertExists()
            assertImmediatelySettled("bucket-$days-to-needs") {
                phases.value = mapOf("a" to SessionRunPhase.WAITING_CLARIFICATION)
            }
            compose.onNodeWithText("需要你处理").assertExists()
            assertImmediatelySettled("needs-to-bucket-$days") { phases.value = emptyMap() }
            compose.onNodeWithText(label).assertExists()
        }
    }
}
