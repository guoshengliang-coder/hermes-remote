package com.hermes.client.share

import javax.inject.Inject
import javax.inject.Singleton

/**
 * One attachment handed to a conversation, bytes and all (HG-40).
 *
 * Raw [bytes], not base64: this store is an in-process singleton, so encoding is pure overhead —
 * and a transcript image would grow by a third on the way through. [PendingShare.imageBase64]
 * below is base64 for its own historical reasons, which are not a reason for new fields to be.
 */
data class PendingShareAttachment(
    val bytes: ByteArray,
    val mimeType: String,
    val name: String,
) {
    // Bytes are excluded from identity on purpose: comparing multi-megabyte arrays is ruinous and
    // nothing here needs value equality. Same rule as PendingAttachment.
    override fun equals(other: Any?) =
        other is PendingShareAttachment && other.name == name && other.mimeType == mimeType
    override fun hashCode() = 31 * name.hashCode() + mimeType.hashCode()
}

/**
 * A pending share handed to the chat that opens for it: text, and/or attachments.
 *
 * Two generations of fields live here on purpose. [imageBase64] / [imageMime] / [attachmentName]
 * are the share-INTO-the-app path (MainActivity): one image, base64. [attachments] is the
 * share-INTO-a-conversation path (HG-40): any number, raw bytes. Merging them would mean
 * reworking the inbound path, which is a different risk for no gain here — see
 * docs/SESSION_EXCHANGE_REQUIREMENTS.md §6.6, which also records that they *may* be merged later
 * provided the inbound path is regression-tested with it.
 */
data class PendingShare(
    val text: String? = null,
    val imageBase64: String? = null,
    val imageMime: String? = null,
    val attachmentName: String? = null,
    val attachments: List<PendingShareAttachment> = emptyList(),
)

/**
 * One-shot, in-process handoff from the share entry point to the chat that opens for it. Not
 * persisted — survives a single navigation. take() is keyed by sessionId so a normal chat open
 * never consumes a share meant for a different (freshly-created) session.
 */
@Singleton
class PendingShareStore @Inject constructor() {
    private var pending: Pair<String, PendingShare>? = null

    @Synchronized
    fun put(sessionId: String, share: PendingShare) {
        pending = sessionId to share
    }

    @Synchronized
    fun take(sessionId: String): PendingShare? {
        val p = pending ?: return null
        if (p.first != sessionId) return null
        pending = null
        return p.second
    }
}
