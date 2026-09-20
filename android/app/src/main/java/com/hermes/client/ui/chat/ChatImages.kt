package com.hermes.client.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.hermes.client.domain.ChatImage
import com.hermes.client.domain.ImageTransferState
import com.hermes.client.ui.components.LoadingDots
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Images attached to one message.
 *
 * A single image is shown **whole**: its container takes the image's own aspect ratio, so a tall
 * screenshot is recognisable without opening it and the bubble narrows around it.
 *
 * Two or more are **also whole** (HG-43, 2026-09-14). The grid gives every cell one shared ratio —
 * the median of the group's own — and draws each image `Fit` inside it, so the columns stay even
 * and nothing is cut off. It was a fixed-height cropped grid until now, recorded on 2026-09-12 as
 * a deliberate trade; what reopened it was three photographed ID cards arriving as three middle
 * strips. That note named the two ways out, masonry or one shared ratio, and this is the second.
 *
 * Cells are also smaller than they were (108dp ceiling, from 132dp fixed). A thumbnail is for
 * recognising which picture it is — the rest is one tap away in the viewer.
 *
 * Tapping asks [onOpen] to open the viewer; this composable owns no viewer state of its own. It used
 * to, in a `remember` inside a LazyColumn item, where a rotation or a history reconcile silently
 * closed whatever the user was looking at.
 */
@Composable
internal fun ChatImageGrid(
    images: List<ChatImage>,
    onOpen: (ChatImage) -> Unit,
) {
    if (images.isEmpty()) return
    if (images.size == 1) {
        val image = images.single()
        val maxHeight = singleImageMaxHeight(LocalConfiguration.current.screenHeightDp.dp)
        BoxWithConstraints {
            val box = singleImageBox(image.width ?: 0, image.height ?: 0, maxWidth, maxHeight)
            ChatImageThumbnail(
                image = image,
                modifier = Modifier.size(box),
                // The box already carries the source aspect, so Fit neither crops nor mats.
                contentScale = ContentScale.Fit,
                onClick = { if (image.localPath != null) onOpen(image) },
            )
        }
        return
    }
    // One ratio for the whole grid, so the rows line up even though the pictures do not match.
    val aspect = remember(images) {
        gridCellAspect(images.map { (it.width ?: 0) to (it.height ?: 0) })
    }
    BoxWithConstraints {
        val cellWidth = (maxWidth - GRID_CELL_GAP) / 2
        val cellHeight = gridCellHeight(cellWidth, aspect)
        Column(verticalArrangement = Arrangement.spacedBy(GRID_CELL_GAP)) {
            images.chunked(2).forEach { rowImages ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(GRID_CELL_GAP),
                ) {
                    rowImages.forEach { image ->
                        ChatImageThumbnail(
                            image = image,
                            modifier = Modifier.weight(1f).height(cellHeight),
                            // Fit, not Crop: the cell carries the group's ratio rather than this
                            // image's, so an image that differs from the median is matted at the
                            // edges instead of having those edges cut away.
                            contentScale = ContentScale.Fit,
                            onClick = { if (image.localPath != null) onOpen(image) },
                        )
                    }
                    if (rowImages.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ChatImageThumbnail(
    image: ChatImage,
    modifier: Modifier,
    contentScale: ContentScale,
    onClick: () -> Unit,
) {
    val language = LocalAppLanguage.current
    BoxWithConstraints(modifier.clip(RoundedCornerShape(14.dp))) {
        val density = LocalDensity.current
        // Decode to the cell we actually measured rather than a fixed 900px. Six full-size decodes
        // is tens of megabytes for thumbnails a few hundred pixels wide.
        val requestedPx = with(density) { (maxWidth.coerceAtLeast(maxHeight).toPx() * 2).toInt() }
        val bitmap by produceState<ImageBitmap?>(null, image.localPath, requestedPx) {
            value = withContext(Dispatchers.IO) {
                image.localPath?.let { decodeSampled(ImageSource.Path(it), requestedPx) }
            }
        }
        val transferring = remember(image) {
            image.state == ImageTransferState.UPLOADING ||
                ((image.remotePath != null || image.sourceUrl != null) && image.state != ImageTransferState.FAILED)
        }
        // The fill is for the EMPTY states only (HG-43). While a cell is waiting or broken it needs
        // a shape to be a placeholder at all; once the picture is drawn it must go, because a grid
        // cell now mats whatever does not match the shared ratio, and a `surface` mat on the
        // bubble's own ground reads as a row of little white cards behind the photos. Matting in
        // the bubble's colour is matting you do not notice.
        if (bitmap == null) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        }
        Box(Modifier.fillMaxSize().clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            val bmp = bitmap
            when {
                bmp != null -> Image(
                    bitmap = bmp,
                    contentDescription = localized(language, "聊天图片", "Chat image"),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = contentScale,
                )
                transferring -> LoadingDots(size = 24.dp)
                else -> Icon(
                    Icons.Rounded.BrokenImage,
                    contentDescription = localized(language, "图片加载失败", "Image unavailable"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
