package com.hermes.client.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.hermes.client.ui.theme.incidentContainerColor
import com.hermes.client.ui.theme.onIncidentColor

/**
 * The one alert strip the grouped lists share (docs/DESIGN.md §5.2, decision 2026-09-10).
 *
 * An inset rounded card, not a full-bleed strip: on warm paper a bleeding band reads as a second
 * app bar, while an inset card reads as one incident sitting on the page.
 *
 * It was inlined in `SessionsScreen.kt` while the cron list drew its own full-bleed
 * `errorContainer` row — the two had already drifted apart in shape, colour and corner radius by
 * the time anyone compared them. One definition so they cannot drift again.
 *
 * [icon] is the caller's: the home strip switches between a channel and a schedule glyph depending
 * on the root cause, and only the caller knows which. Everything else — colours, radius, insets,
 * the trailing arrow — is fixed here.
 */
@Composable
internal fun IncidentStrip(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = incidentContainerColor(),
        contentColor = onIncidentColor(),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = onIncidentColor(),
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = onIncidentColor(),
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = onIncidentColor(),
            )
        }
    }
}
