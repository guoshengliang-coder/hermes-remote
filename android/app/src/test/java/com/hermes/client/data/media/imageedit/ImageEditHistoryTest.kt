package com.hermes.client.data.media.imageedit

import com.hermes.client.ui.theme.InkColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageEditHistoryTest {

    private val base = ImageEditDocument(sourceWidth = 1200, sourceHeight = 900)

    private fun strokeAt(x: Float) =
        ImageEditOp.Stroke(listOf(SourcePoint(x, x)), InkColor.RED, StrokeWeight.MEDIUM)

    @Test
    fun undoAndRedoRoundTrip() {
        val one = base.withOp(strokeAt(1f))
        val two = one.withOp(strokeAt(2f))
        var history = EditHistory(base).commit(one).commit(two)

        assertTrue(history.canUndo)
        assertFalse(history.canRedo)

        history = history.undo()
        assertEquals(one, history.present)
        assertTrue(history.canRedo)

        history = history.redo()
        assertEquals(two, history.present)
    }

    @Test
    fun aFreshEditDiscardsTheRedoBranch() {
        val one = base.withOp(strokeAt(1f))
        val two = one.withOp(strokeAt(2f))
        val history = EditHistory(base).commit(one).commit(two).undo().commit(one.withOp(strokeAt(9f)))

        assertFalse(history.canRedo)
    }

    @Test
    fun undoingAtTheStartIsANoOp() {
        val history = EditHistory(base)

        assertEquals(history, history.undo())
        assertEquals(history, history.redo())
        assertFalse(history.canUndo)
    }

    /** One gesture, one commit — that is what makes one undo remove exactly one visible thing. */
    @Test
    fun committingTheSameDocumentTwiceDoesNotStackAnEmptyStep() {
        val one = base.withOp(strokeAt(1f))
        val history = EditHistory(base).commit(one).commit(one)

        assertEquals(1, history.past.size)
    }

    @Test
    fun theDepthCapDropsTheOldestAndNeverThePresent() {
        var history = EditHistory(base)
        repeat(HISTORY_DEPTH + 10) { i -> history = history.commit(base.withOp(strokeAt(i.toFloat()))) }

        assertEquals(HISTORY_DEPTH, history.past.size)
        assertEquals(strokeAt((HISTORY_DEPTH + 9).toFloat()), history.present.ops.single())
        // Still walkable all the way back.
        repeat(HISTORY_DEPTH) { history = history.undo() }
        assertFalse(history.canUndo)
    }

    /** Crop and rotation are field edits, not ops, and the same history must carry them. */
    @Test
    fun cropAndRotationUndoThroughTheSameStack() {
        val cropped = base.copy(crop = CropBox(10, 10, 500, 400))
        val turned = cropped.rotatedQuarter()
        var history = EditHistory(base).commit(cropped).commit(turned)

        history = history.undo()
        assertEquals(0, history.present.quarterTurns)
        assertEquals(CropBox(10, 10, 500, 400), history.present.crop)

        history = history.undo()
        assertEquals(null, history.present.crop)
    }
}
