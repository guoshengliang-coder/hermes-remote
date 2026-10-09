package com.hermes.client.ui.workspace

enum class WorkspaceMode { AUTO, SINGLE }

data class WorkspacePreference(val mode: WorkspaceMode = WorkspaceMode.AUTO, val listWidth: Float = 300f) {
    fun normalized() = copy(listWidth = listWidth.takeIf { it.isFinite() }?.coerceIn(240f, 480f) ?: 300f)
}

data class WorkspaceBudget(val split: Boolean, val minimum: Float, val maximum: Float, val listWidth: Float) {
    fun clamp(width: Float) = width.coerceIn(minimum, maximum)
}

data class WorkspaceHinge(val start: Float, val end: Float, val vertical: Boolean = true)

data class WorkspaceRegion(val offset: Float, val extent: Float, val listWidth: Float?, val gap: Float = 0f)

/** An occluding hinge fixes the division. In single mode use the larger unobstructed region. */
fun workspaceRegion(extent: Float, fontScale: Float, split: Boolean, hinge: WorkspaceHinge?): WorkspaceRegion {
    if (hinge == null || hinge.start <= 0 || hinge.end >= extent || hinge.end < hinge.start) return WorkspaceRegion(0f, extent, null)
    val scale = fontScale.coerceAtLeast(1f)
    if (hinge.vertical && split && hinge.start >= 240f * scale && extent - hinge.end >= 360f * scale) {
        return WorkspaceRegion(0f, extent, hinge.start, hinge.end - hinge.start)
    }
    return if (hinge.start >= extent - hinge.end) WorkspaceRegion(0f, hinge.start, null)
    else WorkspaceRegion(hinge.end, extent - hinge.end, null)
}

/** Widths are usable container dp, after system insets. A narrow window never edits preferences. */
fun workspaceBudget(width: Float, fontScale: Float, preference: WorkspacePreference): WorkspaceBudget {
    val scale = fontScale.coerceAtLeast(1f)
    val minimum = 240f * scale
    val maximum = minOf(480f, width - 360f * scale - 24f).coerceAtLeast(minimum)
    val split = preference.mode == WorkspaceMode.AUTO && width >= maxOf(840f, 600f * scale + 24f)
    return WorkspaceBudget(split, minimum, maximum, preference.normalized().listWidth.coerceIn(minimum, maximum))
}

/** Length-prefixing avoids collisions between accounts, devices, profiles and session ids. */
fun workspaceSessionKey(parts: List<String?>): String = parts.joinToString("") { "${it.orEmpty().length}:${it.orEmpty()}" }
