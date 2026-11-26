package com.hermes.client.domain

import com.hermes.client.data.network.MessageDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Hermes' context-compaction carrier, projected for display.
 *
 * When a conversation outgrows its context window Hermes compacts the earlier turns and carries
 * the handoff through the SAME user-role channel a person's messages use. Upstream strips it
 * before showing a transcript (`agent/compaction_display.py` →
 * `ContextCompressor._strip_context_summary_handoff_message`); the dashboard REST history we read
 * does not, so every compacted conversation showed the reader a wall of English machine
 * scaffolding — "[PRIOR CONTEXT — for reference only; not a new message]", "[CONTEXT COMPACTION —
 * REFERENCE ONLY] Earlier turns were compacted into the summary below…" — as if Hermes had said it.
 *
 * The markers below are hand-copied from upstream `agent/context_compressor.py` and are pinned by
 * HermesContractTest; see docs/HERMES_CONTRACT.md.
 *
 * Dropping the whole turn on sight would be wrong: the carrier can have real content merged into
 * it — the prior tail before the delimiter, or a live user question after the legacy end marker —
 * and that content is a genuine part of the conversation. So this mirrors upstream's shape rather
 * than pattern-matching the noise away.
 */
object CompactionCarrier {
    /** Header of a carrier that merged the previous tail in front of the summary. */
    const val PRIOR_CONTEXT_HEADER = "[PRIOR CONTEXT — for reference only; not a new message]"

    /** Everything from here on is the summary, not conversation. */
    const val SUMMARY_DELIMITER = "[END OF PRIOR CONTEXT — COMPACTION SUMMARY BELOW]"

    /** Legacy form: the live user message follows this marker. */
    const val SUMMARY_END_MARKER =
        "--- END OF CONTEXT SUMMARY — respond to the message below, not the summary above ---"

    internal const val REPLAY_HEADER =
        "[STILL IN PROGRESS — this is the active request, restated after the " +
            "compaction boundary because it was not finished yet. Continue it; do not start over.]"

    private val modelSwitchPreamble = Regex(
        """^\[System: The active model for this chat has changed to [^\r\n]+\. From this point forward, use this runtime metadata when answering questions about what model/provider is active\.\]\s*"""
    )

    /** Project a whole loaded window, so an older page can replace a recovered copy with the original. */
    internal fun withoutReplayCopies(rows: List<Pair<MessageDto, ChatMessage>>): List<ChatMessage> {
        val seenByPrompt = mutableMapOf<ChatMessage, MutableSet<String>>()
        return rows.mapNotNull { (row, message) ->
            if (message.role != Role.USER || (message.displayKind != null && message.displayKind != "steer")) {
                return@mapNotNull message
            }
            val text = message.text.trimStart()
            if (!text.startsWith(REPLAY_HEADER) || !row.isReplayCarrier()) {
                val ids = seenByPrompt.getOrPut(promptKey(message)) { mutableSetOf() }
                row.messageUid?.let(ids::add)
                return@mapNotNull message
            }

            val recovered = message.copy(
                text = modelSwitchPreamble.replaceFirst(text.removePrefix(REPLAY_HEADER).trimStart(), "")
            )
            val absorbed = row.absorbedMessageUids.asUidList()
            val relatedIds = absorbed + listOfNotNull(row.messageUid)
            // A standalone replay can get a fresh UID without an absorbed ID.
            val unlinkedReplay = absorbed.isEmpty() && row.content.orEmpty().trimStart().startsWith(REPLAY_HEADER)
            val key = promptKey(recovered)
            val previousIds = seenByPrompt[key]
            val alreadyShown = previousIds != null &&
                (unlinkedReplay || relatedIds.isEmpty() || previousIds.isEmpty() || relatedIds.any(previousIds::contains))
            seenByPrompt.getOrPut(key) { mutableSetOf() }.addAll(relatedIds)
            recovered.takeUnless { alreadyShown }
        }
    }

    private fun MessageDto.isReplayCarrier(): Boolean {
        val raw = content.orEmpty().trimStart()
        val flagged = (displayMetadata?.get("compaction_inflight_task_restatement") as? JsonPrimitive)
            ?.booleanOrNull == true
        return flagged || raw.startsWith(PRIOR_CONTEXT_HEADER) ||
            PURE_HANDOFF_PREFIXES.any { raw.startsWith(it, ignoreCase = true) } ||
            displayContent != null || (messageUid == null && raw.startsWith(REPLAY_HEADER))
    }

    private fun promptKey(message: ChatMessage) = ChatMessage(
        id = "", role = Role.USER, text = message.text.trim(), images = message.images, files = message.files
    )

    // SQLite-backed history can return these IDs as a JSON-encoded string.
    private fun JsonElement?.asUidList(): List<String> {
        val array = when (this) {
            is JsonArray -> this
            is JsonPrimitive -> contentOrNull?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonArray
            else -> null
        }
        return array.orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }

    /** Openers of a carrier that holds nothing but a handoff. */
    private val PURE_HANDOFF_PREFIXES = listOf("[CONTEXT COMPACTION", "[CONTEXT SUMMARY")

    /**
     * The part of [text] a reader should see: the text unchanged when it is not a carrier, the
     * real content merged into a carrier, or null when the turn is pure handoff and should not be
     * rendered at all.
     *
     * Matching is anchored at the start, deliberately: someone quoting one of these strings
     * mid-message is saying something real, and upstream's own detector is anchored for the same
     * reason.
     */
    fun project(text: String): String? {
        val trimmed = text.trimStart()
        val startsWithPriorContext = trimmed.startsWith(PRIOR_CONTEXT_HEADER)
        val startsWithHandoff = PURE_HANDOFF_PREFIXES.any { trimmed.startsWith(it, ignoreCase = true) }
        if (!startsWithPriorContext && !startsWithHandoff && !trimmed.contains(SUMMARY_END_MARKER)) {
            return text
        }
        if (startsWithPriorContext) {
            val delimiterAt = trimmed.indexOf(SUMMARY_DELIMITER)
            if (delimiterAt >= 0) {
                val prior = trimmed.substring(PRIOR_CONTEXT_HEADER.length, delimiterAt).trim()
                return prior.ifBlank { null }
            }
            // A header with no delimiter carries nothing a reader needs.
            return null
        }
        val markerAt = trimmed.indexOf(SUMMARY_END_MARKER)
        if (markerAt >= 0) {
            val live = trimmed.substring(markerAt + SUMMARY_END_MARKER.length).trim()
            return live.ifBlank { null }
        }
        return null
    }
}
