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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Dns
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
    // Deliberately credential-free: never attach Relay cookies/tokens to third-party Markdown URLs.
    private val externalHttp = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(false)
        .dns(Dns { hostname ->
                Dns.SYSTEM.lookup(hostname).also { addresses ->
                require(addresses.none { address ->
                    address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress
                        || address.address.let { bytes -> bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc }
                }) { "private image hosts are not allowed" }
            }
        })
        .build()

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
        val sourceKey = image.remotePath ?: image.sourceUrl
            ?: return image.copy(state = ImageTransferState.FAILED)
        return runCatching {
            withContext(Dispatchers.IO) {
                val cacheKey = sha256("${profile.orEmpty()}\n$sourceKey")
                val existing = directory.listFiles()?.firstOrNull { it.name.startsWith(cacheKey) }
                if (existing != null && existing.isFile && existing.length() > 0L) {
                    return@withContext image.copy(localPath = existing.absolutePath)
                }
                val mime = image.mimeType ?: mimeForPath(sourceKey)
                val file = File(directory, safeName(cacheKey, mime))
                if (image.sourceUrl != null) {
                    val (resolvedMime, bytes) = downloadExternalImage(image.sourceUrl)
                    val resolvedFile = File(directory, safeName(cacheKey, resolvedMime))
                    resolvedFile.writeBytes(bytes)
                    trimCache()
                    return@withContext image.copy(mimeType = resolvedMime, localPath = resolvedFile.absolutePath)
                }
                // Connector streams the original bytes in acknowledged chunks. This avoids the
                // former data:image;base64 JSON response and its second tunnel-level Base64 layer.
                rest.downloadArtifact(requireNotNull(image.remotePath), file)
                trimCache()
                image.copy(mimeType = mime, localPath = file.absolutePath)
            }
        }.getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            image.copy(state = ImageTransferState.FAILED)
        }
    }

    private fun downloadExternalImage(url: String): Pair<String, ByteArray> {
        require(url.startsWith("https://")) { "only HTTPS images are supported" }
        externalHttp.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            require(response.isSuccessful) { "image HTTP ${response.code}" }
            val body = requireNotNull(response.body) { "empty image response" }
            val mime = body.contentType()?.toString()?.substringBefore(';') ?: "image/jpeg"
            require(mime.startsWith("image/")) { "URL did not return an image" }
            val announced = body.contentLength()
            require(announced < 0 || announced <= MAX_EXTERNAL_IMAGE_BYTES) { "image is too large" }
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            body.byteStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_EXTERNAL_IMAGE_BYTES) { "image is too large" }
                    output.write(buffer, 0, read)
                }
            }
            return mime to output.toByteArray()
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

    private fun mimeForPath(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> "image/jpeg"
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
        const val MAX_EXTERNAL_IMAGE_BYTES = 25 * 1024 * 1024
        const val MAX_CACHE_FILES = 200
        const val MAX_CACHE_BYTES = 200L * 1024L * 1024L
    }
}
