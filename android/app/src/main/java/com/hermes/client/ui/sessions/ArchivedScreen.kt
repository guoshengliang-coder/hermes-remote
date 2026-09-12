package com.hermes.client.ui.sessions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.domain.Session
import com.hermes.client.ui.chat.ChatLaunch
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized

/**
 * Archived chats, as a full-screen page reached from the Chats overflow menu (docs/DESIGN.md
 * §5.16, 2026-09-09). No FAB — you do not start work in the archive.
 *
 * [vm] has no default ON PURPOSE — see the note on [ProjectsScreen].
 */
@Composable
fun ArchivedScreen(
    vm: SessionsViewModel,
    onBack: () -> Unit,
    onOpen: (ChatLaunch) -> Unit,
) {
    val language = LocalAppLanguage.current
    val archivedState by vm.archivedState.collectAsStateWithLifecycle()
    val defaultProjectPath by vm.defaultProjectPath.collectAsStateWithLifecycle()
    val openSession = rememberSessionOpener(vm, onOpen)

    LaunchedEffect(Unit) { vm.loadArchived() }

    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = localized(language, "已归档", "Archived"),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = localized(language, "返回", "Back"),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                archivedState.loading && archivedState.sessions.isEmpty() ->
                    com.hermes.client.ui.components.ListLoadingState()
                archivedState.error != null ->
                    com.hermes.client.ui.components.ErrorState(
                        error = archivedState.error!!,
                        onRetry = { vm.loadArchived() },
                    )
                archivedState.sessions.isEmpty() ->
                    com.hermes.client.ui.components.EmptyState(
                        title = localized(language, "暂无归档会话", "Nothing archived"),
                        subtitle = localized(language, "长按会话可将它归档。", "Long-press a session to archive it."),
                    )
                else -> LazyColumn {
                    items(archivedState.sessions, key = { "a-${it.profile.orEmpty()}:${it.id}" }) { s ->
                        ArchivedRow(
                            session = s,
                            defaultProjectPath = defaultProjectPath,
                            onOpen = { openSession(s) },
                            onUnarchive = { vm.unarchive(s) },
                            onDelete = { vm.delete(s) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * One archived session: stroke archive-box + title/model (matching the Projects row layout),
 * no divider. Tap opens; LONG-PRESS offers unarchive and delete — the same sheet pattern as
 * live session rows (the old trailing unarchive button broke the layout symmetry).
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ArchivedRow(
    session: Session,
    defaultProjectPath: String?,
    onOpen: () -> Unit,
    onUnarchive: () -> Unit,
    onDelete: () -> Unit,
) {
    val language = LocalAppLanguage.current
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    ListItem(
        leadingContent = {
            Icon(
                com.hermes.client.ui.components.ArchiveBoxIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        },
        headlineContent = { Text(session.title, style = com.hermes.client.ui.theme.SessionRowTitle) },
        supportingContent = { SessionSubline(session, defaultProjectPath = defaultProjectPath) },
        modifier = Modifier.combinedClickable(
            onClick = onOpen,
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                menuOpen = true
            },
        ),
    )

    if (menuOpen) {
        com.hermes.client.ui.components.RowActionSheet(
            typeLabel = localized(language, "已归档", "Archived"),
            title = session.title,
            onDismiss = { menuOpen = false },
        ) {
            ArchivedActionItems(
                onUnarchive = { menuOpen = false; onUnarchive() },
                onDelete = { menuOpen = false; confirmingDelete = true },
            )
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(localized(language, "删除会话？", "Delete session?")) },
            text = { Text(localized(language, "“${session.title}”将被永久删除。", "\"${session.title}\" will be permanently deleted.")) },
            confirmButton = {
                TextButton(onClick = { confirmingDelete = false; onDelete() }) {
                    Text(localized(language, "删除", "Delete"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text(localized(language, "取消", "Cancel")) }
            },
        )
    }
}

/**
 * The two actions an archived row offers — the same component the live session list uses
 * (docs/DESIGN.md §5.5 行长按操作单), so the two long-press menus cannot drift apart again.
 *
 * 「取消归档」 carries no trailing hint: it IS the undo, so there is nothing to reassure anyone
 * about. Delete keeps 「不可撤销」 and the second confirm.
 */
@Composable
internal fun ArchivedActionItems(onUnarchive: () -> Unit, onDelete: () -> Unit) {
    val language = LocalAppLanguage.current
    com.hermes.client.ui.components.RowActionItem(
        icon = Icons.Rounded.Unarchive,
        label = localized(language, "取消归档", "Unarchive"),
        onClick = onUnarchive,
    )
    com.hermes.client.ui.components.RowActionDivider()
    com.hermes.client.ui.components.RowActionItem(
        icon = Icons.Rounded.Delete,
        label = localized(language, "删除会话", "Delete"),
        hint = localized(language, "不可撤销", "Permanent"),
        destructive = true,
        onClick = onDelete,
    )
}
