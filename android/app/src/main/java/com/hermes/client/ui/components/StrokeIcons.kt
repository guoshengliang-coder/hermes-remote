package com.hermes.client.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// Shared thin-stroke (1.7dp) icon set — the same brush as the card page's hand-drawn glyphs,
// for list surfaces that need matching outline icons. Tinted by Icon like any vector.

private fun strokeIcon(name: String, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }.build()

/** Hollow folder for project rows — the filled Material glyph read as a solid colour block. */
val FolderStrokeIcon: ImageVector by lazy {
    strokeIcon("StrokeFolder") {
        // Tab-top folder silhouette.
        moveTo(3.5f, 7f)
        arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 5.5f, y1 = 5f)
        lineTo(9.3f, 5f)
        lineTo(11.3f, 7.3f)
        lineTo(18.5f, 7.3f)
        arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 20.5f, y1 = 9.3f)
        lineTo(20.5f, 17f)
        arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 18.5f, y1 = 19f)
        lineTo(5.5f, 19f)
        arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 3.5f, y1 = 17f)
        close()
    }
}

/** Archive box (lid + body + handle) for archived rows, matching the folder's weight. */
val ArchiveBoxIcon: ImageVector by lazy {
    strokeIcon("StrokeArchiveBox") {
        // Lid.
        moveTo(4f, 5f)
        lineTo(20f, 5f)
        arcTo(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 21f, y1 = 6f)
        lineTo(21f, 8f)
        arcTo(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 20f, y1 = 9f)
        lineTo(4f, 9f)
        arcTo(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 3f, y1 = 8f)
        lineTo(3f, 6f)
        arcTo(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 4f, y1 = 5f)
        close()
        // Body.
        moveTo(4.5f, 9f)
        lineTo(4.5f, 17f)
        arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 6.5f, y1 = 19f)
        lineTo(17.5f, 19f)
        arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 19.5f, y1 = 17f)
        lineTo(19.5f, 9f)
        // Handle.
        moveTo(10f, 12.5f)
        lineTo(14f, 12.5f)
    }
}

/** Folder with a house mark: the DEFAULT project (the gateway's launch directory). */
val HomeFolderStrokeIcon: ImageVector by lazy {
    strokeIcon("StrokeHomeFolder") {
        // Same folder silhouette as [FolderStrokeIcon].
        moveTo(3.5f, 7f)
        arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 5.5f, y1 = 5f)
        lineTo(9.3f, 5f)
        lineTo(11.3f, 7.3f)
        lineTo(18.5f, 7.3f)
        arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 20.5f, y1 = 9.3f)
        lineTo(20.5f, 17f)
        arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 18.5f, y1 = 19f)
        lineTo(5.5f, 19f)
        arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 3.5f, y1 = 17f)
        close()
        // House: roof apex + walls, centred in the folder body.
        moveTo(9.5f, 16f)
        lineTo(9.5f, 12.8f)
        lineTo(12f, 10.7f)
        lineTo(14.5f, 12.8f)
        lineTo(14.5f, 16f)
        close()
    }
}

/** Git branch glyph (trunk with two nodes and a merge curve) for branch sublines. */
val BranchStrokeIcon: ImageVector by lazy {
    strokeIcon("StrokeBranch") {
        moveTo(6f, 3f)
        lineTo(6f, 15f)
        // Lower node.
        moveTo(6f, 15f)
        arcTo(3f, 3f, 0f, isMoreThanHalf = true, isPositiveArc = false, x1 = 6.01f, y1 = 21f)
        // Upper-right node.
        moveTo(18f, 9f)
        arcTo(3f, 3f, 0f, isMoreThanHalf = true, isPositiveArc = false, x1 = 18.01f, y1 = 3f)
        // Merge curve from the right node into the trunk.
        moveTo(18f, 9f)
        curveTo(18f, 13f, 14f, 13f, 9f, 15f)
    }
}

/** Thin trailing chevron for tappable entry rows (icon + title + chevron paradigm). */
val ThinChevronIcon: ImageVector by lazy {
    strokeIcon("StrokeThinChevron") {
        moveTo(9.5f, 5.5f); lineTo(16f, 12f); lineTo(9.5f, 18.5f)
    }
}

// Small-icon compensation (docs/DESIGN.md §4.1): the 1.7dp stroke is tuned for 24dp glyphs. An
// icon embedded at 16–18dp scales that stroke to ~1.2dp — thinner than the text beside it —
// so glyphs meant for pills and rows are drawn at 2.4 (≈1.8dp at 18dp), matching labelLarge.
private fun smallStrokeIcon(name: String, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.4f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }.build()

/** Arrow pressed against a top line: "back to the start of this turn" (turn-jump pill, prompt list). */
val ArrowToTopIcon: ImageVector by lazy {
    smallStrokeIcon("StrokeArrowToTop") {
        moveTo(5f, 5f)
        lineTo(19f, 5f)
        moveTo(12f, 20f)
        lineTo(12f, 9f)
        moveTo(7.5f, 13.5f)
        lineTo(12f, 9f)
        lineTo(16.5f, 13.5f)
    }
}

/** Three lines with leading dots: the prompt list (pill segment and top-bar menu). */
val PromptListIcon: ImageVector by lazy {
    smallStrokeIcon("StrokePromptList") {
        for (y in listOf(6f, 12f, 18f)) {
            moveTo(9f, y)
            lineTo(20f, y)
            // A zero-length round-capped stroke renders as a dot.
            moveTo(4f, y)
            lineTo(4.01f, y)
        }
    }
}

/** Pencil — the "edit this identity" row action on the profile picker. */
val PencilStrokeIcon: ImageVector by lazy {
    strokeIcon("StrokePencil") {
        moveTo(4f, 20f); lineTo(8.2f, 20f); lineTo(19f, 9.2f)
        arcTo(1.6f, 1.6f, 0f, false, false, 19f, 6.9f)
        lineTo(17.1f, 5f)
        arcTo(1.6f, 1.6f, 0f, false, false, 14.8f, 5f)
        lineTo(4f, 15.8f); close()
        moveTo(13.5f, 6.3f); lineTo(17.7f, 10.5f)
    }
}

/** Camera — the "change photo" badge on the identity settings avatar. */
val CameraStrokeIcon: ImageVector by lazy {
    strokeIcon("StrokeCamera") {
        moveTo(4f, 8.5f)
        arcTo(1.5f, 1.5f, 0f, false, true, 5.5f, 7f)
        lineTo(8f, 7f); lineTo(9.4f, 5f); lineTo(14.6f, 5f); lineTo(16f, 7f); lineTo(18.5f, 7f)
        arcTo(1.5f, 1.5f, 0f, false, true, 20f, 8.5f)
        lineTo(20f, 17.5f)
        arcTo(1.5f, 1.5f, 0f, false, true, 18.5f, 19f)
        lineTo(5.5f, 19f)
        arcTo(1.5f, 1.5f, 0f, false, true, 4f, 17.5f)
        close()
        moveTo(15.2f, 13f)
        arcTo(3.2f, 3.2f, 0f, true, true, 8.8f, 13f)
        arcTo(3.2f, 3.2f, 0f, true, true, 15.2f, 13f)
    }
}
