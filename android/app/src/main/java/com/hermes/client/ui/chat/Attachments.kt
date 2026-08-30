package com.hermes.client.ui.chat

enum class AttachmentKind { IMAGE, PDF, FILE }

/** A picked-but-unsent attachment, held locally until Send. Identity is [id] (bytes excluded). */
class PendingAttachment(
    val id: String,
    val bytes: ByteArray,
    val mimeType: String,
    val name: String = "attachment",
    val kind: AttachmentKind = attachmentKind(mimeType, name),
) {
    val sizeBytes: Long get() = bytes.size.toLong()
    override fun equals(other: Any?) = other is PendingAttachment && other.id == id
    override fun hashCode() = id.hashCode()
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
