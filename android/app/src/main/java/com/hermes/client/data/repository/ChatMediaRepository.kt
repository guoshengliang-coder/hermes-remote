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
        // What lands in the photo library has to be the picture, not the preview of it (HG-115).
        val full = requiredOriginal(image)
        val source = requireLocalImage(full)
        val mimeType = exportMimeType(full)
        val displayName = exportDisplayName(full)
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
        val source = requireLocalImage(requiredOriginal(image))
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

    /**
     * The image with its full-size bytes on disk, fetching them when only the preview is cached.
     *
     * Everything that owes the user the real picture goes through here (HG-115): the fullscreen
     * viewer, save, save-as, share. A bubble does not — that is the whole point of the preview.
     * Returns the image unchanged when [ChatImage.originalPath] is null, which is what "this is
     * already the original" means.
     *
     * A failure returns the preview rather than throwing: a fullscreen picture that is softer than
     * it should be still shows the user their image, where an error shows them nothing. The export
     * paths below are the ones that must not accept that, and they check.
     */
    suspend fun original(image: ChatImage): ChatImage {
        val target = image.originalPath ?: return image
        val remotePath = image.remotePath ?: return image
        return runCatching {
            withContext(Dispatchers.IO) {
                val file = File(target)
                if (!file.isFile || file.length() == 0L) {
                    downloads.withPermit { rest.downloadArtifact(remotePath, file) }
                    trimCache()
                }
                measured(image.copy(localPath = file.absolutePath, originalPath = null), file)
            }
        }.getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            image
        }
    }

    /**
     * [original], but an export may not quietly fall back to the preview: saving or sharing a
     * downscaled copy under the original's name hands the user a file that is not what they asked
     * for, and they have no way to tell. Failing is the honest outcome — the caller already shows
     * `HR-MEDIA-001` and the user can try again.
     */
    suspend fun requiredOriginal(image: ChatImage): ChatImage {
        if (image.originalPath == null) return image
        val full = original(image)
        check(full.originalPath == null) { "Full-size image is not available on this device yet" }
        return full
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
                // An original already on disk beats downloading a preview of it: an install that
                // cached full-size images before HG-115 must not re-fetch every one of them.
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
                // A bubble shows a small picture, so fetch a small picture (HG-115). The Connector
                // streams it in acknowledged chunks, as it did the original — this avoids the
                // former data:image;base64 JSON response and its second tunnel-level Base64 layer.
                val preview = previewFileFor(cacheKey)
                val cachedPreview = preview.takeIf { it.isFile && it.length() > 0L }
                if (cachedPreview == null) {
                    rest.downloadArtifact(requireNotNull(image.remotePath), preview, thumbWidth = THUMBNAIL_WIDTH)
                    trimCache()
                }
                measured(
                    image.copy(mimeType = mime, localPath = preview.absolutePath, originalPath = file.absolutePath),
                    preview,
                )
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

    /**
     * The preview's cache file for an image whose original is keyed by [cacheKey].
     *
     * The name must NOT begin with [cacheKey]: the original is found by scanning for exactly that
     * prefix, and a preview sitting in front of it would be served as the full-size copy. Hence a
     * prefix of its own. No extension, because the Connector picks the preview's format from the
     * source's transparency — the name would be a guess; nothing reads it, decoding sniffs the
     * bytes, and every export path goes through the original.
     */
    private fun previewFileFor(cacheKey: String): File =
        File(directory, "preview-$THUMBNAIL_WIDTH-$cacheKey")

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

        /**
         * The preview tier asked of the Connector for bubbles (HG-115). Matches the single tier it
         * makes; anything else snaps back to that one, so this is the value, not a preference.
         */
        const val THUMBNAIL_WIDTH = 1080
    }
}

data class SavedChatImage(val uri: Uri, val displayName: String)
