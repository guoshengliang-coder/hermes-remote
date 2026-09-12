package com.hermes.client.ui.chat.imageedit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hermes.client.data.media.imageedit.CropAspect
import com.hermes.client.data.media.imageedit.CropBox
import com.hermes.client.data.media.imageedit.CropHandle
import com.hermes.client.data.media.imageedit.FitTransform
import com.hermes.client.data.media.imageedit.MIN_CROP_SOURCE_PX
import com.hermes.client.data.media.imageedit.MIN_CROP_VIEW_DP
import com.hermes.client.data.media.imageedit.SourcePoint
import com.hermes.client.data.media.imageedit.applyAspect
import com.hermes.client.data.media.imageedit.aspectRatioFor
import com.hermes.client.data.media.imageedit.dragHandle
import com.hermes.client.data.media.imageedit.hitHandle
import com.hermes.client.data.media.imageedit.sourceToBox
import kotlin.math.roundToInt

/** Lets a test aim at a handle from the overlay's own measured bounds instead of guessing. */
const val CropOverlayTestTag = "crop-overlay"

/**
 * The crop rectangle drawn over the photo, with eight handles and an interior move target.
 *
 * The box is stored in unrotated source pixels and projected here, which is why rotating the image
 * never has to touch the box data.
 */
@Composable
internal fun CropOverlay(
    box: CropBox,
    imageWidth: Int,
    imageHeight: Int,
    quarterTurns: Int,
    fit: FitTransform,
    aspectRatio: CropAspect,
    onCropChange: (CropBox) -> Unit,
    onCropSettled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val hitRadiusPx = with(density) { 24.dp.toPx() }
    // The minimum side is a touch constraint, not a visual one: below about 96dp the neighbouring
    // 48dp hit circles overlap and the corner becomes ungrabbable.
    val minSourceSide = remember(fit.scale, density) {
        val fromTouch = with(density) { MIN_CROP_VIEW_DP.dp.toPx() } / fit.scale.coerceAtLeast(0.0001f)
        fromTouch.roundToInt().coerceAtLeast(MIN_CROP_SOURCE_PX)
    }

    var dragging by remember { mutableStateOf<CropHandle?>(null) }
    val ratio = aspectRatioFor(aspectRatio, imageWidth, imageHeight, quarterTurns)

    // The box's four corners in view space, via exactly the same mapping the strokes use.
    fun corner(x: Int, y: Int): Offset {
        val (vx, vy) = sourceToBox(SourcePoint(x.toFloat(), y.toFloat()), box, quarterTurns, fit)
        return Offset(vx, vy)
    }
    val topLeft = corner(box.left, box.top)
    val bottomRight = corner(box.right, box.bottom)
    val left = minOf(topLeft.x, bottomRight.x)
    val right = maxOf(topLeft.x, bottomRight.x)
    val top = minOf(topLeft.y, bottomRight.y)
    val bottom = maxOf(topLeft.y, bottomRight.y)

    Canvas(
        modifier.testTag(CropOverlayTestTag).pointerInput(box, fit, quarterTurns, ratio, minSourceSide) {
            detectDragGestures(
                onDragStart = { position ->
                    dragging = hitHandle(position.x, position.y, left, top, right, bottom, hitRadiusPx)
                },
                // One history entry per gesture, pushed on release. Committing inside onDrag
                // would stack dozens of undo steps for a single handle drag.
                onDragEnd = { dragging = null; onCropSettled() },
                onDragCancel = { dragging = null; onCropSettled() },
                onDrag = { change, delta ->
                    val handle = dragging ?: return@detectDragGestures
                    change.consume()
                    // View delta -> source delta. Rotation permutes the axes, so the delta goes
                    // through the same quarter-turn logic the points do.
                    val dxView = delta.x / fit.scale
                    val dyView = delta.y / fit.scale
                    val (dx, dy) = when (quarterTurns.mod(4)) {
                        0 -> dxView to dyView
                        1 -> dyView to -dxView
                        2 -> -dxView to -dyView
                        else -> -dyView to dxView
                    }
                    var next = dragHandle(
                        box,
                        handle,
                        dx.roundToInt(),
                        dy.roundToInt(),
                        imageWidth,
                        imageHeight,
                        minSourceSide,
                    )
                    if (ratio != null && handle != CropHandle.MOVE) {
                        next = applyAspect(next, ratio, imageWidth, imageHeight, handle, minSourceSide)
                    }
                    onCropChange(next)
                },
            )
        },
    ) {
        val scrim = Color.Black.copy(alpha = 0.4f)
        // Scrim everything outside the box, in four bands rather than a path so it stays cheap.
        drawRect(scrim, Offset.Zero, Size(size.width, top.coerceAtLeast(0f)))
        drawRect(scrim, Offset(0f, bottom), Size(size.width, (size.height - bottom).coerceAtLeast(0f)))
        drawRect(scrim, Offset(0f, top), Size(left.coerceAtLeast(0f), bottom - top))
        drawRect(scrim, Offset(right, top), Size((size.width - right).coerceAtLeast(0f), bottom - top))

        drawRect(
            color = Color.White.copy(alpha = 0.8f),
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            style = Stroke(width = 1.5.dp.toPx()),
        )

        // Thirds only while dragging: at rest the photo has to be readable.
        if (dragging != null) {
            val hair = Color.White.copy(alpha = 0.35f)
            val w = right - left
            val h = bottom - top
            for (i in 1..2) {
                val x = left + w * i / 3f
                val y = top + h * i / 3f
                drawLine(hair, Offset(x, top), Offset(x, bottom), strokeWidth = 1.dp.toPx())
                drawLine(hair, Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
            }
        }

        val arm = 24.dp.toPx()
        val thickness = 3.dp.toPx()
        val white = Color.White
        fun bracket(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(white, Offset(x, y), Offset(x + dx * arm, y), strokeWidth = thickness, cap = StrokeCap.Round)
            drawLine(white, Offset(x, y), Offset(x, y + dy * arm), strokeWidth = thickness, cap = StrokeCap.Round)
        }
        bracket(left, top, 1f, 1f)
        bracket(right, top, -1f, 1f)
        bracket(left, bottom, 1f, -1f)
        bracket(right, bottom, -1f, -1f)

        val bar = 20.dp.toPx()
        val midX = (left + right) / 2f
        val midY = (top + bottom) / 2f
        drawLine(white, Offset(midX - bar / 2, top), Offset(midX + bar / 2, top), strokeWidth = thickness, cap = StrokeCap.Round)
        drawLine(white, Offset(midX - bar / 2, bottom), Offset(midX + bar / 2, bottom), strokeWidth = thickness, cap = StrokeCap.Round)
        drawLine(white, Offset(left, midY - bar / 2), Offset(left, midY + bar / 2), strokeWidth = thickness, cap = StrokeCap.Round)
        drawLine(white, Offset(right, midY - bar / 2), Offset(right, midY + bar / 2), strokeWidth = thickness, cap = StrokeCap.Round)
    }
}
