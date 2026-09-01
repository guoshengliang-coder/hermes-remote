package com.hermes.client.ui.chat

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
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

    @Test fun exactSameWidthRestoreFollowsStableKeyWhenTailChangesIndices() {
        val controller = ChatViewportController()
        controller.setPinnedToBottom(false)
        controller.onViewportWidth(400)
        controller.updateViewport(Rect(0f, 0f, 400f, 800f))
        controller.updateBlock("turn-b", Rect(0f, -30f, 400f, 500f))
        controller.updateListPosition("turn-b", index = 3, offset = 27)
        controller.holdCurrent()
        controller.requestHeldRestore()

        // New streamed turns can shift reverse-layout indices while the overlay is open. The key,
        // not the stale numerical index, identifies the same visible conversation turn.
        val target = controller.exactRestoreTarget(
            listOf("bottom-edge", "new-tail", "turn-c", "turn-b", "turn-a"),
        )

        assertEquals(ChatExactRestoreTarget(index = 3, offset = 27), target)
    }

    @Test fun overlayDefersRestoreUntilDismissAndFallsBackAcrossWidth() {
        val controller = ChatViewportController()
        controller.setPinnedToBottom(false)
        controller.onViewportWidth(400)
        controller.updateViewport(Rect(0f, 0f, 400f, 800f))
        controller.updateBlock("table-turn", Rect(0f, -10f, 400f, 600f))
        controller.updateListPosition("table-turn", index = 2, offset = 16)

        controller.lockForOverlay()
        controller.onViewportWidth(700)

        assertEquals(ChatViewportMode.OVERLAY_LOCKED, controller.mode)
        assertEquals(
            ChatViewportMode.OVERLAY_LOCKED,
            ChatViewportController(controller.saveAnchor()).mode,
        )
        assertEquals(0, controller.restoreGeneration)

        controller.requestHeldRestore()

        assertEquals(ChatViewportMode.LAYOUT_RESTORING, controller.mode)
        assertEquals(
            ChatViewportMode.LAYOUT_RESTORING,
            ChatViewportController(controller.saveAnchor()).mode,
        )
        assertTrue(controller.waitingForExactWidth())
        assertEquals(null, controller.exactRestoreTarget(listOf("bottom-edge", "table-turn")))
    }

    @Test fun userDragCancelsProgrammaticLayoutRestore() {
        val controller = ChatViewportController()
        controller.setPinnedToBottom(false)
        controller.updateViewport(Rect(0f, 0f, 400f, 800f))
        controller.updateBlock("answer", Rect(0f, -20f, 400f, 500f))
        controller.holdCurrent()
        controller.requestHeldRestore()

        controller.cancelRestoreForUser()

        assertFalse(controller.isRestoring())
        assertEquals(ChatViewportMode.BROWSING_HISTORY, controller.mode)
    }
}
