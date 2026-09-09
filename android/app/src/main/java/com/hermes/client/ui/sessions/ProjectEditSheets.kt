package com.hermes.client.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.client.domain.Project
import com.hermes.client.ui.components.FolderStrokeIcon
import com.hermes.client.ui.components.PROJECT_ICONS
import com.hermes.client.ui.components.hermesSheetState
import com.hermes.client.ui.components.projectIconFor
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized

/**
 * Twelve evenly spaced hues, matching the desktop's `hsl(index*30 68% 58%)` swatches so a project
 * coloured on the phone looks the same over there. Stored verbatim in the gateway's `color` column.
 */
val PROJECT_COLORS: List<String> = (0 until 12).map { "hsl(${it * 30} 68% 58%)" }

/**
 * What a new project gets before the user picks. Not index 0: that hue is red, which this app
 * reserves for destructive actions (§5.5), so a brand-new project would open looking like a
 * warning. Index 7 is the blue nearest the brand accent.
 */
val DEFAULT_PROJECT_COLOR: String = PROJECT_COLORS[7]

/** Parse the stored colour string. Accepts the desktop's `hsl(H S% L%)` and a plain `#RRGGBB`. */
fun parseProjectColor(value: String?): Color? {
    val raw = value?.trim()?.ifBlank { null } ?: return null
    if (raw.startsWith("#")) return runCatching { Color(android.graphics.Color.parseColor(raw)) }.getOrNull()
    val nums = Regex("[-+]?[0-9]*\\.?[0-9]+").findAll(raw).map { it.value.toFloat() }.toList()
    if (!raw.startsWith("hsl", ignoreCase = true) || nums.size < 3) return null
    return runCatching {
        Color(android.graphics.Color.HSVToColor(floatArrayOf(nums[0] % 360f, nums[1] / 100f, nums[2] / 100f)))
    }.getOrNull()
}

/**
 * Create or rename a project: a name field, the glyph row and the colour row. One sheet for both
 * so the two paths cannot drift; [existing] null means create.
 *
 * Folder choice is NOT here — it is a separate full-screen browse (see [FolderPickerScreen]), and
 * a bottom sheet is the wrong container for a screen you navigate inside of.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProjectEditSheet(
    existing: Project?,
    folder: String?,
    onPickFolder: () -> Unit,
    onDismiss: () -> Unit,
    onSubmit: (name: String, icon: String?, color: String?) -> Unit,
) {
    val language = LocalAppLanguage.current
    var name by remember(existing?.id) { mutableStateOf(existing?.label.orEmpty()) }
    var icon by remember(existing?.id) { mutableStateOf(existing?.icon ?: PROJECT_ICONS.first()) }
    var color by remember(existing?.id) { mutableStateOf(existing?.color ?: DEFAULT_PROJECT_COLOR) }
    val creating = existing == null

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = hermesSheetState()) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(
                if (creating) localized(language, "新建项目", "New project")
                else localized(language, "编辑项目", "Edit project"),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(localized(language, "名称", "Name")) },
                modifier = Modifier.fillMaxWidth(),
            )

            if (creating) {
                // A project with no folder can never own a chat (membership is a path match
                // upstream), so creating always picks one.
                ListItem(
                    leadingContent = {
                        Icon(FolderStrokeIcon, contentDescription = null, modifier = Modifier.size(24.dp))
                    },
                    headlineContent = {
                        Text(
                            folder ?: localized(language, "选择文件夹", "Choose a folder"),
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                        )
                    },
                    trailingContent = {
                        Icon(
                            com.hermes.client.ui.components.ThinChevronIcon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    modifier = Modifier.clickable(onClick = onPickFolder),
                )
            }

            Text(
                localized(language, "图标", "Icon"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
            )
            // Flow, not Row: eight glyphs and twelve swatches overflow a 390dp screen at any
            // font scale, and the twelfth colour was being clipped off the right edge.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                PROJECT_ICONS.forEach { candidate ->
                    val selected = candidate == icon
                    Box(
                        Modifier
                            .size(36.dp)
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(10.dp),
                            )
                            .clickable { icon = candidate },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            projectIconFor(candidate),
                            contentDescription = candidate,
                            tint = parseProjectColor(color) ?: MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            Text(
                localized(language, "颜色", "Colour"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                PROJECT_COLORS.forEach { candidate ->
                    val swatch = parseProjectColor(candidate) ?: MaterialTheme.colorScheme.primary
                    Box(
                        Modifier
                            .size(24.dp)
                            .background(swatch, CircleShape)
                            .border(
                                width = if (candidate == color) 2.dp else 0.dp,
                                color = MaterialTheme.colorScheme.onSurface,
                                shape = CircleShape,
                            )
                            .clickable { color = candidate },
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 20.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text(localized(language, "取消", "Cancel")) }
                TextButton(
                    enabled = name.isNotBlank() && (!creating || folder != null),
                    onClick = { onSubmit(name, icon, color) },
                ) { Text(localized(language, "保存", "Save")) }
            }
        }
    }
}

/** Long-press menu on a project row. Only ever shown for a server-backed (non-auto) project. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectActionSheet(
    project: Project,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onManageFolders: () -> Unit,
    onDelete: () -> Unit,
) {
    val language = LocalAppLanguage.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = hermesSheetState()) {
        Text(
            projectDisplayLabel(project),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        ListItem(
            headlineContent = { Text(localized(language, "编辑项目", "Edit project")) },
            leadingContent = { Icon(Icons.Rounded.Edit, contentDescription = null) },
            modifier = Modifier.clickable { onDismiss(); onEdit() },
        )
        ListItem(
            headlineContent = { Text(localized(language, "管理文件夹", "Manage folders")) },
            leadingContent = { Icon(FolderStrokeIcon, contentDescription = null, modifier = Modifier.size(24.dp)) },
            modifier = Modifier.clickable { onDismiss(); onManageFolders() },
        )
        ListItem(
            headlineContent = {
                Text(localized(language, "移除分组", "Remove grouping"), color = MaterialTheme.colorScheme.error)
            },
            supportingContent = {
                Text(localized(language, "会话和文件夹都会保留", "Chats and folders are kept"))
            },
            leadingContent = {
                Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            },
            modifier = Modifier.clickable { onDismiss(); onDelete() },
        )
        androidx.compose.foundation.layout.Spacer(Modifier.size(20.dp))
    }
}

/**
 * Confirm removing the grouping. Deliberately explicit that nothing is destroyed: upstream deletes
 * the project row and its folder rows only, so the chats stay and the folder comes straight back
 * as an auto-discovered project. Calling this "delete" would be a lie.
 */
@Composable
fun RemoveProjectDialog(project: Project, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val language = LocalAppLanguage.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(language, "移除分组？", "Remove grouping?")) },
        text = {
            Text(
                localized(
                    language,
                    "「${projectDisplayLabel(project)}」这个分组会被移除。会话、文件夹和 Mac 上的文件都不会被删除，该文件夹会重新作为自动识别的项目出现。",
                    "The grouping \"${projectDisplayLabel(project)}\" is removed. No chats, folders or files on the Mac are deleted, and the folder reappears as an auto-detected project.",
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text(localized(language, "移除", "Remove"), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(language, "取消", "Cancel")) } },
    )
}

/** Manage a project's folders: promote a primary, remove one, or add another. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectFoldersSheet(
    project: Project,
    onDismiss: () -> Unit,
    onAddFolder: () -> Unit,
    onSetPrimary: (String) -> Unit,
    onRemoveFolder: (String) -> Unit,
) {
    val language = LocalAppLanguage.current
    // The tree exposes a project's folders as its repos; the primary is the project's own path.
    val folders = remember(project) {
        project.repos.mapNotNull { it.path }.distinct().ifEmpty { listOfNotNull(project.path) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = hermesSheetState()) {
        Text(
            localized(language, "管理文件夹", "Manage folders"),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        folders.forEach { path ->
            val primary = path == project.path
            ListItem(
                leadingContent = {
                    Icon(FolderStrokeIcon, contentDescription = null, modifier = Modifier.size(24.dp))
                },
                headlineContent = { Text(path, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
                supportingContent = if (primary) {
                    { Text(localized(language, "主文件夹 · 新建会话落在这里", "Primary · new chats are created here")) }
                } else null,
                trailingContent = if (primary) null else {
                    {
                        Row {
                            TextButton(onClick = { onSetPrimary(path) }) {
                                Text(localized(language, "设为主", "Make primary"))
                            }
                            TextButton(onClick = { onRemoveFolder(path) }) {
                                Text(localized(language, "移除", "Remove"), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                },
            )
        }
        ListItem(
            leadingContent = { Icon(Icons.Rounded.Add, contentDescription = null) },
            headlineContent = { Text(localized(language, "添加文件夹", "Add a folder")) },
            modifier = Modifier.clickable { onDismiss(); onAddFolder() },
        )
        androidx.compose.foundation.layout.Spacer(Modifier.size(20.dp))
    }
}
