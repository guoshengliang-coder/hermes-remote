package com.hermes.client.ui.chat

import com.hermes.client.domain.ChatMessage

/** Indices of [messages] whose text contains [query] (case-insensitive); empty for a blank query. */
fun matchIndices(messages: List<ChatMessage>, query: String): List<Int> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    return messages.indices.filter { messages[it].text.contains(q, ignoreCase = true) }
}

enum class SearchSource { TEXT, THINKING, TOOL }

/** One occurrence of the query, with enough surrounding context to recognize the spot. */
data class SearchHit(
    val turnIndex: Int,
    val source: SearchSource,
    val snippet: String,
)

private const val SNIPPET_CONTEXT_CHARS = 28
private const val MAX_HITS_PER_FIELD = 3
private const val MAX_TOTAL_HITS = 200

/**
 * Occurrence-level search across turn text, reasoning, and tool outputs. A turn-level index match
 * told the reader "somewhere in this five-thousand-character card"; a snippet tells them where.
 */
fun searchHits(messages: List<ChatMessage>, query: String): List<SearchHit> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    val hits = mutableListOf<SearchHit>()
    outer@ for (index in messages.indices) {
        val message = messages[index]
        for ((source, body) in listOf(
            SearchSource.TEXT to message.text,
            SearchSource.THINKING to message.thinking,
        )) {
            collectHits(body, q, index, source, hits)
            if (hits.size >= MAX_TOTAL_HITS) break@outer
        }
        for (tool in message.tools) {
            collectHits(tool.output, q, index, SearchSource.TOOL, hits)
            if (hits.size >= MAX_TOTAL_HITS) break@outer
        }
    }
    return hits
}

private fun collectHits(
    body: String,
    query: String,
    turnIndex: Int,
    source: SearchSource,
    into: MutableList<SearchHit>,
) {
    if (body.isBlank()) return
    var from = 0
    var found = 0
    while (found < MAX_HITS_PER_FIELD && into.size < MAX_TOTAL_HITS) {
        val at = body.indexOf(query, from, ignoreCase = true)
        if (at < 0) break
        into += SearchHit(turnIndex, source, snippetAround(body, at, query.length))
        found++
        from = at + query.length
    }
}

internal fun snippetAround(body: String, at: Int, matchLength: Int): String {
    val start = (at - SNIPPET_CONTEXT_CHARS).coerceAtLeast(0)
    val end = (at + matchLength + SNIPPET_CONTEXT_CHARS).coerceAtMost(body.length)
    val core = body.substring(start, end).replace(Regex("\\s+"), " ").trim()
    val prefix = if (start > 0) "…" else ""
    val suffix = if (end < body.length) "…" else ""
    return "$prefix$core$suffix"
}
