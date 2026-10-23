package com.hermes.client.data.video

import java.io.*
import java.security.MessageDigest
import java.util.UUID

/** Only whole, validated blocks are visible. Neither sparse files nor crash-left bytes are hits. */
class ArtifactRangeCache(private val directory: File, private val maxBytes: Long = 256L * 1024 * 1024) {
    init {
        directory.mkdirs()
        // A crashed writer leaves no usable block. Reap old temp files without touching an
        // in-flight writer belonging to another instance in the same process.
        directory.listFiles().orEmpty().filter { it.name.endsWith(".part") &&
            it.lastModified() < System.currentTimeMillis() - 60_000 }.forEach { it.delete() }
    }

    @Synchronized fun read(key: String, start: Long, expected: Int): ByteArray? {
        val file = block(key, start)
        return try {
            if (file.length() != expected.toLong() + 36) { file.delete(); return null }
            val bytes = DataInputStream(file.inputStream()).use { input ->
                if (input.readInt() != expected) throw IOException("invalid cached length")
                val digest = ByteArray(32).also(input::readFully)
                val data = ByteArray(expected).also(input::readFully)
                if (!MessageDigest.isEqual(digest, hash(data))) throw IOException("invalid cached digest")
                data
            }
            file.setLastModified(System.currentTimeMillis())
            bytes
        } catch (_: IOException) { file.delete(); null }
    }

    @Synchronized fun write(key: String, start: Long, data: ByteArray) {
        writeBlocks(key, sequenceOf(start to data))
    }

    /** Evict once per download batch, not after every 256 KiB block. This is an evictable
     * cache: atomic rename and read-time checksums suffice; durable downloads own fsync.
     */
    @Synchronized fun writeBlocks(key: String, blocks: Sequence<Pair<Long, ByteArray>>) {
        try { for ((start, data) in blocks) writeBlock(key, start, data) }
        finally { trim() }
    }

    private fun writeBlock(key: String, start: Long, data: ByteArray) {
        require(data.isNotEmpty() && data.size <= BLOCK_BYTES && start >= 0 && start % BLOCK_BYTES == 0L)
        if (data.size + 36 > maxBytes) return
        val temp = File(directory, "${UUID.randomUUID()}.part")
        try {
            FileOutputStream(temp).use { stream ->
                val out = DataOutputStream(stream)
                out.writeInt(data.size); out.write(hash(data)); out.write(data); out.flush()
            }
            if (!temp.renameTo(block(key, start))) throw IOException("cache commit failed")
        } finally { temp.delete() }
    }

    private fun trim() {
        val files = directory.listFiles().orEmpty().filter { it.name.endsWith(".block") }.sortedBy { it.lastModified() }
        var size = files.sumOf { it.length() }
        for (file in files) { if (size <= maxBytes) break; val length = file.length(); if (file.delete()) size -= length }
    }

    private fun block(key: String, start: Long): File {
        require(key.matches(Regex("[a-f0-9]{64}")) && start >= 0)
        return File(directory, "$key-$start.block")
    }

    companion object {
        const val BLOCK_BYTES = 256 * 1024
        fun identity(owner: String, path: String, etag: String, size: Long): String =
            hash("$owner\u0000$path\u0000$etag\u0000$size".toByteArray()).joinToString("") { "%02x".format(it) }
        private fun hash(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)
    }
}
