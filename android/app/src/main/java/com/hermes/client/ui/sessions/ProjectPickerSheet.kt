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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.hermes.client.data.error.AppError
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
    onPick: suspend (Project) -> AppError?,
    onDismiss: () -> Unit,
    localOnly: Boolean = false,
) {
    val feedback = rememberSessionActionFeedback()
    val context = LocalContext.current
    val moved = l10n("已移动", "Moved")
    var destination by remember { mutableStateOf<String?>(null) }
    val dismiss = { if (!feedback.pending) onDismiss() }
    ModalBottomSheet(
        onDismissRequest = dismiss,
        sheetGesturesEnabled = !feedback.pending,
        sheetState = com.hermes.client.ui.components.hermesSheetState {
            it != androidx.compose.material3.SheetValue.Hidden || !feedback.pending
        },
    ) {
        Text(
            l10n("移动到项目", "Move to project"),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        SessionActionError(feedback) { onDismiss() }
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
                trailingContent = if (feedback.pending && destination == p.id && !localOnly) ({
                    SessionActionProgress(l10n("移动中…", "Moving…"))
                }) else if (isCurrent) ({
                    Icon(Icons.Rounded.Check, contentDescription = l10n("当前项目", "Current project"), tint = MaterialTheme.colorScheme.primary)
                }) else null,
                modifier = Modifier.clickable(enabled = !feedback.pending) {
                    if (feedback.pending) return@clickable
                    if (isCurrent) {
                        dismiss()
                    } else {
                        destination = p.id
                        feedback.submit({ onPick(p) }) {
                            if (!localOnly) android.widget.Toast.makeText(
                                context,
                                moved,
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                            onDismiss()
                        }
                    }
                },
            )
        }
        androidx.compose.foundation.layout.Spacer(Modifier.size(20.dp))
    }
}
