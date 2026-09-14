package com.hermes.client.data.media.imageedit

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import com.hermes.client.ui.theme.inkArgb
import kotlin.math.ceil
import kotlin.math.max

/**
 * Composites an [ImageEditDocument] onto a bitmap.
 *
 * The whole coordinate story at bake time is one translate: ops are already stored in source pixels,
 * so shifting the canvas by the crop origin puts everything where it belongs and nothing can drift.
 */
object ImageEditBaker {

    /**
     * Largest source the editor will open. Checked from a bounds-only decode *before* allocating
     * anything, the way the transcript exporter checks its budget before mounting.
     */
    const val MAX_SOURCE_PIXELS = 60_000_000L

    /** Long-edge cap for the working bitmap. Ops are captured and baked at this resolution. */
    const val EDIT_MAX_EDGE = 2560

    /**
     * Down-sample factor for decoding a source whose long edge is [longEdge], so the working bitmap
     * lands at or under [EDIT_MAX_EDGE].
     */
    fun workingSampleSize(longEdge: Int): Int {
        var sample = 1
        while (longEdge / sample > EDIT_MAX_EDGE) sample *= 2
        return sample
    }

    fun fitsBudget(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && width.toLong() * height.toLong() <= MAX_SOURCE_PIXELS

    /**
     * Draw [doc] over [working] and return the result.
     *
     * [working] is left untouched; the caller still owns it, because cancelling an edit has to leave
     * the original intact.
     */
    fun bake(working: Bitmap, doc: ImageEditDocument): Bitmap {
        val crop = cropToSourceRect(doc.effectiveCrop, working.width, working.height)
        val out = Bitmap.createBitmap(crop.width, crop.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        // Source pixels -> output pixels. The only transform in the whole bake.
        canvas.translate(-crop.left.toFloat(), -crop.top.toFloat())
        canvas.drawBitmap(working, 0f, 0f, null)

        val longEdge = max(working.width, working.height)
        // Built only if something is actually mosaicked; it costs as much as the image itself.
        val pixelated: Bitmap? by lazy(LazyThreadSafetyMode.NONE) {
            if (doc.ops.any { it is ImageEditOp.Mosaic }) pixelate(working, mosaicBlockPx(longEdge)) else null
        }

        try {
            // List order is draw order, so a mosaic drawn after a stroke covers it.
            doc.ops.forEach { op ->
                when (op) {
                    is ImageEditOp.Stroke -> canvas.drawPath(
                        androidPath(op.points, strokeWidthPx(op.weight, longEdge)),
                        strokePaint(inkArgb(op.color), strokeWidthPx(op.weight, longEdge)),
                    )
                    is ImageEditOp.Mosaic -> {
                        val source = pixelated ?: return@forEach
                        val width = mosaicBrushPx(op.brush, longEdge)
                        canvas.drawPath(androidPath(op.points, width), mosaicPaint(source, width))
                    }
                }
            }
        } finally {
            pixelated?.recycle()
        }

        if (doc.quarterTurns.mod(4) == 0) return out
        val matrix = Matrix().apply { postRotate(90f * doc.quarterTurns.mod(4)) }
        val rotated = Bitmap.createBitmap(out, 0, 0, out.width, out.height, matrix, true)
        if (rotated !== out) out.recycle()
        return rotated
    }

    /**
     * Block-average [src] into [blockPx] squares.
     *
     * Two details decide whether this reads as deliberate redaction or as a compression artefact.
     *
     * First, the downscale is halved repeatedly rather than done in one step: `createScaledBitmap`
     * with filtering is bilinear and samples 2x2, so a single 40x reduction looks at 4 of each
     * block's 1600 pixels and produces noise. Each halving *is* a 2x2 box average, so repeated
     * halving is the box filter this needs.
     *
     * Second, the upscale is unfiltered and goes to an exact multiple of [blockPx] before being
     * cropped back, so block edges are hard and the grid is aligned rather than fractional.
     */
    fun pixelate(src: Bitmap, blockPx: Int): Bitmap {
        val block = blockPx.coerceAtLeast(1)
        val cols = max(1, ceil(src.width / block.toFloat()).toInt())
        val rows = max(1, ceil(src.height / block.toFloat()).toInt())

        var small = src
        while (small.width / 2 >= cols && small.height / 2 >= rows && small.width > 1 && small.height > 1) {
            val next = Bitmap.createScaledBitmap(small, small.width / 2, small.height / 2, true)
            if (small !== src) small.recycle()
            small = next
        }
        val averaged = Bitmap.createScaledBitmap(small, cols, rows, true)
        if (small !== src && small !== averaged) small.recycle()

        val grown = Bitmap.createScaledBitmap(averaged, cols * block, rows * block, false)
        if (averaged !== grown) averaged.recycle()

        val cropped = Bitmap.createBitmap(grown, 0, 0, src.width, src.height)
        if (grown !== cropped) grown.recycle()
        return cropped
    }

    private fun androidPath(points: List<SourcePoint>, width: Float): Path {
        val path = Path()
        if (points.isEmpty()) return path
        if (points.size == 1) {
            // A tap produces one point, and a one-point path strokes nothing. Draw the dot the user
            // actually made.
            path.addCircle(points[0].x, points[0].y, width / 2f, Path.Direction.CW)
            return path
        }
        path.moveTo(points[0].x, points[0].y)
        smoothSegments(points).forEach { path.quadTo(it[0], it[1], it[2], it[3]) }
        return path
    }

    private fun strokePaint(argb: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = argb
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /**
     * A stroke painted with the pixelated image as its shader.
     *
     * One draw call, no `saveLayer`, no alpha mask, and no per-op layer — a naive `saveLayer` per
     * mosaic op is about 14 MB each at 2560x1440. It also makes the live renderer and the baker
     * structurally the same code, which is what guarantees the preview matches the result.
     */
    private fun mosaicPaint(pixelated: Bitmap, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = BitmapShader(pixelated, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
}
