package com.hermes.client.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Semantic status colours that deliberately do NOT follow the brand palette.
//
// Before the blue swap, primary was doing two unrelated jobs at once: the brand colour AND the
// "completed" status — which only ever read correctly because mint happens to look like
// "success". Meanwhile StatusDot carried its own hardcoded traffic light whose green (#2E7D32)
// was a SECOND, different green from the brand's #087A5C. Blue chrome frees green up, so both
// jobs collapse onto the one palette below and green means exactly one thing again.
//
// Two tiers because the traffic light used to be theme-blind: the light values are unreadable
// on a dark surface and vice versa. Every value is pinned by StatusColorsTest.
//
// The math is pure (ARGB Int in/out) so it unit-tests without Compose or an Android runtime —
// same reason as ProfileAccent.kt.

/** What a status colour MEANS, independent of theme. */
enum class StatusTone { GOOD, WARN, BAD }

private val GOOD_LIGHT = 0xFF2E7D32.toInt() // 5.13:1 on white — safe as 12sp label text
private val WARN_LIGHT = 0xFFC77700.toInt() // 3.46:1 on white — DOT ONLY, not text
private val BAD_LIGHT = 0xFFC62828.toInt() //  5.62:1 on white

private val GOOD_DARK = 0xFF7CDC80.toInt() // 10.45:1 on the dark surface
private val WARN_DARK = 0xFFFFB945.toInt() // 10.35:1
private val BAD_DARK = 0xFFFFB4AB.toInt() // 10.42:1

/**
 * The status colour for [tone] on the given theme, as ARGB.
 *
 * The dark tier is deliberately level with each other AND with the dark primary (#A9C7FF,
 * 10.36:1): status and brand carry the same weight there and separate by hue alone, so a green
 * "done" never shouts louder than the chrome around it.
 */
fun statusArgb(tone: StatusTone, dark: Boolean): Int = when (tone) {
    StatusTone.GOOD -> if (dark) GOOD_DARK else GOOD_LIGHT
    StatusTone.WARN -> if (dark) WARN_DARK else WARN_LIGHT
    StatusTone.BAD -> if (dark) BAD_DARK else BAD_LIGHT
}

/**
 * Compose-facing wrapper. The default reads the EFFECTIVE theme, never the system setting —
 * DESIGN.md §2.2. It used to default to `isSystemInDarkTheme()`, so an app set to Dark on a
 * phone set to Light painted the LIGHT status tier onto the dark surface: "已完成" green landed
 * at 3.66:1 and "运行失败" red at 3.34:1, both under the 4.5:1 floor §5.7 owes body text.
 * Two call sites relied on this default (the session list's completed dot and StatusDot), so the
 * fix belongs on the default itself rather than on the callers.
 */
@Composable
fun statusColor(tone: StatusTone, dark: Boolean = isDarkSurface()): Color =
    Color(statusArgb(tone, dark))
