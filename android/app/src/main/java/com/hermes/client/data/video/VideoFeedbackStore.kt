package com.hermes.client.data.video

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Durable app-private user work, separate from the evictable video cache and FileProvider. */
interface VideoFeedbackStorage {
    suspend fun read(key: String): VideoFeedbackDocument
    suspend fun write(key: String, document: VideoFeedbackDocument)
}

class VideoFeedbackStore(private val directory: File) : VideoFeedbackStorage {
    private val json = Json { ignoreUnknownKeys = true }
    private fun file(key: String): File {
        require(key.matches(Regex("[a-f0-9]{64}")))
        return File(directory, "$key.json")
    }
    override suspend fun read(key: String): VideoFeedbackDocument = mutex.withLock {
        withContext(Dispatchers.IO) {
            val file = file(key)
            if (!file.exists()) VideoFeedbackDocument() else {
                require(file.length() <= 32L * 1024 * 1024)
                json.decodeFromString<VideoFeedbackDocument>(file.readText()).validated()
            }
        }
    }
    override suspend fun write(key: String, document: VideoFeedbackDocument) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                document.validated()
                check(directory.isDirectory || directory.mkdirs())
                val target = file(key)
                val temporary = File.createTempFile("feedback-", ".tmp", directory)
                try {
                    FileOutputStream(temporary).use { out ->
                        out.write(json.encodeToString(document).toByteArray(Charsets.UTF_8)); out.fd.sync()
                    }
                    Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } finally { temporary.delete() }
            }
        }
    }
    companion object { private val mutex = Mutex() }
}
