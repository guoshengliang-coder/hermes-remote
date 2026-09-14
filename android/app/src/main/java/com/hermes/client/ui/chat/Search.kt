package com.hermes.client.ui.chat

import com.hermes.client.domain.ChatMessage

/** Indices of [messages] whose text contains [query] (case-insensitive); empty for a blank query. */
fun matchIndices(messages: List<ChatMessage>, query: String): List<Int> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    return messages.indices.filter { messages[it].text.contains(q, ignoreCase = true) }
}

/** One occurrence of the query: which turn, and which mark within that turn (HG-45). */
data class SearchHit(
    val turnIndex: Int,
    /** 0-based position among this turn's hits — the same order the renderer draws its marks in. */
    val occurrence: Int,
)

private const val MAX_TOTAL_HITS = 200

/**
 * Occurrence-level search over what the reader can actually see: the user's own words and the
 * assistant's body text (HG-45, 2026-09-13). Reasoning and tool output are deliberately out of
 * scope — they used to be counted, which inflated `n/N` with hits inside collapsed cards and made
 * the counter disagree with the marks on screen.
 *
 * Matching goes through the SAME [searchHighlightTerms] / [searchHighlightRangesFor] pair the
 * renderer marks with, so "hit k" and "the k-th mark in this turn" are the same thing. They were
 * not before: this searched for the whole query while the renderer also marked each word of it,
 * so a two-word query produced more marks than hits.
 *
 * Timeline notes (`displayKind != null`) are skipped: `MessageBubble` renders them as plain
 * `TimelineNoteRow`s that never carry a mark, so counting them would count the invisible.
 */
fun searchHits(messages: List<ChatMessage>, query: String): List<SearchHit> {
    val terms = searchHighlightTerms(query)
    if (terms.isEmpty()) return emptyList()
    val hits = mutableListOf<SearchHit>()
    for (index in messages.indices) {
        val message = messages[index]
        if (message.displayKind != null || message.text.isBlank()) continue
        val ranges = searchHighlightRangesFor(message.text, terms)
        for (occurrence in ranges.indices) {
            if (hits.size >= MAX_TOTAL_HITS) return hits
            hits += SearchHit(index, occurrence)
        }
    }
    return hits
}
