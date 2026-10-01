package com.hermes.client.ui.chat

import kotlin.math.abs

enum class VoiceReleaseAction { SEND, CANCEL, EDIT }

/**
 * HG-153: one swipe target exactly as [VoiceRecordingOverlay] draws it, in root coordinates.
 *
 * The judgement used to be a fixed 96dp climb followed by left/right thirds of the hold button,
 * while the overlay drew its two target circles from its own bottom-anchored column. The two
 * geometries did not agree: the drawn circles sit well below the 96dp climb, so no point inside a
 * drawn target ever selected — the finger had to overshoot the circle before anything lit up.
 * Taking the geometry from the drawn circles keeps the hot area and the visible target the same
 * thing, through any change of screen size, font scale or navigation-bar inset.
 */
internal data class VoiceTarget(
    val action: VoiceReleaseAction,
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
)

/** The two swipe targets of one recording, or null until the overlay has been laid out once. */
internal data class VoiceTargets(val cancel: VoiceTarget, val edit: VoiceTarget)

/**
 * Which action the finger is over. [fingerX] / [fingerY] are root coordinates — the same space as
 * [VoiceTargets] — and [hitMargin] widens each target so its edge is forgiving.
 *
 * A target's zone is its circle widened by [hitMargin] on both sides, with no ceiling: sliding
 * further up a target's column keeps that target selected, exactly as the old upward climb did.
 * Below the circle's lower edge (plus the margin) the zone ends, so a sideways drift at the
 * button's own height still sends. With no geometry yet recorded (the first frame of a press,
 * before the overlay has been laid out) the answer stays SEND.
 */
internal fun voiceReleaseAction(
    fingerX: Float,
    fingerY: Float,
    targets: VoiceTargets?,
    hitMargin: Float,
    currentAction: VoiceReleaseAction = VoiceReleaseAction.SEND,
    exitMargin: Float = 0f,
): VoiceReleaseAction = when {
    targets == null -> VoiceReleaseAction.SEND
    targets.cancel.reaches(fingerX, fingerY, hitMargin) -> VoiceReleaseAction.CANCEL
    targets.edit.reaches(fingerX, fingerY, hitMargin) -> VoiceReleaseAction.EDIT
    currentAction == VoiceReleaseAction.CANCEL && targets.cancel.reaches(fingerX, fingerY, hitMargin + exitMargin) -> currentAction
    currentAction == VoiceReleaseAction.EDIT && targets.edit.reaches(fingerX, fingerY, hitMargin + exitMargin) -> currentAction
    else -> VoiceReleaseAction.SEND
}

private fun VoiceTarget.reaches(x: Float, y: Float, margin: Float): Boolean {
    val reach = radius + margin
    return abs(x - centerX) <= reach && y <= centerY + reach
}
