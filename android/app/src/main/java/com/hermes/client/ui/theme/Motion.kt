package com.hermes.client.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically

// Shared motion tokens so animations feel like one system instead of ad-hoc per screen.
// Durations follow Material's guidance (short for small elements, medium for containers).

object Motion {
    const val DurationShort = 150
    const val DurationMedium = 250
    const val DurationLong = 400

    /**
     * One loop period for every brand loading animation (mark, skeleton sweep, top line, sending
     * bubble). Already the period of the startup progress sweep — new loading motion aligns with
     * it instead of adding a fourth cadence (docs/DESIGN.md §5.6).
     */
    const val LoopPeriod = 1200

    /** Nothing that says "loading" may appear before this; faster waits show no furniture at all. */
    const val RevealDelay = 250

    /** Fade used when a loading surface finally does appear. */
    const val RevealFade = 200

    /** Material "emphasized" easing — energetic in, gentle settle. Good for expand/collapse. */
    val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Standard easing for most enter/exit. */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Enter/exit for collapsible group content (session groups, expandable cards). */
    val expandCollapseEnter: EnterTransition =
        expandVertically(animationSpec = tween(DurationMedium, easing = Emphasized)) +
            fadeIn(animationSpec = tween(DurationMedium, easing = Standard))

    val expandCollapseExit: ExitTransition =
        shrinkVertically(animationSpec = tween(DurationMedium, easing = Emphasized)) +
            fadeOut(animationSpec = tween(DurationShort, easing = Standard))
}
