package com.hermes.client.domain

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
