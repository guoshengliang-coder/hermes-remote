package com.hermes.client.ui.components

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hermes.client.ui.theme.Motion
import kotlinx.coroutines.delay

// Brand loading motion, derived from the launcher icon (design: docs/design/loading-motion.html,
// contract: docs/DESIGN.md §5.6). Three shapes cover every indeterminate wait in the app:
//   HermesMark    — the icon's H with a light sweeping around the crossbar centre. 14/20/32dp.
//   SkeletonRows  — first load of a list whose row shape is known and stable.
//   TopProgressLine — refresh that must not cover content the user is already reading.
// All three run on one 1200ms period and one 250ms reveal gate, and all three are single-colour:
// the multicolour icon belongs to the startup gate only (§2.1 keeps status colours out of chrome).

/** The H on the icon's 24-unit grid: bar = 28.6% of the width, crossbar = 36.7%–63.3% of it. */
private const val BAR = 0.286f
private const val CROSS_TOP = 0.367f
private const val CROSS_BOTTOM = 0.633f

/** The icon's own 14:15 proportion, so the mark is never a stretched H. */
const val MARK_ASPECT = 14f / 15f

private const val DIM_ALPHA = 0.30f

/** Reduce-motion parks the sweep at a flat, readable fraction of the lit face. */
private const val STILL_ALPHA = 0.6f

/** Test seam for the system "remove animations" setting; null = read the real setting. */
val LocalReduceMotion = staticCompositionLocalOf<Boolean?> { null }

@Composable
fun reduceMotion(): Boolean {
    val override = LocalReduceMotion.current
    val system = remember { !ValueAnimator.areAnimatorsEnabled() }
    return override ?: system
}

fun hermesMarkPath(size: Size): Path {
    val w = size.width
    val h = size.height
    val bar = w * BAR
    val top = h * CROSS_TOP
    val bottom = h * CROSS_BOTTOM
    return Path().apply {
        moveTo(0f, 0f)
        lineTo(bar, 0f)
        lineTo(bar, top)
        lineTo(w - bar, top)
        lineTo(w - bar, 0f)
        lineTo(w, 0f)
        lineTo(w, h)
        lineTo(w - bar, h)
        lineTo(w - bar, bottom)
        lineTo(bar, bottom)
        lineTo(bar, h)
        lineTo(0f, h)
        close()
    }
}

/**
 * The brand loading mark: the H stands still while one light lobe rotates around the crossbar
 * centre — the icon's own logic (one light source over folded faces), not a spinning shape.
 */
@Composable
fun HermesMark(
    size: Dp = 32.dp,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    contentDescription: String? = null,
) {
    val still = reduceMotion()
    var sweep = 0f
    if (!still) {
        sweep = rememberInfiniteTransition(label = "hermes-mark").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(Motion.LoopPeriod, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "hermes-mark-sweep",
        ).value
    }
    Canvas(
        modifier
            .size(width = size * MARK_ASPECT, height = size)
            .testTag("hermes-mark")
            .semantics {
                if (contentDescription != null) this.contentDescription = contentDescription
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            },
    ) {
        val path = hermesMarkPath(this.size)
        if (still) {
            drawPath(path, color.copy(alpha = STILL_ALPHA))
            return@Canvas
        }
        val brush = Brush.sweepGradient(
            0f to color,
            0.28f to color.copy(alpha = DIM_ALPHA),
            0.72f to color.copy(alpha = DIM_ALPHA),
            1f to color,
            center = center,
        )
        clipPath(path) {
            rotate(sweep, pivot = center) {
                // Inflated square: a rotating rect must never expose a corner inside the clip.
                val reach = this.size.maxDimension
                drawRect(brush, topLeft = Offset(center.x - reach, center.y - reach), size = Size(reach * 2, reach * 2))
            }
        }
    }
}

/**
 * Nothing appears for the first [delayMs]: a wait that resolves inside the gate must look like
 * an instant result, not a flash of loading furniture (same rule as the sending bubble, §5.4).
 */
@Composable
fun DelayedReveal(
    delayMs: Long = Motion.RevealDelay.toLong(),
    content: @Composable () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(delayMs) {
        delay(delayMs)
        visible = true
    }
    if (reduceMotion()) {
        if (visible) content()
    } else {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(Motion.RevealFade, easing = Motion.Standard)),
            exit = ExitTransition.None,
        ) { content() }
    }
}

// ── Skeleton rows ────────────────────────────────────────────────────────────────────────────
// Only for lists whose row shape is known and stable (sessions / projects / archived / search all
// share one shape). Pages with unpredictable rows use HermesMark instead: a skeleton that guesses
// wrong is worse than a mark, because the content reflows the moment it arrives.

const val SKELETON_MAX_ROWS = 5
private val SkeletonSide = 16.dp
private val SkeletonRowPaddingV = 11.dp
private val SkeletonTitleHeight = 15.dp
private val SkeletonSublineHeight = 11.dp
private val SkeletonLineGap = 7.dp
private val SkeletonHeaderHeight = 10.dp
private val SkeletonHeaderPaddingTop = 8.dp
private val SkeletonHeaderPaddingBottom = 4.dp
private val SkeletonHeaderWidth = 56.dp
private val SkeletonCorner = 7.dp

/** Base fill of every skeleton block, app-wide (chat history and lists share this pair). */
@Composable
fun skeletonBaseColor(): Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)

/** Sweep highlight. onSurface-based, so one value works in both themes without a luminance branch. */
@Composable
fun skeletonHighlightColor(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.085f)

/** 0..1 sweep phase shared by every skeleton; parked mid-travel when animations are off. */
@Composable
fun rememberSkeletonSweep(): Float {
    if (reduceMotion()) return 0.5f
    return rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(Motion.LoopPeriod, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "skeleton-sweep",
    ).value
}

/** Deterministic widths: real titles are ragged, and identical bars read as a progress meter. */
private val TitleWidths = listOf(0.62f, 0.48f, 0.70f, 0.55f, 0.66f)
private val SublineWidths = listOf(0.38f, 0.44f, 0.32f, 0.40f, 0.30f)

private val SkeletonRowHeight =
    SkeletonRowPaddingV * 2 + SkeletonTitleHeight + SkeletonLineGap + SkeletonSublineHeight
private val SkeletonHeaderBlock =
    SkeletonHeaderPaddingTop + SkeletonHeaderHeight + SkeletonHeaderPaddingBottom

/** Total height of [rows] skeleton rows plus the group headers drawn before rows 0 and [headerAt]. */
fun skeletonHeight(rows: Int, headerAt: Int = 3): Dp {
    val capped = rows.coerceIn(0, SKELETON_MAX_ROWS)
    val headers = if (capped > headerAt) 2 else if (capped > 0) 1 else 0
    return SkeletonRowHeight * capped + SkeletonHeaderBlock * headers
}

/**
 * The whole page is one shape: every block is cut out of a single surface, so one highlight
 * crosses the page (the startup progress bar's sweep), not one twinkle per block.
 */
@Composable
fun SkeletonRows(
    rows: Int = SKELETON_MAX_ROWS,
    modifier: Modifier = Modifier,
    headerAt: Int = 3,
) {
    val capped = rows.coerceIn(0, SKELETON_MAX_ROWS)
    val still = reduceMotion()
    val progress = rememberSkeletonSweep()
    val base = skeletonBaseColor()
    val highlight = skeletonHighlightColor()
    Canvas(
        modifier
            .fillMaxWidth()
            .height(skeletonHeight(capped, headerAt))
            .testTag("skeleton-rows"),
    ) {
        val side = SkeletonSide.toPx()
        val corner = CornerRadius(SkeletonCorner.toPx())
        val path = Path()
        var y = 0f
        fun block(width: Float, height: Float) {
            path.addRoundRect(RoundRect(Rect(side, y, side + width, y + height), corner))
        }
        fun header() {
            y += SkeletonHeaderPaddingTop.toPx()
            block(SkeletonHeaderWidth.toPx(), SkeletonHeaderHeight.toPx())
            y += SkeletonHeaderHeight.toPx() + SkeletonHeaderPaddingBottom.toPx()
        }
        val usable = size.width - side * 2
        repeat(capped) { row ->
            if (row == 0 || row == headerAt) header()
            y += SkeletonRowPaddingV.toPx()
            block(usable * TitleWidths[row % TitleWidths.size], SkeletonTitleHeight.toPx())
            y += SkeletonTitleHeight.toPx() + SkeletonLineGap.toPx()
            block(usable * SublineWidths[row % SublineWidths.size], SkeletonSublineHeight.toPx())
            y += SkeletonSublineHeight.toPx() + SkeletonRowPaddingV.toPx()
        }
        clipPath(path) {
            drawRect(base)
            if (!still) {
                val band = size.width * 0.38f
                val centerX = progress * (size.width + band * 2) - band
                drawRect(
                    Brush.linearGradient(
                        colors = listOf(Color.Transparent, highlight, Color.Transparent),
                        start = Offset(centerX - band, 0f),
                        end = Offset(centerX + band, size.height),
                    ),
                )
            }
        }
    }
}

// ── Top progress line ────────────────────────────────────────────────────────────────────────

private val TopProgressHeight = 2.dp

/**
 * Refresh with content already on screen: never cover it, never swap it for a skeleton.
 * Single-colour on purpose — the startup gate's three-colour bar must stay unmistakable.
 */
@Composable
fun TopProgressLine(modifier: Modifier = Modifier) {
    val still = reduceMotion()
    // Parked mid-track (not off-screen at progress 0) so reduce-motion still shows a line.
    var progress = 0.5f
    if (!still) {
        progress = rememberInfiniteTransition(label = "top-progress").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(Motion.LoopPeriod, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "top-progress-travel",
        ).value
    }
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    val color = MaterialTheme.colorScheme.primary.copy(alpha = if (still) STILL_ALPHA else 1f)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(TopProgressHeight)
            .testTag("top-progress-line")
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
    ) {
        drawRect(track)
        val segment = size.width * 0.36f
        val start = progress * (size.width + segment) - segment
        drawRect(
            Brush.horizontalGradient(
                colors = listOf(Color.Transparent, color, Color.Transparent),
                startX = start,
                endX = start + segment,
            ),
            topLeft = Offset(start, 0f),
            size = Size(segment, size.height),
        )
    }
}
