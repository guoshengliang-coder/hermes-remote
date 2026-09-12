package com.hermes.client.ui.chat

enum class AttachmentKind { IMAGE, PDF, FILE }

/**
 * A picked-but-unsent attachment, held locally until Send.
 *
 * Identity is [id] plus [revision], never the bytes: comparing multi-megabyte arrays on every state
 * comparison and recomposition would be ruinous.
 *
 * [revision] exists because id-only identity is not enough once content can be **replaced** in
 * place. `MutableStateFlow.value` drops an assignment whose value compares equal, so an edit that
 * swapped the bytes under the same id was silently discarded and the original was sent — found on a
 * device, not in a test. Anything that caches by attachment (a decoded thumbnail, an [ImageSource])
 * must key on [contentKey] for the same reason.
 */
class PendingAttachment(
    val id: String,
    val bytes: ByteArray,
    val mimeType: String,
    val name: String = "attachment",
    val kind: AttachmentKind = attachmentKind(mimeType, name),
    val revision: Int = 0,
) {
    val sizeBytes: Long get() = bytes.size.toLong()

    /** Stable key for this attachment's *current content*. Use it for decode caches and state keys. */
    val contentKey: String get() = if (revision == 0) id else "$id#$revision"

    override fun equals(other: Any?) =
        other is PendingAttachment && other.id == id && other.revision == revision

    override fun hashCode() = 31 * id.hashCode() + revision
}

const val ATTACH_CAP = 6
const val MAX_DIRECT_ATTACHMENT_BYTES = 6 * 1024 * 1024

fun attachmentKind(mimeType: String, name: String): AttachmentKind = when {
    mimeType.startsWith("image/", ignoreCase = true) -> AttachmentKind.IMAGE
    mimeType.equals("application/pdf", ignoreCase = true) || name.endsWith(".pdf", ignoreCase = true) -> AttachmentKind.PDF
    else -> AttachmentKind.FILE
}

fun attachmentSizeLabel(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(java.util.Locale.US, bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.0f KB".format(java.util.Locale.US, bytes / 1024.0)
    else -> "$bytes B"
}

/** Add [a] unless already at [cap]; returns the list unchanged when full. */
fun List<PendingAttachment>.plusCapped(a: PendingAttachment, cap: Int = ATTACH_CAP): List<PendingAttachment> =
    if (size >= cap) this else this + a

/** True when a message may be sent: connected, has text or an attachment, and not mid-generation. */
fun canSend(connected: Boolean, hasText: Boolean, hasAttachments: Boolean, isGenerating: Boolean): Boolean =
    connected && (hasText || hasAttachments) && !isGenerating
