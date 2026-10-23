package com.hermes.client.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression for the turn-jump thrash (2026-09-03): aligning a prompt that has too little content
 * below it to reach the top used to bounce between scrollToItem and a clamped scrollBy for the
 * whole 180-frame budget, flickering and holding the scroll mutex. The loop must give up within a
 * few frames once the list is clamped, and still align a reachable target exactly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class TurnJumpAlignTest {
    @get:Rule
    val compose = createComposeRule()

    private class Run(var frames: Int = 0, var done: Boolean = false, var first: Int = -1, var offset: Int = -1, var overshoot: Int? = null)

    /**
     * A reverse-layout transcript of [count] rows, [rowDp] tall each, in a fixed [viewportDp]
     * viewport. With [streamingTail] the newest row grows 30dp per frame like a live answer.
     */
    private fun align(count: Int, target: Int, rowDp: Int = 300, viewportDp: Int = 1000, streamingTail: Boolean = false, asyncRows: Boolean = false): Run {
        val run = Run()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val state: LazyListState = rememberLazyListState()
            var tailDp by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(60) }
            LazyColumn(state = state, reverseLayout = true, modifier = Modifier.fillMaxWidth().height(viewportDp.dp)) {
                items(count) { i ->
                    // asyncRows: like a freshly composed answer whose Markdown parses over the next
                    // frames — 40dp when first composed, growing to rowDp; forgotten on dispose.
                    var grown by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(if (asyncRows) 40 else rowDp) }
                    if (asyncRows) {
                        LaunchedEffect(Unit) { repeat(6) { withFrameNanos { }; grown = (grown + 60).coerceAtMost(rowDp) } }
                    }
                    val h = if (streamingTail && i == 0) tailDp else grown
                    Box(Modifier.fillMaxWidth().height(h.dp)) { Text("row $i") }
                }
            }
            LaunchedEffect(Unit) {
                // Count the frames the alignment consumes by racing it against a frame ticker,
                // which also drives the growing tail.
                val ticker = launch {
                    while (true) {
                        withFrameNanos { }
                        run.frames++
                        if (streamingTail) tailDp = (tailDp + 30).coerceAtMost(900)
                    }
                }
                state.alignItemTopToViewport(target, 0)
                ticker.cancel()
                run.done = true
                run.first = state.firstVisibleItemIndex
                run.offset = state.firstVisibleItemScrollOffset
                val li = state.layoutInfo
                run.overshoot = li.visibleItemsInfo.firstOrNull { it.index == target }
                    ?.let { (it.offset + it.size) - (li.viewportEndOffset - li.afterContentPadding) }
            }
        }
        repeat(400) { if (!run.done) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() } }
        assertTrue("alignment never finished", run.done)
        return run
    }

    @Test fun a_prompt_near_the_bottom_stops_within_a_few_frames_instead_of_thrashing() {
        // Item 1 (second newest) has one 300dp row below it in a 1000dp viewport, so it can never
        // reach the top. The 0.1.83 loop burned its whole 180-frame budget here.
        val run = align(count = 12, target = 1)
        assertTrue("used ${run.frames} frames", run.frames <= TURN_JUMP_CLAMPED_FRAMES + 16)
        // Settled clamped at the bottom end rather than bouncing between two states.
        assertEquals(0, run.first)
        assertEquals(0, run.offset)
    }

    @Test fun a_prompt_near_the_bottom_with_a_growing_live_answer_below_it_does_not_thrash() {
        // The device case: the newest answer is still streaming below the target, so the layout
        // under the target changes every frame while the list is clamped at its bottom end.
        val run = align(count = 12, target = 1, streamingTail = true)
        assertTrue("used ${run.frames} frames", run.frames <= TURN_JUMP_CLAMPED_FRAMES + 16)
    }

    @Test fun a_far_prompt_whose_neighbours_grow_after_composition_still_lands_at_the_top() {
        // The 0.1.84 regression: rows below the target compose at 40dp and only grow to 300dp
        // over the next frames. The first placement is computed against the small rows, clamps
        // at the bottom, and the growth then pushes the target off the top. The loop must keep
        // re-placing from the target's known size — not give up at the bottom.
        val run = align(count = 12, target = 8, asyncRows = true)
        assertTrue("used ${run.frames} frames", run.frames <= 60)
        assertTrue("ended at the bottom (first=${run.first})", run.first != 0)
        assertTrue("target top sits ${run.overshoot}px from the viewport top", run.overshoot != null && kotlin.math.abs(run.overshoot!!) <= TURN_JUMP_TOLERANCE_PX)
    }

    @Test fun a_reachable_prompt_is_aligned_to_the_top() {
        // Item 8 has eight 300dp rows below it: enough content to put its top at the viewport top.
        val run = align(count = 12, target = 8)
        assertTrue("used ${run.frames} frames", run.frames <= 20)
        assertTrue("target top sits ${run.overshoot}px from the viewport top", run.overshoot != null && kotlin.math.abs(run.overshoot!!) <= TURN_JUMP_TOLERANCE_PX)
    }
}
