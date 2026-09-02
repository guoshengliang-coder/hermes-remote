package com.hermes.client.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.client.domain.Project
import com.hermes.client.ui.components.FolderStrokeIcon
import com.hermes.client.ui.components.HomeFolderStrokeIcon
import com.hermes.client.ui.localization.l10n

/** Localized display name of a derived project (the default project has a fixed name). */
@Composable
fun projectDisplayLabel(project: Project): String =
    if (project.id == DEFAULT_PROJECT_ID) l10n("默认项目", "Default project") else project.label

/** Localized display name for a nullable project label (null = the default project). */
@Composable
fun projectLabelText(label: String?): String = label ?: l10n("默认项目", "Default project")

/**
 * The ONE project picker (docs/DESIGN.md §5.3): used by the long-press「移动到项目…」and the chat
 * subtitle. Title left-aligned like every sheet; the default project stays first and is marked
 * 「当前」/checked when it is the session's project. The default project is offered as a target
 * only once its path is known (learned from a top-level create), otherwise it is shown only
 * when current. Picking the current project just dismisses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectPickerSheet(
    projects: List<Project>,
    currentProjectId: String?,
    onPick: (Project) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = com.hermes.client.ui.components.hermesSheetState()) {
        Text(
            l10n("移动到项目", "Move to project"),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        val rows = projects.filter { p ->
            p.id != DEFAULT_PROJECT_ID || p.path != null || p.id == currentProjectId
        }
        rows.forEach { p ->
            val isCurrent = p.id == currentProjectId
            val isDefault = p.id == DEFAULT_PROJECT_ID
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            ListItem(
                headlineContent = { Text(projectDisplayLabel(p), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    val detail = when {
                        isDefault -> l10n("网关启动目录", "Gateway launch directory")
                        else -> p.path.orEmpty()
                    }
                    // "Current" leads so a long path can never push it out of view.
                    val text = if (isCurrent) l10n("当前 · $detail", "Current · $detail") else detail
                    Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                leadingContent = {
                    Icon(
                        if (isDefault) HomeFolderStrokeIcon else FolderStrokeIcon,
                        contentDescription = null,
                        tint = if (isDefault) muted else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                },
                trailingContent = if (isCurrent) ({
                    Icon(Icons.Rounded.Check, contentDescription = l10n("当前项目", "Current project"), tint = MaterialTheme.colorScheme.primary)
                }) else null,
                modifier = Modifier.clickable { if (isCurrent) onDismiss() else onPick(p) },
            )
        }
        androidx.compose.foundation.layout.Spacer(Modifier.size(20.dp))
    }
}
