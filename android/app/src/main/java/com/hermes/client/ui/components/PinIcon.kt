package com.hermes.client.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The pinned-session marker: a small two-colour push pin, filled rather than stroked.
 *
 * **Why this is not in StrokeIcons.kt.** That file is the 1.7dp outline system (docs/DESIGN.md
 * §4.1) and everything in it is one colour, applied by `Icon(tint = …)`. This glyph is a filled
 * two-tone object, which is a different category and needs `Image`, because a tint would collapse
 * both colours back into one. §4 is amended to allow exactly this one exception.
 *
 * **Why it exists at all.** The Stitch mock draws the pin as the emoji 📌
 * (`<span class="text-[11px] … text-[#2563EB]">📌</span>` — the colour class does nothing to a
 * colour-emoji glyph, so it renders as the system's red pushpin). Shipping the emoji was rejected
 * on 2026-09-12: the shape comes from whatever emoji font the ROM has, so HONOR, Xiaomi and
 * Samsung each draw a different pin; it cannot be tinted or adapted for dark mode; and TalkBack
 * announces the emoji's own name. This is the mock's *intent* — a small coloured object rather
 * than a line drawing — under our control.
 *
 * **Why it is not the emoji's red.** Red is already "run failed" in this app's status vocabulary
 * (`StatusColors.kt`), and a failed row can carry a red dot on its right while this sits on its
 * left. The head therefore takes the blue this app already means "pinned" by — the same value the
 * 已置顶 group's pillar uses (`Tiles.kt` PillarPinned*) — and the needle a neutral, so the glyph
 * reads as an object without inventing a third meaning for red.
 */
private fun pinIcon(head: Color, needle: Color): ImageVector =
    ImageVector.Builder(
        name = "PinFilled",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        // The needle first, so the head overlaps it rather than the other way round.
        path(fill = SolidColor(needle)) {
            moveTo(11.25f, 12f)
            lineTo(12.75f, 12f)
            lineTo(12f, 21f)
            close()
        }
        // Cap bar plus the flared body, as one shape.
        path(fill = SolidColor(head)) {
            moveTo(8.5f, 2.5f)
            lineTo(15.5f, 2.5f)
            lineTo(15.5f, 5f)
            lineTo(14f, 5f)
            lineTo(16.5f, 12.5f)
            lineTo(7.5f, 12.5f)
            lineTo(10f, 5f)
            lineTo(8.5f, 5f)
            close()
        }
    }.build()

/**
 * The pin for the current theme. Not a `val`: the two colours differ per scheme and `Image` does
 * not read `LocalContentColor`, so the vector itself has to be rebuilt rather than re-tinted.
 */
@Composable
fun pinnedIcon(): ImageVector = pinIcon(
    head = com.hermes.client.ui.theme.pillarPinnedColor(),
    needle = com.hermes.client.ui.theme.sublineFaintColor(),
)
