package com.hermes.client.ui.cron

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.data.network.CronRunDto
import com.hermes.client.ui.components.ClockStrokeIcon
import com.hermes.client.ui.components.CopyStrokeIcon
import com.hermes.client.ui.components.DocumentStrokeIcon
import com.hermes.client.ui.components.ErrorState
import com.hermes.client.ui.components.LoadingState
import com.hermes.client.ui.components.PauseStrokeIcon
import com.hermes.client.ui.components.PencilStrokeIcon
import com.hermes.client.ui.components.PlayGlyphIcon
import com.hermes.client.ui.components.RefreshStrokeIcon
import com.hermes.client.ui.components.ResumeStrokeIcon
import com.hermes.client.ui.components.TrashStrokeIcon
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.l10n
import com.hermes.client.ui.localization.localizedMessage
import com.hermes.client.ui.theme.CronActionLabel
import com.hermes.client.ui.theme.CronActionLabelSmall
import com.hermes.client.ui.theme.CronDetailTopBarTitle
import com.hermes.client.ui.theme.CronFieldLabel
import com.hermes.client.ui.theme.CronFieldValue
import com.hermes.client.ui.theme.CronPill
import com.hermes.client.ui.theme.CronPromptBody
import com.hermes.client.ui.theme.CronRunMeta
import com.hermes.client.ui.theme.CronRunTime
import com.hermes.client.ui.theme.CronScheduleValue
import com.hermes.client.ui.theme.CronSectionTitle
import com.hermes.client.ui.theme.StatusTone
import com.hermes.client.ui.theme.cronActionColor
import com.hermes.client.ui.theme.cronActionOutlineColor
import com.hermes.client.ui.theme.cronCardBorderColor
import com.hermes.client.ui.theme.cronCardColor
import com.hermes.client.ui.theme.cronCardHeaderColor
import com.hermes.client.ui.theme.cronCardShadow
import com.hermes.client.ui.theme.cronInsetColor
import com.hermes.client.ui.theme.statusColor

/** How many lines of the prompt the preview shows before 「展开全部提示词」 (`line-clamp-6`). */
private const val PROMPT_PREVIEW_LINES = 6

/**
 * One scheduled job (docs/DESIGN.md §5.18; Stitch 基线-定时任务/任务详情 / 暗夜, 2026-09-12).
 *
 * Stateful shell; everything the mock draws lives in [CronDetailContent].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CronDetailScreen(
    jobId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit = {},
    vm: CronDetailViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val language = LocalAppLanguage.current
    val stateMessage = state.message?.resolve(language)
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(jobId) { vm.load(jobId) }
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    LaunchedEffect(stateMessage) {
        stateMessage?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    CronDetailContent(
        state = state,
        snackbar = snackbar,
        onBack = onBack,
        onRefresh = { vm.load(jobId) },
        onPause = { vm.pause() },
        onResume = { vm.resume() },
        onTrigger = { vm.trigger() },
        onEdit = onEdit,
        onDelete = { vm.delete() },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CronDetailContent(
    state: CronDetailUiState,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    onBack: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    onTrigger: () -> Unit = {},
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    val language = LocalAppLanguage.current
    var confirmingDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = state.job?.let { cronDisplayName(it.name, it.prompt, it.id) }
                    ?: l10n("定时任务", "Cron job"),
                centered = true,
                titleStyle = CronDetailTopBarTitle,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = l10n("返回", "Back"),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            RefreshStrokeIcon,
                            contentDescription = l10n("刷新", "Refresh"),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    // The mock also draws an overflow here. Pause / run / edit / delete are all
                    // already in the action block below, so it would open onto nothing.
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            state.loading -> LoadingState(modifier = Modifier.padding(padding).fillMaxSize())
            state.job == null -> state.error?.let {
                ErrorState(
                    error = it,
                    modifier = Modifier.padding(padding).fillMaxSize(),
                    onRetry = onRefresh,
                )
            } ?: ErrorState(
                message = l10n("无法加载该定时任务", "Couldn't load this cron job"),
                modifier = Modifier.padding(padding).fillMaxSize(),
                onRetry = onRefresh,
            )
            else -> {
                val job = state.job
                LazyColumn(
                    Modifier.padding(padding).fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    item(key = "status") {
                        CronCard {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.size(28.dp)
                                            .border(1.dp, cronCardBorderColor(), RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            ClockStrokeIcon,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                    Spacer(Modifier.size(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            l10n("计划节奏", "Schedule"),
                                            style = CronFieldLabel,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            cronScheduleText(job.scheduleText, language),
                                            style = CronScheduleValue,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                    val enabled = !job.isPaused && job.enabled
                                    CronPillBadge(
                                        text = when {
                                            job.isPaused -> l10n("已暂停", "Paused")
                                            job.enabled -> l10n("已启用", "Enabled")
                                            else -> l10n("已停用", "Disabled")
                                        },
                                        tone = if (enabled) StatusTone.GOOD else null,
                                        withDot = true,
                                    )
                                }
                                Spacer(Modifier.height(14.dp))
                                CronHairline()
                                Spacer(Modifier.height(14.dp))
                                Row {
                                    CronField(
                                        l10n("下次运行", "Next run"),
                                        cronTimeText(job.nextRunAt, language),
                                        Modifier.weight(1f),
                                    )
                                    // The same value the channel page calls 默认投递落点. A job that
                                    // "runs but nothing arrives" is usually this plus a dead channel.
                                    CronField(
                                        l10n("投递落点", "Delivers to"),
                                        cronDeliveryText(job.deliver).resolve(language),
                                        Modifier.weight(1f),
                                    )
                                }
                                Spacer(Modifier.height(14.dp))
                                CronHairline()
                                Spacer(Modifier.height(10.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        l10n("上次运行", "Last run"),
                                        style = CronFieldLabel,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        cronTimeText(job.lastRunAt, language),
                                        style = CronFieldValue,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    cronStatusLabel(job.lastStatus, language)?.let { label ->
                                        Spacer(Modifier.size(6.dp))
                                        CronPillBadge(
                                            text = label,
                                            tone = when (job.lastStatus?.trim()?.lowercase()) {
                                                "ok", "success", "succeeded", "cron_complete" -> StatusTone.GOOD
                                                "delivery_failed" -> StatusTone.WARN
                                                "error", "failed", "timeout", "timed_out" -> StatusTone.BAD
                                                else -> null
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Errors are a 保留项 — the mocks draw a healthy job and never show this block,
                    // and an implementation must not delete it to match a mock. The localized code
                    // and a short reason come first; the raw cause stays behind 展开 (§ERROR_HANDLING).
                    job.lastDeliveryError?.takeIf { it.isNotBlank() }?.let { err ->
                        item(key = "delivery-error") {
                            CronErrorBlock(
                                AppError(
                                    AppErrorCode.CRON_DELIVERY_FAILED,
                                    retryable = true,
                                    technicalCause = err,
                                    stage = "cron_delivery",
                                ),
                                raw = err,
                            )
                        }
                    }
                    job.lastError?.takeIf { it.isNotBlank() }?.let { err ->
                        item(key = "run-error") {
                            CronErrorBlock(
                                // HR-CRON-002, not the transport code this used to print: a cron
                                // job whose run failed is a scheduling problem, and HR-RPC-001 told
                                // the reader nothing about the schedule.
                                AppError(
                                    AppErrorCode.CRON_RUN_FAILED,
                                    retryable = true,
                                    technicalCause = err,
                                    stage = "cron_run",
                                ),
                                raw = err,
                            )
                        }
                    }

                    item(key = "actions") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            // 「立即运行」 is the one thing this page is usually opened to do, so it
                            // gets the full width and the filled treatment. Near-black, not brand
                            // blue — see Tiles.kt's CronActionLight.
                            Button(
                                onClick = onTrigger,
                                modifier = Modifier.fillMaxWidth().height(44.dp).border(
                                    1.dp, cronActionOutlineColor(), RoundedCornerShape(12.dp),
                                ),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = cronActionColor(),
                                    contentColor = Color.White,
                                ),
                            ) {
                                Icon(PlayGlyphIcon, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(8.dp))
                                Text(l10n("立即运行", "Run now"), style = CronActionLabel)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CronSecondaryButton(
                                    text = if (job.isPaused) l10n("恢复任务", "Resume") else l10n("暂停任务", "Pause"),
                                    icon = if (job.isPaused) ResumeStrokeIcon else PauseStrokeIcon,
                                    onClick = if (job.isPaused) onResume else onPause,
                                    modifier = Modifier.weight(1f),
                                )
                                CronSecondaryButton(
                                    text = l10n("编辑配置", "Edit"),
                                    icon = PencilStrokeIcon,
                                    onClick = onEdit,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            // Deliberately set apart from the two it would otherwise sit beside:
                            // it is the one action that cannot be undone (docs/DESIGN.md §5.5).
                            Row(
                                Modifier.fillMaxWidth().padding(top = 2.dp),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                TextButton(onClick = { confirmingDelete = true }) {
                                    Icon(
                                        TrashStrokeIcon,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.size(6.dp))
                                    Text(
                                        l10n("删除此定时任务", "Delete this cron job"),
                                        style = CronActionLabelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }

                    job.prompt?.takeIf { it.isNotBlank() }?.let { prompt ->
                        item(key = "prompt") { CronPromptCard(prompt) }
                    }

                    item(key = "runs-header") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                l10n("运行历史", "Run history"),
                                style = CronSectionTitle,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.size(8.dp))
                            CronCountChip(state.runs.size)
                        }
                    }
                    if (state.runs.isEmpty()) {
                        // The mock never draws an empty history; the header alone used to read
                        // 「运行历史（0）」 over a blank page.
                        item(key = "runs-empty") {
                            CronCard {
                                Text(
                                    l10n("这个任务还没有运行记录。", "This job has not run yet."),
                                    style = CronRunMeta,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                    } else {
                        item(key = "runs") {
                            CronCard {
                                Column {
                                    state.runs.forEachIndexed { index, run ->
                                        if (index > 0) CronHairline()
                                        CronRunRow(run)
                                    }
                                }
                            }
                        }
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
                TextButton(onClick = { confirmingDelete = false; onDelete() }) {
                    Text(l10n("删除", "Delete"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text(l10n("取消", "Cancel")) }
            },
        )
    }
}

/** The detail page's card: pale-card language (§2.3) with the detail mock's radius and hairline. */
@Composable
private fun CronCard(content: @Composable () -> Unit) {
    Surface(
        color = cronCardColor(),
        shape = RoundedCornerShape(16.dp),
        shadowElevation = cronCardShadow(),
        modifier = Modifier.fillMaxWidth().border(1.dp, cronCardBorderColor(), RoundedCornerShape(16.dp)),
    ) { content() }
}

@Composable
private fun CronHairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(cronCardBorderColor()))
}

@Composable
private fun CronField(label: String, value: String, modifier: Modifier = Modifier) {
    // No fixed label width: the old 96dp column clipped the English labels and every label at
    // fontScale 1.3. The mock stacks label over value instead.
    Column(modifier) {
        Text(label, style = CronFieldLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Text(value, style = CronFieldValue, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * A status pill. [tone] null means "no claim" — an unknown or disabled state keeps the neutral
 * chip rather than borrowing a health colour it has not earned.
 */
@Composable
private fun CronPillBadge(text: String, tone: StatusTone?, withDot: Boolean = false) {
    val ink = tone?.let { statusColor(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = if (tone != null) ink.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (withDot) {
                Box(Modifier.size(6.dp).background(ink, RoundedCornerShape(percent = 50)))
                Spacer(Modifier.size(6.dp))
            }
            Text(text, style = CronPill, color = ink)
        }
    }
}

/** Same chip the group headers wear, so a count looks like a count everywhere (§5.2). */
@Composable
private fun CronCountChip(count: Int) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            count.toString(),
            style = com.hermes.client.ui.theme.SessionGroupCount,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun CronSecondaryButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, cronCardBorderColor()),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = cronCardColor(),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.size(6.dp))
        Text(text, style = CronActionLabelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CronErrorBlock(error: AppError, raw: String) {
    val language = LocalAppLanguage.current
    var expanded by rememberSaveable(raw) { mutableStateOf(false) }
    CronCard {
        Column(Modifier.padding(16.dp)) {
            Text(
                // Code first, then a short reason — never the raw body as the primary message.
                "${error.code.value} · ${error.localizedMessage(language)}",
                style = CronFieldValue,
                color = statusColor(StatusTone.BAD),
                modifier = Modifier.clickable { expanded = !expanded },
            )
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Text(
                    // Redacted: credentials and personal data never reach this surface.
                    error.sanitizedDiagnostic(),
                    style = CronPromptBody,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                        .background(cronInsetColor(), RoundedCornerShape(8.dp))
                        .padding(12.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (expanded) l10n("收起", "Show less") else l10n("展开详情", "Show details"),
                style = CronActionLabelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { expanded = !expanded },
            )
        }
    }
}

@Composable
private fun CronPromptCard(prompt: String) {
    val clipboard = LocalClipboardManager.current
    var expanded by rememberSaveable(prompt) { mutableStateOf(false) }
    CronCard {
        Column {
            Row(
                Modifier.fillMaxWidth()
                    .background(cronCardHeaderColor())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    DocumentStrokeIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    l10n("执行提示词", "Prompt"),
                    style = CronSectionTitle,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { clipboard.setText(AnnotatedString(prompt)) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Icon(
                        CopyStrokeIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        l10n("复制", "Copy"),
                        style = CronActionLabelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            CronHairline()
            Column(Modifier.padding(16.dp)) {
                Text(
                    prompt,
                    style = CronPromptBody,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (expanded) Int.MAX_VALUE else PROMPT_PREVIEW_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                        .background(cronInsetColor(), RoundedCornerShape(8.dp))
                        .padding(12.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (expanded) l10n("收起提示词", "Show less") else l10n("展开全部提示词", "Show the whole prompt"),
                    style = CronActionLabelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun CronRunRow(run: CronRunDto) {
    val language = LocalAppLanguage.current
    val tone = when (run.endReason?.trim()?.lowercase()) {
        "ok", "success", "succeeded", "cron_complete" -> StatusTone.GOOD
        "delivery_failed" -> StatusTone.WARN
        "error", "failed", "timeout", "timed_out" -> StatusTone.BAD
        "running" -> StatusTone.RUNNING
        else -> null
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            cronRunTimeText(run.startedAt, language),
            style = CronRunTime,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(6.dp).background(
                    tone?.let { statusColor(it) } ?: MaterialTheme.colorScheme.outline,
                    RoundedCornerShape(percent = 50),
                ),
            )
            Spacer(Modifier.size(6.dp))
            // The localized outcome, not the raw `cron_complete` the mock prints in mono.
            // Duration is arithmetic on the two stamps Hermes already sends; the mock's run
            // number, log link and retention notice have no data source and are not drawn.
            Text(
                listOfNotNull(
                    cronStatusLabel(run.endReason, language),
                    cronRunDurationLabel(run.startedAt, run.endedAt, language),
                ).joinToString("  ·  ").ifBlank { "—" },
                style = CronRunMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
