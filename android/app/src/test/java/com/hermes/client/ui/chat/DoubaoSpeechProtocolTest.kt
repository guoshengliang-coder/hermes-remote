package com.hermes.client.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class DoubaoSpeechProtocolTest {
    @Test fun requestAndLastAudio_haveExpectedFraming() {
        val request = DoubaoSpeechProtocol.initialRequest().toByteArray()
        assertEquals(0x11, request[0].toInt())
        assertEquals(0x10, request[1].toInt())
        assertTrue(String(request, 8, request.size - 8).contains("enable_nonstream"))
        // A live provider response rejected audio sequence 1: the full request already used it.
        val first = DoubaoSpeechProtocol.audio(
            DoubaoSpeechProtocol.FIRST_AUDIO_SEQUENCE, byteArrayOf(1, 2), last = false,
        ).toByteArray()
        assertEquals(2, ByteBuffer.wrap(first, 4, 4).order(ByteOrder.BIG_ENDIAN).int)
        val last = DoubaoSpeechProtocol.audio(3, byteArrayOf(1, 2), last = true).toByteArray()
        assertEquals(0x23, last[1].toInt())
        assertEquals(-3, ByteBuffer.wrap(last, 4, 4).order(ByteOrder.BIG_ENDIAN).int)
        assertEquals(2, ByteBuffer.wrap(last, 8, 4).order(ByteOrder.BIG_ENDIAN).int)
    }

    @Test fun response_distinguishesPartialAndFinal() {
        val partial = response("{\"result\":{\"text\":\"你好\"}}", final = false)
        assertEquals("你好", DoubaoSpeechProtocol.parse(partial).text)
        assertFalse(DoubaoSpeechProtocol.parse(partial).finished)
        val final = response("{\"result\":{\"text\":\"你好。\"}}", final = true)
        assertTrue(DoubaoSpeechProtocol.parse(final).finished)
        val enveloped = response(
            "{\"code\":0,\"is_last_package\":true,\"payload_msg\":{\"result\":{\"text\":\"你好！\"}}}",
            final = false,
        )
        assertEquals("你好！", DoubaoSpeechProtocol.parse(enveloped).text)
        assertTrue(DoubaoSpeechProtocol.parse(enveloped).finished)
        assertEquals(
            "第一句。第二句。",
            DoubaoSpeechProtocol.parse(response("{\"result\":[{\"text\":\"第一句。\"},{\"text\":\"第二句。\"}]}", final = false)).text,
        )
    }

    @Test fun response_acceptsGzipAndRejectsProviderError() {
        val json = "{\"result\":{\"text\":\"实时文字\"}}".toByteArray()
        val zipped = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(json) }
        }.toByteArray()
        val packet = ByteBuffer.allocate(8 + zipped.size).order(ByteOrder.BIG_ENDIAN)
            .put(byteArrayOf(0x11, 0x90.toByte(), 0x11, 0x00))
            .putInt(zipped.size).put(zipped).array()
        assertEquals("实时文字", DoubaoSpeechProtocol.parse(packet).text)
        assertTrue(DoubaoSpeechProtocol.parse(response("{\"code\":45000001}", final = false)).error)
    }

    private fun response(json: String, final: Boolean): ByteArray {
        val payload = json.toByteArray()
        return ByteBuffer.allocate(12 + payload.size).order(ByteOrder.BIG_ENDIAN)
            .put(byteArrayOf(0x11, if (final) 0x93.toByte() else 0x90.toByte(), 0x10, 0x00))
            .apply { if (final) putInt(-1) }
            .putInt(payload.size).put(payload).array()
    }
}
