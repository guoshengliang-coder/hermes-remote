package com.hermes.client.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.videoPlaybackError
import com.hermes.client.data.video.VideoPlayback
import com.hermes.client.ui.localization.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** No external URL/intent: Media3's loader reads authenticated, bounded ranges on demand. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPlayerDialog(
    name: String,
    prepare: suspend () -> VideoPlayback,
    download: () -> Unit,
    onDismiss: () -> Unit,
    downloaded: Boolean = false,
    openExternally: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var wantsPlay by remember { mutableStateOf(true) }
    var source by remember { mutableStateOf<VideoPlayback?>(null) }
    var error by remember { mutableStateOf<AppError?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var buffering by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0) }
    var duration by remember { mutableLongStateOf(0) }
    var seeking by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(foreground, retry) {
        if (foreground && source == null) {
            error = null
            try { source = prepare() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = videoPlaybackError(failure); buffering = false; wantsPlay = false }
        }
    }
    val player = remember(source, foreground, wantsPlay, retry) {
        source?.takeIf { foreground && wantsPlay && error == null }?.let { video ->
            ExoPlayer.Builder(context).setLoadControl(DefaultLoadControl.Builder()
                .setBufferDurationsMs(2_000, 8_000, 750, 1_500).build()).build().apply {
                setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
                setHandleAudioBecomingNoisy(true)
                setMediaSource(video.mediaSource(context))
                seekTo(video.positionMs)
                playWhenReady = true
            }
        }
    }
    DisposableEffect(player) {
        val video = source
        val lease = if (player != null) video?.lease() else null
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) {
                    video?.positionMs = 0; position = 0; wantsPlay = false
                }
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady) {
                    video?.positionMs = player?.currentPosition?.coerceAtLeast(0) ?: position
                    wantsPlay = false
                }
            }
            override fun onPlayerError(failure: PlaybackException) {
                video?.positionMs = player?.currentPosition ?: position
                error = videoPlaybackError(failure); buffering = false
                wantsPlay = false
            }
        }
        player?.addListener(listener)
        player?.prepare()
        onDispose {
            if (player != null) {
                if (player.playbackState != Player.STATE_ENDED) video?.positionMs = player.currentPosition.coerceAtLeast(0)
                player.removeListener(listener)
                player.release()
            }
            lease?.close()
        }
    }
    DisposableEffect(lifecycle, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                source?.positionMs = player?.currentPosition?.coerceAtLeast(0) ?: position
                foreground = false; wantsPlay = false
            } else if (event == Lifecycle.Event.ON_START) foreground = true
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(player) {
        while (player != null) {
            if (player.playbackState != Player.STATE_ENDED) {
                position = player.currentPosition.coerceAtLeast(0)
                source?.positionMs = position
            }
            duration = player.duration.coerceAtLeast(0)
            delay(250)
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(name, Modifier.weight(1f), maxLines = 2, style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onDismiss) { Text(localized(language, "关闭", "Close")) }
                }
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    if (player != null) AndroidView(
                        factory = { PlayerView(it).apply { useController = false; keepScreenOn = true } },
                        update = { it.player = player },
                        onRelease = { it.player = null },
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (buffering && wantsPlay && error == null) CircularProgressIndicator()
                    if (!wantsPlay && error == null) Text(localized(language, "已暂停，点继续播放", "Paused. Tap Play to continue."),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                error?.let { failure ->
                    Text(failure.localizedMessage(language), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Hermes diagnostics", failure.sanitizedDiagnostic()))
                    }) { Text(localized(language, "复制诊断", "Copy diagnostics")) }
                    if (failure.retryable) TextButton(onClick = {
                        error = null; buffering = true; wantsPlay = true; retry++
                    }) { Text(localized(language, "重试", "Retry")) }
                }
                Slider(
                    value = seeking ?: position.toFloat().coerceAtMost(duration.toFloat()),
                    onValueChange = { seeking = it },
                    onValueChangeFinished = {
                        val target = seeking?.toLong() ?: position
                        source?.positionMs = target; position = target
                        player?.seekTo(target); seeking = null
                    },
                    valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = duration > 0 && error == null,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("${videoTime(position)} / ${videoTime(duration)}", style = MaterialTheme.typography.labelMedium, // l10n-allow: language-neutral elapsed / total duration digits.
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(enabled = source != null && error == null, onClick = {
                        source?.positionMs = player?.currentPosition?.coerceAtLeast(0) ?: position
                        wantsPlay = !wantsPlay
                        if (wantsPlay) buffering = true
                    }) { Text(if (wantsPlay) localized(language, "暂停", "Pause") else localized(language, "继续播放", "Play")) }
                    TextButton(onClick = download, enabled = !downloaded) {
                        Text(if (downloaded) localized(language, "已下载", "Downloaded") else localized(language, "下载", "Download"))
                    }
                }
                openExternally?.let { open ->
                    TextButton(onClick = open) { Text(localized(language, "其他播放器", "Other player")) }
                }
            }
        }
    }
}

private fun videoTime(ms: Long): String = java.lang.String.format(java.util.Locale.ROOT,
    "%d:%02d", ms / 60_000, ms / 1_000 % 60)


/** The caller supplies only a verified complete file; the player cache is never exported. */
internal fun openCompletedVideo(context: android.content.Context, file: java.io.File, mimeType: String?,
    onFailure: (AppError) -> Unit) {
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType ?: "video/*")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    } catch (error: Exception) {
        onFailure(AppError(com.hermes.client.data.error.AppErrorCode.ATTACHMENT_NO_VIEWER,
            retryable = false, technicalCause = error::class.java.simpleName, stage = "video_external_open"))
    }
}
