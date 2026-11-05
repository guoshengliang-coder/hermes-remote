package com.hermes.client.data.repository

import android.content.Context
import com.hermes.client.data.auth.AccountSessionManager
import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.domain.ChatFile
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.domain.parseMessageContent
import com.hermes.client.domain.ArtifactMetadata
import com.hermes.client.domain.ARTIFACT_DOWNLOAD_LIMIT_BYTES
import com.hermes.client.data.network.HermesApiException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatFileRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val rest: HermesRestApi,
    private val accounts: AccountSessionManager,
) {
    private val directory = File(context.cacheDir, "chat-files").apply { mkdirs() }
    private val sentFiles by lazy { SentFileStore(File(context.filesDir, "sent-file-index")) }
    // First touched on the IO dispatcher. A process killed between a preview's download and its
    // delete leaves a partial file behind; nothing else ever reads this folder, so start it empty.
    private val previewDirectory by lazy {
        File(context.cacheDir, "inline-previews").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
    }

    suspend fun metadata(file: ChatFile, deviceId: String? = null): ArtifactMetadata? = withContext(Dispatchers.IO) {
        file.localPath?.let(::File)?.takeIf { it.isFile }?.let { return@withContext ArtifactMetadata(it.length()) }
        file.remotePath?.let { rest.artifactMetadata(it, deviceId) }
    }

    suspend fun download(file: ChatFile, deviceId: String? = null): File = withContext(Dispatchers.IO) {
        if ((file.sizeBytes ?: 0L) > ARTIFACT_DOWNLOAD_LIMIT_BYTES) {
            throw HermesApiException(413, "artifact exceeds app download limit")
        }
        file.localPath?.let(::File)?.takeIf { it.isFile && it.length() > 0 }?.let { return@withContext it }
        val remote = file.remotePath ?: error("This file has no downloadable reference")
        val target = cacheTarget(remote, file.name, deviceId)
        if (target.isFile && target.length() > 0) return@withContext target
        val partial = File(directory, "${target.name}.part")
        runCatching { partial.delete() }
        try {
            rest.downloadArtifact(remote, partial, deviceId = deviceId)
            check(partial.length() > 0) { "Downloaded file is empty" }
            check(partial.renameTo(target)) { "Unable to finalize downloaded file" }
            trimCache()
            target
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }
    }

    /**
     * The current text of an HTML file an inline `::preview` card draws (docs/DESIGN.md §5.23).
     *
     * Read fresh rather than through [download]'s cache slot: upstream re-reads the file every
     * time the card mounts, because the agent may rewrite it, and a cached copy would keep showing
     * the old page for as long as the slot survives. Nothing is kept on disk; the caller holds the
     * text for the life of the conversation screen.
     *
     * Any 413 — this card's own [maxBytes] or the Mac's transfer ceiling — means the same thing
     * here: too large to load into a frame, so it becomes [InlinePreviewTooLargeException].
     */
    suspend fun previewDocument(path: String, deviceId: String?, maxBytes: Long): String = withContext(Dispatchers.IO) {
        val temporary = File(previewDirectory, "${java.util.UUID.randomUUID()}.part")
        try {
            rest.downloadArtifact(path, temporary, maxBytes = maxBytes, deviceId = deviceId)
            decodeHtml(temporary.readBytes())
        } catch (error: HermesApiException) {
            if (error.code == 413) throw InlinePreviewTooLargeException(maxBytes)
            throw error
        } finally {
            temporary.delete()
        }
    }

    suspend fun upload(bytes: ByteArray, name: String, mimeType: String) =
        rest.uploadArtifact(bytes, name, mimeType)

    suspend fun uploadStagedFile(file: File, name: String, mimeType: String) =
        rest.uploadArtifact(file, name, mimeType)

    /**
     * Compatibility lookup for an existing download-cache copy, using the full reference hash
     * even when the visible name changed. New sent files restore their scoped localPath first.
     */
    suspend fun cachedCopy(file: ChatFile, deviceId: String? = null): File? = withContext(Dispatchers.IO) {
        cachedReference(file, deviceId)?.second
    }

    /** file.attach's absolute path and its possibly relative/quoted @file reference are aliases. */
    suspend fun rememberSent(file: ChatFile, refText: String, sessionId: String, profile: String?, deviceId: String?): ChatFile {
        val owner = localOwner(deviceId) ?: return file
        val remote = file.remotePath ?: return file
        val references = (listOf(remote) + parseMessageContent(refText).files.mapNotNull { it.remotePath }).distinct()
        return withContext(Dispatchers.IO) {
            val scope = sentScope(owner, sessionId, profile)
            val source = file.localPath?.let(::File)?.takeIf { it.parentFile == directory && it.isFile }
            val target = cacheTarget(remote, file.name, scope)
            val local = source?.let {
                if (it == target || it.renameTo(target)) target else null
            }
            sentFiles.put(scope, SentFileRecord(
                references, remote, file.name, file.mimeType,
                local?.name,
            ))
            file.copy(localPath = local?.path ?: file.localPath)
        }
    }

    /** Restore before history reaches any consumer, including reconciliation and cold disk reads. */
    suspend fun restoreSentFiles(messages: List<ChatMessage>, sessionId: String, profile: String?, deviceId: String?): List<ChatMessage> {
        if (messages.none { it.role == Role.USER && it.files.isNotEmpty() }) return messages
        val owner = localOwner(deviceId) ?: return messages
        return withContext(Dispatchers.IO) {
            val records = sentFiles.read(sentScope(owner, sessionId, profile))
            messages.map { message ->
                if (message.role != Role.USER) message else message.copy(files = message.files.map { file ->
                    val record = records.lastOrNull { file.remotePath in it.references }
                    if (record != null) {
                        val local = record.cacheName?.takeIf { File(it).name == it }
                            ?.let { File(directory, it) }?.takeIf { it.isFile && it.length() > 0 }
                        file.copy(name = record.name, mimeType = record.mimeType ?: file.mimeType,
                            remotePath = record.remotePath, localPath = local?.path,
                            sizeBytes = local?.length() ?: file.sizeBytes)
                    } else {
                        // Upgrade compatibility: only an existing device+full-path hash proves
                        // the `/` workspace alias. Never prepend `/` to arbitrary remote requests.
                        val cached = cachedReference(file, deviceId)
                        if (cached == null) file else file.copy(remotePath = cached.first,
                            localPath = cached.second.path, sizeBytes = cached.second.length(),
                            name = cached.first.substringAfterLast('/').replace(UPLOAD_PREFIX, ""))
                    }
                })
            }
        }
    }

    private fun sentScope(owner: String, sessionId: String, profile: String?) =
        listOf(owner, profile?.ifBlank { null } ?: "default", sessionId).joinToString("\u0000")

    // lifecycleCursorScope reads local account state only. artifactOwnerKey would refresh bearer
    // credentials and make an offline disk-history/local-file read depend on the network.
    private fun localOwner(deviceId: String?): String? {
        val account = accounts.lifecycleCursorScope() ?: return null
        val device = deviceId ?: accounts.session.value?.selectedDeviceId ?: return null
        return "$account\u0000$device"
    }

    private fun cachedReference(file: ChatFile, deviceId: String?): Pair<String, File>? {
        val remote = file.remotePath ?: return null
        val candidates = if (remote.startsWith('/')) listOf(remote) else listOf(remote, "/$remote")
        for (candidate in candidates) {
            val exact = cacheTarget(candidate, file.name, deviceId)
            if (exact.isFile && exact.length() > 0) return candidate to exact
            val prefix = "${sha256("${deviceId.orEmpty()}\n$candidate").take(20)}-"
            val matches = directory.listFiles()?.filter {
                it.name.startsWith(prefix) && !it.name.endsWith(".part") && it.isFile && it.length() > 0
            }.orEmpty()
            if (matches.size == 1) return candidate to matches.single()
        }
        return null
    }

    /**
     * Keep the staged upload copy on this phone instead of deleting it after the send succeeds.
     * It lands in the deterministic slot `download` would use, so the same eviction rules apply
     * and a rebuilt conversation can still find it via [cachedCopy]. Returns null (and leaves the
     * caller's cleanup untouched) when no staged file exists or the move fails.
     */
    suspend fun adoptSent(staged: File?, remotePath: String, name: String, deviceId: String? = null): String? =
        withContext(Dispatchers.IO) {
            val source = staged?.takeIf { it.isFile && it.length() > 0 } ?: return@withContext null
            val target = cacheTarget(remotePath, name, deviceId)
            when {
                target.isFile && target.length() > 0L -> { source.delete(); trimCache() }
                source.renameTo(target) -> trimCache()
                else -> return@withContext null
            }
            target.absolutePath
        }

    private fun cacheTarget(remote: String, name: String, deviceId: String?): File {
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).ifBlank { "attachment" }
        return File(directory, "${sha256("${deviceId.orEmpty()}\n$remote").take(20)}-$safeName")
    }

    private fun trimCache() {
        val files = directory.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }
            ?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        files.forEachIndexed { index, file ->
            total += file.length()
            if (index >= 100 || total > 512L * 1024L * 1024L) file.delete()
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private companion object {
        val UPLOAD_PREFIX = Regex("^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}-")
    }
}

/** An inline preview's file is larger than a frame will load (HR-PREVIEW-002). */
class InlinePreviewTooLargeException(val limitBytes: Long) :
    Exception("inline preview file exceeds $limitBytes bytes")

/**
 * HTML bytes as text. Agents write UTF-8; a UTF-16 byte-order mark is honoured, and a UTF-8 one is
 * dropped so the document still begins with its doctype.
 */
internal fun decodeHtml(bytes: ByteArray): String = when {
    bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
        String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
    bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
        String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
    bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
        String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
    else -> String(bytes, Charsets.UTF_8)
}
