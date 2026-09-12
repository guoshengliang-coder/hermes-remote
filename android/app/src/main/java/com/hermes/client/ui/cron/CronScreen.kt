package com.hermes.client.ui.cron

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.data.network.CronJobDto
import com.hermes.client.ui.components.AlertTriangleIcon
import com.hermes.client.ui.components.ClockStrokeIcon
import com.hermes.client.ui.components.ErrorState
import com.hermes.client.ui.components.IncidentStrip
import com.hermes.client.ui.components.LoadingState
import com.hermes.client.ui.components.MoreDotsIcon
import com.hermes.client.ui.components.SectionHeader
import com.hermes.client.ui.components.SectionTone
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.l10n
import com.hermes.client.ui.theme.CronTopBarSubtitle
import com.hermes.client.ui.theme.CronTopBarTitle
import com.hermes.client.ui.theme.SessionRowSubline
import com.hermes.client.ui.theme.SessionRowTitle
import com.hermes.client.ui.theme.StatusTone
import com.hermes.client.ui.theme.fabContainerColor
import com.hermes.client.ui.theme.fabOutlineColor
import com.hermes.client.ui.theme.statusColor
import kotlinx.coroutines.launch

/**
 * The scheduled-jobs list (docs/DESIGN.md §5.18; Stitch 基线-定时任务列表 / 暗夜, 2026-09-12).
 *
 * Stateful shell: it owns the view model and the action toast, and hands everything the mock draws
 * to [CronScreenContent] so the screens can be captured (`CronScreenshotTest`).
 */
@Composable
fun CronScreen(
    onMenu: () -> Unit,
    onOpen: (String) -> Unit = {},
    onNew: (String) -> Unit = {},
    onEdit: (String) -> Unit = {},
    vm: CronViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val language = LocalAppLanguage.current
    val stateMessage = state.message?.resolve(language)
    val context = LocalContext.current
    // Reload when returning to this screen (e.g. after create/edit/delete) so the list is fresh.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.load() }
    // Outside the `when`: an action raised from the last row of a list that then emptied used to
    // lose its toast, because the effect lived in the non-empty branch.
    LaunchedEffect(stateMessage) {
        stateMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.clearMessage()
        }
    }
    val nowMs = remember(state.jobs) { System.currentTimeMillis() }
    CronScreenContent(
        state = state,
        nowMs = nowMs,
        onMenu = onMenu,
        onOpen = onOpen,
        onNew = onNew,
        onEdit = onEdit,
        onAction = { job, action -> vm.runAction(job.id, job.name ?: job.id, action) },
        onRetry = { vm.load() },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CronScreenContent(
    state: CronUiState,
    nowMs: Long,
    onMenu: () -> Unit = {},
    onOpen: (String) -> Unit = {},
    onNew: (String) -> Unit = {},
    onEdit: (String) -> Unit = {},
    onAction: (CronJobDto, CronAction) -> Unit = { _, _ -> },
    onRetry: () -> Unit = {},
) {
    val language = LocalAppLanguage.current
    var confirmingDelete by remember { mutableStateOf<CronJobDto?>(null) }
    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = l10n("定时任务", "Cron jobs"),
                titleStyle = CronTopBarTitle,
                // 「当前身份 · default」 with a neutral dot, per the mock. It used to be
                // 「身份：X」 in the brand blue — a chrome accent doing a caption's job.
                subtitleContent = state.profile?.let { profile ->
                    {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(6.dp)
                                    .background(MaterialTheme.colorScheme.outline, CircleShape),
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(
                                l10n("当前身份 · $profile", "Profile · $profile"),
                                style = CronTopBarSubtitle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onMenu) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = l10n("返回", "Back"),
                        )
                    }
                },
                // No overflow in the bar: the mock draws one, but nothing in this app has a
                // menu item to put in it (docs/DESIGN.md §7 item 8 — no inventing UI).
            )
        },
        floatingActionButton = {
            // Neutral near-black, 56dp, 28dp plus — the same shape as the new-chat FAB
            // (docs/DESIGN.md §2.7 item 1). It was a brand-blue extended FAB, which put the one
            // blue thing on the page next to six status colours.
            FloatingActionButton(
                onClick = { onNew("new") },
                modifier = Modifier.border(
                    1.dp,
                    fabOutlineColor(),
                    FloatingActionButtonDefaults.shape,
                ),
                containerColor = fabContainerColor(),
                contentColor = Color.White,
            ) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = l10n("新建定时任务", "New cron job"),
                    modifier = Modifier.size(28.dp),
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                // Loading / empty / error are 保留项: the mocks draw none of the three, and an
                // implementation must not delete them to match a mock (docs/DESIGN.md §5.2).
                state.loading -> LoadingState()
                state.error != null -> ErrorState(error = state.error, onRetry = onRetry)
                state.jobs.isEmpty() -> CronEmpty(onNew = onNew)
                else -> {
                    var menuFor by remember { mutableStateOf<String?>(null) }
                    val sections = remember(state.jobs, nowMs) { cronSections(state.jobs, nowMs) }
                    val needsYou = sections.firstOrNull { it.group == CronGroup.NEEDS_YOU }?.jobs?.size ?: 0
                    val listState = rememberLazyListState()
                    val scope = rememberCoroutineScope()
                    LazyColumn(Modifier.fillMaxSize(), state = listState) {
                        // The same inset card the home screen uses. Without it a long list makes
                        // the reader scroll to find out whether anything is wrong at all.
                        if (needsYou > 0) {
                            item(key = "health") {
                                IncidentStrip(
                                    // The count, and nothing else. The mock's second line names a
                                    // job and a raw ECONNREFUSED; strings come from the error-code
                                    // catalogue, and a transport token is not a user-facing reason.
                                    label = l10n(
                                        "$needsYou 个任务需要处理",
                                        "$needsYou job(s) need attention",
                                    ),
                                    icon = AlertTriangleIcon,
                                    // Tappable: it scrolls to the group that needs a person, and a
                                    // strip that only announces is a dead end.
                                    onClick = { scope.launch { listState.animateScrollToItem(1) } },
                                )
                            }
                        }
                        sections.forEach { section ->
                            item(key = "hdr-${section.group.name}") {
                                SectionHeader(
                                    label = section.title.resolve(language),
                                    count = section.jobs.size,
                                    tone = when (section.group) {
                                        CronGroup.NEEDS_YOU -> SectionTone.NEEDS_YOU
                                        CronGroup.ACTIVE -> SectionTone.ACTIVE
                                        CronGroup.PAUSED -> SectionTone.PAUSED
                                    },
                                )
                            }
                            items(section.jobs, key = { it.id }) { job ->
                                CronRow(
                                    job = job,
                                    status = cronRowStatus(job, nowMs),
                                    menuOpen = menuFor == job.id,
                                    onOpen = { onOpen(job.id) },
                                    onMenuOpen = { menuFor = job.id },
                                    onMenuDismiss = { menuFor = null },
                                    onAction = { action -> menuFor = null; onAction(job, action) },
                                    onEdit = { menuFor = null; onEdit(job.id) },
                                    onDelete = { menuFor = null; confirmingDelete = job },
                                )
                                // No divider: list rows sit directly on the warm paper and the
                                // group headers do the separating (docs/DESIGN.md §5.2). The mock
                                // wraps each group in a white card with hairlines between rows —
                                // rejected, because the sibling lists would then read differently.
                            }
                        }
                    }
                }
            }
        }
    }

    confirmingDelete?.let { job ->
        AlertDialog(
            onDismissRequest = { confirmingDelete = null },
            title = { Text(l10n("删除定时任务？", "Delete cron job?")) },
            text = {
                Text(
                    l10n(
                        "将永久删除「${cronDisplayName(job.name, job.prompt, job.id)}」。",
                        "This permanently deletes “${cronDisplayName(job.name, job.prompt, job.id)}”.",
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { confirmingDelete = null; onAction(job, CronAction.DELETE) },
                ) {
                    // Red, because it cannot be undone (docs/DESIGN.md §5.5).
                    Text(l10n("删除", "Delete"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = null }) { Text(l10n("取消", "Cancel")) }
            },
        )
    }
}

/**
 * One job. Hand-drawn, like the session row and for the same reasons (docs/DESIGN.md §5.2):
 * `ListItem` enforces a 72dp two-line floor, and these rows are the sibling list's 49dp.
 *
 * `[dot] [name]` on the first line, subline under the dot — the mock indents the title by the dot
 * and its gap, and leaves the second line flush with the dot's left edge.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CronRow(
    job: CronJobDto,
    status: CronRowStatus,
    menuOpen: Boolean,
    onOpen: () -> Unit,
    onMenuOpen: () -> Unit,
    onMenuDismiss: () -> Unit,
    onAction: (CronAction) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val language = LocalAppLanguage.current
    // Status colour lives on the dot and on the outcome half of the subline, never on a whole
    // row. The brand colour does not stand in for 「成功」 (docs/DESIGN.md §5.16).
    val tone = when (status) {
        CronRowStatus.FAILED, CronRowStatus.OVERDUE -> StatusTone.BAD
        CronRowStatus.UNDELIVERED -> StatusTone.WARN
        CronRowStatus.OK -> StatusTone.GOOD
        CronRowStatus.PAUSED -> null
    }
    val dotColor = tone?.let { statusColor(it) } ?: MaterialTheme.colorScheme.outline
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = com.hermes.client.ui.tuning.tunedRowPaddingV()), // TUNING-TEMP
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(dotColor, CircleShape))
                Spacer(Modifier.size(8.dp))
                Text(
                    cronDisplayName(job.name, job.prompt, job.id),
                    style = SessionRowTitle,
                    // A paused job is still legible, just quieter. The mock fades the whole card;
                    // fading the row would take the dot and the overflow down with it.
                    color = if (status == CronRowStatus.PAUSED) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.size(com.hermes.client.ui.tuning.tunedSublineGap())) // TUNING-TEMP
            // 节奏或落点 · 上次结果 — the row grammar the three lists share, from one pure function
            // that the tests pin. The exact next-run time lives on the detail screen: in a list,
            // 「每 10 分钟」 plus 「上次失败」 is what decides whether to look.
            val neutral = MaterialTheme.colorScheme.onSurfaceVariant
            val outcomeColor = if (tone == null || tone == StatusTone.GOOD) neutral else statusColor(tone)
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = neutral)) {
                        append(cronSublineBase(job.scheduleText, job.deliver, language))
                    }
                    // Only the outcome is coloured. A failed job's rhythm and delivery target are
                    // not themselves failures, and painting the whole line red said they were.
                    cronSublineOutcome(status, language)?.let { outcome ->
                        withStyle(SpanStyle(color = neutral)) { append(CRON_SUBLINE_SEPARATOR) }
                        withStyle(SpanStyle(color = outcomeColor)) { append(outcome) }
                    }
                },
                style = SessionRowSubline,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = onMenuOpen) {
                Icon(
                    MoreDotsIcon,
                    contentDescription = l10n("操作", "Actions"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            // Pause / run / edit / delete all need an entry somewhere (docs/DESIGN.md §5.16);
            // edit and delete used to exist only on the detail screen.
            DropdownMenu(expanded = menuOpen, onDismissRequest = onMenuDismiss) {
                DropdownMenuItem(
                    text = { Text(if (job.isPaused) l10n("恢复", "Resume") else l10n("暂停", "Pause")) },
                    onClick = { onAction(if (job.isPaused) CronAction.RESUME else CronAction.PAUSE) },
                )
                DropdownMenuItem(
                    text = { Text(l10n("立即运行", "Run now")) },
                    onClick = { onAction(CronAction.RUN) },
                )
                DropdownMenuItem(text = { Text(l10n("编辑", "Edit")) }, onClick = onEdit)
                DropdownMenuItem(
                    text = { Text(l10n("删除", "Delete"), color = MaterialTheme.colorScheme.error) },
                    onClick = onDelete,
                )
            }
        }
    }
}

@Composable
private fun CronEmpty(onNew: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Hand-drawn, 1.7dp stroke. `Icons.Rounded.Schedule` is Material's filled set, which
        // §4.1 bars from this icon system — the rows lost their filled glyphs long ago and this
        // one outlived them.
        Icon(
            ClockStrokeIcon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(l10n("暂无定时任务", "No cron jobs"), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            l10n("从模板开始：", "Start from a template:"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        CRON_TEMPLATES.forEach { t ->
            OutlinedButton(
                onClick = { onNew(t.id) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Text(l10n(t.labelZh, t.labelEn))
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { onNew("new") }) { Text(l10n("新建定时任务", "New cron job")) }
    }
}
