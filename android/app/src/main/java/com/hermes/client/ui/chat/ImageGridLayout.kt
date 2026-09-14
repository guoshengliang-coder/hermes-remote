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

/**
 * Ceiling for a cell in a 2-column grid (HG-43, 2026-09-14; was a fixed 132dp).
 *
 * Smaller than what it replaced, deliberately. A thumbnail's job is to let you recognise WHICH
 * picture it is; anything more than that is available by tapping it, and the full-screen viewer has
 * existed since HG-35. A grid of four images used to claim ~276dp of a conversation.
 */
val GRID_CELL_MAX_HEIGHT = 108.dp

/** Floor for a grid cell, for the same touch-target reason as [MIN_BOX_H]. */
private val GRID_CELL_MIN_HEIGHT = 44.dp

/**
 * Bounds on the shared aspect ratio, so one freak image cannot set the shape of the whole grid.
 *
 * Below the lower bound a column of panoramas would collapse toward a strip; above the upper bound
 * a set of tall screenshots would push every cell to its ceiling and hand a four-image message the
 * better part of a screen. Both ends are clamps on the MEDIAN, so they only bite when most of the
 * group is extreme — which is exactly when a compromise shape is wanted.
 */
private const val MIN_GRID_ASPECT = 0.6f
private const val MAX_GRID_ASPECT = 1.9f

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
 * The aspect ratio every cell of a multi-image grid shares: the MEDIAN of the group's own ratios.
 *
 * **This replaces cropping** (HG-43, 2026-09-14). Until now a grid was fixed-height cells filled
 * with `ContentScale.Crop`, recorded on 2026-09-12 as a deliberate trade: a ragged grid is harder
 * to read than a cropped one, and sharing screenshots is overwhelmingly a single-image act. The
 * cost was that three photographed ID cards came out as three middle strips, which is the report
 * that reopened it. That note named the two ways out — masonry, or one shared ratio — and the
 * product owner chose the shared ratio: the grid stays a grid, and every picture is whole.
 *
 * The median rather than the mean because one panorama among three screenshots should not drag the
 * shape of the other two; the median moves only when most of the group does. With an even count it
 * takes the lower of the two middle ratios, which is the wider-is-safer direction — a cell slightly
 * too tall mats an image, a cell too short cannot mat anything because the image is drawn `Fit`.
 *
 * Images whose intrinsic size is not known yet do not vote. When NONE of them is known the answer
 * is `1f`, which is the square the grid used to be — the right thing to hold while they arrive.
 */
internal fun gridCellAspect(sizes: List<Pair<Int, Int>>): Float {
    val ratios = sizes
        .filter { (w, h) -> w > 0 && h > 0 }
        .map { (w, h) -> w.toFloat() / h.toFloat() }
        .sorted()
    if (ratios.isEmpty()) return 1f
    val median = ratios[(ratios.size - 1) / 2]
    return median.coerceIn(MIN_GRID_ASPECT, MAX_GRID_ASPECT)
}

/**
 * Height of one grid cell, given the measured cell width and the group's shared [aspect].
 *
 * Clamped at both ends: [GRID_CELL_MAX_HEIGHT] so a column of tall screenshots cannot take the
 * screen, [GRID_CELL_MIN_HEIGHT] so a row of panoramas stays tappable.
 */
internal fun gridCellHeight(cellWidth: Dp, aspect: Float): Dp =
    (cellWidth / aspect).coerceIn(GRID_CELL_MIN_HEIGHT, GRID_CELL_MAX_HEIGHT)

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
