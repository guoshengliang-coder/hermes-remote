package com.hermes.client.ui.sessions

/**
 * Pure text helpers for search result rows (docs/DESIGN.md §5.2 搜索页). No Compose here so the
 * rules are unit-testable: where the query occurs in a string, and how a long snippet is cut so
 * the first occurrence stays visible.
 */

/** Every case-insensitive, non-overlapping occurrence of [query] in [text]; empty for a blank query. */
fun highlightRanges(text: String, query: String): List<IntRange> {
    val q = query.trim()
    if (q.isEmpty() || text.isEmpty()) return emptyList()
    val ranges = mutableListOf<IntRange>()
    var from = 0
    while (from <= text.length - q.length) {
        val at = text.indexOf(q, from, ignoreCase = true)
        if (at < 0) break
        ranges += at until at + q.length
        from = at + q.length
    }
    return ranges
}

/**
 * Cuts [raw] to a window around the first occurrence of [query] (±[context] chars, whitespace
 * collapsed) so the match is visible in a two-line row. Without a match the head of the text is
 * kept. The gateway's own snippet varies by search path (short for the CJK trigram path, 120 for
 * the LIKE fallback, ~330 for FTS5), so the client normalizes here.
 */
fun centerSnippet(raw: String?, query: String, context: Int = 40): String {
    val text = raw.orEmpty().replace(Regex("\\s+"), " ").trim()
    if (text.isEmpty()) return ""
    val q = query.trim()
    val at = if (q.isEmpty()) -1 else text.indexOf(q, ignoreCase = true)
    val start: Int
    val end: Int
    if (at < 0) {
        start = 0
        end = (2 * context + q.length).coerceAtMost(text.length)
    } else {
        start = (at - context).coerceAtLeast(0)
        end = (at + q.length + context).coerceAtMost(text.length)
    }
    val core = text.substring(start, end).trim()
    val prefix = if (start > 0 && !core.startsWith("…")) "…" else ""
    val suffix = if (end < text.length && !core.endsWith("…")) "…" else ""
    return "$prefix$core$suffix"
}

/** Whether [session] is a title match for [query]: title or project label contains it. */
fun titleMatches(title: String, projectLabel: String?, query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return false
    return title.contains(q, ignoreCase = true) || (projectLabel?.contains(q, ignoreCase = true) == true)
}
