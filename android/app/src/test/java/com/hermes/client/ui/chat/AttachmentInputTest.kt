package com.hermes.client.ui.chat

import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AttachmentInputTest {
    @Test fun ordinary_file_copy_accepts_the_exact_bound_without_base64() {
        val target = File.createTempFile("attachment-input", ".pending")
        try {
            val payload = ByteArray(16) { it.toByte() }
            assertEquals(16L, stageOrdinaryFile(ByteArrayInputStream(payload), target, maxBytes = 16))
            assertArrayEquals(payload, target.readBytes())
        } finally {
            target.delete()
        }
    }

    @Test fun unknown_size_is_rejected_as_soon_as_the_stream_crosses_the_bound() {
        val target = File.createTempFile("attachment-input", ".pending")
        try {
            assertThrows(AttachmentTooLargeException::class.java) {
                stageOrdinaryFile(ByteArrayInputStream(ByteArray(17)), target, maxBytes = 16)
            }
            assertEquals(0L, target.length())
        } finally {
            target.delete()
        }
    }

    @Test fun ordinary_file_limit_is_exactly_50_mib() {
        assertEquals(50L * 1024 * 1024, MAX_FILE_ATTACHMENT_BYTES)
        assertEquals(6 * 1024 * 1024, MAX_DIRECT_ATTACHMENT_BYTES)
    }
}
