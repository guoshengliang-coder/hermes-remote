package com.hermes.client.ui.chat

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.client.R
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.videoPlaybackError
import com.hermes.client.data.video.VideoPlayback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Authenticated bounded Range playback, with a persistent surface while loading is stopped. */
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
    val activity = context.videoActivity()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var wantsPlay by rememberSaveable { mutableStateOf(true) }
    var selectedSpeed by rememberSaveable { mutableFloatStateOf(1f) }
    val speedPreferences = remember { context.getSharedPreferences("video_player_preferences", Context.MODE_PRIVATE) }
    var holdSpeed by remember { mutableFloatStateOf(runCatching { speedPreferences.getFloat("hold_speed", 2f) }.getOrDefault(2f).takeIf { it in VIDEO_HOLD_SPEEDS } ?: 2f) }
    var wasOpened by rememberSaveable { mutableStateOf(false) }
    var knownDuration by rememberSaveable { mutableLongStateOf(0) }
    val frameKey = rememberSaveable { java.util.UUID.randomUUID().toString() }
    val frameMemory: VideoFrameMemory = viewModel(key = "video-frame:$frameKey")
    var videoView by remember { mutableStateOf<PlayerView?>(null) }
    fun captureFrame() {
        val texture = videoView?.videoSurfaceView as? android.view.TextureView ?: return
        if (!texture.isAvailable || texture.width <= 0 || texture.height <= 0) return
        val scale = minOf(1f, 1280f / maxOf(texture.width, texture.height))
        try {
            texture.getBitmap((texture.width * scale).toInt().coerceAtLeast(1),
                (texture.height * scale).toInt().coerceAtLeast(1))?.let { frameMemory.frame = it }
        } catch (_: OutOfMemoryError) {
            // Optional visual continuity must never fail playback; keep the previous bounded frame.
        }
    }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val originalOrientation = rememberSaveable { activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    var source by remember { mutableStateOf<VideoPlayback?>(null) }
    var error by remember { mutableStateOf<AppError?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val currentPrepare by rememberUpdatedState(prepare)

    LaunchedEffect(Unit) {
        // Session UI snapshots also survive navigation/process restoration. Only a real rotation
        // may restore autoplay; returning to a page must wait for an explicit Play action.
        if (wasOpened && !frameMemory.configurationRestart) wantsPlay = false
        if (wasOpened && !frameMemory.configurationChange) selectedSpeed = 1f
        frameMemory.configurationChange = false
        wasOpened = true
        frameMemory.configurationRestart = false
    }
    LaunchedEffect(foreground, retry) {
        if (foreground && source == null) {
            try { source = currentPrepare() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = videoPlaybackError(failure); wantsPlay = false }
        }
    }
    val controller = remember(source) {
        source?.let { video ->
            val player = ExoPlayer.Builder(context).setLoadControl(VideoBufferPolicy.loadControl()).build().apply {
                setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
                setHandleAudioBecomingNoisy(true)
                setMediaSource(video.mediaSource(context))
            }
            VideoPlayerController(player, video.positionMs, video::lease,
                { video.positionMs = it }, { wantsPlay = it }, { error = videoPlaybackError(it) }, knownDuration,
                selectedSpeed, { selectedSpeed = it })
        }
    }
    val precache by frameMemory.precache.state.collectAsState()
    var cacheRetry by remember { mutableIntStateOf(0) }
    LaunchedEffect(source, foreground, cacheRetry) {
        if (foreground) source?.precache(frameMemory.precache) {
            controller?.state?.let(VideoBufferPolicy::mayPrecache) == true
        }
    }
    DisposableEffect(controller) { onDispose { controller?.close() } }
    DisposableEffect(frameMemory) {
        onDispose {
            if (activity?.isChangingConfigurations != true) {
                frameMemory.frame = null
                frameMemory.precache = com.hermes.client.data.video.VideoPrecacheSession()
                frameMemory.configurationRestart = false
            }
        }
    }
    LaunchedEffect(controller) {
        if (foreground && wantsPlay) controller?.play()
        while (controller != null) {
            controller.refresh()
            if (controller.state.duration > 0) knownDuration = controller.state.duration
            delay(250)
        }
    }
    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                // A configuration change reattaches the same file and intent; an actual background
                // transition always pauses, stops loading and requires explicit user continuation.
                frameMemory.configurationChange = activity?.isChangingConfigurations == true
                controller?.endBoost()
                frameMemory.configurationRestart = activity?.isChangingConfigurations == true && wantsPlay
                if (controller?.state?.hasFrame == true) captureFrame()
                if (activity?.isChangingConfigurations != true) { wantsPlay = false; controller?.pause() }
                foreground = false
            } else if (event == Lifecycle.Event.ON_START) foreground = true
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(fullscreen) {
        if (fullscreen) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else activity?.requestedOrientation = originalOrientation
    }
    DisposableEffect(activity) {
        onDispose { if (activity?.isChangingConfigurations != true) activity?.requestedOrientation = originalOrientation }
    }
    fun exit() { if (fullscreen) fullscreen = false else onDismiss() }
    Dialog(onDismissRequest = ::exit,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(window) {
            window ?: return@LaunchedEffect
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        VideoPlayerContent(
            name = name,
            state = controller?.state ?: VideoPlayerState(wantsPlay = wantsPlay, buffering = error == null),
            error = error, fullscreen = fullscreen, canPlay = controller != null,
            downloaded = downloaded, download = download, openExternally = openExternally,
            onClose = onDismiss, onFullscreen = { fullscreen = !fullscreen },
            onTogglePlay = { controller?.let { if (it.state.wantsPlay) { if (it.state.hasFrame) captureFrame(); it.pause() } else it.play() } },
            onSeek = { controller?.seek(it) },
            onSpeed = { controller?.selectSpeed(it) },
            holdSpeed = holdSpeed,
            onHoldSpeed = { holdSpeed = it; speedPreferences.edit().putFloat("hold_speed", it).apply() },
            onBoost = { if (it) controller?.beginBoost(holdSpeed) else controller?.endBoost() },
            precache = precache, onCacheRetry = { cacheRetry++ },
            onStatusDiagnostics = { failure ->
                context.getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Hermes diagnostics", failure.sanitizedDiagnostic()))
            },
            onRetry = { error = null; wantsPlay = true; source = null; retry++ },
            onDiagnostics = {
                error?.let { failure ->
                    context.getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("Hermes diagnostics", failure.sanitizedDiagnostic()))
                }
            },
        ) {
            if (controller != null) AndroidView(
                factory = {
                    android.view.LayoutInflater.from(it).inflate(
                        R.layout.video_preview_player, android.widget.FrameLayout(it), false,
                    ) as PlayerView
                },
                update = { videoView = it; it.player = controller.player; it.keepScreenOn = controller.state.wantsPlay },
                onRelease = { it.player = null; videoView = null }, modifier = Modifier.fillMaxSize(),
            )
            frameMemory.frame?.takeIf { controller?.state?.let { !it.wantsPlay || !it.hasFrame } != false }?.let { frame ->
                Image(frame.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/** Bounded last frame across configuration recreation; cleared on close, never written to disk. */
internal class VideoFrameMemory : ViewModel() {
    var frame by mutableStateOf<android.graphics.Bitmap?>(null)
    var configurationRestart = false
    var configurationChange = false
    var precache = com.hermes.client.data.video.VideoPrecacheSession()
}

private tailrec fun Context.videoActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.videoActivity()
    else -> null
}

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
