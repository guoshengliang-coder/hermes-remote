package com.hermes.client.data.voice

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class VoiceRecording(
    val id: String,
    val scope: String,
    val created: Long,
    val bytes: Long = 0,
    val complete: Boolean = false,
    val limited: Boolean = false,
    val text: String? = null,
    val baseText: String? = null,
    @kotlinx.serialization.Transient val active: Boolean = false,
)

/** Private, excluded-from-backup PCM journal. Call disk operations on Dispatchers.IO. */
class VoiceRecordingStore(private val root: File, private val now: () -> Long = System::currentTimeMillis) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "voice-recordings"))
    companion object {
        const val RETENTION_MS = 24 * 60 * 60 * 1000L
        const val MAX_BYTES = 16000 * 2 * 300L
        const val CAPACITY = 32 * 1024 * 1024L
        private val lock = Any()
        private var epoch = 0L
        private val activeWriters = mutableSetOf<String>()
        fun scope(parts: List<String>): String = MessageDigest.getInstance("SHA-256")
            .digest(Json.encodeToString(parts).toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private val owner = synchronized(lock) { epoch }
    private fun checkOwner() = check(owner == epoch) { "voice owner expired" }
    private fun writerKey(id: String) = "${root.canonicalPath}/$id"
    private fun metadata(id: String): File { require(id.matches(Regex("[a-f0-9-]{36}"))); return File(root, "$id.json") }
    fun audio(id: String): File { metadata(id); return File(root, "$id.pcm") }
    private fun read(file: File): VoiceRecording? = runCatching {
        Json.decodeFromString<VoiceRecording>(file.readText()).also { metadata(it.id) }
    }.getOrNull()
    private fun write(record: VoiceRecording) {
        checkOwner()
        val target = metadata(record.id)
        val temporary = File(root, "${record.id}.tmp")
        temporary.writeText(Json.encodeToString(record))
        check(temporary.renameTo(target)) { "voice metadata failed" }
    }
    fun list(scope: String): List<VoiceRecording> = synchronized(lock) {
        checkOwner()
        val result = mutableListOf<VoiceRecording>()
        root.listFiles()?.filter { it.extension == "json" }?.forEach { file ->
            val record = read(file)
            if (record == null) file.delete()
            else if (record.created + RETENTION_MS <= now()) remove(record.id)
            else if (record.scope == scope) {
                val bytes = audio(record.id).length()
                val active = writerKey(record.id) in activeWriters
                if (bytes > 0 || record.text != null || active) result += record.copy(bytes = bytes, complete = record.complete && bytes == record.bytes, active = active)
                else remove(record.id)
            }
        }
        // A process may have died between creating the PCM and committing its metadata.
        root.listFiles()?.filter { it.extension in setOf("pcm", "tmp") && !metadata(it.nameWithoutExtension).exists() }
            ?.forEach { it.delete() }
        result.sortedByDescending { it.created }
    }
    fun begin(scope: String): VoiceRecording = synchronized(lock) {
        checkOwner(); check(root.exists() || root.mkdirs()) { "voice storage unavailable" }
        list(scope)
        val all = root.listFiles()?.filter { it.extension == "json" }?.mapNotNull(::read).orEmpty()
        check(all.none { it.scope == scope && it.text == null }) { "voice recording already pending" }
        val used = all.sumOf { if (!it.complete && it.text == null) MAX_BYTES else audio(it.id).length() }
        check(used + MAX_BYTES <= CAPACITY && root.usableSpace > MAX_BYTES) { "voice storage full" }
        val record = VoiceRecording(UUID.randomUUID().toString(), scope, now())
        check(audio(record.id).createNewFile()) { "voice storage unavailable" }
        try { write(record) } catch (error: Exception) { audio(record.id).delete(); throw error }
        activeWriters.add(writerKey(record.id))
        record
    }
    fun append(id: String, pcm: ByteArray): Unit = synchronized(lock) {
        checkOwner()
        val record = read(metadata(id)) ?: error("voice recording unavailable")
        check(writerKey(id) in activeWriters && !record.complete && record.text == null && record.created + RETENTION_MS > now())
        val file = audio(id)
        check(file.exists() && file.length() + pcm.size <= MAX_BYTES)
        file.appendBytes(pcm)
    }
    fun load(id: String): VoiceRecording = synchronized(lock) {
        checkOwner()
        val record = read(metadata(id)) ?: error("voice recording unavailable")
        val bytes = audio(id).length()
        check(writerKey(id) !in activeWriters && record.created + RETENTION_MS > now() && audio(id).exists() && bytes <= MAX_BYTES && bytes % 2 == 0L)
        record.copy(bytes = bytes, complete = record.complete && bytes == record.bytes)
    }
    fun seal(id: String, complete: Boolean, limited: Boolean): VoiceRecording = synchronized(lock) {
        checkOwner()
        val record = read(metadata(id)) ?: error("voice recording unavailable")
        try { record.copy(bytes = audio(id).length(), complete = complete, limited = limited).also(::write) }
        finally { activeWriters.remove(writerKey(id)) }
    }
    fun handoff(id: String, text: String, baseText: String? = null): VoiceRecording = synchronized(lock) {
        checkOwner()
        val record = read(metadata(id)) ?: error("voice recording unavailable")
        check(writerKey(id) !in activeWriters) { "voice recording active" }
        record.copy(bytes = 0, text = text, baseText = baseText).also { write(it); audio(id).delete() }
    }
    fun remove(id: String): Unit = synchronized(lock) {
        checkOwner(); activeWriters.remove(writerKey(id)); metadata(id).delete(); audio(id).delete(); File(root, "$id.tmp").delete(); Unit
    }
    fun clear(): Unit = synchronized(lock) {
        epoch++
        activeWriters.removeAll { it.startsWith("${root.canonicalPath}/") }
        root.deleteRecursively()
        Unit
    }
}
