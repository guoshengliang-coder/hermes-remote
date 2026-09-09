package com.hermes.client.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.ui.components.FolderStrokeIcon
import com.hermes.client.ui.components.ThinChevronIcon
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.localization.localizedMessage

/**
 * Pick a folder on the Mac. The phone has no filesystem of its own to browse, so this walks the
 * gateway's `/api/fs/list` one level at a time — the same endpoint the desktop's remote picker
 * uses. Directories only: the destination of a project is always a directory.
 *
 * Deliberately NOT a general file browser. There is no jail on that endpoint upstream, so this
 * screen offers exactly one action — choose the directory you are standing in — and never opens,
 * reads, downloads or deletes anything.
 */
@Composable
fun FolderPickerScreen(
    vm: SessionsViewModel,
    onBack: () -> Unit,
    onPicked: (String) -> Unit,
) {
    val language = LocalAppLanguage.current
    var path by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<AppError?>(null) }
    var isRepo by remember { mutableStateOf(false) }

    // Reloads whenever the directory changes, including the first resolve of the default cwd.
    LaunchedEffect(path) {
        loading = true
        error = null
        val target = path ?: vm.defaultBrowseFolder() ?: "/"
        try {
            entries = vm.browseFolder(target).map { it.name to it.path }
            isRepo = vm.gitRootOf(target) != null
            if (path == null) path = target
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            entries = emptyList()
            isRepo = false
            error = AppError(
                AppErrorCode.FOLDER_BROWSE_FAILED,
                retryable = true,
                technicalCause = e.message,
                stage = "folder_browse",
            )
        }
        loading = false
    }

    val here = path
    val parent = here?.trimEnd('/')?.substringBeforeLast('/', "")?.ifBlank { "/" }

    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = localized(language, "选择文件夹", "Choose a folder"),
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
        bottomBar = {
            // The whole point of the screen: commit the directory you are standing in.
            Surface(tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        here ?: "…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                    if (isRepo) {
                        Text(
                            localized(language, "这是一个 Git 仓库", "This folder is a Git repository"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Button(
                        onClick = { here?.let(onPicked) },
                        enabled = here != null && error == null,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) { Text(localized(language, "选择此文件夹", "Use this folder")) }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                loading && entries.isEmpty() -> com.hermes.client.ui.components.ListLoadingState()
                error != null -> com.hermes.client.ui.components.ErrorState(
                    error = error!!,
                    onRetry = { path = parent?.takeIf { it != here } ?: here },
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    if (here != null && here != "/") {
                        item(key = "..") {
                            ListItem(
                                leadingContent = {
                                    Icon(
                                        Icons.Rounded.ArrowUpward,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(24.dp),
                                    )
                                },
                                headlineContent = { Text(localized(language, "上一级", "Up one level")) },
                                modifier = Modifier.clickable { path = parent },
                            )
                        }
                    }
                    items(entries, key = { it.second }) { (name, full) ->
                        ListItem(
                            leadingContent = {
                                Icon(
                                    FolderStrokeIcon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp),
                                )
                            },
                            headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingContent = {
                                Icon(
                                    ThinChevronIcon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                            modifier = Modifier.clickable { path = full },
                        )
                    }
                    if (entries.isEmpty()) {
                        item(key = "empty") {
                            Row(
                                Modifier.fillMaxWidth().padding(24.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (loading) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    Text(
                                        localized(language, "这里没有子文件夹。", "No sub-folders here."),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
