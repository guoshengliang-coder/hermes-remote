package com.hermes.client.ui.cron
import androidx.compose.material.icons.automirrored.rounded.ArrowBack

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PauseCircleOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import com.hermes.client.ui.localization.l10n
import com.hermes.client.ui.localization.LocalAppLanguage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CronScreen(
    onMenu: () -> Unit,
    onOpen: (String) -> Unit = {},
    onNew: (String) -> Unit = {},
    vm: CronViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val language = LocalAppLanguage.current
    val stateMessage = state.message?.resolve(language)
    // Reload when returning to this screen (e.g. after create/edit/delete) so the list is fresh.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { vm.load() }

    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = l10n("定时任务", "Cron jobs"),
                subtitle = state.profile?.let { l10n("身份：$it", "Profile: $it") },
                navigationIcon = { IconButton(onClick = onMenu) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = l10n("返回", "Back")) } },
            )
        },
        floatingActionButton = {
            androidx.compose.material3.ExtendedFloatingActionButton(
                onClick = { onNew("new") },
                text = { Text(l10n("新建", "New")) },
                icon = { Icon(androidx.compose.material.icons.Icons.Rounded.Add, contentDescription = null) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> com.hermes.client.ui.components.LoadingState()
                state.error != null -> com.hermes.client.ui.components.ErrorState(
                    error = state.error!!, onRetry = { vm.load() },
                )
                state.jobs.isEmpty() -> CronEmpty(onNew = onNew)
                else -> {
                    val nowMs = remember(state.jobs) { System.currentTimeMillis() }
                    val menuFor = remember { mutableStateOf<String?>(null) }
                    val context = LocalContext.current
                    LaunchedEffect(stateMessage) {
                        stateMessage?.let {
                            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                            vm.clearMessage()
                        }
                    }
                    val sections = remember(state.jobs, nowMs) { cronSections(state.jobs, nowMs) }
                    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
                    LazyColumn(Modifier.fillMaxSize()) {
                      sections.forEach { section ->
                        item(key = "hdr-${section.group.name}") {
                            Text(
                                section.title.resolve(language),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                            )
                        }
                        items(section.jobs, key = { it.id }) { job ->
                            val rowStatus = cronRowStatus(job, nowMs)
                            ListItem(
                                // No leading icon: the Chats and channels lists carry none either,
                                // and DESIGN §4.1 bars Material's filled set from this icon system —
                                // ErrorOutline / CheckCircle / PauseCircleOutline all came from it.
                                // Status now reads from the dot beside the name, on StatusColors.
                                overlineContent = {
                                    Text(
                                        job.scheduleText + "  ·  " + cronDeliveryText(job.deliver).resolve(language),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                headlineContent = {
                                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        Text(
                                            cronDisplayName(job.name, job.prompt, job.id),
                                            modifier = Modifier.weight(1f, fill = false),
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        )
                                        // 状态色只出现在圆点与徽标上（DESIGN §5.9）；品牌色不再兼任「成功」。
                                        val tone = when (rowStatus) {
                                            CronRowStatus.FAILED, CronRowStatus.OVERDUE -> com.hermes.client.ui.theme.StatusTone.BAD
                                            CronRowStatus.UNDELIVERED -> com.hermes.client.ui.theme.StatusTone.WARN
                                            CronRowStatus.OK -> com.hermes.client.ui.theme.StatusTone.GOOD
                                            CronRowStatus.PAUSED -> null
                                        }
                                        Box(
                                            Modifier.padding(start = 8.dp).size(8.dp).background(
                                                tone?.let { com.hermes.client.ui.theme.statusColor(it, dark) }
                                                    ?: MaterialTheme.colorScheme.outline,
                                                CircleShape,
                                            ),
                                        )
                                    }
                                },
                                supportingContent = {
                                    val next = job.nextRunAt?.let { l10n("下次：", "Next: ") + com.hermes.client.ui.util.formatIso(it) }
                                    // Prompt snippet is only useful here when the headline is the name; when the job is
                                    // unnamed the headline already shows the prompt (via cronDisplayName), so don't repeat it.
                                    val fallback = job.name?.takeIf { it.isNotBlank() }?.let {
                                        job.prompt?.replace("\n", " ")?.trim()?.take(100)
                                    }
                                    Text(next ?: fallback.orEmpty())
                                },
                                trailingContent = {
                                    Box {
                                        IconButton(onClick = { menuFor.value = job.id }) {
                                            Icon(Icons.Rounded.MoreVert, contentDescription = l10n("操作", "Actions"))
                                        }
                                        DropdownMenu(expanded = menuFor.value == job.id, onDismissRequest = { menuFor.value = null }) {
                                            DropdownMenuItem(
                                                text = { Text(if (job.isPaused) l10n("恢复", "Resume") else l10n("暂停", "Pause")) },
                                                onClick = {
                                                    vm.runAction(job.id, job.name ?: job.id, if (job.isPaused) CronAction.RESUME else CronAction.PAUSE)
                                                    menuFor.value = null
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(l10n("立即运行", "Run now")) },
                                                onClick = {
                                                    vm.runAction(job.id, job.name ?: job.id, CronAction.RUN)
                                                    menuFor.value = null
                                                }
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.clickable { onOpen(job.id) },
                            )
                            HorizontalDivider()
                        }
                      }
                    }
                }
            }
        }
    }
}

@Composable
private fun CronEmpty(onNew: (String) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.Schedule, contentDescription = null, tint = accent, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text(l10n("暂无定时任务", "No cron jobs"), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(l10n("从模板开始：", "Start from a template:"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        CRON_TEMPLATES.forEach { t ->
            OutlinedButton(onClick = { onNew(t.id) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(l10n(t.labelZh, t.labelEn))
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { onNew("new") }) { Text(l10n("新建定时任务", "New cron job")) }
    }
}
