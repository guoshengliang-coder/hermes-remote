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
import com.hermes.client.ui.components.HermesMark
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Images attached to one message.
 *
 * A single image is shown **whole**: its container takes the image's own aspect ratio, so a tall
 * screenshot is recognisable without opening it and the bubble narrows around it. Two or more stay a
 * cropped 2-up grid — a deliberate trade recorded in `docs/DESIGN.md` §5.4, because a ragged grid is
 * harder to read than a cropped one and sharing screenshots is overwhelmingly a single-image act.
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
    Column(verticalArrangement = Arrangement.spacedBy(GRID_CELL_GAP)) {
        images.chunked(2).forEach { rowImages ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(GRID_CELL_GAP),
            ) {
                rowImages.forEach { image ->
                    ChatImageThumbnail(
                        image = image,
                        modifier = Modifier.weight(1f).height(GRID_CELL_HEIGHT),
                        contentScale = ContentScale.Crop,
                        onClick = { if (image.localPath != null) onOpen(image) },
                    )
                }
                if (rowImages.size == 1) Spacer(Modifier.weight(1f))
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
    BoxWithConstraints(modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface)) {
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
        Box(Modifier.fillMaxSize().clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            val bmp = bitmap
            when {
                bmp != null -> Image(
                    bitmap = bmp,
                    contentDescription = localized(language, "聊天图片", "Chat image"),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = contentScale,
                )
                transferring -> HermesMark(size = 24.dp)
                else -> Icon(
                    Icons.Rounded.BrokenImage,
                    contentDescription = localized(language, "图片加载失败", "Image unavailable"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
