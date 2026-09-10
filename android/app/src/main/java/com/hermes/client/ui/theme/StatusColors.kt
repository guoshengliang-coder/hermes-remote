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
// Values now come from the design source's own screens (decision 2026-09-10, DESIGN.md §2.1).
// Two things came out of that alignment:
//
//  * RUNNING exists at all. "Thinking", "Responding", "Using <tool>" used to resolve to
//    onSurfaceVariant — the SAME grey as the subline beside them — so a session that was actively
//    running looked identical to one sitting still. The design paints that state cyan for exactly
//    that reason, and this was never compared against it until a user asked why the colours in the
//    app did not match the mock.
//  * WARN is text-legal now. It was #C77700 at 3.29:1 on paper, carrying a "DOT ONLY" caveat while
//    the session row rendered it as 12sp text anyway. #C2410C is 4.92:1, so the caveat is gone
//    rather than merely written down.
//
// The design uses three separate ambers (bright for dots and pillars, deeper for the header and
// the status line). They collapse to one here: the deepest clears the graphic floor too, and three
// near-identical ambers in a single row is a distinction no reader can act on.
//
// The math is pure (ARGB Int in/out) so it unit-tests without Compose or an Android runtime —
// same reason as ProfileAccent.kt.

/** What a status colour MEANS, independent of theme. */
enum class StatusTone { GOOD, WARN, BAD, RUNNING }

private val GOOD_LIGHT = 0xFF2E7D32.toInt() // 4.87:1 on warm paper — safe as 12sp label text
private val WARN_LIGHT = 0xFFC2410C.toInt() // 4.92:1 — text-legal, unlike the #C77700 it replaced
private val BAD_LIGHT = 0xFFC62828.toInt() //  5.34:1
private val RUNNING_LIGHT = 0xFF0369A1.toInt() // 5.63:1

private val GOOD_DARK = 0xFF7CDC80.toInt() // 10.71:1 on the dark surface
private val WARN_DARK = 0xFFFBBF24.toInt() // 11.24:1
private val BAD_DARK = 0xFFFFB4AB.toInt() // 10.68:1
private val RUNNING_DARK = 0xFF67E8F9.toInt() // 12.94:1

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
    StatusTone.RUNNING -> if (dark) RUNNING_DARK else RUNNING_LIGHT
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
