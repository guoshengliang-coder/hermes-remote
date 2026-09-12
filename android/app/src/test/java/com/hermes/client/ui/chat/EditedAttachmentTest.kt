package com.hermes.client.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EditedAttachmentTest {

    @Test
    fun anEditedNameKeepsTheStemAndBecomesJpeg() {
        assertEquals("photo-edited.jpg", editedAttachmentName("photo.png"))
        assertEquals("screenshot-edited.jpg", editedAttachmentName("screenshot.jpeg"))
    }

    /** Re-editing must not pile up suffixes. */
    @Test
    fun theSuffixIsIdempotent() {
        assertEquals("photo-edited.jpg", editedAttachmentName("photo-edited.jpg"))
        assertEquals("photo-edited.jpg", editedAttachmentName(editedAttachmentName("photo.png")))
    }

    @Test
    fun aNamelessOrExtensionlessAttachmentStillGetsAName() {
        assertEquals("capture-edited.jpg", editedAttachmentName("capture"))
        assertEquals("attachment-edited.jpg", editedAttachmentName(""))
    }

    /** The name travels to the Mac, so it stays ASCII-safe and bounded. */
    @Test
    fun aLongUnicodeNameIsSanitisedAndCapped() {
        val long = "屏幕截图".repeat(60) + ".png"
        val edited = editedAttachmentName(long)

        assertTrue(edited.endsWith("-edited.jpg"))
        assertTrue(edited.length <= 160 + "-edited.jpg".length)
        assertEquals("evil-edited.jpg", editedAttachmentName("../../etc/evil.png"))
    }

    @Test
    fun replacingKeepsTheIdAndThePositionInTheStrip() {
        val state = ChatUiState()
            .withAttachment(PendingAttachment("a", byteArrayOf(1), "image/png", "a.png"))
            .withAttachment(PendingAttachment("b", byteArrayOf(2), "image/png", "b.png"))
            .withAttachment(PendingAttachment("c", byteArrayOf(3), "image/png", "c.png"))

        val replaced = state.withReplacedAttachment("b", byteArrayOf(9, 9), "image/jpeg", "b-edited.jpg")

        assertEquals(listOf("a", "b", "c"), replaced.pendingAttachments.map { it.id })
        val edited = replaced.pendingAttachments[1]
        assertEquals("b-edited.jpg", edited.name)
        assertEquals("image/jpeg", edited.mimeType)
        assertEquals(2, edited.bytes.size)
    }

    @Test
    fun replacingLeavesTheOtherAttachmentsAlone() {
        val first = PendingAttachment("a", byteArrayOf(1), "image/png", "a.png")
        val state = ChatUiState()
            .withAttachment(first)
            .withAttachment(PendingAttachment("b", byteArrayOf(2), "image/png", "b.png"))

        val replaced = state.withReplacedAttachment("b", byteArrayOf(9), "image/jpeg", "b-edited.jpg")

        assertSame(first, replaced.pendingAttachments[0])
    }

    /**
     * The regression that sent the original image instead of the edited one.
     *
     * PendingAttachment's identity is id-only by design, so a content-only swap produced a state
     * that compared **equal** to the previous one -- and `MutableStateFlow.value` silently drops an
     * assignment whose value is equal. The edit was discarded and the untouched bytes were uploaded.
     * The revision is what makes the two states differ.
     */
    @Test
    fun replacingProducesAStateThatDiffersFromTheOldOne() {
        val state = ChatUiState().withAttachment(PendingAttachment("a", byteArrayOf(1), "image/png", "a.png"))

        val replaced = state.withReplacedAttachment("a", byteArrayOf(9, 9), "image/jpeg", "a-edited.jpg")

        assertNotEquals("a content swap must not compare equal", state, replaced)
        assertNotEquals(state.pendingAttachments, replaced.pendingAttachments)
        assertNotEquals(state.pendingAttachments.single(), replaced.pendingAttachments.single())
    }

    /** Decode caches key on the content, or an edited image keeps showing its old thumbnail. */
    @Test
    fun theContentKeyChangesWithEachReplacement() {
        val original = PendingAttachment("a", byteArrayOf(1), "image/png", "a.png")
        val state = ChatUiState().withAttachment(original)

        val once = state.withReplacedAttachment("a", byteArrayOf(2), "image/jpeg", "a-edited.jpg")
        val twice = once.withReplacedAttachment("a", byteArrayOf(3), "image/jpeg", "a-edited.jpg")

        assertEquals("a", original.contentKey)
        assertNotEquals(original.contentKey, once.pendingAttachments.single().contentKey)
        assertNotEquals(
            once.pendingAttachments.single().contentKey,
            twice.pendingAttachments.single().contentKey,
        )
    }

    /** Identity still excludes the bytes -- comparing megabyte arrays on every state read is ruinous. */
    @Test
    fun identityStillIgnoresTheBytesThemselves() {
        val a = PendingAttachment("a", ByteArray(1024) { 1 }, "image/png", "a.png")
        val b = PendingAttachment("a", ByteArray(1024) { 2 }, "image/png", "a.png")

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    /** The chip can be removed while the editor is open; coming back must not resurrect it. */
    @Test
    fun replacingAnAttachmentThatIsGoneChangesNothing() {
        val state = ChatUiState().withAttachment(PendingAttachment("a", byteArrayOf(1), "image/png", "a.png"))

        val replaced = state.withReplacedAttachment("gone", byteArrayOf(9), "image/jpeg", "x.jpg")

        assertEquals(1, replaced.pendingAttachments.size)
        assertEquals("a", replaced.pendingAttachments.single().id)
        assertNotSame(state.pendingAttachments, replaced.pendingAttachments)
    }
}
