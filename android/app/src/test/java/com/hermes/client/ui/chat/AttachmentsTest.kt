package com.hermes.client.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentsTest {
    private fun att(id: String) = PendingAttachment(id, byteArrayOf(1, 2, 3), "image/jpeg")

    @Test fun canSend_requires_connection_content_and_not_generating() {
        assertFalse(canSend(connected = true, hasText = false, hasAttachments = false, isGenerating = false))
        assertTrue(canSend(connected = true, hasText = true, hasAttachments = false, isGenerating = false))
        assertTrue(canSend(connected = true, hasText = false, hasAttachments = true, isGenerating = false))
        assertFalse(canSend(connected = false, hasText = true, hasAttachments = true, isGenerating = false))
        assertFalse(canSend(connected = true, hasText = true, hasAttachments = true, isGenerating = true))
    }

    @Test fun plusCapped_adds_under_cap_and_noops_at_cap() {
        val four = (0 until 4).map { att("a$it") }
        assertEquals(5, four.plusCapped(att("a4")).size)
        val six = (0 until ATTACH_CAP).map { att("a$it") }
        assertEquals(ATTACH_CAP, six.plusCapped(att("aX")).size) // no-op at cap
    }

    @Test fun pendingAttachment_equality_by_id() {
        assertEquals(PendingAttachment("x", byteArrayOf(1), "image/png"), PendingAttachment("x", byteArrayOf(9, 9), "image/jpeg"))
        assertFalse(PendingAttachment("x", byteArrayOf(1), "image/png") == PendingAttachment("y", byteArrayOf(1), "image/png"))
    }

    @Test fun attachment_kind_distinguishes_images_pdfs_and_files() {
        assertEquals(AttachmentKind.IMAGE, attachmentKind("image/png", "x.png"))
        assertEquals(AttachmentKind.PDF, attachmentKind("application/octet-stream", "report.PDF"))
        assertEquals(AttachmentKind.FILE, attachmentKind("text/plain", "notes.txt"))
    }

    @Test fun direct_attachment_limit_is_safely_below_tunnel_limit() {
        assertEquals(6 * 1024 * 1024, MAX_DIRECT_ATTACHMENT_BYTES)
    }
}
