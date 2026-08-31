package com.hermes.client.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.BuildConfig
import com.hermes.client.ui.components.HermesTopBar
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.localization.localizedMessage
import com.hermes.client.update.DownloadPhase
import com.hermes.client.update.UpdateRow
import com.hermes.client.update.UpdateUiState
import com.hermes.client.update.UpdateViewModel
import com.hermes.client.update.VersionEligibility
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun AppUpdateScreen(onBack: () -> Unit, viewModel: UpdateViewModel = hiltViewModel()) {
    val language = LocalAppLanguage.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { HermesTopBar(title = localized(language,"检查更新","App updates"),navigationIcon = { IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Rounded.ArrowBack,localized(language,"返回","Back"))} }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {
                Spacer(Modifier.height(4.dp))
                Text(localized(language,"当前版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})","Current version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"))
                Text(localized(language,"最新版本：${state.latestVersionName ?: "—"} (${state.latestVersionCode ?: "—"})","Latest version: ${state.latestVersionName ?: "—"} (${state.latestVersionCode ?: "—"})"))
                Button(onClick=viewModel::refresh,enabled=!state.loading){Text(if(state.loading)localized(language,"正在检查…","Checking…") else localized(language,"手动检查更新","Check now"))}
                state.error?.let { Text(it.localizedMessage(language),color=MaterialTheme.colorScheme.error) }
            }
            items(state.rows,key={it.version.versionCode}) { row -> VersionCard(row,state,viewModel) }
        }
    }
}

@Composable
private fun VersionCard(row: UpdateRow, state: UpdateUiState, viewModel: UpdateViewModel) {
    val language=LocalAppLanguage.current; val version=row.version; val active=state.activeVersionCode==version.versionCode
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text("${version.versionName} (${version.versionCode})",style=MaterialTheme.typography.titleMedium) // l10n-allow: version data
        Text(eligibilityLabel(row.eligibility,language))
        Text(localized(language,"发布：${formatDate(version.publishedAt)} · ${formatBytes(version.sizeBytes)}","Published: ${formatDate(version.publishedAt)} · ${formatBytes(version.sizeBytes)}"))
        Text(localized(language,"更新内容","Release notes"),style=MaterialTheme.typography.labelLarge)
        if(version.releaseNotes.isEmpty()) Text(localized(language,"暂无说明","No release notes")) else version.releaseNotes.forEach { Text("• $it") } // l10n-allow: server release-note data
        if(row.eligibility==VersionEligibility.UPDATE) when {
            active&&state.phase==DownloadPhase.INSTALLABLE -> { Button(onClick=viewModel::install){Text(localized(language,"由系统安装器安装","Install with system installer"))};Text(localized(language,"如需授权未知来源，返回此页后再次点击安装。","If permission is requested, return here and tap install again.")) }
            active&&state.phase in setOf(DownloadPhase.WAITING,DownloadPhase.DOWNLOADING,DownloadPhase.VERIFYING,DownloadPhase.DOWNLOADED) -> {
                if (state.percent == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
                Text(phaseLabel(state.phase,state.percent,language))
            }
            else -> Button(
                onClick={viewModel.download(version)},
                enabled = state.phase !in setOf(DownloadPhase.WAITING, DownloadPhase.DOWNLOADING, DownloadPhase.VERIFYING, DownloadPhase.DOWNLOADED),
            ){Text(if(active&&state.phase==DownloadPhase.FAILED)localized(language,"重试下载","Retry download") else localized(language,"下载并安装","Download and install"))}
        }
    } }
}

private fun eligibilityLabel(value: VersionEligibility, language: AppLanguage) = when (value) {
    VersionEligibility.CURRENT -> localized(language, "当前版本", "Current version")
    VersionEligibility.OLD -> localized(language, "旧版本 · 不支持覆盖降级", "Older version · In-place downgrade is not supported")
    VersionEligibility.INCOMPATIBLE -> localized(language, "包名、渠道、签名或系统版本不兼容", "Incompatible package, channel, signature, or Android version")
    VersionEligibility.UPDATE -> localized(language, "可更新", "Update available")
}

private fun phaseLabel(value: DownloadPhase, percent: Int?, language: AppLanguage) = when (value) {
    DownloadPhase.WAITING -> localized(language, "等待下载", "Waiting")
    DownloadPhase.DOWNLOADING -> percent?.let { localized(language, "下载中 $it%", "Downloading $it%") }
        ?: localized(language, "下载中", "Downloading")
    DownloadPhase.VERIFYING, DownloadPhase.DOWNLOADED -> localized(language, "正在校验 APK", "Verifying APK")
    else -> ""
}
private fun formatDate(value:String)=runCatching{DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))}.getOrDefault(value)
private fun formatBytes(value:Long)=if(value>=1024*1024) "%.1f MB".format(value/1024.0/1024.0) else "${value/1024} KB"
