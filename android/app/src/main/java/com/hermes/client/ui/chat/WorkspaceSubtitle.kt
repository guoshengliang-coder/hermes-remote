package com.hermes.client.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.client.ui.components.BranchStrokeIcon
import com.hermes.client.ui.components.FolderStrokeIcon
import com.hermes.client.ui.components.HomeFolderStrokeIcon
import com.hermes.client.ui.components.ThinChevronIcon
import com.hermes.client.ui.localization.l10n

/**
 * Chat top-bar subtitle (docs/DESIGN.md §5.4): `[folder] project · [branch] branch ▾`. It is the
 * workspace the chat's tools run in AND the entry to change it — the small down-chevron says so;
 * while a turn is running the gateway refuses a move, so the row is muted and loses the chevron.
 * The default project shows the house-folder + 「默认项目」.
 */
@Composable
fun WorkspaceSubtitle(
    projectLabel: String?,
    branch: String?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val name = projectLabel ?: l10n("默认项目", "Default project")
    Row(
        modifier
            .alpha(if (enabled) 1f else 0.6f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (projectLabel == null) HomeFolderStrokeIcon else FolderStrokeIcon,
            contentDescription = l10n("所属项目", "Project"),
            tint = muted,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            name,
            style = MaterialTheme.typography.labelMedium,
            color = muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (branch != null) {
            Text(" · ", style = MaterialTheme.typography.labelMedium, color = muted) // l10n-allow: separator
            Icon(BranchStrokeIcon, contentDescription = l10n("分支", "Branch"), tint = muted, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            // Unweighted (capped) so Row measures it first and the project name gets the rest;
            // two weighted texts would split the width in half and truncate the project early.
            Text(
                branch,
                style = MaterialTheme.typography.labelMedium,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 150.dp),
            )
        }
        if (enabled) {
            Spacer(Modifier.width(2.dp))
            Icon(
                ThinChevronIcon,
                contentDescription = l10n("更改项目", "Change project"),
                tint = muted,
                modifier = Modifier.size(12.dp).rotate(90f),
            )
        }
    }
}
