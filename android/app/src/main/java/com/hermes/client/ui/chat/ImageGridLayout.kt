package com.hermes.client.ui.chat

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * Sizing for images inside a message.
 *
 * Pure functions, deliberately: the interesting cases are extreme aspect ratios, and those are far
 * cheaper to pin in a JVM test than to eyeball in a golden.
 */

/**
 * Floor for a single image's box height.
 *
 * This exists for the **touch target**, not for looks. Since the box takes the image's own aspect
 * ratio and the image is drawn `Fit`, raising the height of a very wide image does not make the
 * image any bigger — it just adds mat above and below. What it does buy is a row tall enough to
 * aim at: a 100:1 banner would otherwise be a 3dp strip nobody can tap.
 */
private val MIN_BOX_H = 44.dp

/** Height for a single image whose intrinsic size is not known yet (still transferring, or undecodable). */
private val UNKNOWN_BOX_H = 190.dp

/** Cell height in a 2-column grid. Multi-image grids stay cropped squares — see `DESIGN.md` §5.4. */
val GRID_CELL_HEIGHT = 132.dp

/** Gap between grid cells, both axes. */
val GRID_CELL_GAP = 6.dp

/**
 * The box for a single image in a message: **the container takes the image's aspect ratio**, so the
 * bubble narrows around a tall screenshot instead of cropping 78% of it away.
 *
 * Width is the constraint first; when the resulting height would exceed [maxH] the image is sized by
 * height instead and comes out narrower than [maxW]. Because the box always carries the source
 * aspect, drawing the image `Fit` inside it is a no-op — no crop, and no mat except in the one case
 * where [MIN_BOX_H] bites.
 *
 * [srcW]/[srcH] of zero or less mean "not known yet"; those get a plain full-width box until the
 * dimensions arrive.
 */
internal fun singleImageBox(srcW: Int, srcH: Int, maxW: Dp, maxH: Dp): DpSize {
    if (srcW <= 0 || srcH <= 0) return DpSize(maxW, minOf(UNKNOWN_BOX_H, maxH))
    val aspect = srcW.toFloat() / srcH.toFloat()
    val heightAtFullWidth = maxW / aspect
    val height = heightAtFullWidth.coerceIn(minOf(MIN_BOX_H, maxH), maxH)
    val width = (height * aspect).coerceAtMost(maxW)
    return DpSize(width, height)
}

/**
 * Upper bound on a single image's height: never more than 320dp, and never more than 42% of the
 * viewport, so a long screenshot cannot own a short device's whole screen.
 */
internal fun singleImageMaxHeight(screenHeight: Dp): Dp = minOf(320.dp, screenHeight * 0.42f)

/**
 * Merge an upstream `image.attach` response into an outgoing image we already measured.
 *
 * Upstream **fills gaps, it does not overwrite**. The local dimensions came from the exact bytes
 * being uploaded, so they are authoritative here; letting an upstream that answers null erase them
 * collapses the thumbnail into the unknown-size fallback box, which is how a portrait screenshot
 * ended up inside a landscape frame.
 */
internal fun com.hermes.client.domain.ChatImage.mergedWithUpstream(
    remotePath: String,
    upstreamWidth: Int?,
    upstreamHeight: Int?,
): com.hermes.client.domain.ChatImage = copy(
    remotePath = remotePath,
    width = width ?: upstreamWidth,
    height = height ?: upstreamHeight,
    state = com.hermes.client.domain.ImageTransferState.READY,
)
