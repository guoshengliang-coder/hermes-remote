package com.hermes.client.ui.chat

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatViewportControllerTest {
    @Test fun prefersSpecificMarkdownBlockOverWholeTurn() {
        val controller = ChatViewportController()
        controller.setPinnedToBottom(false)
        controller.updateViewport(Rect(0f, 100f, 400f, 900f))
        controller.updateBlock("turn", Rect(0f, 0f, 400f, 1800f))
        controller.updateBlock("block", Rect(0f, 80f, 400f, 360f))

        assertEquals("block", controller.currentAnchor()?.blockKey)
        assertEquals(-20f, controller.currentAnchor()?.offsetFromTopPx)
    }

    @Test fun computesCorrectionForSameBlockAfterReflow() {
        val controller = ChatViewportController()
        controller.setPinnedToBottom(false)
        controller.updateViewport(Rect(0f, 100f, 400f, 900f))
        controller.updateBlock("answer:markdown:4", Rect(0f, 160f, 400f, 420f))
        controller.holdCurrent()

        controller.updateBlock("answer:markdown:4", Rect(0f, 245f, 700f, 430f))

        assertNotNull(controller.correctionPx())
        assertEquals(85f, controller.correctionPx()!!, 0.01f)
    }

    @Test fun widthChangeQueuesRestoreFromOldCoordinates() {
        val controller = ChatViewportController()
        controller.setPinnedToBottom(false)
        controller.updateViewport(Rect(0f, 0f, 400f, 800f))
        controller.updateBlock("answer:turn", Rect(0f, -40f, 400f, 900f))
        controller.onViewportWidth(400)

        controller.onViewportWidth(700)

        assertEquals(1, controller.restoreGeneration)
        assertEquals("answer:turn", controller.saveAnchor()?.blockKey)
    }

    @Test fun reflowPreservesReadingProgressInsideBlockInsteadOfOldTopOffset() {
        val controller = ChatViewportController()
        controller.setPinnedToBottom(false)
        controller.updateViewport(Rect(0f, 0f, 400f, 1_000f))
        controller.updateBlock("answer:markdown:2", Rect(0f, -100f, 400f, 300f))
        controller.holdCurrent()

        // The block doubles in height after a foldable opens. The same semantic reading point is
        // now already one pixel from the viewport top; restoring the old block-top offset would
        // incorrectly request a 100px move.
        controller.updateBlock("answer:markdown:2", Rect(0f, -200f, 700f, 600f))

        assertEquals(1f, controller.correctionPx()!!, 0.01f)
    }

    @Test fun pinnedViewportRestoresAsBottomAnchor() {
        val controller = ChatViewportController()
        controller.holdCurrent()
        controller.requestHeldRestore()

        assertTrue(controller.restoringBottom())
        assertEquals(1, controller.restoreGeneration)
    }
}
