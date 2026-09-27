package com.hermes.client.ui.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

internal data class SpeechResultFrame(val text: String?, val finished: Boolean, val error: Boolean)

/** Volcengine V3 binary framing; sizes and sequence numbers are big endian. */
internal object DoubaoSpeechProtocol {
    // The full-client initialization request occupies sequence 1 in Volcengine's stream.
    const val FIRST_AUDIO_SEQUENCE = 2

    fun initialRequest(): ByteString {
        val payload = """{"audio":{"format":"pcm","codec":"raw","rate":16000,"bits":16,"channel":1},"request":{"model_name":"bigmodel","result_type":"full","show_utterances":true,"enable_nonstream":true,"enable_itn":true,"enable_punc":true}}"""
            .toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(8 + payload.size).order(ByteOrder.BIG_ENDIAN)
            .put(byteArrayOf(0x11, 0x10, 0x10, 0x00))
            .putInt(payload.size).put(payload).array().toByteString()
    }

    fun audio(sequence: Int, pcm: ByteArray, last: Boolean): ByteString {
        require(sequence > 0)
        return ByteBuffer.allocate(12 + pcm.size).order(ByteOrder.BIG_ENDIAN)
            .put(byteArrayOf(0x11, if (last) 0x23 else 0x21, 0x00, 0x00))
            .putInt(if (last) -sequence else sequence)
            .putInt(pcm.size).put(pcm).array().toByteString()
    }

    fun parse(bytes: ByteArray): SpeechResultFrame {
        require(bytes.size >= 8 && (bytes[0].toInt() and 0xf0) == 0x10) { "invalid speech frame" }
        val headerSize = (bytes[0].toInt() and 0x0f) * 4
        require(headerSize in 4..bytes.size) { "invalid speech header" }
        val kind = (bytes[1].toInt() ushr 4) and 0x0f
        val flags = bytes[1].toInt() and 0x0f
        val compression = bytes[2].toInt() and 0x0f
        var offset = headerSize
        if (kind == 0x0f) offset += 4 // provider error code
        if (flags and 0x01 != 0 || flags and 0x02 != 0) offset += 4 // sequence
        require(offset + 4 <= bytes.size) { "truncated speech frame" }
        val size = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int
        require(size in 0..262144 && offset + 4 + size <= bytes.size) { "invalid speech payload" }
        val raw = bytes.copyOfRange(offset + 4, offset + 4 + size)
        val payload = when (compression) {
            0 -> raw
            1 -> GZIPInputStream(raw.inputStream()).use { input ->
                val output = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    require(output.size() + count <= 262144) { "speech payload too large" }
                    output.write(chunk, 0, count)
                }
                output.toByteArray()
            }
            else -> error("unsupported speech compression")
        }
        val json = runCatching { Json.parseToJsonElement(payload.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
        val body = (json?.get("payload_msg") as? JsonObject) ?: json
        val result = body?.get("result")
        val text = when (result) {
            is JsonObject -> result["text"]?.jsonPrimitive?.content
            is JsonArray -> result.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.content }
                .joinToString("").takeIf { it.isNotEmpty() }
            else -> null
        }
        val code = json?.get("code")?.jsonPrimitive?.intOrNull ?: body?.get("code")?.jsonPrimitive?.intOrNull
        val last = json?.get("is_last_package")?.jsonPrimitive?.booleanOrNull == true
            || body?.get("is_last_package")?.jsonPrimitive?.booleanOrNull == true
            || flags and 0x02 != 0
        return SpeechResultFrame(text, last, kind == 0x0f || (code != null && code != 0))
    }
}
