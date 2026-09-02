package com.hermes.client.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.BuildConfig
import com.hermes.client.ui.components.HermesTopBar
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.localization.localizedMessage
import com.hermes.client.ui.theme.StatusTone
import com.hermes.client.ui.theme.statusColor
import com.hermes.client.update.DownloadPhase
import com.hermes.client.update.ExportResult
import com.hermes.client.update.UpdateRow
import com.hermes.client.update.UpdateTask
import com.hermes.client.update.UpdateUiState
import com.hermes.client.update.UpdateVersion
import com.hermes.client.update.UpdateViewModel
import com.hermes.client.update.VersionEligibility
import com.hermes.client.update.downloadFailureText
import com.hermes.client.update.downloadPauseText
import com.hermes.client.update.taskIsSuperseded
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The update page recommends exactly one release — the manifest's latest — and keeps the version
 * record in view: the newest [VISIBLE_HISTORY] entries render as a standing list (summary line,
 * tap to expand the full notes), older ones behind an explicit reveal. Old releases are never
 * directly installable (Android forbids in-place downgrades); rows whose APK survived the
 * keep-newest-five retention offer an export for the documented uninstall-reinstall rollback.
 * See docs/DESIGN.md §5.9 and docs/APP_UPDATE.md.
 */
@Composable
fun AppUpdateScreen(onBack: () -> Unit, viewModel: UpdateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Page entry: check the public index and, independently, restore any persisted download.
    LaunchedEffect(Unit) { viewModel.onOpen() }
    // Foreground visibility gates the auto-install hand-off (background completions notify).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.setPageVisible(true)
                Lifecycle.Event.ON_PAUSE -> viewModel.setPageVisible(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            viewModel.setPageVisible(false)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    AppUpdateContent(
        state = state,
        onBack = onBack,
        onCheck = viewModel::check,
        onDownload = viewModel::download,
        onRetry = viewModel::retry,
        onCancel = viewModel::cancel,
        onInstall = viewModel::install,
        onExport = viewModel::export,
        onDismissExportNotice = viewModel::dismissExportNotice,
    )
}

/** History entries shown without the extra "older versions" reveal. */
internal const val VISIBLE_HISTORY = 10

/** Recommended-card notes shown before the "show all" reveal kicks in. */
internal const val VISIBLE_NOTES = 6

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AppUpdateContent(
    state: UpdateUiState,
    onBack: () -> Unit = {},
    onCheck: () -> Unit = {},
    onDownload: (UpdateVersion) -> Unit = {},
    onRetry: () -> Unit = {},
    onCancel: () -> Unit = {},
    onInstall: () -> Unit = {},
    onExport: (UpdateVersion) -> Unit = {},
    onDismissExportNotice: () -> Unit = {},
) {
    val language = LocalAppLanguage.current
    var olderExpanded by rememberSaveable { mutableStateOf(false) }
    var rollbackHelpFor by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = {
            HermesTopBar(
                title = localized(language, "检查更新", "App updates"),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, localized(language, "返回", "Back"))
                    }
                },
                actions = {
                    if (state.checking) {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                Modifier.size(20.dp).semantics {
                                    contentDescription = localized(language, "正在检查更新", "Checking for updates")
                                },
                                strokeWidth = 2.dp,
                            )
                        }
                    } else {
                        IconButton(onClick = onCheck) {
                            Icon(Icons.Rounded.Refresh, localized(language, "重新检查", "Check again"))
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Spacer(Modifier.height(2.dp))
                CurrentVersionHeader(state)
            }
            item { PrimaryUpdateCard(state, onCheck, onDownload, onRetry, onCancel, onInstall) }
            if (state.history.isNotEmpty()) {
                item { HistoryTitle() }
                val visible = state.history.take(VISIBLE_HISTORY)
                val older = state.history.drop(VISIBLE_HISTORY)
                items(visible.size, key = { state.history[it].version.versionCode }) { i ->
                    HistoryRow(visible[i], state, onExport, onDismissExportNotice) { rollbackHelpFor = it }
                }
                if (older.isNotEmpty()) {
                    item { OlderToggle(older.size, olderExpanded) { olderExpanded = !olderExpanded } }
                    if (olderExpanded) {
                        items(older.size, key = { older[it].version.versionCode }) { i ->
                            HistoryRow(older[i], state, onExport, onDismissExportNotice) { rollbackHelpFor = it }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
    rollbackHelpFor?.let { versionName ->
        RollbackHelpDialog(versionName) { rollbackHelpFor = null }
    }
}

@Composable
private fun CurrentVersionHeader(state: UpdateUiState) {
    val language = LocalAppLanguage.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                localized(language, "当前版本 ${BuildConfig.VERSION_NAME}", "Current version ${BuildConfig.VERSION_NAME}"),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "(${BuildConfig.VERSION_CODE})", // l10n-allow: version code
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        state.lastCheckedAtMs?.let {
            Text(
                localized(language, "上次检查 ", "Last checked ") + relativeCheckTime(it, System.currentTimeMillis(), language),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** Status dot + heading: the tone lives in the dot, the title keeps the body color. */
@Composable
private fun CardHeading(text: String, tone: StatusTone? = null) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (tone != null) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor(tone, dark)),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
    }
}

@Composable
private fun PrimaryUpdateCard(
    state: UpdateUiState,
    onCheck: () -> Unit,
    onDownload: (UpdateVersion) -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
) {
    val language = LocalAppLanguage.current
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val task = state.task
            when {
                // A restored or running download owns the card: it renders with no index at all.
                task != null -> TaskSection(task, state, state.taskIsSuperseded, onRetry, onCancel, onInstall)
                state.checking && !state.checkedOnce -> {
                    CardHeading(localized(language, "正在检查更新…", "Checking for updates…"))
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth().semantics {
                            contentDescription = localized(language, "正在检查更新", "Checking for updates")
                        },
                    )
                }
                state.latest?.eligibility == VersionEligibility.UPDATE -> {
                    val version = state.latest.version
                    CardHeading(
                        localized(language, "发现新版本 ${version.versionName}", "New version ${version.versionName} available"),
                        tone = StatusTone.WARN,
                    )
                    ReleaseSummary(version)
                    Button(
                        onClick = { onDownload(version) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(localized(language, "下载更新", "Download update")) }
                }
                state.latest?.eligibility == VersionEligibility.INCOMPATIBLE -> {
                    CardHeading(localized(language, "最新版本不兼容此设备", "The latest release is not compatible"), tone = StatusTone.BAD)
                    Text(
                        localized(
                            language,
                            "最新版本的包名、渠道、签名或系统版本要求与本机不符，已阻止安装。",
                            "The latest release's package, channel, signature, or Android requirement does not match this device, so installation is blocked.",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                state.checkedOnce -> {
                    CardHeading(localized(language, "已是最新版本", "You're up to date"), tone = StatusTone.GOOD)
                    Text(
                        localized(
                            language,
                            "内部测试渠道 · ${BuildConfig.VERSION_NAME} 就是最新。",
                            "Internal channel · ${BuildConfig.VERSION_NAME} is the latest.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.checkError != null -> Unit
                else -> CardHeading(localized(language, "正在检查更新…", "Checking for updates…"))
            }
            // The check banner sits under the card body so a failed refresh never hides a
            // restored download that is still perfectly installable.
            state.checkError?.let {
                ErrorBlock(it.localizedMessage(language), it.sanitizedDiagnostic())
                if (task == null) {
                    Button(onClick = onCheck, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(localized(language, "重新检查", "Check again"))
                    }
                } else {
                    OutlinedButton(onClick = onCheck, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(localized(language, "重新检查版本", "Check versions again"))
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskSection(
    task: UpdateTask,
    state: UpdateUiState,
    superseded: Boolean,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
) {
    val language = LocalAppLanguage.current
    val version = task.version
    CardHeading(
        when {
            superseded -> localized(language, "已有更新版本", "A newer release is available")
            else -> when (task.phase) {
                DownloadPhase.INSTALLABLE -> localized(language, "更新已就绪 ${version.versionName}", "Update ${version.versionName} is ready")
                DownloadPhase.FAILED -> localized(language, "更新未完成 ${version.versionName}", "Update ${version.versionName} did not finish")
                DownloadPhase.DOWNLOADING -> localized(language, "正在下载 ${version.versionName}", "Downloading ${version.versionName}")
                else -> localized(language, "正在准备 ${version.versionName}", "Preparing ${version.versionName}")
            }
        },
        tone = if (superseded) StatusTone.WARN else when (task.phase) {
            DownloadPhase.INSTALLABLE -> StatusTone.GOOD
            DownloadPhase.FAILED -> StatusTone.BAD
            else -> null
        },
    )
    if (task.phase == DownloadPhase.INSTALLABLE || task.phase == DownloadPhase.FAILED || superseded) {
        ReleaseSummary(version, notes = task.phase == DownloadPhase.INSTALLABLE && !superseded)
    }

    val inFlight = !superseded && task.phase in setOf(
        DownloadPhase.ENQUEUING, DownloadPhase.WAITING, DownloadPhase.PAUSED,
        DownloadPhase.DOWNLOADING, DownloadPhase.VERIFYING, DownloadPhase.CANCELLING, DownloadPhase.DOWNLOADED,
    )
    if (inFlight) {
        // One condensed line: bytes on the left, percentage on the right, bar underneath.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                if (task.phase == DownloadPhase.DOWNLOADING && task.totalBytes > 0) {
                    "${formatBytes(task.downloadedBytes)} / ${formatBytes(task.totalBytes)}" // l10n-allow: byte counters
                } else phaseAnnouncement(task, language),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // Announce phase changes, not percentages: per-percent TalkBack is pure noise.
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            task.percent?.takeIf { task.phase == DownloadPhase.DOWNLOADING }?.let {
                Text(
                    "$it%", // l10n-allow: percentage
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        val percent = task.percent
        if (percent == null || task.phase != DownloadPhase.DOWNLOADING) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
        }
        Text(
            localized(language, "可离开本页，下载会在后台继续；完成后回到这里安装。", "You can leave this page — the download continues in the background. Come back here to install."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    } else if (!superseded && task.phase == DownloadPhase.INSTALLABLE) {
        Text(
            phaseAnnouncement(task, language),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    } else if (superseded) {
        Text(
            localized(language, "此下载不再是最新版，已阻止安装。", "This download is no longer the latest release, so installation is blocked."),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }

    val visibleError = if (superseded) {
        com.hermes.client.data.error.AppError(com.hermes.client.data.error.AppErrorCode.UPDATE_SUPERSEDED, retryable = false, stage = "update_superseded")
    } else state.taskError
    visibleError?.let { error ->
        val detail = task.reason?.takeIf { task.phase == DownloadPhase.FAILED }?.let { downloadFailureText(it).resolve(language) }
        ErrorBlock(error.localizedMessage(language), error.sanitizedDiagnostic(), detail)
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            superseded -> OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(localized(language, "删除旧下载", "Delete old download"))
            }
            else -> when (task.phase) {
                DownloadPhase.INSTALLABLE -> {
                    Button(onClick = onInstall, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(localized(language, "安装更新", "Install update"))
                    }
                    OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(localized(language, "删除下载", "Delete download"))
                    }
                }
                DownloadPhase.FAILED -> {
                    Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(localized(language, "重试下载", "Retry download"))
                    }
                    OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(localized(language, "取消更新", "Cancel update"))
                    }
                }
                DownloadPhase.ENQUEUING, DownloadPhase.CANCELLING -> Unit
                else -> OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(localized(language, "取消下载", "Cancel download"))
                }
            }
        }
    }

    if (task.phase == DownloadPhase.INSTALLABLE && !superseded) {
        Text(
            localized(
                language,
                "安装仍需你在系统安装器中确认；若提示未知来源权限，请授权后返回本页再次点击安装。",
                "Android still asks you to confirm the installation. If it requests unknown-source permission, grant it, come back, and tap install again.",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HistoryTitle() {
    val language = LocalAppLanguage.current
    Text(
        localized(language, "版本记录", "Version record"),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 6.dp).semantics { heading() },
    )
}

/**
 * One release in the record: summary line collapsed, tap to expand the full notes. Rows whose
 * APK survived retention offer the rollback export; the current release is badged.
 */
@Composable
private fun HistoryRow(
    row: UpdateRow,
    state: UpdateUiState,
    onExport: (UpdateVersion) -> Unit,
    onDismissExportNotice: () -> Unit,
    onRollbackHelp: (String) -> Unit,
) {
    val language = LocalAppLanguage.current
    var expanded by rememberSaveable(row.version.versionCode) { mutableStateOf(false) }
    val isCurrent = row.eligibility == VersionEligibility.CURRENT
    val hasApk = row.version.versionCode in state.apkOnDisk
    Card(
        Modifier
            .fillMaxWidth()
            .clickable { if (expanded) onDismissExportNotice(); expanded = !expanded }
            .semantics {
                contentDescription = localized(
                    language,
                    "版本 ${row.version.versionName}" + if (expanded) "，已展开" else "，点击展开更新内容",
                    "Version ${row.version.versionName}" + if (expanded) ", expanded" else ", tap to expand release notes",
                )
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.version.versionName, // l10n-allow: version data
                    style = MaterialTheme.typography.titleSmall,
                )
                if (isCurrent) {
                    Spacer(Modifier.width(8.dp))
                    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
                    Surface(shape = CircleShape, color = statusColor(StatusTone.GOOD, dark).copy(alpha = 0.12f)) {
                        Text(
                            localized(language, "当前", "Current"),
                            Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor(StatusTone.GOOD, dark),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    relativeCheckTime(parseInstantMs(row.version.publishedAt), System.currentTimeMillis(), language),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            val notes = row.version.releaseNotes
            if (!expanded) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        notes.firstOrNull() ?: localized(language, "暂无说明", "No release notes"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (notes.size > 1) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            localized(language, "共 ${notes.size} 条", "${notes.size} notes"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            } else {
                notes.forEach {
                    Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) // l10n-allow: server release-note data
                }
                if (notes.isEmpty()) {
                    Text(localized(language, "暂无说明", "No release notes"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    eligibilityLabel(row.eligibility, language) + " · " + formatBytes(row.version.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (hasApk && !isCurrent && row.eligibility == VersionEligibility.OLD) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { onExport(row.version) }, modifier = Modifier.heightIn(min = 40.dp)) {
                            Text(localized(language, "导出安装包", "Export APK"))
                        }
                        TextButton(onClick = { onRollbackHelp(row.version.versionName) }, modifier = Modifier.heightIn(min = 40.dp)) {
                            Text(localized(language, "如何回滚？", "How to roll back?"))
                        }
                    }
                }
                state.exportNotice?.takeIf { it.first == row.version.versionCode }?.let { (_, result) ->
                    Text(
                        when (result) {
                            ExportResult.SavedToDownloads -> localized(language, "已导出到系统下载目录。", "Exported to the device's Downloads folder.")
                            ExportResult.ShareSheetOpened -> localized(language, "已打开分享面板，请选择保存位置。", "Share sheet opened — choose where to save the file.")
                            is ExportResult.Failure -> localized(language, "导出失败：安装包校验未通过或无法写入，请重试（HR-UPDATE-001）。", "Export failed: the package failed verification or could not be written. Retry (HR-UPDATE-001).")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (result is ExportResult.Failure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun OlderToggle(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val language = LocalAppLanguage.current
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(CircleShape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (expanded) localized(language, "收起更早版本 ▴", "Hide older versions ▴")
            else localized(language, "更早版本（$count）▾", "Older versions ($count) ▾"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun RollbackHelpDialog(versionName: String, onDismiss: () -> Unit) {
    val language = LocalAppLanguage.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(language, "回滚到 $versionName", "Roll back to $versionName")) },
        text = {
            Text(
                localized(
                    language,
                    "Android 不允许直接安装比当前更旧的版本，回滚需要四步：\n\n" +
                        "1. 点「导出安装包」，把 $versionName 保存到系统下载目录；\n" +
                        "2. 卸载当前版本（会清除本机的连接配置，会话数据保存在服务器上、不受影响）；\n" +
                        "3. 在文件管理器的下载目录中点开导出的安装包完成安装；\n" +
                        "4. 打开 App 重新填写 Relay 地址和 App Token。\n\n" +
                        "如果是版本本身有问题，通常更快的做法是联系维护者重新发布一个修复版，所有设备都能直接升级过去。",
                    "Android does not allow installing a version older than the current one. Rolling back takes four steps:\n\n" +
                        "1. Tap “Export APK” to save $versionName into the device's Downloads folder.\n" +
                        "2. Uninstall the current app (this clears the local connection settings; conversations live on the server and are unaffected).\n" +
                        "3. Open the exported APK from the Downloads folder in your file manager and install it.\n" +
                        "4. Launch the app and re-enter the Relay URL and App Token.\n\n" +
                        "If the release itself is broken, it is usually faster to ask the maintainer to publish a fixed build that every device can update to normally.",
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(localized(language, "知道了", "Got it")) }
        },
    )
}

@Composable
private fun ReleaseSummary(version: UpdateVersion, notes: Boolean = true) {
    val language = LocalAppLanguage.current
    val published = relativeCheckTime(parseInstantMs(version.publishedAt), System.currentTimeMillis(), language)
    Text(
        localized(language, "$published 发布 · ", "Published $published · ") + formatBytes(version.sizeBytes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (!notes) return
    val items = version.releaseNotes
    var showAll by rememberSaveable(version.versionCode) { mutableStateOf(false) }
    if (items.isEmpty()) {
        Text(localized(language, "暂无说明", "No release notes"), style = MaterialTheme.typography.bodyMedium)
    } else {
        val visible = if (showAll) items else items.take(VISIBLE_NOTES)
        visible.forEach {
            Text("• $it", style = MaterialTheme.typography.bodyMedium) // l10n-allow: server release-note data
        }
        if (items.size > VISIBLE_NOTES && !showAll) {
            TextButton(onClick = { showAll = true }, modifier = Modifier.heightIn(min = 40.dp)) {
                Text(localized(language, "展开全部（${items.size} 条）", "Show all (${items.size})"))
            }
        }
    }
}

@Composable
private fun ErrorBlock(summary: String, diagnostic: String, detail: String? = null) {
    val language = LocalAppLanguage.current
    var showDetails by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        TextButton(onClick = { showDetails = !showDetails }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(
                if (showDetails) localized(language, "隐藏详情", "Hide details")
                else localized(language, "查看详情", "View details"),
            )
        }
        if (showDetails) {
            Text(
                diagnostic, // l10n-allow: redacted diagnostic payload
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun phaseAnnouncement(task: UpdateTask, language: AppLanguage): String = when (task.phase) {
    DownloadPhase.ENQUEUING -> localized(language, "正在开始下载…", "Starting the download…")
    DownloadPhase.WAITING -> localized(language, "已排队，等待开始下载。", "Queued and waiting to start.")
    DownloadPhase.PAUSED -> task.reason?.let { downloadPauseText(it).resolve(language) }
        ?: localized(language, "下载已暂停。", "The download is paused.")
    DownloadPhase.DOWNLOADING -> localized(language, "正在下载…", "Downloading…")
    DownloadPhase.DOWNLOADED, DownloadPhase.VERIFYING -> localized(language, "正在校验安装包…", "Verifying the package…")
    DownloadPhase.CANCELLING -> localized(language, "正在取消并清理下载…", "Cancelling and cleaning up the download…")
    DownloadPhase.INSTALLABLE -> localized(language, "已下载并校验通过，可以安装。", "Downloaded and verified. Ready to install.")
    DownloadPhase.FAILED -> localized(language, "更新未完成。", "The update did not finish.")
    DownloadPhase.IDLE -> ""
}

private fun eligibilityLabel(value: VersionEligibility, language: AppLanguage) = when (value) {
    VersionEligibility.CURRENT -> localized(language, "当前版本", "Current version")
    VersionEligibility.OLD -> localized(language, "旧版本 · 不支持覆盖降级", "Older version · In-place downgrade is not supported")
    VersionEligibility.INCOMPATIBLE -> localized(language, "包名、渠道、签名或系统版本不兼容", "Incompatible package, channel, signature, or Android version")
    VersionEligibility.UPDATE -> localized(language, "可更新", "Update available")
}

/**
 * Localized relative timestamp for the update page: "刚刚 / 3 分钟前 / 14:32 / 昨天 20:00 /
 * 2026-08-30 20:00". Pure so it is unit-testable.
 */
internal fun relativeCheckTime(epochMs: Long, nowMs: Long, language: AppLanguage): String {
    if (epochMs <= 0) return ""
    val zone = ZoneId.systemDefault()
    val minutes = (nowMs - epochMs) / 60_000
    val time = Instant.ofEpochMilli(epochMs).atZone(zone)
    val now = Instant.ofEpochMilli(nowMs).atZone(zone)
    val hm = DateTimeFormatter.ofPattern("HH:mm").format(time)
    return when {
        minutes in 0..0 -> localized(language, "刚刚", "just now")
        minutes in 1..59 -> localized(language, "$minutes 分钟前", "${minutes}m ago")
        time.toLocalDate() == now.toLocalDate() -> localized(language, "今天 $hm", "Today $hm")
        time.toLocalDate() == now.toLocalDate().minusDays(1) -> localized(language, "昨天 $hm", "Yesterday $hm")
        else -> DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(time)
    }
}

internal fun parseInstantMs(value: String): Long =
    runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)

private fun formatBytes(value: Long) =
    if (value >= 1024 * 1024) "%.1f MB".format(value / 1024.0 / 1024.0) else "${value / 1024} KB"
