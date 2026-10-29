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
    val independentDownloads: Boolean = false,
    val limitKnown: Boolean = false,
) {
    val tooLarge: Boolean get() = rejected || (limitKnown && (sizeBytes ?: 0L) > maxBytes)
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
                values[key] = FileCardInfo(metadata.sizeBytes, metadata.maxBytes, values[key]?.rejected == true, metadata.independentDownloads, limitKnown = true)
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

internal data class ChatFileDownloads(
    val tasks: List<com.hermes.client.data.downloads.ArtifactDownloadTask>,
    val download: (ChatFile) -> Unit,
    val pause: (String) -> Unit,
    val cancel: (String) -> Unit,
)
internal val LocalChatFileDownloads = staticCompositionLocalOf<ChatFileDownloads?> { null }

/**
 * Decimal units (1 KB = 1,000 bytes), matching Android's own file manager and the update screen.
 * Round upwards so a file one byte above the cap cannot look like exactly 100 MB.
 */
internal fun artifactSizeLabel(bytes: Long): String = when {
    bytes < 1_000 -> "$bytes B"
    bytes < 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1f KB", kotlin.math.ceil(bytes / 1_000.0 * 10) / 10)
    bytes < 1_000_000_000 -> String.format(java.util.Locale.ROOT, "%.1f MB", kotlin.math.ceil(bytes / 1_000_000.0 * 10) / 10)
    else -> String.format(java.util.Locale.ROOT, "%.1f GB", kotlin.math.ceil(bytes / 1_000_000_000.0 * 10) / 10)
}

internal fun artifactLimitMessage(info: FileCardInfo, language: com.hermes.client.ui.localization.AppLanguage): String {
    val limit = artifactSizeLabel(info.maxBytes)
    return com.hermes.client.ui.localization.localized(language, "下载上限 $limit。", "Download limit: $limit.") + "\n" +
        com.hermes.client.data.error.AppError(
            com.hermes.client.data.error.AppErrorCode.ARTIFACT_TOO_LARGE, retryable = false,
        ).localizedMessage(language)
}

/** MIME is preferred; common containers remain discoverable when upstream omitted it. */
fun isVideoAttachment(file: com.hermes.client.domain.ChatFile): Boolean =
    file.mimeType?.startsWith("video/", ignoreCase = true) == true ||
        file.name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT) in setOf("mp4", "m4v", "mov", "webm", "mkv", "3gp", "avi", "mpeg", "mpg")
