package com.hermes.client.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * HG-153: the release judgement hit-tests the two circles [VoiceRecordingOverlay] actually draws,
 * so the area that selects is the area the user aims at. Everything here is in root coordinates,
 * where up is negative; the numbers stand in for a 360dp-wide xxhdpi frame.
 *
 * The regression this pins: the drawn circles sit well below the old fixed 96dp climb, so pointing
 * at a drawn target used to answer SEND until the finger overshot it.
 */
class VoiceGestureTest {

    // 64dp circles whose centres are 40dp above the hold button's top edge (y = 2000).
    private val cancel = VoiceTarget(VoiceReleaseAction.CANCEL, centerX = 192f, centerY = 1880f, radius = 96f)
    private val edit = VoiceTarget(VoiceReleaseAction.EDIT, centerX = 888f, centerY = 1880f, radius = 96f)
    private val targets = VoiceTargets(cancel, edit)

    // 12dp of forgiveness around a 32dp radius.
    private val margin = 36f

    /** Where the finger is when it has not moved: the middle of the 52dp button. */
    private val pressPoint = 2078f

    @Test fun noGeometryYet_staysSend() {
        assertEquals(VoiceReleaseAction.SEND, voiceReleaseAction(192f, 1880f, null, margin))
    }

    @Test fun theDrawnCirclesAreHot() {
        assertEquals(VoiceReleaseAction.CANCEL, voiceReleaseAction(192f, 1880f, targets, margin))
        assertEquals(VoiceReleaseAction.EDIT, voiceReleaseAction(888f, 1880f, targets, margin))
    }

    @Test fun theMarginWidensEachCircle() {
        assertEquals(VoiceReleaseAction.CANCEL, voiceReleaseAction(192f + 131f, 1880f, targets, margin))
        assertEquals(VoiceReleaseAction.SEND, voiceReleaseAction(192f + 133f, 1880f, targets, margin))
        // The lower edge is the circle's bottom plus the margin, so the button itself stays SEND.
        assertEquals(VoiceReleaseAction.CANCEL, voiceReleaseAction(192f, 1880f + 131f, targets, margin))
        assertEquals(VoiceReleaseAction.SEND, voiceReleaseAction(192f, 1880f + 133f, targets, margin))
    }

    @Test fun aSidewaysDriftAtButtonHeightStaysSend() {
        assertEquals(VoiceReleaseAction.SEND, voiceReleaseAction(192f, pressPoint, targets, margin))
        assertEquals(VoiceReleaseAction.SEND, voiceReleaseAction(888f, pressPoint, targets, margin))
    }

    @Test fun furtherUpATargetColumnKeepsItSelected() {
        assertEquals(VoiceReleaseAction.CANCEL, voiceReleaseAction(192f, -400f, targets, margin))
        assertEquals(VoiceReleaseAction.EDIT, voiceReleaseAction(888f, -400f, targets, margin))
    }

    @Test fun betweenTheTargets_staysSend() {
        assertEquals(VoiceReleaseAction.SEND, voiceReleaseAction(540f, -400f, targets, margin))
    }
}
