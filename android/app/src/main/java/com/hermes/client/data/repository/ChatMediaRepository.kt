package com.hermes.client.data.repository

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import java.net.URI
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Dns
import javax.inject.Inject
import javax.inject.Singleton

/** Keeps image bytes off Compose state and turns Hermes file references into device-local files. */
@Singleton
class ChatMediaRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
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
            val size = pixelSize(file)
            ChatImage(
                id = id,
                mimeType = mimeType,
                localPath = file.absolutePath,
                width = size?.first,
                height = size?.second,
                state = ImageTransferState.UPLOADING,
            )
        }

    /** Copy the already-hydrated original bytes into the user-visible system photo library. */
    suspend fun saveToGallery(image: ChatImage): SavedChatImage = withContext(Dispatchers.IO) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "System Save As is required on this Android version"
        }
        val source = requireLocalImage(image)
        val mimeType = exportMimeType(image)
        val displayName = exportDisplayName(image)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Hermes Remote")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create a photo-library item")
        try {
            resolver.openOutputStream(uri, "w")?.use { output -> source.inputStream().use { it.copyTo(output) } }
                ?: error("Unable to open the photo-library destination")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            check(resolver.update(uri, values, null, null) == 1) { "Unable to publish the saved image" }
            SavedChatImage(uri, displayName)
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    /** Android 8/9 and explicit “Save as…” destinations arrive as a user-granted content Uri. */
    suspend fun copyToUri(image: ChatImage, destination: Uri): Unit = withContext(Dispatchers.IO) {
        val source = requireLocalImage(image)
        try {
            context.contentResolver.openOutputStream(destination, "w")?.use { output ->
                source.inputStream().use { it.copyTo(output) }
            } ?: error("Unable to open the selected destination")
        } catch (error: Throwable) {
            runCatching { context.contentResolver.delete(destination, null, null) }
            throw error
        }
    }

    fun exportMimeType(image: ChatImage): String = image.mimeType
        ?.takeIf { it.startsWith("image/") }
        ?: mimeForPath(image.remotePath ?: image.sourceUrl ?: image.localPath.orEmpty())

    fun exportDisplayName(image: ChatImage, now: Date = Date()): String {
        val sourceName = listOfNotNull(
            image.remotePath?.substringAfterLast('/'),
            image.sourceUrl?.let { runCatching { URI(it).path.substringAfterLast('/') }.getOrNull() },
        ).firstOrNull { it.isNotBlank() }
        val safeSource = sourceName
            ?.replace(Regex("[\\u0000-\\u001f/\\\\:]"), "_")
            ?.trim(' ', '.')
            ?.take(120)
            ?.takeIf { it.isNotBlank() }
        val expectedExtension = extensionForMime(exportMimeType(image))
        if (safeSource != null && supportedImageExtension(safeSource) != null) return safeSource
        val base = safeSource?.substringBeforeLast('.', safeSource)?.takeIf { it.isNotBlank() }
            ?: "Hermes_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(now)}"
        return "$base.$expectedExtension"
    }

    fun requireLocalImage(image: ChatImage): File {
        val source = image.localPath?.let(::File)?.takeIf { it.isFile && it.length() > 0L }
            ?: error("Image is not available on this device yet")
        val root = directory.canonicalFile
        val canonical = source.canonicalFile
        check(canonical.toPath().startsWith(root.toPath())) { "Image cache path is not trusted" }
        return canonical
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
        if (!image.localPath.isNullOrBlank() && File(image.localPath).isFile) {
            // Measure even here: a message restored from the runtime store can arrive already
            // holding a path but no dimensions, and this early return is the only place it passes.
            return measured(image, File(image.localPath))
        }
        val sourceKey = image.remotePath ?: image.sourceUrl
            ?: return image.copy(state = ImageTransferState.FAILED)
        return runCatching {
            withContext(Dispatchers.IO) {
                val cacheKey = sha256("${profile.orEmpty()}\n$sourceKey")
                val existing = directory.listFiles()?.firstOrNull { it.name.startsWith(cacheKey) }
                if (existing != null && existing.isFile && existing.length() > 0L) {
                    return@withContext measured(image.copy(localPath = existing.absolutePath), existing)
                }
                val mime = image.mimeType ?: mimeForPath(sourceKey)
                val file = File(directory, safeName(cacheKey, mime))
                if (image.sourceUrl != null) {
                    val (resolvedMime, bytes) = downloadExternalImage(image.sourceUrl)
                    val resolvedFile = File(directory, safeName(cacheKey, resolvedMime))
                    resolvedFile.writeBytes(bytes)
                    trimCache()
                    return@withContext measured(
                        image.copy(mimeType = resolvedMime, localPath = resolvedFile.absolutePath),
                        resolvedFile,
                    )
                }
                // Connector streams the original bytes in acknowledged chunks. This avoids the
                // former data:image;base64 JSON response and its second tunnel-level Base64 layer.
                rest.downloadArtifact(requireNotNull(image.remotePath), file)
                trimCache()
                measured(image.copy(mimeType = mime, localPath = file.absolutePath), file)
            }
        }.getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            image.copy(state = ImageTransferState.FAILED)
        }
    }

    /**
     * The cached file for an inline Markdown image, or null.
     *
     * Separate from [hydrateMessages] because the two answer different questions. That one owns
     * images that are the message's content and belong to a profile; an inline icon inside a table
     * cell belongs to nobody — the same GitHub mark appears under every profile — so it is keyed by
     * URL alone and shared.
     */
    fun cachedInlineImage(url: String): File? {
        if (!url.startsWith("https://")) return null
        val key = inlineCacheKey(url)
        return directory.listFiles()?.firstOrNull { it.name.startsWith(key) }?.takeIf { it.length() > 0L }
    }

    /**
     * [cachedInlineImage], fetching when it misses. Goes through the same credential-free,
     * SSRF-guarded, HTTPS-only, size-capped path as every other third-party image: an assistant
     * can put any URL in a table cell, and this must never carry a Relay credential to it or be
     * talked into dialling a private address.
     */
    suspend fun loadInlineImage(url: String): File? {
        cachedInlineImage(url)?.let { return it }
        if (!url.startsWith("https://")) return null
        return runCatching {
            withContext(Dispatchers.IO) {
                downloads.withPermit {
                    // Re-check under the permit: several cells can reference one icon, and without
                    // this they all download it.
                    cachedInlineImage(url) ?: run {
                        val (mime, bytes) = downloadExternalImage(url)
                        val file = File(directory, safeName(inlineCacheKey(url), mime))
                        file.writeBytes(bytes)
                        trimCache()
                        file
                    }
                }
            }
        }.getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            null
        }
    }

    private fun inlineCacheKey(url: String): String = sha256("inline\n$url")

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

    /**
     * Fill [ChatImage.width]/[ChatImage.height] from the file we have just written, unless the
     * caller already knew them.
     *
     * The renderer needs the aspect ratio to size a thumbnail's container, and learning it later
     * would resize the bubble after the fact -- a layout jump inside a reverse-layout LazyColumn,
     * which is exactly what "data changes never steal the viewport" forbids. Measuring here costs
     * one bounds-only decode on a thread that just finished writing the bytes anyway.
     */
    private fun measured(image: ChatImage, file: File): ChatImage {
        if (image.width != null && image.height != null) return image
        val size = pixelSize(file) ?: return image
        return image.copy(width = size.first, height = size.second)
    }

    /** Intrinsic pixel size of [file] without allocating its pixels, or null if it is not decodable. */
    private fun pixelSize(file: File): Pair<Int, Int>? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds) }
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth to bounds.outHeight else null
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

    private fun supportedImageExtension(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp" -> name.substringAfterLast('.').lowercase()
        else -> null
    }

    private fun extensionForMime(mimeType: String): String = when (mimeType.lowercase()) {
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        else -> "jpg"
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

data class SavedChatImage(val uri: Uri, val displayName: String)
