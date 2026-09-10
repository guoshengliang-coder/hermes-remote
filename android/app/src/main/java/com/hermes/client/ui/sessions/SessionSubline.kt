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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.client.domain.Session
import com.hermes.client.ui.components.BranchStrokeIcon
import com.hermes.client.ui.components.FolderStrokeIcon
import com.hermes.client.ui.components.PinStrokeIcon
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized

/**
 * The shared session subline: `[pin] [14dp glyph] <project|branch> · <model>` (docs/DESIGN.md
 * §5.2). The lead segment takes at most 60% of the width before ellipsizing so the model stays
 * visible; the default project renders no lead segment at all (absence = default). Inherits the
 * surrounding text style and colour (ListItem's supporting slot), so it sits in any list.
 *
 * [pinned] prefixes a 14dp brand-blue pin — the row-level pinned marker. It lives here rather
 * than in ListItem's leading slot so every title in the list shares one left edge and the marker
 * does not wander between top- and centre-aligned as the row gains a status line
 * (decision 2026-09-02).
 *
 * Laid out with a plain [Layout], not BoxWithConstraints: ListItem measures its slots
 * intrinsically, and SubcomposeLayout-based components throw when asked for intrinsics.
 *
 * The 12sp step is applied HERE rather than at each ListItem (decision 2026-09-10): four screens
 * render this line — the session list, search results, the project drill-down and the archive —
 * and a subline that is 12sp in one list and 14sp in another is worse than either size. Material's
 * ListItem would otherwise hand it bodyMedium at 14sp, only 1sp under the title, which is what
 * made the secondary line compete with the primary one (docs/DESIGN.md §5.2).
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
    // (decision 2026-09-10). The design puts the folder glyph and the "device only" note a further
    // step down again at #A8A29E — NOT adopted: that measures 2.39:1 on paper, under the 3:1 floor
    // a graphic owes. Icon and text share this one tier instead.
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.outline,
    ) {
        androidx.compose.material3.ProvideTextStyle(com.hermes.client.ui.theme.SessionRowSubline) {
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
        Icon(
            PinStrokeIcon,
            contentDescription = localized(language, "已置顶", "Pinned"),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp),
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
                tint = LocalContentColor.current,
                modifier = Modifier.size(14.dp),
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
