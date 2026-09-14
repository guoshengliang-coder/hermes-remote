package com.hermes.client.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.hermes.client.domain.ChatImage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sentinel owner for the composer's staging strip, which has no message id of its own.
 */
const val PENDING_VIEWER_OWNER = "@pending"

/** One page of the viewer. [export] is the sent image save/share act on; pending images have none. */
internal data class ImageViewerItem(
    val id: String,
    val source: ImageSource,
    val export: ChatImage? = null,
)

/** The action set in the top-right corner, which differs by where the viewer was opened from. */
internal sealed interface ImageViewerChrome {
    /** An image already in the transcript: save it, share it, save it somewhere else. */
    data class Sent(
        val onSave: (ChatImage) -> Unit,
        val onSaveAs: (ChatImage) -> Unit,
        val onShare: (ChatImage) -> Unit,
        val savingImageId: String?,
    ) : ImageViewerChrome

    /**
     * An attachment staged in the composer. No save and no share: it is a file the user picked
     * seconds ago, so saving is a no-op, and both paths require a cache-rooted path a pending
     * attachment does not have.
     */
    data class Pending(
        val onEdit: (String) -> Unit,
        val onDelete: (String) -> Unit,
    ) : ImageViewerChrome
}

/**
 * Fullscreen image viewer. Pages between the images **of one message** (or the whole pending strip),
 * pinch-zooms 1-5x, and double-taps to zoom or reset.
 *
 * The Dialog is kept separate from [ImageViewerContent] so screenshot tests can capture the content
 * without a window, the way ClarifySheet already splits.
 */
@Composable
internal fun ImageViewer(
    items: List<ImageViewerItem>,
    currentId: String?,
    chrome: ImageViewerChrome,
    onPageChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (items.isEmpty()) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Hiding the bars on the activity window does nothing while a dialog holds focus, so the
        // viewer has to do it on its own window.
        val window = (androidx.compose.ui.platform.LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(window) {
            window ?: return@LaunchedEffect
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
        ImageViewerContent(items, currentId, chrome, onPageChange, onDismiss)
    }
}

@Composable
internal fun ImageViewerContent(
    items: List<ImageViewerItem>,
    currentId: String?,
    chrome: ImageViewerChrome,
    onPageChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (items.isEmpty()) return
    val language = LocalAppLanguage.current
    val startPage = items.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = startPage) { items.size }

    // Zoom lives above the pager because `userScrollEnabled` is a property of the pager and has to
    // be able to read it. Resetting on settle keeps a page from inheriting its neighbour's zoom.
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(pagerState.settledPage) {
        scale = IMAGE_VIEWER_MIN_SCALE
        offset = Offset.Zero
        items.getOrNull(pagerState.settledPage)?.let { onPageChange(it.id) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            // Once zoomed, a one-finger drag means pan; the pager must not also claim it.
            userScrollEnabled = imagePagerUserScrollEnabled(scale),
            // Each page is a multi-megabyte bitmap. Do not keep neighbours warm.
            beyondViewportPageCount = 0,
            key = { items[it].id },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            ImageViewerPage(
                item = items[page],
                // Only the settled page wears the transform; a neighbour sliding in during a swipe
                // must not be drawn with the outgoing page's zoom.
                scale = if (page == pagerState.currentPage) scale else IMAGE_VIEWER_MIN_SCALE,
                offset = if (page == pagerState.currentPage) offset else Offset.Zero,
                onTransform = { nextScale, nextOffset -> scale = nextScale; offset = nextOffset },
            )
        }

        ImageViewerAction(
            contentDescription = localized(language, "关闭", "Close"),
            modifier = Modifier.align(Alignment.TopStart).padding(top = 30.dp, start = 18.dp),
            onClick = onDismiss,
        ) { Icon(Icons.Rounded.Close, null, tint = Color.White) }

        if (items.size > 1) {
            val position = pagerState.currentPage + 1
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 30.dp).semantics {
                    contentDescription =
                        localized(language, "第 $position 张，共 ${items.size} 张", "Image $position of ${items.size}")
                },
                shape = RoundedCornerShape(23.dp),
                color = Color.Black.copy(alpha = 0.58f),
            ) {
                Text(
                    "$position / ${items.size}",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }

        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 30.dp, end = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val current = items.getOrNull(pagerState.currentPage)
            when (chrome) {
                is ImageViewerChrome.Sent -> SentImageActions(chrome, current?.export)
                is ImageViewerChrome.Pending -> PendingImageActions(chrome, current?.id)
            }
        }
    }
}

@Composable
private fun SentImageActions(chrome: ImageViewerChrome.Sent, image: ChatImage?) {
    val language = LocalAppLanguage.current
    var menuOpen by remember(image?.id) { mutableStateOf(false) }
    val saving = image != null && chrome.savingImageId == image.id
    val enabled = image != null && !saving

    ImageViewerAction(
        contentDescription = localized(language, "保存图片", "Save image"),
        enabled = enabled,
        onClick = { image?.let(chrome.onSave) },
    ) {
        // Stays an M3 spinner: white-on-photo, where a single-colour brand mark has no guaranteed
        // contrast (docs/DESIGN.md §5.6).
        if (saving) CircularProgressIndicator(Modifier.size(21.dp), strokeWidth = 2.dp, color = Color.White)
        else Icon(Icons.Rounded.Download, null, tint = Color.White)
    }
    ImageViewerAction(
        contentDescription = localized(language, "分享图片", "Share image"),
        enabled = enabled,
        onClick = { image?.let(chrome.onShare) },
    ) { Icon(Icons.Rounded.Share, null, tint = Color.White) }
    Box {
        ImageViewerAction(
            contentDescription = localized(language, "更多图片操作", "More image actions"),
            enabled = enabled,
            onClick = { menuOpen = true },
        ) { Icon(Icons.Rounded.MoreVert, null, tint = Color.White) }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(localized(language, "另存为…", "Save as…")) },
                onClick = { menuOpen = false; image?.let(chrome.onSaveAs) },
            )
        }
    }
}

@Composable
private fun PendingImageActions(chrome: ImageViewerChrome.Pending, attachmentId: String?) {
    val language = LocalAppLanguage.current
    ImageViewerAction(
        contentDescription = localized(language, "编辑图片", "Edit image"),
        enabled = attachmentId != null,
        onClick = { attachmentId?.let(chrome.onEdit) },
    ) { Icon(Icons.Rounded.Edit, null, tint = Color.White) }
    ImageViewerAction(
        contentDescription = localized(language, "移除附件", "Remove attachment"),
        enabled = attachmentId != null,
        onClick = { attachmentId?.let(chrome.onDelete) },
    ) { Icon(Icons.Rounded.Delete, null, tint = Color.White) }
}

@Composable
private fun ImageViewerPage(
    item: ImageViewerItem,
    scale: Float,
    offset: Offset,
    onTransform: (Float, Offset) -> Unit,
) {
    val language = LocalAppLanguage.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    // Sharp to roughly 2x zoom and bounded above. The old value of 4096 was, with the sampling bug,
    // effectively "decode at full size", and three of those at once is most of a phone's heap.
    val requestedPx = remember(configuration) {
        with(density) {
            val longest = maxOf(configuration.screenWidthDp.dp.toPx(), configuration.screenHeightDp.dp.toPx())
            minOf(4096, (longest * 2).toInt())
        }
    }
    val bitmap by produceState<ImageBitmap?>(null, item.source, requestedPx) {
        value = withContext(Dispatchers.IO) { decodeSampled(item.source, requestedPx) }
    }
    var failed by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(bitmap, item.id) {
        // produceState starts at null, so "no bitmap" only means failure once the decode has run.
        if (bitmap == null) {
            kotlinx.coroutines.delay(1)
            failed = withContext(Dispatchers.IO) { decodeBounds(item.source) == null }
        } else {
            failed = false
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val container = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val displayed = remember(bitmap, container) {
            val bmp = bitmap
            if (bmp == null) container
            else fittedSize(Size(bmp.width.toFloat(), bmp.height.toFloat()), container)
        }
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = localized(language, "查看原图", "View full image"),
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    )
                    .pointerInput(item.id) {
                        detectTapGestures(
                            onDoubleTap = { onTransform(doubleTapScale(scale), Offset.Zero) },
                        )
                    }
                    .pointerInput(item.id, container, displayed) {
                        awaitImageTransform(
                            currentScale = { scale },
                            currentOffset = { offset },
                            container = container,
                            displayed = displayed,
                            onTransform = onTransform,
                        )
                    },
                contentScale = ContentScale.Fit,
            )
        } else if (failed) {
            // Better than the previous behaviour, which drew nothing at all and left the user in a
            // black dead end with no explanation.
            Text(
                localized(language, "图片无法显示（HR-MEDIA-004）", "This image can't be displayed (HR-MEDIA-004)"),
                color = Color.White.copy(alpha = 0.82f),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp, color = Color.White)
        }
    }
}

/**
 * The transform loop, written out rather than using `detectTransformGestures`, because that helper
 * consumes every change past touch slop — including a one-finger drag at 1x, which is the drag the
 * pager needs.
 *
 * See [shouldConsumePan] and [panDidMove] for the two decisions this makes.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.awaitImageTransform(
    currentScale: () -> Float,
    currentOffset: () -> Offset,
    container: Size,
    displayed: Size,
    onTransform: (Float, Offset) -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break

            val zoom = if (pressed.size >= 2 && event.calculateCentroidSize() > 0f) event.calculateZoom() else 1f
            val pan = event.calculatePan()
            val scaleBefore = currentScale()
            val nextScale = (scaleBefore * zoom).coerceIn(IMAGE_VIEWER_MIN_SCALE, IMAGE_VIEWER_MAX_SCALE)

            if (!shouldConsumePan(pressed.size, nextScale)) {
                // Hand the drag to the pager untouched. Snapping back to fit here also cleans up
                // after a pinch that ended below 1x.
                if (nextScale != scaleBefore) onTransform(IMAGE_VIEWER_MIN_SCALE, Offset.Zero)
                continue
            }

            val before = currentOffset()
            val next = clampPan(before + pan, nextScale, container, displayed)
            val moved = panDidMove(before, next) || nextScale != scaleBefore
            if (moved) onTransform(nextScale, next)
            // An edge pan that changed nothing stays unconsumed so the pager can take over and the
            // user keeps swiping instead of hitting a wall.
            if (moved) event.changes.forEach { if (it.positionChanged()) it.consume() }
        } while (true)
    }
}

@Composable
private fun ImageViewerAction(
    contentDescription: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.semantics { this.contentDescription = contentDescription },
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.58f),
    ) {
        Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(24.dp)) { icon() }
        }
    }
}
