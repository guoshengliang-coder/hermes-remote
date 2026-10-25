package com.hermes.client.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.client.data.error.AppError
import com.hermes.client.data.video.VideoPrecacheState
import com.hermes.client.ui.localization.*
import kotlinx.coroutines.delay

/** Media chrome uses the same black/white treatment in both app themes, like ImageViewer. */
@Composable
internal fun VideoPlayerContent(
    name: String,
    state: VideoPlayerState,
    error: AppError? = null,
    fullscreen: Boolean = false,
    canPlay: Boolean = true,
    downloaded: Boolean = false,
    download: () -> Unit = {},
    openExternally: (() -> Unit)? = null,
    onClose: () -> Unit = {},
    onFullscreen: () -> Unit = {},
    onTogglePlay: () -> Unit = {},
    onSeek: (Long) -> Unit = {},
    onRetry: () -> Unit = {},
    onDiagnostics: () -> Unit = {},
    onSpeed: (Float) -> Unit = {},
    onBoost: (Boolean) -> Unit = {},
    holdSpeed: Float = 2f,
    onHoldSpeed: (Float) -> Unit = {},
    precache: VideoPrecacheState = VideoPrecacheState(),
    onCacheRetry: () -> Unit = {},
    onStatusDiagnostics: (AppError) -> Unit = {},
    video: @Composable () -> Unit = {},
) {
    val language = LocalAppLanguage.current
    var controls by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    var holdMenu by remember { mutableStateOf(false) }
    val boost by rememberUpdatedState(onBoost)
    var seeking by remember { mutableStateOf<Float?>(null) }
    var interaction by remember { mutableIntStateOf(0) }
    val pinned = !state.wantsPlay || state.buffering || error != null || menu || speedMenu || holdMenu || seeking != null
    LaunchedEffect(pinned, interaction) {
        controls = true
        if (!pinned) { delay(3_000); controls = false }
    }
    val visible = controls || pinned
    val playLabel = when {
        state.ended -> localized(language, "重新播放", "Replay")
        state.wantsPlay -> localized(language, "暂停", "Pause")
        else -> localized(language, "继续播放", "Play")
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        video()
        Box(Modifier.fillMaxSize().testTag("video-touch-surface")
            .videoSpeedGesture(state.wantsPlay && !state.buffering && error == null,
                localized(language, "显示或隐藏播放控件", "Show or hide playback controls"),
                tap = { controls = !controls; if (controls) interaction++ }, hold = { boost(it) }))
        if (state.boosting) Text(
            localized(language, "${speedLabel(state.speed)} 加速播放", "${speedLabel(state.speed)} speed boost"),
            Modifier.align(Alignment.TopCenter).padding(top = 80.dp)
                .background(Color.Black.copy(alpha = .7f), MaterialTheme.shapes.small).padding(12.dp),
            color = Color.White, style = MaterialTheme.typography.labelLarge)
        if (visible) {
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(alpha = .68f))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                MediaButton(Icons.Rounded.Close, localized(language, "关闭", "Close"), onClick = onClose)
                Text(name, Modifier.weight(1f).padding(horizontal = 8.dp), color = Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Box {
                    MediaButton(Icons.Rounded.MoreVert, localized(language, "更多", "More"), onClick = { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false; interaction++ }) {
                        DropdownMenuItem(text = { Text(if (downloaded) localized(language, "已下载", "Downloaded") else localized(language, "下载", "Download")) },
                            enabled = !downloaded, onClick = { menu = false; interaction++; download() })
                        DropdownMenuItem(text = { Text(localized(language, "长按加速：${speedLabel(holdSpeed)}", "Hold speed: ${speedLabel(holdSpeed)}")) },
                            onClick = { menu = false; holdMenu = true; interaction++ })
                        openExternally?.let { open ->
                            DropdownMenuItem(text = { Text(localized(language, "其他播放器", "Other player")) },
                                onClick = { menu = false; open() })
                        }
                    }
                    DropdownMenu(expanded = holdMenu, onDismissRequest = { holdMenu = false; interaction++ }) {
                        VIDEO_HOLD_SPEEDS.forEach { speed ->
                            DropdownMenuItem(text = { Text(localized(language, "长按 ${speedLabel(speed)}", "Hold ${speedLabel(speed)}")) },
                                leadingIcon = { if (holdSpeed == speed) Icon(Icons.Rounded.Check, null) },
                                onClick = { onHoldSpeed(speed); holdMenu = false; interaction++ })
                        }
                    }
                }
            }
        }
        if (error != null) {
            Column(Modifier.align(Alignment.Center).padding(horizontal = 24.dp, vertical = 64.dp)
                .widthIn(max = 480.dp).background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
                .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(error.localizedMessage(language), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (error.retryable) Button(onClick = onRetry) { Text(localized(language, "重试", "Retry")) }
                    if (!downloaded) OutlinedButton(onClick = download) { Text(localized(language, "下载", "Download")) }
                    else openExternally?.let { open -> OutlinedButton(onClick = open) { Text(localized(language, "其他播放器", "Other player")) } }
                }
                TextButton(onClick = onDiagnostics) { Text(localized(language, "复制诊断", "Copy diagnostics")) }
            }
        } else {
            if (state.buffering) {
                Column(Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .65f), MaterialTheme.shapes.medium)
                    .padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 2.dp)
                    Text(if (state.hasFrame) localized(language, "正在缓冲…", "Buffering…") else localized(language, "正在加载视频…", "Loading video…"),
                        color = Color.White, style = MaterialTheme.typography.bodySmall)
                }
            } else if (visible) {
                MediaButton(if (state.ended) Icons.Rounded.Replay else if (state.wantsPlay) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    playLabel, Modifier.align(Alignment.Center).size(64.dp).background(Color.Black.copy(alpha = .58f), CircleShape),
                    enabled = canPlay, onClick = { interaction++; onTogglePlay() })
            }
            if (visible) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = .68f))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(horizontal = 16.dp, vertical = 4.dp)) {
                if (state.duration > 0) {
                    if (seeking != null) Text(videoTime(seeking!!.toLong()), Modifier.align(Alignment.CenterHorizontally),
                        color = Color.White, style = MaterialTheme.typography.labelLarge)
                    VideoTimeline(state, seeking,
                        onChange = { seeking = it; interaction++ },
                        onFinished = { seeking?.let { onSeek(it.toLong()) }; seeking = null; interaction++ })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    MediaButton(if (state.ended) Icons.Rounded.Replay else if (state.wantsPlay) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        playLabel, enabled = canPlay, onClick = { interaction++; onTogglePlay() })
                    Text(if (state.duration > 0) "${videoTime(seeking?.toLong() ?: state.position)} / ${videoTime(state.duration)}" else "—:—", // l10n-allow: language-neutral media time.
                        Modifier.weight(1f).padding(horizontal = 8.dp), color = Color.White, style = MaterialTheme.typography.labelMedium)
                    Box {
                        TextButton(onClick = { speedMenu = true; interaction++ }, enabled = canPlay,
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .semantics { contentDescription = localized(language, "播放倍速", "Playback speed") }) {
                            Text(speedLabel(state.speed), color = Color.White)
                        }
                        DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false; interaction++ },
                            modifier = Modifier.heightIn(max = 360.dp)) {
                            VIDEO_SPEEDS.forEach { speed ->
                                DropdownMenuItem(text = { Text(speedLabel(speed)) },
                                    leadingIcon = { if (state.speed == speed) Icon(Icons.Rounded.Check, null) },
                                    onClick = { onSpeed(speed); speedMenu = false; interaction++ })
                            }
                        }
                    }
                    MediaButton(if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                        if (fullscreen) localized(language, "退出全屏", "Exit fullscreen") else localized(language, "横屏全屏", "Fullscreen"),
                        onClick = { interaction++; onFullscreen() })
                }
                if (precache.size > 0) Text(
                    if (precache.cleared) localized(language, "本次观看已停止预缓存；重新打开视频可恢复", "Precaching stopped for this viewing; reopen to resume")
                    else localized(language, "预览缓存 ${precache.percent}% · ${cacheBytes(precache.bytes)} / ${cacheBytes(precache.size)}",
                        "Preview cache ${precache.percent}% · ${cacheBytes(precache.bytes)} / ${cacheBytes(precache.size)}"),
                    Modifier.testTag("video-cache-progress"), color = Color.White.copy(alpha = .75f), style = MaterialTheme.typography.labelSmall)
                val status = state.speedError ?: precache.error
                status?.let { failure ->
                    Text(failure.localizedMessage(language), color = Color.White, style = MaterialTheme.typography.bodySmall)
                    FlowRow {
                        if (failure.retryable) TextButton(onClick = onCacheRetry) { Text(localized(language, "重试缓存", "Retry caching"), color = Color.White) }
                        TextButton(onClick = { onStatusDiagnostics(failure) }) { Text(localized(language, "复制诊断", "Copy diagnostics"), color = Color.White) }
                    }
                }
            }
        }
    }
}

private fun cacheBytes(value: Long): String = if (value >= 1024L * 1024 * 1024)
    java.lang.String.format(java.util.Locale.ROOT, "%.2f GiB", value / (1024.0 * 1024 * 1024))
else java.lang.String.format(java.util.Locale.ROOT, "%.1f MiB", value / (1024.0 * 1024))

@Composable
private fun MediaButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick, modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp), enabled = enabled) {
        Icon(icon, label, tint = if (enabled) Color.White else Color.White.copy(alpha = .38f), modifier = Modifier.size(28.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoTimeline(state: VideoPlayerState, seeking: Float?, onChange: (Float) -> Unit, onFinished: () -> Unit) {
    val language = LocalAppLanguage.current
    val value = seeking ?: state.position.toFloat()
    Box(Modifier.fillMaxWidth().height(36.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().padding(horizontal = 6.dp).height(4.dp)) {
            val y = size.height / 2
            fun track(fraction: Float, color: Color) = drawLine(color, Offset(0f, y), Offset(size.width * fraction.coerceIn(0f, 1f), y), size.height, StrokeCap.Round)
            track(1f, Color.White.copy(alpha = .24f))
            track(state.buffered.toFloat() / state.duration.coerceAtLeast(1), Color.White.copy(alpha = .5f))
            track(value / state.duration.coerceAtLeast(1), Color.White)
        }
        Slider(value.coerceIn(0f, state.duration.toFloat()), onValueChange = onChange,
            onValueChangeFinished = onFinished, valueRange = 0f..state.duration.coerceAtLeast(1).toFloat(),
            modifier = Modifier.fillMaxWidth().testTag("video-timeline").semantics {
                contentDescription = localized(language, "播放进度", "Playback position")
            },
            thumb = { Box(Modifier.size(12.dp).background(Color.White, CircleShape)) }, track = { Spacer(Modifier.fillMaxWidth().height(4.dp)) })
    }
}
