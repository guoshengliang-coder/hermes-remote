package com.hermes.client.data.repository

import android.content.Context
import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.domain.ChatFile
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
) {
    private val directory = File(context.cacheDir, "chat-files").apply { mkdirs() }

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

    suspend fun upload(bytes: ByteArray, name: String, mimeType: String) =
        rest.uploadArtifact(bytes, name, mimeType)

    suspend fun uploadStagedFile(file: File, name: String, mimeType: String) =
        rest.uploadArtifact(file, name, mimeType)

    /**
     * A local copy already in the deterministic download-cache slot — found by name, not by the
     * message's `localPath`, which is lost when the conversation is rebuilt from server history.
     */
    suspend fun cachedCopy(file: ChatFile, deviceId: String? = null): File? = withContext(Dispatchers.IO) {
        val remote = file.remotePath ?: return@withContext null
        cacheTarget(remote, file.name, deviceId).takeIf { it.isFile && it.length() > 0 }
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
}
