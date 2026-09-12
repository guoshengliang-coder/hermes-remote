package com.hermes.client.data.media.imageedit

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Coordinate mapping and proportional sizing for the image editor.
 *
 * Everything here is pure. The mapping is the part that silently ruins an editor — a stroke that
 * lands a few pixels off, or drifts once the image is rotated — and it is far cheaper to pin with a
 * round-trip assertion than to chase on a device.
 *
 * The chain from a finger to a stored point is:
 *
 *     view px --(inverse viewport zoom/pan)--> box px --(inverse fit)--> rotated crop-local px
 *             --(inverse quarter turn)--> crop-local px --(+crop origin)--> source px
 */

/** Where the cropped, rotated image sits inside the canvas box, and at what scale. */
data class FitTransform(
    val originX: Float,
    val originY: Float,
    val scale: Float,
    val displayWidth: Float,
    val displayHeight: Float,
)

/**
 * Fit the cropped, rotated extent into a [boxW] x [boxH] canvas, centred.
 *
 * Odd quarter turns transpose the displayed extent — that is the whole reason rotation cannot be
 * folded into the crop box.
 */
fun fitTransform(crop: CropBox, quarterTurns: Int, boxW: Float, boxH: Float): FitTransform {
    val even = quarterTurns % 2 == 0
    val displayW = (if (even) crop.width else crop.height).toFloat()
    val displayH = (if (even) crop.height else crop.width).toFloat()
    if (displayW <= 0f || displayH <= 0f || boxW <= 0f || boxH <= 0f) {
        return FitTransform(0f, 0f, 1f, max(displayW, 0f), max(displayH, 0f))
    }
    val scale = min(boxW / displayW, boxH / displayH)
    return FitTransform(
        originX = (boxW - displayW * scale) / 2f,
        originY = (boxH - displayH * scale) / 2f,
        scale = scale,
        displayWidth = displayW * scale,
        displayHeight = displayH * scale,
    )
}

/**
 * Undo the editor's viewport zoom/pan, so the rest of the chain can work in unzoomed box
 * coordinates.
 *
 * The viewport is applied as `graphicsLayer(scale, translation)` about the box centre, so the
 * inverse is centre-relative too.
 */
fun viewportToBox(
    viewX: Float,
    viewY: Float,
    viewportScale: Float,
    viewportOffsetX: Float,
    viewportOffsetY: Float,
    boxW: Float,
    boxH: Float,
): Pair<Float, Float> {
    if (viewportScale <= 0f) return viewX to viewY
    val cx = boxW / 2f
    val cy = boxH / 2f
    return ((viewX - cx - viewportOffsetX) / viewportScale + cx) to
        ((viewY - cy - viewportOffsetY) / viewportScale + cy)
}

/**
 * Map a point in unzoomed box coordinates to source pixels.
 *
 * Clamping happens **here, at capture**, so a finger that slides off the photo can never write a
 * point outside the image into the document.
 */
fun boxToSource(boxX: Float, boxY: Float, crop: CropBox, quarterTurns: Int, fit: FitTransform): SourcePoint {
    if (fit.scale <= 0f) return SourcePoint(crop.left.toFloat(), crop.top.toFloat())
    val dx = (boxX - fit.originX) / fit.scale
    val dy = (boxY - fit.originY) / fit.scale
    val cw = crop.width.toFloat()
    val ch = crop.height.toFloat()
    val local = when (quarterTurns.mod(4)) {
        0 -> dx to dy
        1 -> dy to (ch - dx)
        2 -> (cw - dx) to (ch - dy)
        else -> (cw - dy) to dx
    }
    return SourcePoint(
        (crop.left + local.first).coerceIn(crop.left.toFloat(), crop.right.toFloat()),
        (crop.top + local.second).coerceIn(crop.top.toFloat(), crop.bottom.toFloat()),
    )
}

/** Exact inverse of [boxToSource], for drawing the crop overlay and committed ops. */
fun sourceToBox(point: SourcePoint, crop: CropBox, quarterTurns: Int, fit: FitTransform): Pair<Float, Float> {
    val lx = point.x - crop.left
    val ly = point.y - crop.top
    val cw = crop.width.toFloat()
    val ch = crop.height.toFloat()
    val rotated = when (quarterTurns.mod(4)) {
        0 -> lx to ly
        1 -> (ch - ly) to lx
        2 -> (cw - lx) to (ch - ly)
        else -> ly to (cw - lx)
    }
    return (fit.originX + rotated.first * fit.scale) to (fit.originY + rotated.second * fit.scale)
}

/**
 * Pen width as a fraction of the image's long edge.
 *
 * Not a fixed pixel count: the same value is a hairline on a 2560px image and a blot on a 900px one.
 * This is correctness, not polish.
 */
fun strokeWidthPx(weight: StrokeWeight, longEdgePx: Int): Float {
    val fraction = when (weight) {
        StrokeWeight.THIN -> 0.004f
        StrokeWeight.MEDIUM -> 0.008f
        StrokeWeight.THICK -> 0.014f
    }
    return max(2f, longEdgePx * fraction)
}

/** Mosaic brush width. Far fatter than a pen: you are covering a phone number, not tracing it. */
fun mosaicBrushPx(brush: BrushSize, longEdgePx: Int): Float {
    val fraction = when (brush) {
        BrushSize.SMALL -> 0.03f
        BrushSize.MEDIUM -> 0.06f
        BrushSize.LARGE -> 0.10f
    }
    return max(8f, longEdgePx * fraction)
}

/**
 * Mosaic block size, proportional so it adapts to the image instead of needing a user control.
 * Floored at 8px, below which "pixelated" stops reading as deliberate.
 */
fun mosaicBlockPx(longEdgePx: Int): Int = max(8, (longEdgePx / 64f).roundToInt())

/**
 * Midpoints for quadratic smoothing.
 *
 * Returned as plain numbers rather than a Path because Compose and android.graphics have different
 * Path types, and this is the part worth sharing and testing. Each entry is
 * `(controlX, controlY, endX, endY)`: the control point is the sample, the end point is the midpoint
 * to the next sample, which is what removes the visible facets a slow finger produces with lineTo.
 */
fun smoothSegments(points: List<SourcePoint>): List<FloatArray> {
    if (points.size < 2) return emptyList()
    val out = ArrayList<FloatArray>(points.size)
    for (i in 0 until points.size - 1) {
        val a = points[i]
        val b = points[i + 1]
        out.add(floatArrayOf(a.x, a.y, (a.x + b.x) / 2f, (a.y + b.y) / 2f))
    }
    val last = points.last()
    out.add(floatArrayOf(last.x, last.y, last.x, last.y))
    return out
}
