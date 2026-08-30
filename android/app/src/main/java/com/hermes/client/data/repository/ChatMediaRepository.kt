package com.hermes.client.data.repository

import android.content.Context
import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.domain.ChatImage
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.ImageTransferState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** Keeps image bytes off Compose state and turns Hermes file references into device-local files. */
@Singleton
class ChatMediaRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val rest: HermesRestApi,
) {
    private val directory = File(context.cacheDir, "chat-images").apply { mkdirs() }
    private val downloads = Semaphore(4)

    suspend fun cacheOutgoing(id: String, bytes: ByteArray, mimeType: String): ChatImage =
        withContext(Dispatchers.IO) {
            val file = File(directory, safeName(id, mimeType))
            file.writeBytes(bytes)
            trimCache()
            ChatImage(
                id = id,
                mimeType = mimeType,
                localPath = file.absolutePath,
                state = ImageTransferState.UPLOADING,
            )
        }

    suspend fun hydrateMessages(messages: List<ChatMessage>, profile: String?): List<ChatMessage> =
        coroutineScope {
            messages.map { message ->
                async {
                    if (message.images.isEmpty()) message
                    else message.copy(images = message.images.map {
                        downloads.withPermit { hydrate(it, profile) }
                    })
                }
            }.awaitAll()
        }

    private suspend fun hydrate(image: ChatImage, profile: String?): ChatImage {
        if (!image.localPath.isNullOrBlank() && File(image.localPath).isFile) return image
        val remote = image.remotePath ?: return image.copy(state = ImageTransferState.FAILED)
        return runCatching {
            withContext(Dispatchers.IO) {
                val cacheKey = sha256("${profile.orEmpty()}\n$remote")
                val existing = directory.listFiles()?.firstOrNull { it.name.startsWith(cacheKey) }
                if (existing != null && existing.isFile && existing.length() > 0L) {
                    return@withContext image.copy(localPath = existing.absolutePath)
                }
                val dataUrl = rest.fileDataUrl(remote, profile)
                val comma = dataUrl.indexOf(',')
                require(comma > 0 && dataUrl.startsWith("data:image/")) { "invalid image data" }
                val header = dataUrl.substring(0, comma)
                val mime = header.substringAfter("data:").substringBefore(';')
                val encoded = dataUrl.substring(comma + 1)
                require(encoded.length <= MAX_BASE64_CHARS) { "image is too large" }
                val bytes = Base64.getDecoder().decode(encoded)
                val file = File(directory, safeName(cacheKey, mime))
                file.writeBytes(bytes)
                trimCache()
                image.copy(mimeType = mime, localPath = file.absolutePath)
            }
        }.getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            image.copy(state = ImageTransferState.FAILED)
        }
    }

    private fun safeName(id: String, mimeType: String): String {
        val ext = when (mimeType.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            else -> "img"
        }
        return "${id.replace(Regex("[^A-Za-z0-9._-]"), "_")}.$ext"
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun trimCache() {
        val files = directory.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
            ?: return
        var total = 0L
        files.forEachIndexed { index, file ->
            total += file.length()
            if (index >= MAX_CACHE_FILES || total > MAX_CACHE_BYTES) file.delete()
        }
    }

    private companion object {
        // Approximately 25 MiB decoded, matching Hermes' attachment ceiling.
        const val MAX_BASE64_CHARS = 35_000_000
        const val MAX_CACHE_FILES = 200
        const val MAX_CACHE_BYTES = 200L * 1024L * 1024L
    }
}
