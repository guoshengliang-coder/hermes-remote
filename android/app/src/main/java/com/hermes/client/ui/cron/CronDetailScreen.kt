package com.hermes.client.ui.cron
import androidx.compose.material.icons.automirrored.rounded.ArrowBack

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.hermes.client.ui.theme.LocalProfileAccent
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.ui.util.formatEpoch
import com.hermes.client.ui.util.formatIso
import com.hermes.client.ui.localization.l10n

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CronDetailScreen(
    jobId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit = {},
    vm: CronDetailViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmingDelete by remember { mutableStateOf(false) }

    LaunchedEffect(jobId) { vm.load(jobId) }
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = state.job?.let { cronDisplayName(it.name, it.prompt, it.id) } ?: l10n("定时任务", "Cron job"),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = l10n("返回", "Back"),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            state.loading -> com.hermes.client.ui.components.LoadingState()
            state.job == null -> com.hermes.client.ui.components.ErrorState(
                message = state.error ?: l10n("无法加载该定时任务", "Couldn't load this cron job"),
                modifier = Modifier.padding(padding).fillMaxSize(),
                onRetry = { vm.load(jobId) },
            )
            else -> {
                val job = state.job!!
                LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                    item {
                        Column(Modifier.padding(16.dp)) {
                            Field(l10n("计划", "Schedule"), job.scheduleText)
                            Spacer(Modifier.height(8.dp))
                            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                                Column(Modifier.padding(12.dp)) {
                                    Field(l10n("状态", "Status"), if (job.isPaused) l10n("已暂停", "Paused") else if (job.enabled) l10n("已启用", "Enabled") else l10n("已停用", "Disabled"))
                                    Field(l10n("下次运行", "Next run"), formatIso(job.nextRunAt))
                                    Field(l10n("上次运行", "Last run"), formatIso(job.lastRunAt) + (job.lastStatus?.let { " · $it" } ?: ""))
                                    job.lastError?.takeIf { it.isNotBlank() }?.let { err ->
                                        var errorExpanded by rememberSaveable(err) { mutableStateOf(false) }
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            err,
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = if (errorExpanded) Int.MAX_VALUE else 3,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.clickable { errorExpanded = !errorExpanded },
                                        )
                                        Text(
                                            if (errorExpanded) l10n("收起", "Show less") else l10n("展开", "Show more"),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = LocalProfileAccent.current.accent,
                                            modifier = Modifier.padding(top = 2.dp).clickable { errorExpanded = !errorExpanded },
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.padding(top = 12.dp))
                            Row {
                                if (job.isPaused) {
                                    Button(onClick = { vm.resume() }) { Text(l10n("恢复", "Resume")) }
                                } else {
                                    OutlinedButton(onClick = { vm.pause() }) { Text(l10n("暂停", "Pause")) }
                                }
                                Spacer(Modifier.width(8.dp))
                                Button(onClick = { vm.trigger() }) { Text(l10n("立即运行", "Run now")) }
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(onClick = onEdit) { Text(l10n("编辑", "Edit")) }
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = { confirmingDelete = true }) { Text(l10n("删除", "Delete")) }
                            }
                            job.prompt?.takeIf { it.isNotBlank() }?.let {
                                Spacer(Modifier.padding(top = 12.dp))
                                Text(l10n("提示词", "PROMPT"), style = MaterialTheme.typography.labelSmall,
                                    color = LocalProfileAccent.current.accent)
                                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 8,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Text(
                            l10n("运行历史（${state.runs.size}）", "RUN HISTORY (${state.runs.size})"),
                            style = MaterialTheme.typography.labelMedium,
                            color = LocalProfileAccent.current.accent,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                        )
                    }
                    items(state.runs, key = { it.id }) { run ->
                        ListItem(
                            headlineContent = { Text(formatEpoch(run.startedAt)) },
                            supportingContent = { Text(run.endReason ?: "—") },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(l10n("删除定时任务？", "Delete cron job?")) },
            text = { Text(l10n("将永久删除该定时任务。", "This permanently deletes the scheduled job.")) },
            confirmButton = {
                TextButton(onClick = { confirmingDelete = false; vm.delete() }) { Text(l10n("删除", "Delete")) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(l10n("取消", "Cancel")) } },
        )
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.width(96.dp), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
