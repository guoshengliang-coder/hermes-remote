package com.hermes.client.ui.chat

import org.junit.Assert.assertEquals
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
