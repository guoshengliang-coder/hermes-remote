package com.hermes.client.data.video

import java.io.*
import java.security.MessageDigest
import java.util.UUID

/** Whole verified blocks only. One initial directory scan; warm reads/writes use an in-memory LRU.
 * Disk methods are called on IO threads. Playback protection leases perform no filesystem work.
 */
class ArtifactRangeCache(
    private val directory: File,
    maxBytes: Long = VideoCacheCapacity.DEFAULT.bytes,
    trimToBytes: Long = maxBytes * 5 / 6,
    private val clock: () -> Long = System::currentTimeMillis,
    private val freeBytes: () -> Long = { directory.usableSpace },
    private val lowSpaceBytes: Long = GIB,
    private val resumeSpaceBytes: Long = GIB * 3 / 2,
    private val loadCapacity: (() -> VideoCacheCapacity)? = null,
    private val changed: (VideoCacheStats) -> Unit = {},
) {
    private data class Entry(val file: File, val size: Long, var usedAt: Long, var verified: Boolean = false)
    private val entries = LinkedHashMap<String, Entry>()
    private val active = mutableMapOf<String, Int>()
    private val hot = mutableMapOf<String, LinkedHashSet<String>>()
    private var indexed = false
    private var bytes = 0L
    private var limit = maxBytes
    private var target = trimToBytes.coerceIn(0, maxBytes)
    private var lastMaintenance = Long.MIN_VALUE
    private var lowSpace = false
    private var clearing = false
    private var epoch = 0L
    internal val revision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    private var published: Pair<VideoCacheStats, Long>? = null
    @Synchronized fun generation(): Long = epoch
    @Synchronized internal fun status(): VideoCacheStats { ensureIndex(); return snapshot() }
    internal var directoryScans = 0
        private set

    @Synchronized fun protect(key: String): AutoCloseable {
        requireKey(key)
        active[key] = (active[key] ?: 0) + 1
        var closed = false
        return AutoCloseable { synchronized(this) {
            if (!closed) {
                closed = true
                val left = active.getValue(key) - 1
                if (left == 0) { active.remove(key); hot.remove(key) } else active[key] = left
            }
        } }
    }

    @Synchronized fun read(key: String, start: Long, expected: Int, markHot: Boolean = true): ByteArray? {
        ensureIndex()
        val file = block(key, start)
        val entry = entries[file.name] ?: return null
        return try {
            if (file.length() != expected.toLong() + HEADER) throw IOException("invalid cached length")
            val data = DataInputStream(file.inputStream()).use { input ->
                if (input.readInt() != expected) throw IOException("invalid cached length")
                val digest = ByteArray(32).also(input::readFully)
                val result = ByteArray(expected).also(input::readFully)
                if (!MessageDigest.isEqual(digest, hash(result))) throw IOException("invalid cached digest")
                result
            }
            entry.verified = true
            entry.usedAt = clock(); file.setLastModified(entry.usedAt)
            entries.remove(file.name); entries[file.name] = entry
            if (markHot) rememberHot(key, file.name)
            data
        } catch (_: IOException) { remove(file.name); publish(); null }
    }

    @Synchronized fun write(key: String, start: Long, data: ByteArray) = writeBlocks(key, sequenceOf(start to data))

    @Synchronized fun writeBlocks(key: String, blocks: Sequence<Pair<Long, ByteArray>>, expectedGeneration: Long? = null, preserveSource: Boolean = false) {
        ensureIndex()
        if (expectedGeneration != null && expectedGeneration != epoch) return
        maintain()
        if (clearing || !storageAvailable()) { publish(); return }
        try {
            for ((start, data) in blocks) {
                require(data.isNotEmpty() && data.size <= BLOCK_BYTES && start >= 0 && start % BLOCK_BYTES == 0L)
                val file = block(key, start)
                if (clearing || data.size + HEADER > limit || !storageAvailable()) continue
                val previous = entries[file.name]?.size ?: 0
                var incoming = data.size + HEADER - previous
                if (bytes + incoming > limit) {
                    evictTo((target - incoming).coerceAtLeast(0), if (preserveSource) key else null)
                    incoming = data.size + HEADER - (entries[file.name]?.size ?: 0)
                }
                if (bytes + incoming > limit) continue // Protected hot blocks never cause unbounded growth.
                val temp = File(directory, "${UUID.randomUUID()}.part")
                try {
                    DataOutputStream(FileOutputStream(temp)).use { out ->
                        out.writeInt(data.size); out.write(hash(data)); out.write(data); out.flush()
                    }
                    if (!temp.renameTo(file)) throw IOException("cache commit failed")
                    entries.remove(file.name)?.let { bytes -= it.size }
                    val entry = Entry(file, data.size.toLong() + HEADER, clock(), verified = true)
                    file.setLastModified(entry.usedAt)
                    entries[file.name] = entry; bytes += entry.size
                    if (!preserveSource) rememberHot(key, file.name)
                } finally { temp.delete() }
            }
        } finally { publish() }
    }

    /** Counts retained, verified blocks of this exact file generation, including holes before playhead.
     * Unknown entries from the startup scan are validated once, never inferred from file names alone.
     */
    @Synchronized internal fun coverage(key: String, size: Long): VideoCacheCoverage {
        ensureIndex()
        val covered = sortedSetOf<Long>()
        var total = 0L
        var validations = 4 // At most 1MiB under this lock; foreground cache hits must stay responsive.
        entries.keys.filter { it.startsWith("$key-") }.forEach { name ->
            val start = name.substringAfter('-').substringBefore('.').toLongOrNull() ?: return@forEach
            if (start !in 0 until size || start % BLOCK_BYTES != 0L) return@forEach
            val count = minOf(BLOCK_BYTES.toLong(), size - start).toInt()
            val entry = entries[name] ?: return@forEach
            if (entry.file.length() != count.toLong() + HEADER) { remove(name); return@forEach }
            if (!entry.verified) {
                if (validations-- <= 0 || read(key, start, count, markHot = false) == null) return@forEach
            }
            covered += start; total += count
        }
        return VideoCacheCoverage(total, size, covered)
    }

    /** Explicit settings refresh also reconciles files Android may have reclaimed externally. */
    @Synchronized fun refresh(): VideoCacheStats {
        ensureIndex()
        scan()
        maintain(force = true)
        return snapshot()
    }

    @Synchronized fun capacity(value: VideoCacheCapacity) {
        ensureIndex(); limit = value.bytes; target = value.trimTo
        maintain(force = true)
        if (bytes > limit) throw IOException("cache capacity cleanup incomplete")
    }

    @Synchronized fun clear(): VideoCacheStats {
        ensureIndex()
        epoch++
        clearing = true
        maintain(force = true)
        return snapshot()
    }

    @Synchronized fun maintain(force: Boolean = false) {
        ensureIndex()
        val now = clock()
        if (clearing) {
            evictTo(0)
            if (active.isEmpty()) { clearing = false; epoch++; if (entries.isNotEmpty()) throw IOException("cache cleanup incomplete") }
        }
        if (force || lastMaintenance == Long.MIN_VALUE || now < lastMaintenance || now - lastMaintenance >= DAY) {
            lastMaintenance = now
            val cutoff = now - 30 * DAY
            entries.filterValues { it.usedAt < cutoff }.keys.toList().forEach { if (!protected(it)) remove(it) }
        }
        if (bytes > limit) evictTo(target)
        storageAvailable()
        publish()
    }

    private fun ensureIndex() {
        if (indexed && directory.isDirectory) return
        indexed = false
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cache directory unavailable")
        loadCapacity?.invoke()?.let { limit = it.bytes; target = it.trimTo }
        scan(); indexed = true
        maintain(force = true)
    }

    private fun scan() {
        directoryScans++
        val files = directory.listFiles() ?: throw IOException("cache directory unreadable")
        val now = clock()
        files.filter { it.name.endsWith(".part") && it.lastModified() < now - 60_000 }.forEach { it.delete() }
        entries.clear(); bytes = 0
        files.filter { BLOCK_NAME.matches(it.name) && it.isFile }.sortedBy { it.lastModified() }.forEach {
            val entry = Entry(it, it.length(), it.lastModified().coerceAtMost(now))
            entries[it.name] = entry; bytes += entry.size
        }
    }

    private fun storageAvailable(): Boolean {
        var available = freeBytes()
        if (available < lowSpaceBytes || lowSpace && available < resumeSpaceBytes) {
            lowSpace = true
            evictTo((bytes - (resumeSpaceBytes - available).coerceAtLeast(0)).coerceAtLeast(0))
            available = freeBytes()
        }
        if (lowSpace && available >= resumeSpaceBytes) lowSpace = false
        return !lowSpace
    }

    private fun rememberHot(key: String, name: String) {
        if (key !in active) return
        val names = hot.getOrPut(key) { linkedSetOf() }
        names.remove(name); names.add(name)
        while (names.size > HOT_BLOCKS) names.remove(names.first())
    }
    private fun protected(name: String) = hot[name.substringBefore('-')]?.contains(name) == true
    private fun evictTo(wanted: Long, preserveKey: String? = null) {
        val candidates = entries.keys.iterator()
        // A full clear must also visit empty blocks left by an interrupted older write.
        while (candidates.hasNext() && (wanted == 0L || bytes > wanted)) {
            val name = candidates.next()
            if (protected(name) || preserveKey != null && name.startsWith("$preserveKey-")) continue
            val entry = entries.getValue(name)
            if (!entry.file.exists() || entry.file.delete()) { candidates.remove(); bytes -= entry.size }
        }
    }
    private fun remove(name: String) {
        val entry = entries[name] ?: return
        if (!entry.file.exists() || entry.file.delete()) { entries.remove(name); bytes -= entry.size }
    }
    private fun snapshot() = VideoCacheStats(indexed, bytes, limit, lowSpace, clearing)
    private fun publish() {
        val value = snapshot() to epoch
        if (published != value) { published = value; revision.value++ }
        changed(value.first)
    }
    private fun block(key: String, start: Long): File {
        requireKey(key); require(start >= 0)
        return File(directory, "$key-$start.block")
    }
    private fun requireKey(key: String) { require(key.matches(Regex("[a-f0-9]{64}"))) }

    companion object {
        const val BLOCK_BYTES = 256 * 1024
        private const val HEADER = 36L
        private const val HOT_BLOCKS = 64 // At most 16 MiB per actively playing source.
        private const val DAY = 86_400_000L
        private val BLOCK_NAME = Regex("[a-f0-9]{64}-[0-9]+\\.block")
        fun identity(owner: String, path: String, etag: String, size: Long): String =
            hash("$owner\u0000$path\u0000$etag\u0000$size".toByteArray()).joinToString("") { "%02x".format(it) }
        private fun hash(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)
    }
}
