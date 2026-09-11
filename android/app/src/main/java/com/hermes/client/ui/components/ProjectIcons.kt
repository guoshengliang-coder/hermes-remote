package com.hermes.client.ui.components

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The glyphs a project can be given, in the same 1.7dp stroke system as the rest of the icon set
 * (docs/DESIGN.md §4) — filled Material glyphs would read as solid blocks next to the folder rows.
 *
 * The KEYS are upstream's codicon names, not ours. Hermes stores `icon` as a free string and the
 * desktop renders it as a codicon, so a project styled on the phone has to come back looking right
 * over there. That is also why the list is a subset: upstream offers 28 names and this app draws
 * the eight that stay legible at 24dp. An icon set on the desktop that is not in this list falls
 * back to the folder glyph rather than rendering nothing.
 */
val PROJECT_ICONS: List<String> = listOf(
    "folder-library", "repo", "rocket", "beaker", "star-full", "terminal", "globe", "package",
)

/** The glyph for a stored icon name; the plain folder for null or anything unrecognised. */
fun projectIconFor(icon: String?): ImageVector = when (icon) {
    "repo" -> RepoStrokeIcon
    "rocket" -> RocketStrokeIcon
    "beaker" -> BeakerStrokeIcon
    "star-full" -> StarStrokeIcon
    "terminal" -> TerminalStrokeIcon
    "globe" -> GlobeStrokeIcon
    "package" -> PackageStrokeIcon
    else -> FolderStrokeIcon
}

/** Bound book: cover plus a spine band — upstream's `repo`. */
val RepoStrokeIcon: ImageVector by lazy {
    strokeIcon("StrokeRepo") {
        moveTo(6.2f, 3.6f)
        lineTo(18.4f, 3.6f)
        lineTo(18.4f, 20.4f)
        lineTo(6.2f, 20.4f)
        close()
        // Spine.
        moveTo(9.4f, 3.6f)
        lineTo(9.4f, 20.4f)
    }
}

/** Nose cone with two fins — upstream's `rocket`. */
val RocketStrokeIcon: ImageVector by lazy {
    strokeIcon("StrokeRocket") {
        moveTo(12f, 3f)
        arcTo(7.5f, 7.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 15.4f, y1 = 12.6f)
        lineTo(15.4f, 16.5f)
        lineTo(8.6f, 16.5f)
        lineTo(8.6f, 12.6f)
        arcTo(7.5f, 7.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 12f, y1 = 3f)
        close()
        // Fins.
        moveTo(8.6f, 13.4f)
        lineTo(6f, 16f)
        lineTo(6f, 19f)
        lineTo(8.6f, 16.5f)
        moveTo(15.4f, 13.4f)
        lineTo(18f, 16f)
        lineTo(18f, 19f)
        lineTo(15.4f, 16.5f)
        // Porthole.
        moveTo(12f, 9.2f)
        arcTo(1.4f, 1.4f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 11.99f, y1 = 9.2f)
        close()
    }
}

/** Flask — upstream's `beaker`. */
val BeakerStrokeIcon: ImageVector by lazy {
    strokeIcon("StrokeBeaker") {
        moveTo(9.5f, 3.5f)
        lineTo(14.5f, 3.5f)
        moveTo(10.5f, 3.5f)
        lineTo(10.5f, 9.5f)
        lineTo(5.6f, 17.8f)
        arcTo(1.6f, 1.6f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 7f, y1 = 20.3f)
        lineTo(17f, 20.3f)
        arcTo(1.6f, 1.6f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 18.4f, y1 = 17.8f)
        lineTo(13.5f, 9.5f)
        lineTo(13.5f, 3.5f)
        // Fill line.
        moveTo(8f, 14f)
        lineTo(16f, 14f)
    }
}

/** Five-point star — upstream's `star-full`. */
val StarStrokeIcon: ImageVector by lazy {
    strokeIcon("StrokeStar") {
        moveTo(12f, 3.6f)
        lineTo(14.6f, 9.1f)
        lineTo(20.4f, 9.9f)
        lineTo(16.2f, 14.1f)
        lineTo(17.2f, 20.1f)
        lineTo(12f, 17.3f)
        lineTo(6.8f, 20.1f)
        lineTo(7.8f, 14.1f)
        lineTo(3.6f, 9.9f)
        lineTo(9.4f, 9.1f)
        close()
    }
}

/** Prompt chevron and caret in a window — upstream's `terminal`. */
val TerminalStrokeIcon: ImageVector by lazy {
    strokeIcon("StrokeTerminal") {
        moveTo(4.5f, 4.5f)
        lineTo(19.5f, 4.5f)
        arcTo(1.6f, 1.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 21.1f, y1 = 6.1f)
        lineTo(21.1f, 17.9f)
        arcTo(1.6f, 1.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 19.5f, y1 = 19.5f)
        lineTo(4.5f, 19.5f)
        arcTo(1.6f, 1.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 2.9f, y1 = 17.9f)
        lineTo(2.9f, 6.1f)
        arcTo(1.6f, 1.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 4.5f, y1 = 4.5f)
        close()
        moveTo(6.6f, 9.4f)
        lineTo(9.6f, 12f)
        lineTo(6.6f, 14.6f)
        moveTo(12.4f, 15f)
        lineTo(17f, 15f)
    }
}

/** Meridian and equator — upstream's `globe`. */
val GlobeStrokeIcon: ImageVector by lazy {
    strokeIcon("StrokeGlobe") {
        moveTo(12f, 3.2f)
        arcTo(8.8f, 8.8f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 11.99f, y1 = 3.2f)
        close()
        moveTo(3.2f, 12f)
        lineTo(20.8f, 12f)
        // Meridian. Cubics, not arcs: a pole-to-pole arc is exactly a half-ellipse, and that
        // degenerate case flattened to a straight line.
        moveTo(12f, 3.2f)
        curveTo(8.4f, 6.4f, 8.4f, 17.6f, 12f, 20.8f)
        curveTo(15.6f, 17.6f, 15.6f, 6.4f, 12f, 3.2f)
        close()
    }
}

/** Closed carton with a seam — upstream's `package`. */
val PackageStrokeIcon: ImageVector by lazy {
    strokeIcon("StrokePackage") {
        moveTo(12f, 2.9f)
        lineTo(20.6f, 7.4f)
        lineTo(20.6f, 16.6f)
        lineTo(12f, 21.1f)
        lineTo(3.4f, 16.6f)
        lineTo(3.4f, 7.4f)
        close()
        moveTo(3.4f, 7.4f)
        lineTo(12f, 11.9f)
        lineTo(20.6f, 7.4f)
        moveTo(12f, 11.9f)
        lineTo(12f, 21.1f)
    }
}
