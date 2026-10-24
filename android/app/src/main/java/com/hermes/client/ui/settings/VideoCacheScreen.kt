package com.hermes.client.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.videoCacheLowStorage
import com.hermes.client.data.video.*
import com.hermes.client.ui.components.HermesTopBar
import com.hermes.client.ui.components.SegmentedCapsule
import com.hermes.client.ui.localization.*

@Composable
fun VideoCacheScreen(onBack: () -> Unit, vm: VideoCacheViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()) {
    val state by vm.cache.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, vm) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) vm.refresh()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    VideoCacheContent(state, busy, error, onBack, vm::select, vm::clear, vm::retry)
}

@Composable
internal fun VideoCacheContent(state: VideoCacheStats, busy: Boolean, error: AppError?, onBack: () -> Unit,
    onSelect: (VideoCacheCapacity) -> Unit, onClear: () -> Unit, onRetry: () -> Unit) {
    val language = LocalAppLanguage.current
    var confirmClear by remember { mutableStateOf(false) }
    val selected = VideoCacheCapacity.entries.firstOrNull { it.bytes == state.maxBytes } ?: VideoCacheCapacity.DEFAULT
    Scaffold(topBar = { HermesTopBar(title = localized(language, "视频缓存", "Video cache"), navigationIcon = {
        IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, localized(language, "返回", "Back")) }
    }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(localized(language, "已用空间", "Storage used"), style = MaterialTheme.typography.titleMedium)
            Text(if (state.loaded) "${cacheSize(state.usedBytes)} / ${selected.gib}GB" else localized(language, "正在读取…", "Loading…"), // l10n-allow: language-neutral storage units.
                style = MaterialTheme.typography.headlineSmall)
            LinearProgressIndicator(progress = { (state.usedBytes.toDouble() / state.maxBytes.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text(localized(language, "所有视频共享此上限，不会预先占满空间。完整下载的文件单独保存。", "All videos share this limit. Space is used only as needed. Downloaded files are stored separately."),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(localized(language, "缓存上限", "Cache limit"), style = MaterialTheme.typography.titleSmall)
            SegmentedCapsule(VideoCacheCapacity.entries, selected, onSelect = { if (state.loaded && !busy) onSelect(it) }, label = { "${it.gib}GB" }) // l10n-allow: language-neutral capacity choices.
            Text(localized(language, "超出上限时优先清理最久未使用的片段；30 天未使用的片段也会自动清理。手机空间不足时，会提前回收缓存。", "Oldest unused segments are removed when the limit is reached. Segments unused for 30 days also expire. Cache is reclaimed earlier when device storage is low."),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.lowSpace) Text(videoCacheLowStorage().localizedMessage(language), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.clearPending) Text(localized(language, "未使用的缓存已清理，正在播放的少量片段将在停止播放后清理。", "Unused cache has been cleared. A small amount in active playback will be removed when playback stops."),
                style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { confirmClear = true }, enabled = state.loaded && !busy && state.usedBytes > 0 && !state.clearPending) {
                Text(localized(language, "清理预览缓存", "Clear preview cache"))
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let {
                com.hermes.client.ui.components.ErrorState(it, onRetry = onRetry)
                var details by remember(it) { mutableStateOf(false) }
                TextButton(onClick = { details = !details }) { Text(localized(language, "查看详情", "View details")) }
                if (details) {
                    Text(it.sanitizedDiagnostic(), style = MaterialTheme.typography.bodySmall)
                    val context = androidx.compose.ui.platform.LocalContext.current
                    TextButton(onClick = { context.getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("Hermes diagnostics", it.sanitizedDiagnostic())) }) {
                        Text(localized(language, "复制诊断", "Copy diagnostics"))
                    }
                }
            }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text(localized(language, "清理预览缓存？", "Clear preview cache?")) },
        text = { Text(localized(language, "之后回看可能需要重新缓冲。完整下载的文件不会删除，正在使用的片段会在停止播放后清理。", "Watching again may require buffering. Downloaded files stay intact. Active segments are cleared after playback stops.")) },
        confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }) { Text(localized(language, "清理", "Clear")) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(localized(language, "取消", "Cancel")) } })
}

internal fun cacheSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.2fGB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(java.util.Locale.ROOT, "%.1fMB", bytes / (1024.0 * 1024))
    else -> "${bytes.coerceAtLeast(0) / 1024}KB"
}
