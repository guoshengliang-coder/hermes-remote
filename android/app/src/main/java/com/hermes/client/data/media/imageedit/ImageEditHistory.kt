package com.hermes.client.data.media.imageedit

/**
 * Undo/redo for the image editor.
 *
 * Snapshots of the whole [ImageEditDocument] rather than per-op deltas, because crop, rotation and
 * aspect are field edits rather than appended ops — one uniform history then covers all six actions
 * (stroke, mosaic, crop drag, rotate, reset ops, reset crop). A document is a few hundred floats, so
 * keeping [HISTORY_DEPTH] of them costs nothing next to the bitmap snapshots the op model exists to
 * avoid.
 *
 * One commit per **gesture**, never per pointer event: the in-flight stroke lives outside the
 * document until the finger lifts. That is what makes one undo remove exactly one visible thing.
 */
data class EditHistory(
    val present: ImageEditDocument,
    val past: List<ImageEditDocument> = emptyList(),
    val future: List<ImageEditDocument> = emptyList(),
) {
    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()
}

/** How many steps back the editor can go. */
const val HISTORY_DEPTH = 40

/** Record [next] as the new present. A fresh edit discards any redo branch. */
fun EditHistory.commit(next: ImageEditDocument): EditHistory {
    if (next == present) return this
    val grown = past + present
    return EditHistory(
        present = next,
        past = if (grown.size > HISTORY_DEPTH) grown.takeLast(HISTORY_DEPTH) else grown,
        future = emptyList(),
    )
}

fun EditHistory.undo(): EditHistory {
    val previous = past.lastOrNull() ?: return this
    return EditHistory(present = previous, past = past.dropLast(1), future = listOf(present) + future)
}

fun EditHistory.redo(): EditHistory {
    val next = future.firstOrNull() ?: return this
    return EditHistory(present = next, past = past + present, future = future.drop(1))
}
