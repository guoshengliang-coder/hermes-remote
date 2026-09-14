package com.hermes.client.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.client.ui.theme.SegmentLabel
import com.hermes.client.ui.theme.isDarkSurface
import com.hermes.client.ui.theme.tileShadow

/**
 * The app's one segmented switch (docs/DESIGN.md §5.2): a submerged track with the selected option
 * raised out of it.
 *
 * Extracted because six screens had grown their own copy of Material's
 * `SingleChoiceSegmentedButtonRow` — Chats, Usage, Appearance (×2), the profile editor and the
 * messaging filter. That last one even carried a comment saying it was matched to the Chats
 * segments by hand, which is exactly how the app ended up with the switch looking one way on the
 * home page and another way two taps in: the moment Chats changed, its hand-made twin drifted.
 *
 * Why a capsule instead of Material's row: M3 fills the selected segment with `primary`, and on a
 * page where every other accent is reporting a runtime state (unread dot, amber pillar, status
 * text), a slab of brand blue reads as one more status rather than as "you are here". Raising the
 * selected option says the same thing with hierarchy instead of hue.
 *
 * Sized for TWO OR THREE options. Five cells (the cron editor's schedule kinds) do not fit this
 * shape at fontScale 1.3 — that screen deliberately stays on Material's outlined row, which
 * degrades better when the cells get narrow.
 */
@Composable
fun <T> SegmentedCapsule(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    icon: (@Composable (T) -> ImageVector)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEach { option ->
                val on = option == selected
                Surface(
                    // Raised RELATIVE TO THE TRACK, not to the page. The faint-card fill
                    // (`tileColor()`) lifts one step off `surface`, which lands within 1.01:1 of
                    // this track in dark and makes the selected option disappear; the raised end
                    // of the ramp is white in light and the highest container in dark.
                    color = if (on) {
                        if (isDarkSurface()) MaterialTheme.colorScheme.surfaceContainerHighest
                        else MaterialTheme.colorScheme.surfaceContainerLowest
                    } else Color.Transparent,
                    contentColor = if (on) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    shadowElevation = if (on) tileShadow() else 0.dp,
                    modifier = Modifier.weight(1f).clickable { onSelect(option) },
                ) {
                    Row(
                        // Height comes from the CONTENT plus this minimum — never fillMaxSize,
                        // which takes whatever the parent offers and lets the row stretch to the
                        // viewport. heightIn, not height, so fontScale 1.3 has room to grow.
                        // py-1.5 px-3 and a 17px glyph, per the session-list mock.
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 32.dp)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        icon?.let {
                            androidx.compose.material3.Icon(
                                it(option),
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                            )
                            androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
                        }
                        Text(
                            label(option),
                            // 13.5sp / 500 / tracking-tight — the mock separates the selected
                            // segment by elevation, not by a heavier label, so titleSmall's 600
                            // was one emphasis too many.
                            style = SegmentLabel,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
