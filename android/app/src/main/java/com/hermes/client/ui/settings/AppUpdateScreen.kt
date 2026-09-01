package com.hermes.client.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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
 * The update page shows exactly one recommended release — the manifest's latest — plus whatever
 * download is currently in flight. Older releases stay readable in a collapsed history but are
 * never installable: offering an arbitrary intermediate build is how a tester ends up on a version
 * nobody is tracking. See docs/DESIGN.md §5.9 and docs/APP_UPDATE.md.
 */
@Composable
fun AppUpdateScreen(onBack: () -> Unit, viewModel: UpdateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Page entry: check the public index and, independently, restore any persisted download.
    LaunchedEffect(Unit) { viewModel.onOpen() }
    AppUpdateContent(
        state = state,
        onBack = onBack,
        onCheck = viewModel::check,
        onDownload = viewModel::download,
        onRetry = viewModel::retry,
        onCancel = viewModel::cancel,
        onInstall = viewModel::install,
    )
}

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
) {
    val language = LocalAppLanguage.current
    // Collapsed by default: the page recommends one release, history is opt-in reading.
    var historyExpanded by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            HermesTopBar(
                title = localized(language, "检查更新", "App updates"),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, localized(language, "返回", "Back"))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Spacer(Modifier.height(4.dp))
                CurrentVersionHeader(state, onCheck)
            }
            item { PrimaryUpdateCard(state, onCheck, onDownload, onRetry, onCancel, onInstall) }
            if (state.history.isNotEmpty()) {
                item { HistoryHeader(state.history.size, historyExpanded) { historyExpanded = !historyExpanded } }
                if (historyExpanded) {
                    items(state.history, key = { it.version.versionCode }) { row -> HistoryCard(row) }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun CurrentVersionHeader(state: UpdateUiState, onCheck: () -> Unit) {
    val language = LocalAppLanguage.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            localized(
                language,
                "当前版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                "Current version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.lastCheckedAtMs?.let {
            Text(
                localized(language, "上次检查 ${formatTime(it)}", "Last checked ${formatTime(it)}"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.checkError == null) {
            TextButton(
                onClick = onCheck,
                enabled = !state.checking,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    if (state.checking) localized(language, "正在检查…", "Checking…")
                    else localized(language, "重新检查", "Check again"),
                )
            }
        }
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
                    CardHeading(localized(language, "发现新版本 ${version.versionName}", "New version ${version.versionName} available"))
                    ReleaseSummary(version)
                    Button(
                        onClick = { onDownload(version) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(localized(language, "下载更新", "Download update")) }
                }
                state.latest?.eligibility == VersionEligibility.INCOMPATIBLE -> {
                    CardHeading(localized(language, "最新版本不兼容此设备", "The latest release is not compatible"))
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
                            "当前 ${BuildConfig.VERSION_NAME} 已是内部测试渠道的最新版本。",
                            "Version ${BuildConfig.VERSION_NAME} is the latest release on the internal channel.",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
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
            else -> localized(language, "正在准备 ${version.versionName}", "Preparing ${version.versionName}")
            }
        },
        tone = if (superseded) null else when (task.phase) {
            DownloadPhase.INSTALLABLE -> StatusTone.GOOD
            DownloadPhase.FAILED -> StatusTone.BAD
            else -> null
        },
    )
    ReleaseSummary(version)

    // Announce phase changes, not percentages: a per-percent live region turns TalkBack into noise.
    Text(
        if (superseded) localized(language, "此下载不再是最新版，已阻止安装。", "This download is no longer the latest release, so installation is blocked.")
        else phaseAnnouncement(task, language),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    if (!superseded && task.phase == DownloadPhase.DOWNLOADING && task.percent != null) {
        Text(
            localized(language, "下载进度 ${task.percent}%", "Download progress ${task.percent}%"),
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    if (task.phase in setOf(DownloadPhase.ENQUEUING, DownloadPhase.WAITING, DownloadPhase.DOWNLOADING, DownloadPhase.VERIFYING, DownloadPhase.CANCELLING, DownloadPhase.DOWNLOADED)) {
        val percent = task.percent
        if (percent == null || task.phase != DownloadPhase.DOWNLOADING) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
        }
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
private fun HistoryHeader(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val language = LocalAppLanguage.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClick = onToggle)
                .semantics {
                    heading()
                    contentDescription = if (expanded) {
                        localized(language, "历史版本，共 $count 个，已展开", "Version history, $count entries, expanded")
                    } else {
                        localized(language, "历史版本，共 $count 个，已折叠", "Version history, $count entries, collapsed")
                    }
                },
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                localized(language, "历史版本（$count）", "Version history ($count)"),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        Text(
            localized(language, "仅可安装最新版本；历史版本只作记录。", "Only the latest release can be installed; history is read-only."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HistoryCard(row: UpdateRow) {
    val language = LocalAppLanguage.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "${row.version.versionName} (${row.version.versionCode})", // l10n-allow: version data
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                eligibilityLabel(row.eligibility, language),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ReleaseSummary(row.version, notes = false)
        }
    }
}

@Composable
private fun CardHeading(text: String, tone: StatusTone? = null) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = tone?.let { statusColor(it, dark) } ?: Color.Unspecified,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun ReleaseSummary(version: UpdateVersion, notes: Boolean = true) {
    val language = LocalAppLanguage.current
    Text(
        localized(
            language,
            "发布 ${formatDate(version.publishedAt)} · ${formatBytes(version.sizeBytes)}",
            "Published ${formatDate(version.publishedAt)} · ${formatBytes(version.sizeBytes)}",
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (!notes) return
    Text(
        localized(language, "更新内容", "Release notes"),
        style = MaterialTheme.typography.labelLarge,
    )
    if (version.releaseNotes.isEmpty()) {
        Text(localized(language, "暂无说明", "No release notes"), style = MaterialTheme.typography.bodyMedium)
    } else {
        version.releaseNotes.forEach {
            Text("• $it", style = MaterialTheme.typography.bodyMedium) // l10n-allow: server release-note data
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

private fun formatDate(value: String) = runCatching {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value)

private fun formatTime(millis: Long) = runCatching {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))
}.getOrDefault("")

private fun formatBytes(value: Long) =
    if (value >= 1024 * 1024) "%.1f MB".format(value / 1024.0 / 1024.0) else "${value / 1024} KB"
