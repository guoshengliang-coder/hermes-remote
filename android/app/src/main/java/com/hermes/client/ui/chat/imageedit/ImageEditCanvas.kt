package com.hermes.client.ui.chat.imageedit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.hermes.client.data.media.imageedit.CropBox
import com.hermes.client.data.media.imageedit.FitTransform
import com.hermes.client.data.media.imageedit.ImageEditDocument
import com.hermes.client.data.media.imageedit.ImageEditOp
import com.hermes.client.data.media.imageedit.SourcePoint
import com.hermes.client.data.media.imageedit.appendDecimated
import com.hermes.client.data.media.imageedit.boxToSource
import com.hermes.client.data.media.imageedit.cropToSourceRect
import com.hermes.client.data.media.imageedit.fitTransform
import com.hermes.client.data.media.imageedit.mosaicBrushPx
import com.hermes.client.data.media.imageedit.smoothSegments
import com.hermes.client.data.media.imageedit.sourceToBox
import com.hermes.client.data.media.imageedit.strokeWidthPx
import com.hermes.client.data.media.imageedit.viewportToBox
import com.hermes.client.ui.theme.inkArgb
import kotlin.math.max

/** Zoom range for the editor's viewport. Independent of the ops; it only changes what you look at. */
private const val MIN_VIEWPORT_SCALE = 1f
private const val MAX_VIEWPORT_SCALE = 6f

/**
 * The editor's drawing surface: the photo under the current crop and rotation, with committed ops
 * and the in-flight stroke drawn over it.
 *
 * The live renderer and [com.hermes.client.data.media.imageedit.ImageEditBaker] consume the same op
 * list in the same source-pixel space, which is what makes the preview honest rather than
 * approximately right.
 */
@Composable
internal fun ImageEditCanvas(
    working: ImageBitmap,
    pixelated: ImageBitmap?,
    state: ImageEditorState,
    onCommit: (ImageEditDocument) -> Unit,
    onCropChange: (CropBox) -> Unit,
    onCropSettled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val doc = state.document
    val crop = remember(doc) { cropToSourceRect(doc.effectiveCrop, working.width, working.height) }
    val longEdge = max(working.width, working.height)

    // Viewport zoom. Two fingers always drive it, in every tool, and never leave a mark.
    var viewportScale by remember { mutableStateOf(1f) }
    var viewportOffset by remember { mutableStateOf(Offset.Zero) }
    // The stroke being drawn lives outside the document until the finger lifts, so one undo removes
    // exactly one visible thing.
    var inFlight by remember { mutableStateOf<ImageEditOp?>(null) }

    BoxWithConstraints(modifier) {
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        val fit = remember(crop, doc.quarterTurns, boxW, boxH) {
            fitTransform(crop, doc.quarterTurns, boxW, boxH)
        }

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = viewportScale,
                    scaleY = viewportScale,
                    translationX = viewportOffset.x,
                    translationY = viewportOffset.y,
                )
                .pointerInput(state.tool, crop, doc.quarterTurns, fit, state.ink, state.weight, state.brush) {
                    awaitEditGestures(
                        tool = state.tool,
                        onZoom = { zoom, pan ->
                            val next = (viewportScale * zoom).coerceIn(MIN_VIEWPORT_SCALE, MAX_VIEWPORT_SCALE)
                            // Snapping back to centre at 1x is what stops a pinch-out-then-in from
                            // leaving the photo parked off to one side.
                            viewportOffset = if (next <= MIN_VIEWPORT_SCALE) Offset.Zero else viewportOffset + pan
                            viewportScale = next
                        },
                        toSource = { position ->
                            val (bx, by) = viewportToBox(
                                position.x, position.y,
                                viewportScale, viewportOffset.x, viewportOffset.y,
                                boxW, boxH,
                            )
                            boxToSource(bx, by, crop, doc.quarterTurns, fit)
                        },
                        onStart = { point ->
                            inFlight = when (state.tool) {
                                EditorTool.DOODLE -> ImageEditOp.Stroke(listOf(point), state.ink, state.weight)
                                EditorTool.MOSAIC -> ImageEditOp.Mosaic(listOf(point), state.brush)
                                EditorTool.CROP -> null
                            }
                        },
                        onMove = { point ->
                            inFlight = when (val op = inFlight) {
                                is ImageEditOp.Stroke -> op.copy(points = op.points.appendDecimated(point))
                                is ImageEditOp.Mosaic -> op.copy(points = op.points.appendDecimated(point))
                                null -> null
                            }
                        },
                        onEnd = {
                            inFlight?.let { onCommit(doc.withOp(it)) }
                            inFlight = null
                        },
                        onCancel = { inFlight = null },
                    )
                },
        ) {
            // Committed ops and the in-flight stroke are separate Canvas nodes on purpose: if the
            // committed layer's draw lambda read the in-flight state, every pointer move would
            // repaint all forty ops.
            Canvas(Modifier.fillMaxSize()) {
                clipRect(
                    left = fit.originX,
                    top = fit.originY,
                    right = fit.originX + fit.displayWidth,
                    bottom = fit.originY + fit.displayHeight,
                ) {
                    // Only the photo is rotated here. The ops must stay outside this block:
                    // sourceToBox already maps them through the same quarter turn, and rotating
                    // them twice is exactly the kind of drift the shared arithmetic exists to
                    // prevent.
                    val centre = Offset(
                        fit.originX + fit.displayWidth / 2f,
                        fit.originY + fit.displayHeight / 2f,
                    )
                    val uprightW = crop.width * fit.scale
                    val uprightH = crop.height * fit.scale
                    rotate(degrees = 90f * doc.quarterTurns.mod(4), pivot = centre) {
                        drawImage(
                            image = working,
                            srcOffset = IntOffset(crop.left, crop.top),
                            srcSize = IntSize(crop.width, crop.height),
                            dstOffset = IntOffset(
                                (centre.x - uprightW / 2f).toInt(),
                                (centre.y - uprightH / 2f).toInt(),
                            ),
                            dstSize = IntSize(uprightW.toInt(), uprightH.toInt()),
                        )
                    }
                    doc.ops.forEach { drawOp(it, crop, doc.quarterTurns, fit, longEdge, pixelated, working) }
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                inFlight?.let { op ->
                    clipRect(
                        left = fit.originX,
                        top = fit.originY,
                        right = fit.originX + fit.displayWidth,
                        bottom = fit.originY + fit.displayHeight,
                    ) {
                        drawOp(op, crop, doc.quarterTurns, fit, longEdge, pixelated, working)
                    }
                }
            }
            if (state.tool == EditorTool.CROP) {
                CropOverlay(
                    box = crop,
                    imageWidth = working.width,
                    imageHeight = working.height,
                    quarterTurns = doc.quarterTurns,
                    fit = fit,
                    aspectRatio = state.aspect,
                    onCropChange = onCropChange,
                    onCropSettled = onCropSettled,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * Rotation is applied by mapping every point through [sourceToBox], not by rotating the canvas, so
 * the live path and the baked path go through exactly the same arithmetic.
 */
private fun DrawScope.drawOp(
    op: ImageEditOp,
    crop: CropBox,
    quarterTurns: Int,
    fit: FitTransform,
    longEdge: Int,
    pixelated: ImageBitmap?,
    working: ImageBitmap,
) {
    val widthSource = when (op) {
        is ImageEditOp.Stroke -> strokeWidthPx(op.weight, longEdge)
        is ImageEditOp.Mosaic -> mosaicBrushPx(op.brush, longEdge)
    }
    val width = widthSource * fit.scale
    val path = viewPath(op.points, crop, quarterTurns, fit, width)
    when (op) {
        is ImageEditOp.Stroke -> drawPath(
            path = path,
            color = Color(inkArgb(op.color)),
            style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        is ImageEditOp.Mosaic -> {
            val source = pixelated ?: return
            // Same structure as the baker: one stroked path painted with a shader of the pixelated
            // image. No saveLayer, no mask bitmap, and no second code path to drift.
            val shader = ImageShader(source, TileMode.Clamp, TileMode.Clamp)
            drawPath(
                path = path,
                brush = ShaderBrush(shader),
                style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

private fun viewPath(
    points: List<SourcePoint>,
    crop: CropBox,
    quarterTurns: Int,
    fit: FitTransform,
    width: Float,
): Path {
    val path = Path()
    if (points.isEmpty()) return path
    val mapped = points.map { sourceToBox(it, crop, quarterTurns, fit) }
    if (mapped.size == 1) {
        // A tap is one point, and a one-point path strokes nothing.
        path.addOval(
            androidx.compose.ui.geometry.Rect(
                Offset(mapped[0].first, mapped[0].second),
                width / 2f,
            ),
        )
        return path
    }
    path.moveTo(mapped[0].first, mapped[0].second)
    val asSource = mapped.map { SourcePoint(it.first, it.second) }
    smoothSegments(asSource).forEach { path.quadraticTo(it[0], it[1], it[2], it[3]) }
    return path
}

/**
 * One gesture loop for every tool.
 *
 * Two or more pointers is always a viewport zoom and never a mark — including in the crop tool, so
 * the user can zoom in to trim precisely. A single pointer belongs to the active tool; in CROP the
 * overlay handles it instead, and this loop deliberately produces nothing, which is the mode
 * isolation the tests pin.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.awaitEditGestures(
    tool: EditorTool,
    onZoom: (zoom: Float, pan: Offset) -> Unit,
    toSource: (Offset) -> SourcePoint,
    onStart: (SourcePoint) -> Unit,
    onMove: (SourcePoint) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var multiTouch = false
        var drawing = false
        if (tool != EditorTool.CROP) {
            onStart(toSource(down.position))
            drawing = true
        }
        do {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break

            if (pressed.size >= 2) {
                if (drawing) {
                    // A second finger means the user wanted to zoom, not to draw. Throw away the
                    // accidental mark rather than committing it.
                    onCancel()
                    drawing = false
                }
                multiTouch = true
                val zoom = if (event.calculateCentroidSize() > 0f) event.calculateZoom() else 1f
                onZoom(zoom, event.calculatePan())
                event.changes.forEach { it.consume() }
                continue
            }

            if (multiTouch || !drawing) continue
            onMove(toSource(pressed.first().position))
            event.changes.forEach { it.consume() }
        } while (true)

        if (drawing) onEnd()
    }
}

/** The viewport zoom bounds, exposed so the toolbar can show whether zooming is available. */
internal val EditorViewportRange = MIN_VIEWPORT_SCALE..MAX_VIEWPORT_SCALE
