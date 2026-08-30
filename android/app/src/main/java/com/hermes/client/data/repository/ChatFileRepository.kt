package com.hermes.client.data.repository

import android.content.Context
import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.domain.ChatFile
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

    suspend fun download(file: ChatFile): File = withContext(Dispatchers.IO) {
        file.localPath?.let(::File)?.takeIf { it.isFile && it.length() > 0 }?.let { return@withContext it }
        val remote = file.remotePath ?: error("This file has no downloadable reference")
        val safeName = file.name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).ifBlank { "attachment" }
        val target = File(directory, "${sha256(remote).take(20)}-$safeName")
        if (target.isFile && target.length() > 0) return@withContext target
        val partial = File(directory, "${target.name}.part")
        runCatching { partial.delete() }
        try {
            rest.downloadArtifact(remote, partial)
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
