package com.hermes.client.ui.chat

import com.hermes.client.ui.localization.localizedMessage
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.hermes.client.domain.ARTIFACT_DOWNLOAD_LIMIT_BYTES
import com.hermes.client.domain.ArtifactMetadata
import com.hermes.client.domain.ChatFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class FileCardInfo(
    val sizeBytes: Long? = null,
    val maxBytes: Long = ARTIFACT_DOWNLOAD_LIMIT_BYTES,
    val rejected: Boolean = false,
) {
    val tooLarge: Boolean get() = rejected || (sizeBytes ?: 0L) > maxBytes
}

/** One conversation-screen lifetime: no process-wide path cache shared between accounts or Macs. */
internal class ChatFileMetadataState(private val inspect: suspend (ChatFile) -> ArtifactMetadata?) {
    private val values = mutableStateMapOf<String, FileCardInfo>()
    private val inspected = mutableSetOf<String>()
    private val inFlight = mutableSetOf<String>()
    private val slots = Semaphore(3)
    private fun key(file: ChatFile) = listOf(file.remotePath, file.localPath).joinToString("\u0000")

    fun info(file: ChatFile): FileCardInfo = values[key(file)] ?: FileCardInfo(file.sizeBytes?.takeIf { it >= 0 })

    suspend fun load(file: ChatFile) {
        val key = key(file)
        if (key in inspected || !inFlight.add(key)) return
        try {
            val metadata = slots.withPermit { inspect(file) }
            if (metadata != null) {
                values[key] = FileCardInfo(metadata.sizeBytes, metadata.maxBytes, values[key]?.rejected == true)
            }
            inspected.add(key)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Inspection is optional. Unknown size keeps the ordinary download path available;
            // its owning boundary will present the actual error if the user chooses to download.
            inspected.add(key)
        } finally {
            inFlight.remove(key)
        }
    }

    fun reject(file: ChatFile) { values[key(file)] = info(file).copy(rejected = true) }
}

internal val LocalChatFileMetadata = staticCompositionLocalOf<ChatFileMetadataState?> { null }

/** Round upwards so a file one byte above the cap cannot look like exactly 100 MiB. */
internal fun artifactSizeLabel(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f KiB", kotlin.math.ceil(bytes / 1024.0 * 10) / 10)
    else -> String.format(java.util.Locale.ROOT, "%.1f MiB", kotlin.math.ceil(bytes / (1024.0 * 1024) * 10) / 10)
}

internal fun artifactLimitMessage(info: FileCardInfo, language: com.hermes.client.ui.localization.AppLanguage): String {
    val limit = artifactSizeLabel(info.maxBytes)
    return com.hermes.client.ui.localization.localized(language, "下载上限 $limit。", "Download limit: $limit.") + "\n" +
        com.hermes.client.data.error.AppError(
            com.hermes.client.data.error.AppErrorCode.ARTIFACT_TOO_LARGE, retryable = false,
        ).localizedMessage(language)
}
