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
