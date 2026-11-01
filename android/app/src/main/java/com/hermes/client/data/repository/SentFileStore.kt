package com.hermes.client.data.repository

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Local presentation/identity only; never edits Hermes history or interprets an alias as a path. */
@Serializable
internal data class SentFileRecord(
    val references: List<String>,
    val remotePath: String,
    val name: String,
    val mimeType: String?,
    val cacheName: String?,
)

/** Kept outside FileProvider's cache root. Loss/corruption is a cache miss, never a send failure. */
internal class SentFileStore(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized fun read(scope: String): List<SentFileRecord> = runCatching {
        val source = target(scope)
        if (!source.isFile || source.length() > 1024 * 1024) return@runCatching emptyList()
        json.decodeFromString<List<SentFileRecord>>(source.readText()).also {
            source.setLastModified(System.currentTimeMillis())
        }
    }.getOrDefault(emptyList())

    @Synchronized fun put(scope: String, record: SentFileRecord) {
        runCatching {
            directory.mkdirs()
            val records = (read(scope).filterNot { old -> old.references.any { it in record.references } } + record)
                .takeLast(100)
            val source = target(scope)
            val temporary = File(directory, "${source.name}.tmp")
            try {
                val text = json.encodeToString(records)
                if (text.length > 1024 * 1024) return@runCatching
                temporary.writeText(text)
                check(temporary.renameTo(source))
            } finally { temporary.delete() }
            directory.listFiles()?.filter { it.extension == "json" }
                ?.sortedByDescending { it.lastModified() }?.drop(300)?.forEach { it.delete() }
        }
    }

    private fun target(scope: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(scope.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$digest.json")
    }
}
