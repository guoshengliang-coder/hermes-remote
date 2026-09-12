package com.hermes.client.ui.chat.imageedit

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import com.hermes.client.data.media.imageedit.BrushSize
import com.hermes.client.data.media.imageedit.CropAspect
import com.hermes.client.data.media.imageedit.CropBox
import com.hermes.client.data.media.imageedit.EditHistory
import com.hermes.client.data.media.imageedit.ImageEditDocument
import com.hermes.client.data.media.imageedit.ImageEditOp
import com.hermes.client.data.media.imageedit.SourcePoint
import com.hermes.client.data.media.imageedit.StrokeWeight
import com.hermes.client.ui.theme.InkColor

/** Which tool the editor is currently in. */
enum class EditorTool { DOODLE, MOSAIC, CROP }

/**
 * Rotation destroys a Dialog's content, so the document has to survive it or a turn of the wrist
 * silently discards every stroke.
 *
 * The document is all floats, ints and enum ordinals by construction, so it flattens into a plain
 * list of primitives with no custom parcelling. Ops are encoded as
 * `[tag, colourOrBrushOrdinal, weightOrdinal, x0, y0, x1, y1, ...]`.
 *
 * Saved state is not unbounded — it travels through a Bundle, and a transaction that overruns takes
 * the whole activity down. [SAVED_POINT_BUDGET] therefore keeps the **most recent** ops and drops
 * the oldest, since a rotation mid-edit should preserve what the user was just doing. In practice
 * the budget is far above any real annotation: it is a guard against a pathological scribble, not a
 * routine limit.
 *
 * The undo stack is deliberately not saved. Forty documents would be forty times this payload, and
 * keeping the drawing is worth much more than keeping the ability to step back past a rotation.
 */
const val SAVED_POINT_BUDGET = 20_000

private fun ImageEditDocument.withinSaveBudget(): ImageEditDocument {
    var total = 0
    val kept = ArrayList<ImageEditOp>(ops.size)
    for (op in ops.asReversed()) {
        total += op.points.size
        if (total > SAVED_POINT_BUDGET) break
        kept.add(op)
    }
    if (kept.size == ops.size) return this
    return copy(ops = kept.asReversed())
}

val ImageEditDocumentSaver: Saver<ImageEditDocument, Any> = listSaver(
    save = { original ->
        val doc = original.withinSaveBudget()
        buildList {
            add(doc.sourceWidth)
            add(doc.sourceHeight)
            add(doc.quarterTurns)
            add(doc.crop?.left ?: -1)
            add(doc.crop?.top ?: -1)
            add(doc.crop?.right ?: -1)
            add(doc.crop?.bottom ?: -1)
            add(doc.ops.size)
            doc.ops.forEach { op ->
                when (op) {
                    is ImageEditOp.Stroke -> {
                        add(0)
                        add(op.color.ordinal)
                        add(op.weight.ordinal)
                    }
                    is ImageEditOp.Mosaic -> {
                        add(1)
                        add(op.brush.ordinal)
                        add(0)
                    }
                }
                add(op.points.size)
                op.points.forEach { add(it.x); add(it.y) }
            }
        }
    },
    restore = { saved ->
        val values = saved
        var i = 0
        fun nextInt(): Int = (values[i++] as Number).toInt()
        fun nextFloat(): Float = (values[i++] as Number).toFloat()

        val sourceWidth = nextInt()
        val sourceHeight = nextInt()
        val quarterTurns = nextInt()
        val left = nextInt()
        val top = nextInt()
        val right = nextInt()
        val bottom = nextInt()
        val crop = if (left < 0) null else CropBox(left, top, right, bottom)
        val opCount = nextInt()
        val ops = ArrayList<ImageEditOp>(opCount)
        repeat(opCount) {
            val tag = nextInt()
            val first = nextInt()
            val second = nextInt()
            val pointCount = nextInt()
            val points = ArrayList<SourcePoint>(pointCount)
            repeat(pointCount) { points.add(SourcePoint(nextFloat(), nextFloat())) }
            ops.add(
                if (tag == 0) {
                    ImageEditOp.Stroke(points, InkColor.entries[first], StrokeWeight.entries[second])
                } else {
                    ImageEditOp.Mosaic(points, BrushSize.entries[first])
                },
            )
        }
        ImageEditDocument(sourceWidth, sourceHeight, ops, crop, quarterTurns)
    },
)

/** Everything the editor holds that is not the bitmap itself. */
data class ImageEditorState(
    val history: EditHistory,
    val tool: EditorTool = EditorTool.DOODLE,
    val ink: InkColor = InkColor.RED,
    val weight: StrokeWeight = StrokeWeight.MEDIUM,
    val brush: BrushSize = BrushSize.MEDIUM,
    val aspect: CropAspect = CropAspect.FREE,
) {
    val document: ImageEditDocument get() = history.present
}
