package com.hermes.client.ui.sessions

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.client.domain.Session
import com.hermes.client.ui.components.BranchStrokeIcon
import com.hermes.client.ui.components.FolderStrokeIcon

/**
 * The shared session subline: `[14dp glyph] <project|branch> · <model>` (docs/DESIGN.md §5.2).
 * The lead segment takes at most 60% of the width before ellipsizing so the model stays visible;
 * the default project renders no lead segment at all (absence = default). Inherits the
 * surrounding text style and colour (ListItem's supporting slot), so it sits in any list.
 *
 * Laid out with a plain [Layout], not BoxWithConstraints: ListItem measures its slots
 * intrinsically, and SubcomposeLayout-based components throw when asked for intrinsics.
 */
@Composable
fun SessionSubline(
    session: Session,
    lead: SublineLead = SublineLead.PROJECT,
    defaultProjectPath: String? = null,
    modifier: Modifier = Modifier,
) {
    val parts = sessionSublineParts(session, lead, defaultProjectPath)
    if (parts.isEmpty) return
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
