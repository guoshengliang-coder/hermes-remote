package com.hermes.client.data.media.imageedit

import com.hermes.client.ui.theme.InkColor

/**
 * The image editor's document: an ordered list of operations over an unmodified base bitmap, plus a
 * crop and a rotation held as separate fields.
 *
 * Operations rather than a bitmap baked after each action, for three reasons. Undo would otherwise
 * need a bitmap snapshot per step, which is about 88 MB for six steps at 2560x1440. A baked crop
 * could never be widened again. And what you see is what you get *by construction*: the live
 * renderer and the baker consume the same numbers in the same coordinate space, so there is no
 * second source of truth to drift.
 */

/** A point in **working-bitmap pixels** — never view coordinates, never normalised. */
data class SourcePoint(val x: Float, val y: Float)

/** Pen weight, as a step rather than a pixel count. See [strokeWidthPx]. */
enum class StrokeWeight { THIN, MEDIUM, THICK }

/** Mosaic brush size, as a step. See [mosaicBrushPx]. */
enum class BrushSize { SMALL, MEDIUM, LARGE }

/**
 * One editing operation.
 *
 * Ops carry palette ids and size steps, not colours and pixel counts, which keeps the document
 * theme-independent, resolution-independent, and trivial to serialise for the rotation Saver.
 */
sealed interface ImageEditOp {
    val points: List<SourcePoint>

    data class Stroke(
        override val points: List<SourcePoint>,
        val color: InkColor,
        val weight: StrokeWeight,
    ) : ImageEditOp

    data class Mosaic(
        override val points: List<SourcePoint>,
        val brush: BrushSize,
    ) : ImageEditOp
}

/** A crop rectangle in **unrotated source pixels**. Rotation never mutates it. */
data class CropBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** Aspect presets for the crop tool, interpreted along the current rotation's long axis. */
enum class CropAspect(val ratio: Float?) {
    FREE(null),
    ORIGINAL(null),
    SQUARE(1f),
    FOUR_THREE(4f / 3f),
    SIXTEEN_NINE(16f / 9f),
}

data class ImageEditDocument(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val ops: List<ImageEditOp> = emptyList(),
    val crop: CropBox? = null,
    /** Clockwise quarter turns, 0..3. */
    val quarterTurns: Int = 0,
) {
    /** The crop actually in effect; null means the whole frame. */
    val effectiveCrop: CropBox get() = crop ?: CropBox(0, 0, sourceWidth, sourceHeight)

    /** True when finishing would produce the input unchanged, so the bytes can be returned as-is. */
    val isUntouched: Boolean get() = ops.isEmpty() && crop == null && quarterTurns == 0

    fun withOp(op: ImageEditOp): ImageEditDocument = copy(ops = ops + op)

    /** Clears annotations but keeps the crop — the crop has its own reset. */
    fun clearedOps(): ImageEditDocument = copy(ops = emptyList())

    /** Clears crop and rotation but keeps annotations. */
    fun clearedCrop(): ImageEditDocument = copy(crop = null, quarterTurns = 0)

    fun rotatedQuarter(): ImageEditDocument = copy(quarterTurns = (quarterTurns + 1) % 4)
}

/** Points closer together than this (in working pixels) are dropped while capturing a stroke. */
const val MIN_POINT_SPACING_PX = 2f

/** Hard cap on one stroke's point count. Reaching it stops appending; it does not end the gesture. */
const val MAX_STROKE_POINTS = 2000

/**
 * Append [point] unless it is within [MIN_POINT_SPACING_PX] of the last one, or the stroke is
 * already at [MAX_STROKE_POINTS].
 *
 * Decimation keeps the document, the Saver payload and the per-frame path rebuild bounded; a finger
 * dragged slowly across a 2560px image otherwise emits thousands of near-identical points.
 */
fun List<SourcePoint>.appendDecimated(point: SourcePoint): List<SourcePoint> {
    if (size >= MAX_STROKE_POINTS) return this
    val last = lastOrNull() ?: return listOf(point)
    val dx = point.x - last.x
    val dy = point.y - last.y
    if (dx * dx + dy * dy < MIN_POINT_SPACING_PX * MIN_POINT_SPACING_PX) return this
    return this + point
}
