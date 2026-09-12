package com.hermes.client.data.media.imageedit

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Crop interaction math.
 *
 * Pure, and deliberately so: the failure modes here are a rectangle that inverts (a negative width
 * crashes `Bitmap.createBitmap`), one that escapes the image, and handles whose hit areas overlap so
 * a corner cannot be grabbed. All three are cheaper to pin than to reproduce by hand.
 */

enum class CropHandle { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, TOP, BOTTOM, LEFT, RIGHT, MOVE }

/**
 * Smallest crop side, measured in **view** space.
 *
 * Defined in view rather than source pixels because the constraint it serves is touch geometry: each
 * handle carries a 48dp hit circle, and at anything under about 96dp the neighbouring circles start
 * to overlap and the corner becomes ungrabbable. Converted through the fit scale at the call site.
 */
const val MIN_CROP_VIEW_DP = 96f

/** Absolute floor in source pixels, so a small image stays croppable at all. */
const val MIN_CROP_SOURCE_PX = 16

/**
 * Which handle a touch at ([x], [y]) grabs, given the crop rectangle in **view** coordinates.
 *
 * Corners are tested before edges so a corner wins where the two hit areas overlap; that is what
 * users aim for, and an edge stealing the corner is the more annoying error.
 */
fun hitHandle(
    x: Float,
    y: Float,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    hitRadius: Float,
): CropHandle? {
    fun near(px: Float, py: Float) = abs(x - px) <= hitRadius && abs(y - py) <= hitRadius
    when {
        near(left, top) -> return CropHandle.TOP_LEFT
        near(right, top) -> return CropHandle.TOP_RIGHT
        near(left, bottom) -> return CropHandle.BOTTOM_LEFT
        near(right, bottom) -> return CropHandle.BOTTOM_RIGHT
    }
    val insideX = x >= left - hitRadius && x <= right + hitRadius
    val insideY = y >= top - hitRadius && y <= bottom + hitRadius
    when {
        insideX && abs(y - top) <= hitRadius -> return CropHandle.TOP
        insideX && abs(y - bottom) <= hitRadius -> return CropHandle.BOTTOM
        insideY && abs(x - left) <= hitRadius -> return CropHandle.LEFT
        insideY && abs(x - right) <= hitRadius -> return CropHandle.RIGHT
    }
    return if (x in left..right && y in top..bottom) CropHandle.MOVE else null
}

/**
 * Apply a drag of ([dx], [dy]) source pixels to [handle] of [box], staying inside
 * [imageWidth] x [imageHeight] and never letting a side fall below [minSide] or invert.
 */
fun dragHandle(
    box: CropBox,
    handle: CropHandle,
    dx: Int,
    dy: Int,
    imageWidth: Int,
    imageHeight: Int,
    minSide: Int = MIN_CROP_SOURCE_PX,
): CropBox {
    val floor = minSide.coerceAtMost(min(imageWidth, imageHeight)).coerceAtLeast(1)
    if (handle == CropHandle.MOVE) {
        val shiftX = dx.coerceIn(-box.left, imageWidth - box.right)
        val shiftY = dy.coerceIn(-box.top, imageHeight - box.bottom)
        return CropBox(box.left + shiftX, box.top + shiftY, box.right + shiftX, box.bottom + shiftY)
    }
    var left = box.left
    var top = box.top
    var right = box.right
    var bottom = box.bottom
    if (handle in setOf(CropHandle.LEFT, CropHandle.TOP_LEFT, CropHandle.BOTTOM_LEFT)) {
        left = (left + dx).coerceIn(0, right - floor)
    }
    if (handle in setOf(CropHandle.RIGHT, CropHandle.TOP_RIGHT, CropHandle.BOTTOM_RIGHT)) {
        right = (right + dx).coerceIn(left + floor, imageWidth)
    }
    if (handle in setOf(CropHandle.TOP, CropHandle.TOP_LEFT, CropHandle.TOP_RIGHT)) {
        top = (top + dy).coerceIn(0, bottom - floor)
    }
    if (handle in setOf(CropHandle.BOTTOM, CropHandle.BOTTOM_LEFT, CropHandle.BOTTOM_RIGHT)) {
        bottom = (bottom + dy).coerceIn(top + floor, imageHeight)
    }
    return CropBox(left, top, right, bottom)
}

/**
 * Reshape [box] to [ratio] (width over height), anchored at the corner opposite [handle] so the
 * handle the finger holds is the one that moves.
 *
 * Returns [box] unchanged when the ratio cannot be satisfied inside the image at all.
 */
fun applyAspect(
    box: CropBox,
    ratio: Float,
    imageWidth: Int,
    imageHeight: Int,
    handle: CropHandle = CropHandle.BOTTOM_RIGHT,
    minSide: Int = MIN_CROP_SOURCE_PX,
): CropBox {
    if (ratio <= 0f) return box
    val anchorLeft = handle !in setOf(CropHandle.LEFT, CropHandle.TOP_LEFT, CropHandle.BOTTOM_LEFT)
    val anchorTop = handle !in setOf(CropHandle.TOP, CropHandle.TOP_LEFT, CropHandle.TOP_RIGHT)

    val availableW = if (anchorLeft) imageWidth - box.left else box.right
    val availableH = if (anchorTop) imageHeight - box.top else box.bottom

    var width = box.width.toFloat()
    var height = width / ratio
    if (height > availableH) {
        height = availableH.toFloat()
        width = height * ratio
    }
    if (width > availableW) {
        width = availableW.toFloat()
        height = width / ratio
    }
    val w = width.roundToInt().coerceAtLeast(min(minSide, availableW))
    val h = height.roundToInt().coerceAtLeast(min(minSide, availableH))
    if (w <= 0 || h <= 0) return box

    val left = if (anchorLeft) box.left else box.right - w
    val top = if (anchorTop) box.top else box.bottom - h
    return cropToSourceRect(CropBox(left, top, left + w, top + h), imageWidth, imageHeight)
}

/**
 * Recompute [box] after a quarter turn when an aspect is locked.
 *
 * The box itself is stored unrotated, so rotation normally touches no box data at all. A locked
 * aspect is the exception: "16:9" means 16:9 *as seen*, so turning the image swaps which source axis
 * is long.
 */
fun rotateWithAspect(box: CropBox, ratio: Float?, imageWidth: Int, imageHeight: Int): CropBox {
    if (ratio == null) return box
    return applyAspect(box, 1f / ratio, imageWidth, imageHeight)
}

/**
 * Snap a crop to valid integer source pixels: inside the image, positive extent, never inverted.
 * This is the invariant `Bitmap.createBitmap` needs and the last thing run before baking.
 */
fun cropToSourceRect(box: CropBox, imageWidth: Int, imageHeight: Int): CropBox {
    val left = box.left.coerceIn(0, max(0, imageWidth - 1))
    val top = box.top.coerceIn(0, max(0, imageHeight - 1))
    val right = box.right.coerceIn(left + 1, imageWidth)
    val bottom = box.bottom.coerceIn(top + 1, imageHeight)
    return CropBox(left, top, right, bottom)
}

/** The ratio a preset resolves to for a given frame, or null when the crop is free. */
fun aspectRatioFor(aspect: CropAspect, sourceWidth: Int, sourceHeight: Int, quarterTurns: Int): Float? = when (aspect) {
    CropAspect.FREE -> null
    CropAspect.ORIGINAL -> {
        val even = quarterTurns.mod(4) % 2 == 0
        if (sourceWidth <= 0 || sourceHeight <= 0) null
        else if (even) sourceWidth.toFloat() / sourceHeight else sourceHeight.toFloat() / sourceWidth
    }
    else -> aspect.ratio
}
