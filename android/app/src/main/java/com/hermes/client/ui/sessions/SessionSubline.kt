package com.hermes.client.ui.sessions

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.client.domain.Session
import com.hermes.client.ui.components.BranchStrokeIcon
import com.hermes.client.ui.components.FolderStrokeIcon
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized

/**
 * The shared session subline: `[pin] [glyph] <project|branch> · <model>` (docs/DESIGN.md
 * §5.2). The lead segment takes at most 60% of the width before ellipsizing so the model stays
 * visible; the default project renders no lead segment at all (absence = default). Inherits the
 * surrounding text style and colour, so it sits in any list.
 *
 * [pinned] prefixes a two-tone filled pin (ui/components/PinIcon.kt) — the row-level pinned
 * marker. It is the subline's first element, not a leading slot on the row, so every title in the
 * list shares one left edge and the marker does not wander as the row gains a status line
 * (decision 2026-09-02).
 *
 * Laid out with a plain [Layout], not BoxWithConstraints: the surrounding list may measure its
 * slots intrinsically, and SubcomposeLayout-based components throw when asked for intrinsics.
 *
 * The type step is applied HERE rather than at each call site (decision 2026-09-10): four screens
 * render this line — the session list, search results, the project drill-down and the archive —
 * and a subline that is one size in one list and another size in the next is worse than either
 * choice. That is also why the 5th pull's 12 → 11.5 reaches all four, while the rest of that
 * pull's density change stops at the session list (docs/DESIGN.md §5.2).
 */
@Composable
fun SessionSubline(
    session: Session,
    lead: SublineLead = SublineLead.PROJECT,
    defaultProjectPath: String? = null,
    pinned: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // The project's real name when the catalog knows it (upstream projects have names of their
    // own); the folder basename otherwise, which is what this row always used to show.
    val projectName = LocalProjectNames.current(session)
    val parts = sessionSublineParts(session, lead, defaultProjectPath, projectName)
    if (parts.isEmpty && !pinned) return
    // One step lighter than ListItem's onSurfaceVariant, matching the design's "muted" tier
    // (decision 2026-09-10). The glyph goes a further step down to the design's faint tier
    // (SublineFaint) — 2.39:1 on paper, adopted 2026-09-11 when the contrast floors were dropped
    // in favour of following the mock exactly (docs/DESIGN.md §7 item 8).
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.outline,
    ) {
        androidx.compose.material3.ProvideTextStyle(com.hermes.client.ui.tuning.tunedSubline()) { // TUNING-TEMP
            SublineContent(parts, lead, pinned, modifier)
        }
    }
}

@Composable
private fun SublineContent(
    parts: SessionSublineParts,
    lead: SublineLead,
    pinned: Boolean,
    modifier: Modifier,
) {
    if (!pinned) {
        SublineBody(parts, lead, modifier)
        return
    }
    val language = LocalAppLanguage.current
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        // `Image`, not `Icon`: the pin is two-tone and a tint would flatten it (see PinIcon.kt).
        // `Image` has no contentDescription of its own, so the announcement moves to a semantics
        // modifier — SessionSublineTest asserts on it, and TalkBack has nothing else to say that
        // this row is pinned when the group header is collapsed or off screen.
        androidx.compose.foundation.Image(
            imageVector = com.hermes.client.ui.components.pinnedIcon(),
            contentDescription = null,
            modifier = Modifier
                .size(com.hermes.client.ui.tuning.tunedSublineGlyph()) // TUNING-TEMP
                .semantics {
                    contentDescription = localized(language, "已置顶", "Pinned")
                },
        )
        if (!parts.isEmpty) {
            Spacer(Modifier.width(4.dp))
            SublineBody(parts, lead, Modifier.weight(1f, fill = false))
        }
    }
}

@Composable
private fun SublineBody(parts: SessionSublineParts, lead: SublineLead, modifier: Modifier) {
    val leadText = parts.lead
    val model = parts.model
    if (leadText == null) {
        Text(model.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
        return
    }
    val gap = 4.dp
    Layout(
        modifier = modifier,
        content = {
            Icon(
                if (lead == SublineLead.BRANCH) BranchStrokeIcon else FolderStrokeIcon,
                contentDescription = null,
                tint = com.hermes.client.ui.theme.sublineFaintColor(),
                modifier = Modifier.size(com.hermes.client.ui.tuning.tunedSublineGlyph()), // TUNING-TEMP
            )
            Text(leadText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (model != null) {
                Text(" · ") // l10n-allow: separator
                Text(model, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
    ) { measurables, constraints ->
        val maxW = constraints.maxWidth
        val gapPx = gap.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val icon = measurables[0].measure(loose)
        val sep = measurables.getOrNull(2)?.measure(loose)
        // Lead segment: at most 60% of the row (glyph + gap included) so the model stays visible.
        val leadCap = ((maxW * 0.6f).toInt() - icon.width - gapPx).coerceAtLeast(0)
        val leadPlaceable = measurables[1].measure(loose.copy(maxWidth = leadCap))
        val used = icon.width + gapPx + leadPlaceable.width + (sep?.width ?: 0)
        val modelPlaceable = measurables.getOrNull(3)?.measure(loose.copy(maxWidth = (maxW - used).coerceAtLeast(0)))
        val height = listOfNotNull(icon.height, leadPlaceable.height, sep?.height, modelPlaceable?.height).max()
        val width = (used + (modelPlaceable?.width ?: 0)).coerceIn(constraints.minWidth, maxW)
        layout(width, height) {
            var x = 0
            icon.placeRelative(x, (height - icon.height) / 2); x += icon.width + gapPx
            leadPlaceable.placeRelative(x, (height - leadPlaceable.height) / 2); x += leadPlaceable.width
            sep?.let { it.placeRelative(x, (height - it.height) / 2); x += it.width }
            modelPlaceable?.placeRelative(x, (height - modelPlaceable.height) / 2)
        }
    }
}
