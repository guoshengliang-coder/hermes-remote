package com.hermes.client.ui.chat

import com.hermes.client.domain.ChatImage
import com.hermes.client.domain.ImageTransferState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What actually reaches the upload after an edit.
 *
 * These stay at the state layer rather than driving the ViewModel, because the defect they guard is
 * in the *value* semantics: `MutableStateFlow.value` drops an assignment whose value compares equal
 * to the current one, and `PendingAttachment` compares by id. A content-only replacement therefore
 * looked like "no change", the write was discarded, and the untouched original was uploaded and
 * sent. It reproduced only on a device.
 */
class EditedAttachmentSendTest {

    private val original = byteArrayOf(1, 2, 3)
    private val edited = byteArrayOf(9, 9, 9, 9)

    @Test
    fun theEditedBytesAreTheOnesLeftInState() {
        val staged = ChatUiState().withAttachment(PendingAttachment("a", original, "image/png", "shot.png"))

        val after = staged.withReplacedAttachment("a", edited, "image/jpeg", "shot-edited.jpg")

        assertArrayEquals(edited, after.pendingAttachments.single().bytes)
        assertEquals("image/jpeg", after.pendingAttachments.single().mimeType)
    }

    /**
     * The exact conflation check StateFlow performs. If this ever passes as "equal" again, an edit
     * will be silently thrown away and the original sent in its place.
     */
    @Test
    fun aStateFlowWouldSeeTheReplacementAsAChange() {
        val staged = ChatUiState().withAttachment(PendingAttachment("a", original, "image/png", "shot.png"))
        val after = staged.withReplacedAttachment("a", edited, "image/jpeg", "shot-edited.jpg")

        // MutableStateFlow.value compares with equals() and skips the write when it returns true.
        assertEquals(false, staged == after)
    }

    @Test
    fun repeatedEditsEachRegisterAsAChange() {
        var state = ChatUiState().withAttachment(PendingAttachment("a", original, "image/png", "shot.png"))
        val seen = mutableSetOf(state)

        repeat(3) { round ->
            val next = state.withReplacedAttachment("a", byteArrayOf(round.toByte()), "image/jpeg", "shot-edited.jpg")
            assertEquals("edit $round was conflated", true, seen.add(next))
            state = next
        }
    }

    /**
     * Upstream fills gaps, it does not overwrite. We measured these dimensions from the exact bytes
     * being uploaded; an upstream that answers null would otherwise erase them and collapse the
     * thumbnail into the unknown-size fallback box -- a landscape frame around a portrait
     * screenshot, which is exactly what it looked like on the device.
     */
    @Test
    fun measuredDimensionsSurviveAnUpstreamThatKnowsNothing() {
        val local = ChatImage(
            id = "a",
            mimeType = "image/jpeg",
            localPath = "/cache/a.jpg",
            width = 1080,
            height = 2408,
            state = ImageTransferState.UPLOADING,
        )

        val patched = local.mergedWithUpstream("/mac/a.jpg", upstreamWidth = null, upstreamHeight = null)

        assertEquals(1080, patched.width)
        assertEquals(2408, patched.height)
        assertEquals("/mac/a.jpg", patched.remotePath)
        assertEquals(ImageTransferState.READY, patched.state)
    }

    /** A disagreeing upstream also loses: we hold the bytes, it does not. */
    @Test
    fun measuredDimensionsWinOverADisagreeingUpstream() {
        val local = ChatImage(id = "a", localPath = "/cache/a.jpg", width = 1080, height = 2408)

        val patched = local.mergedWithUpstream("/mac/a.jpg", upstreamWidth = 640, upstreamHeight = 480)

        assertEquals(1080, patched.width)
        assertEquals(2408, patched.height)
    }

    @Test
    fun upstreamDimensionsAreUsedWhenWeHaveNone() {
        val local = ChatImage(id = "a", mimeType = "image/jpeg", localPath = "/cache/a.jpg")

        val patched = local.mergedWithUpstream("/mac/a.jpg", upstreamWidth = 640, upstreamHeight = 480)

        assertEquals(640, patched.width)
        assertEquals(480, patched.height)
    }
}
