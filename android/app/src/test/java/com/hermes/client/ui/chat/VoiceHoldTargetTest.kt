package com.hermes.client.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * HG-153: the finger on a drawn swipe target must select that target, and arriving on one must
 * buzz once. The composer and the recording layer are laid out together exactly as `ChatScreen`
 * stacks them, so this exercises the real geometry the bug report describes.
 *
 * Measured at 360dp/420dpi: a target circle's centre sits 77dp above the hold button's top edge
 * (radius 32dp, so the circle spans 45dp..109dp). The old judgement demanded a fixed 96dp climb
 * before it looked at left/right at all, so only the top third of every drawn target could ever
 * select: pointing at the circle — or its centre — answered SEND until the finger overshot it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-420dpi")
class VoiceHoldTargetTest {
    @get:Rule val compose = createComposeRule()

    private class Harness {
        val zones = mutableListOf<VoiceReleaseAction>()
        val buzzes = mutableListOf<HapticFeedbackType>()
        val filler = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                buzzes += hapticFeedbackType
            }
        }
        var targets by mutableStateOf<VoiceTargets?>(null)

        @Composable
        fun tree() {
            HermesTheme {
                Box(Modifier.fillMaxSize()) {
                    // ChatScreen draws the recording layer last, over the whole page, and the
                    // gesture still works there because the composer already captured the pointer
                    // before that layer exists — `voiceHeld` composes it on the press. A test has
                    // to inject the press into an already-composed tree, so the composer goes on
                    // top here to stay reachable; the layer's own layout, and so the geometry under
                    // test, is identical either way.
                    VoiceRecordingOverlay(
                        language = AppLanguage.ZH,
                        held = true,
                        waiting = false,
                        transcript = "",
                        cancelZone = false,
                        editZone = false,
                        elapsedMs = 12_400L,
                        onDismissWaiting = {},
                        onTargetsMeasured = { targets = it },
                    )
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                        VoiceComposerBar(
                            language = AppLanguage.ZH,
                            holdLabel = "按住说话",
                            holdEnabled = true,
                            keyboardEnabled = true,
                            sessionWritable = true,
                            isGenerating = false,
                            targets = targets,
                            onKeyboard = {},
                            onDown = {},
                            onZone = { zones += it },
                            onRelease = { zones += it },
                            onAdd = {},
                            onStop = {},
                        )
                    }
                }
            }
        }
    }

    private fun harness(): Harness {
        val harness = Harness()
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides harness.filler) {
                harness.tree()
            }
        }
        compose.waitForIdle()
        return harness
    }

    /** Where inside the hold button the finger has to be to sit on [label]'s drawn circle. */
    private fun aimAt(label: String): Offset {
        val button = compose.onNodeWithTag("voice-hold").fetchSemanticsNode().boundsInRoot
        val target = compose.onNodeWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        return Offset(target.center.x - button.left, target.center.y - button.top)
    }

    private fun buttonHeight(): Float =
        compose.onNodeWithTag("voice-hold").fetchSemanticsNode().boundsInRoot.height

    /** The old judgement wanted 96dp of climb; the button is 52dp tall, so that is ~1.8 of these. */
    private fun oldClimbWasFarAway(aim: Offset) {
        assertTrue("the aim point must be above the hold button, was $aim", aim.y < 0f)
        assertTrue(
            "this only regresses HG-153 if the target sits below the old 96dp climb, was ${-aim.y}",
            -aim.y < buttonHeight() * 1.8f,
        )
    }

    @Test fun theDrawnCancelTargetIsHotAndBuzzesOnce() {
        val harness = harness()
        val aim = aimAt("移到这里取消")
        oldClimbWasFarAway(aim)
        compose.onNodeWithTag("voice-hold").performTouchInput {
            down(center)
            moveTo(aim)
        }
        assertEquals(listOf(VoiceReleaseAction.CANCEL), harness.zones)
        assertEquals(listOf(HapticFeedbackType.Confirm), harness.buzzes)
    }

    @Test fun theDrawnTextTargetIsHot() {
        val harness = harness()
        val aim = aimAt("滑到这里转文字")
        oldClimbWasFarAway(aim)
        compose.onNodeWithTag("voice-hold").performTouchInput {
            down(center)
            moveTo(aim)
        }
        assertEquals(listOf(VoiceReleaseAction.EDIT), harness.zones)
        assertEquals(listOf(HapticFeedbackType.Confirm), harness.buzzes)
    }

    @Test fun droppingBackToSendIsSilentAndReEnteringBuzzesAgain() {
        val harness = harness()
        val cancel = aimAt("移到这里取消")
        val edit = aimAt("滑到这里转文字")
        compose.onNodeWithTag("voice-hold").performTouchInput {
            down(center)
            moveTo(cancel)
            // Back to the middle at the same height: no target, so the release would send.
            moveTo(Offset(center.x, cancel.y))
            moveTo(edit)
        }
        assertEquals(
            listOf(VoiceReleaseAction.CANCEL, VoiceReleaseAction.SEND, VoiceReleaseAction.EDIT),
            harness.zones,
        )
        assertEquals(
            listOf(HapticFeedbackType.Confirm, HapticFeedbackType.Confirm),
            harness.buzzes,
        )
    }

}
