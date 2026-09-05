package com.hermes.client.ui.settings
import androidx.compose.material.icons.automirrored.rounded.ArrowBack

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.hermes.client.data.diagnostics.DebugLog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.hermes.client.ui.localization.l10n

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    onOpenGallery: () -> Unit = {},
    vm: DiagnosticsViewModel = hiltViewModel(),
) {
    val enabled by vm.enabled.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val sessionIds by vm.sessionIds.collectAsStateWithLifecycle()
    // docs/DESIGN.md §5.15: a chip per session the log mentions; the list and the Share button
    // follow the selection, so a report can carry exactly the conversation that misbehaved.
    var selectedSession by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val shownEntries = remember(entries, selectedSession) {
        val id = selectedSession
        if (id == null) entries else entries.filter { DebugLog.mentionsSession(it.message, id) }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = l10n("诊断", "Diagnostics"),
            // captured for use inside onClick lambdas (l10n is composition-scoped)
                navigationIcon = { IconButton(onClick = onBack) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = l10n("返回", "Back")) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val shareSubject = l10n("Hermes GO 诊断日志", "Hermes GO diagnostic log")
            val shareTitle = l10n("分享诊断日志", "Share diagnostic log")
            val markNote = l10n("问题就发生在这里", "the problem happened here")
            ListItem(
                headlineContent = { Text(l10n("诊断日志", "Diagnostic logging")) },
                supportingContent = {
                    Text(l10n("记录网络与会话活动，用于排查\u201c找不到消息\u201d这类错误。默认关闭；会话令牌不会被记录。记录存在应用私有目录，最多保留 7 天，重启后仍可查看上次运行的记录。", "Records network and session activity to diagnose errors like \"message not found\". Off by default; the session token is never logged. Entries are kept in the app's private storage for up to 7 days, so the previous run is still readable after a restart."))
                },
                trailingContent = {
                    Switch(checked = enabled, onCheckedChange = { vm.setEnabled(it) })
                },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(l10n("组件展廊", "Component gallery")) },
                supportingContent = { Text(l10n("用固定假数据渲染聊天组件的各个状态，用于视觉检查。", "Chat components rendered from fixed fake data, for visual checks.")) },
                modifier = Modifier.clickable(onClick = onOpenGallery),
            )
            HorizontalDivider()

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Shares the file, not EXTRA_TEXT: the full history is far too large for an
                // Intent extra, and reading it off disk must not happen on the main thread.
                Button(
                    onClick = {
                        scope.launch {
                            vm.share(
                                context,
                                shareTitle,
                                selectedSession?.let { "$shareSubject · $it" } ?: shareSubject,
                                selectedSession,
                            )
                        }
                    },
                ) { Text(l10n("分享", "Share")) }
                OutlinedButton(
                    enabled = enabled,
                    onClick = { vm.mark(markNote) },
                ) { Text(l10n("标记现场", "Mark")) }
                OutlinedButton(onClick = { vm.clear() }) { Text(l10n("清除", "Clear")) }
            }
            if (sessionIds.isNotEmpty()) {
                androidx.compose.foundation.lazy.LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    item(key = "all") {
                        androidx.compose.material3.FilterChip(
                            selected = selectedSession == null,
                            onClick = { selectedSession = null },
                            label = { Text(l10n("全部", "All")) },
                        )
                    }
                    items(sessionIds.size, key = { sessionIds[it] }) { index ->
                        val id = sessionIds[index]
                        androidx.compose.material3.FilterChip(
                            selected = selectedSession == id,
                            onClick = { selectedSession = if (selectedSession == id) null else id },
                            label = { Text(id, maxLines = 1) },
                        )
                    }
                }
            }
            HorizontalDivider()

            if (shownEntries.isEmpty()) {
                Text(
                    if (enabled) l10n("日志已开启。复现问题后，这里会显示记录。", "Logging is on. Reproduce the issue and entries will appear here.")
                    else l10n("先开启诊断日志，然后复现问题。", "Turn on Diagnostic logging, then reproduce the issue."),
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Newest first for quick reading.
                val reversed = shownEntries.asReversed()
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(reversed, key = { i, _ -> "$i" }) { _, e -> LogRow(e) }
                }
            }
        }
    }
}

private val timeFmt =
    DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

@Composable
private fun LogRow(e: DebugLog.LogEntry) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        // Restored entries carry the same clock as live ones, so the run they came from has to be
        // said outright — a timestamp from an hour ago is otherwise indistinguishable.
        val origin = if (e.fromPreviousRun) l10n("  · 上次运行", "  · previous run") else ""
        Text(
            "${timeFmt.format(Instant.ofEpochMilli(e.timeMillis))}  ${e.category}$origin",
            style = MaterialTheme.typography.labelSmall,
            color = if (e.fromPreviousRun) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.primary,
        )
        Text(
            e.message,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
