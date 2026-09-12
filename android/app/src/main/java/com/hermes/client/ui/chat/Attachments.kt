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

/**
 * How many more attachments this message can take. The 「添加会话」 picker caps its selection with
 * this rather than keeping a count of its own: conversations-as-Markdown land on the same chip row
 * as photos and files, and two separate ceilings would let someone pick six conversations and only
 * then be told they do not fit (HG-38).
 */
fun remainingAttachmentSlots(staged: Int, cap: Int = ATTACH_CAP): Int = (cap - staged).coerceAtLeast(0)

/**
 * Make every name in [names] distinct, appending ` (2)`, ` (3)`… before the extension.
 *
 * Conversation titles are written by a model and two of them really can match; two attachments
 * called the same thing in one message is a needless puzzle for whoever — or whatever — opens them
 * (HG-38).
 */
fun uniqueAttachmentNames(names: List<String>): List<String> {
    val seen = mutableMapOf<String, Int>()
    return names.map { name ->
        val n = seen.getOrDefault(name, 0) + 1
        seen[name] = n
        if (n == 1) return@map name
        val dot = name.lastIndexOf('.')
        if (dot <= 0) "$name ($n)" else name.substring(0, dot) + " ($n)" + name.substring(dot)
    }
}

/** Add [a] unless already at [cap]; returns the list unchanged when full. */
fun List<PendingAttachment>.plusCapped(a: PendingAttachment, cap: Int = ATTACH_CAP): List<PendingAttachment> =
    if (size >= cap) this else this + a

/** True when a message may be sent: connected, has text or an attachment, and not mid-generation. */
fun canSend(connected: Boolean, hasText: Boolean, hasAttachments: Boolean, isGenerating: Boolean): Boolean =
    connected && (hasText || hasAttachments) && !isGenerating
