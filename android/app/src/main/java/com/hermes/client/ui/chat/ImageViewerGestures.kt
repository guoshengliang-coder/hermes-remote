package com.hermes.client.ui.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.max

/**
 * Arbitration between pinch-zoom on an image and the pager that swipes between images.
 *
 * These have to be separated out because they are where this breaks. `detectTransformGestures`
 * consumes *every* change once past touch slop, including a one-finger drag at 1x, and the pager's
 * `scrollable` sits at the parent and reads the main pass after the child. Left alone, the child
 * always wins and the pager never sees a swipe at all.
 */

/**
 * Float comparisons here carry an epsilon on purpose. `scale` accumulates through repeated
 * `scale * zoom`, so pinching out and back in almost never lands exactly on 1.0, and an exact
 * comparison would leave the pager permanently disabled after the first pinch.
 */
private const val SCALE_EPSILON = 0.001f

/** Zoom bounds. 1x is "fit"; past 5x a phone photo is mush. */
const val IMAGE_VIEWER_MIN_SCALE = 1f
const val IMAGE_VIEWER_MAX_SCALE = 5f

/** What double-tap zooms to, and what it returns from. */
const val IMAGE_VIEWER_DOUBLE_TAP_SCALE = 2.5f

internal fun isUnzoomed(scale: Float): Boolean = scale <= IMAGE_VIEWER_MIN_SCALE + SCALE_EPSILON

/**
 * Whether the pager may take horizontal drags. Only while the image is unzoomed — once zoomed, a
 * one-finger drag means "pan", and the two gestures cannot both own it.
 */
internal fun imagePagerUserScrollEnabled(scale: Float): Boolean = isUnzoomed(scale)

/**
 * Whether the image should consume this pointer event instead of letting it reach the pager.
 *
 * Two or more pointers is always a pinch and always ours. A single pointer at 1x is consumed by
 * nobody here, which is precisely what lets the pager swipe: this returning true for
 * `(1, 1f)` is the bug that makes paging impossible.
 */
internal fun shouldConsumePan(pointerCount: Int, scale: Float): Boolean =
    pointerCount >= 2 || !isUnzoomed(scale)

/**
 * Keep a zoomed image's pan inside its own bounds.
 *
 * Without this the offset accumulates unbounded and a zoomed image can be dragged off screen with
 * no way to bring it back — which is what the previous single-image viewer did.
 *
 * [displayed] is the size the image occupies at 1x (already letterboxed into the container), so the
 * slack on each axis is half of how much the scaled image overflows the container. Axes are
 * independent: a tall image zoomed 2x pans vertically but has nothing to give horizontally.
 */
internal fun clampPan(offset: Offset, scale: Float, container: Size, displayed: Size): Offset {
    val maxX = max(0f, (displayed.width * scale - container.width) / 2f)
    val maxY = max(0f, (displayed.height * scale - container.height) / 2f)
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

/**
 * Whether a pan actually moved the image, given where it was.
 *
 * Used to decide consumption at the edges: when a zoomed image is already hard against its right
 * bound, further dragging changes nothing, so leaving it unconsumed hands the gesture to the pager
 * and the user keeps swiping to the next image instead of hitting a wall.
 */
internal fun panDidMove(before: Offset, after: Offset): Boolean =
    abs(before.x - after.x) > 0.01f || abs(before.y - after.y) > 0.01f

/**
 * The size an image of [source] pixels occupies when fitted into [container] — i.e. what
 * `ContentScale.Fit` produces. Needed by [clampPan], which cannot reason about the letterboxing it
 * has to stay inside.
 */
internal fun fittedSize(source: Size, container: Size): Size {
    if (source.width <= 0f || source.height <= 0f) return container
    val scale = minOf(container.width / source.width, container.height / source.height)
    return Size(source.width * scale, source.height * scale)
}

/** Where a page lands after double-tapping: out to [IMAGE_VIEWER_DOUBLE_TAP_SCALE], or back to fit. */
internal fun doubleTapScale(current: Float): Float =
    if (isUnzoomed(current)) IMAGE_VIEWER_DOUBLE_TAP_SCALE else IMAGE_VIEWER_MIN_SCALE

/**
 * Which image to show after the one at [removedIndex] is deleted: the next one, else the previous,
 * else nothing — in which case the viewer closes rather than sitting on an empty pager.
 */
internal fun <T> neighbourAfterRemoval(items: List<T>, removedIndex: Int): T? = when {
    removedIndex < 0 || removedIndex >= items.size -> null
    removedIndex + 1 < items.size -> items[removedIndex + 1]
    removedIndex - 1 >= 0 -> items[removedIndex - 1]
    else -> null
}
