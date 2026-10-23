package com.hermes.client.ui.chat

import com.hermes.client.domain.ChatFile
import com.hermes.client.domain.ChatImage
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role

internal data class ConversationArtifact(
    val reference: String,
    val messageId: String,
    val timestamp: Long?,
    val displayName: String,
    val image: ChatImage? = null,
    val file: ChatFile? = null,
)

/** Feed the complete conversation, in logical order. Tool-only media and uploads stay out. */
internal fun conversationArtifacts(messages: List<ChatMessage>): List<ConversationArtifact> {
    val uploads = messages.filter { it.role == Role.USER }.flatMap { m ->
        m.images.mapNotNull { it.remotePath ?: it.sourceUrl ?: it.localPath } +
            m.files.mapNotNull { it.remotePath ?: it.localPath }
    }.toSet()
    val byReference = linkedMapOf<String, ConversationArtifact>()
    for (message in messages) {
        if (message.role != Role.ASSISTANT || message.isStreaming || message.isError || message.displayKind != null) continue
        val deliveries = message.images.filter { message.deliveredImageIds == null || it.id in message.deliveredImageIds }.mapNotNull { image ->
            val ref = image.remotePath ?: image.sourceUrl ?: image.localPath ?: return@mapNotNull null
            ConversationArtifact(ref, message.id, message.timestamp, ref.substringAfterLast('/'), image = image)
        } + message.files.filter { message.deliveredFileIds == null || it.id in message.deliveredFileIds }.mapNotNull { file ->
            val ref = file.remotePath ?: file.localPath ?: return@mapNotNull null
            ConversationArtifact(ref, message.id, message.timestamp, file.name, file = file)
        }
        for (artifact in deliveries) {
            if (artifact.reference in uploads) continue
            byReference.remove(artifact.reference)
            byReference[artifact.reference] = artifact
        }
    }
    val artifacts = byReference.values.toList().asReversed()
    val reserved = artifacts.map { it.displayName }.toSet()
    val used = mutableSetOf<String>()
    return artifacts.map { artifact ->
        if (used.add(artifact.displayName)) artifact else {
            val name = artifact.displayName
            val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
            var n = 2
            var unique: String
            do { unique = "${name.take(dot)} (${n++})${name.drop(dot)}" } while (unique in reserved || unique in used)
            used.add(unique)
            artifact.copy(displayName = unique)
        }
    }
}
